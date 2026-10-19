package io.github.liufor02144.erroragent.service.push;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.github.liufor02144.erroragent.config.InspectProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 飞书开放平台 API 客户端。
 *
 * <p>只覆盖推送闭环需要的三件事：拿 token、发卡片、改卡片。
 *
 * <p>token 在本地缓存。开放平台签发的 tenant_access_token 有效期 2 小时，
 * 每次推送都重新申请既慢又会撞上申请频控，因此按过期时间提前 5 分钟续期。
 */
@Slf4j
@Component
public class FeishuApiClient {

    private static final String BASE = "https://open.feishu.cn/open-apis";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 提前量：避免拿到一个下一秒就过期的 token */
    private static final long EXPIRE_AHEAD_MS = 5 * 60 * 1000L;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    @Resource
    private InspectProperties properties;

    private volatile String cachedToken;
    private volatile long tokenExpireAt;
    /** 换 token 用的凭据，变更时作废缓存 */
    private volatile String tokenAppId;

    @Data
    public static class ApiResult {
        private boolean success;
        private int code;
        private String msg;
        private JSONObject data;
        /** 发送成功时的消息 ID，用于后续更新卡片 */
        private String messageId;
    }

    /**
     * 发送交互卡片到群。
     *
     * @param cardJson 完整卡片 JSON
     */
    public ApiResult sendCard(String chatId, String cardJson) {
        Map<String, Object> body = new HashMap<>(4);
        body.put("receive_id", chatId);
        body.put("msg_type", "interactive");
        // content 必须是字符串化的卡片，直接塞对象会被判为参数非法
        body.put("content", cardJson);

        ApiResult result = post(BASE + "/im/v1/messages?receive_id_type=chat_id", JSON.toJSONString(body));
        if (result.isSuccess() && result.getData() != null) {
            result.setMessageId(result.getData().getString("message_id"));
        }
        return result;
    }

    /**
     * 更新已发出的卡片。
     *
     * <p>用于回调之外的场景（例如页面上标注后，把群里的卡片同步成已处理）。
     * 回调内的更新不走这里——那条路径直接在响应里回传新卡片，不额外请求。
     */
    public ApiResult patchCard(String messageId, String cardJson) {
        Map<String, Object> body = new HashMap<>(2);
        body.put("content", cardJson);
        return patch(BASE + "/im/v1/messages/" + messageId, JSON.toJSONString(body));
    }

    /** 连通性自检：拿一次 token 即可验证 app-id / app-secret */
    public ApiResult checkAuth() {
        ApiResult result = new ApiResult();
        try {
            String token = tenantAccessToken(true);
            result.setSuccess(token != null);
            result.setMsg(token != null ? "凭据有效" : "换取 token 失败");
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMsg(e.getMessage());
        }
        return result;
    }

    // ---------------- 内部 ----------------

    private ApiResult post(String url, String json) {
        return execute(new Request.Builder().url(url).post(RequestBody.create(JSON_TYPE, json)));
    }

    private ApiResult patch(String url, String json) {
        return execute(new Request.Builder().url(url).patch(RequestBody.create(JSON_TYPE, json)));
    }

    private ApiResult execute(Request.Builder builder) {
        ApiResult result = new ApiResult();
        String token;
        try {
            token = tenantAccessToken(false);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMsg("获取 tenant_access_token 失败：" + e.getMessage());
            return result;
        }

        Request request = builder.addHeader("Authorization", "Bearer " + token).build();
        try (Response response = HTTP.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            JSONObject obj = parse(text);
            if (obj == null) {
                result.setSuccess(false);
                result.setMsg("飞书返回非 JSON，HTTP " + response.code());
                return result;
            }
            int code = obj.getIntValue("code");
            result.setCode(code);
            result.setMsg(obj.getString("msg"));
            result.setData(obj.getJSONObject("data"));
            result.setSuccess(code == 0);
            if (code != 0) {
                // token 失效时作废缓存，下次调用会重新申请
                if (code == 99991663 || code == 99991661) {
                    cachedToken = null;
                }
                log.warn("飞书 API 返回错误 code={} msg={}", code, result.getMsg());
            }
        } catch (IOException e) {
            result.setSuccess(false);
            result.setMsg("请求飞书失败：" + e.getMessage());
            log.warn("请求飞书失败 {}", e.getMessage());
        }
        return result;
    }

    /**
     * 取 tenant_access_token。
     *
     * @param force 忽略缓存强制刷新
     */
    private String tenantAccessToken(boolean force) {
        InspectProperties.Push cfg = properties.getPush();
        if (cfg.getAppId() == null || cfg.getAppSecret() == null) {
            throw new IllegalStateException("未配置 erroragent.push.app-id / app-secret");
        }
        boolean stale = cachedToken == null
                || System.currentTimeMillis() >= tokenExpireAt
                || !cfg.getAppId().equals(tokenAppId);
        if (!force && !stale) {
            return cachedToken;
        }
        synchronized (this) {
            if (!force && cachedToken != null && System.currentTimeMillis() < tokenExpireAt
                    && cfg.getAppId().equals(tokenAppId)) {
                return cachedToken;
            }
            Map<String, String> body = new HashMap<>(2);
            body.put("app_id", cfg.getAppId());
            body.put("app_secret", cfg.getAppSecret());

            Request request = new Request.Builder()
                    .url(BASE + "/auth/v3/tenant_access_token/internal")
                    .post(RequestBody.create(JSON_TYPE, JSON.toJSONString(body)))
                    .build();
            try (Response response = HTTP.newCall(request).execute()) {
                String text = response.body() == null ? "" : response.body().string();
                JSONObject obj = parse(text);
                if (obj == null || obj.getIntValue("code") != 0) {
                    String msg = obj == null ? text : obj.getString("msg");
                    throw new IllegalStateException("换取 token 失败：" + msg
                            + "（检查 erroragent.push.app-id / app-secret）");
                }
                cachedToken = obj.getString("tenant_access_token");
                long expireSec = obj.getIntValue("expire");
                tokenExpireAt = System.currentTimeMillis() + expireSec * 1000L - EXPIRE_AHEAD_MS;
                tokenAppId = cfg.getAppId();
                log.info("飞书 token 已更新，有效期 {}s", expireSec);
                return cachedToken;
            } catch (IOException e) {
                throw new IllegalStateException("换取 token 网络失败：" + e.getMessage(), e);
            }
        }
    }

    private JSONObject parse(String text) {
        try {
            return JSON.parseObject(text);
        } catch (Exception e) {
            return null;
        }
    }
}
