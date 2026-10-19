package io.github.liufor02144.erroragent.service.analyze;

import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.entity.InspectLlmUsage;
import io.github.liufor02144.erroragent.mapper.InspectLlmUsageMapper;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 调用封装。使用 OpenAI 兼容协议，因此 kimi / 通义 / 内部网关都能直接接。
 *
 * <p>每次调用都记账（含失败），否则闸门失效时（例如幂等窗口配错导致重复分析）
 * 要等月底账单才发现。
 */
@Slf4j
@Component
public class LlmClient {

    /** 429 后的重试次数。限流已在本地做，这里只兜底并发或配额抖动 */
    private static final int RETRY_ON_RATE_LIMIT = 2;

    @Resource
    private InspectProperties properties;

    @Resource
    private InspectConfigService configService;

    @Resource
    private InspectLlmUsageMapper usageMapper;

    private volatile OpenAiChatModel model;
    private volatile String builtWithModelName;
    private volatile Integer builtWithTemperature;
    /** 超时也要纳入重建判断，否则页面上调大了超时却一直按旧值执行 */
    private volatile Integer builtWithTimeout;

    /** 本地发号器：保证两次调用间隔不低于配额允许的最小值 */
    private final Object rateLock = new Object();
    private volatile long lastCallAt = 0L;

    @Data
    public static class LlmResponse {
        private boolean success;
        private String content;
        private String errorMsg;
        private int inputTokens;
        private int outputTokens;
        private int elapsedMs;
        private String model;
    }

    /**
     * 发起一次对话。
     *
     * @param scene 场景标识，用于成本按用途归类（ANALYZE / TEMPLATE_EXTRACT / TEMPLATE_REVIEW）
     * @param fpId  关联指纹，可为 null
     */
    public LlmResponse chat(String scene, Long fpId, String systemPrompt, String userPrompt) {
        LlmResponse result = new LlmResponse();
        InspectProperties.Llm cfg = properties.getLlm();
        result.setModel(cfg.getModel());

        if (!cfg.ready()) {
            result.setSuccess(false);
            result.setErrorMsg("LLM 配置不完整，请检查 erroragent.llm.* 配置");
            return result;
        }

        List<ChatMessage> messages = new ArrayList<>(2);
        messages.add(SystemMessage.from(systemPrompt));
        // 系统提示是固定文本，只有用户消息里带着日志和代码
        messages.add(UserMessage.from(cfg.isRedact() ? Redactor.redact(userPrompt) : userPrompt));

        long began = System.currentTimeMillis();
        Exception lastError = null;
        for (int attempt = 0; attempt <= RETRY_ON_RATE_LIMIT; attempt++) {
            acquireRateSlot();
            try {
                Response<AiMessage> response = model().generate(messages);
                result.setElapsedMs((int) (System.currentTimeMillis() - began));
                result.setSuccess(true);
                result.setContent(response.content() == null ? null : response.content().text());
                if (response.tokenUsage() != null) {
                    result.setInputTokens(nz(response.tokenUsage().inputTokenCount()));
                    result.setOutputTokens(nz(response.tokenUsage().outputTokenCount()));
                }
                record(scene, fpId, result);
                return result;
            } catch (Exception e) {
                lastError = e;
                if (!isRateLimited(e) || attempt == RETRY_ON_RATE_LIMIT) {
                    break;
                }
                // 被限流说明本地节奏仍快于服务端配额，退避后重试
                long backoff = (attempt + 1) * 20_000L;
                log.warn("LLM 触发限流，{}ms 后重试({}/{})", backoff, attempt + 1, RETRY_ON_RATE_LIMIT);
                sleepQuietly(backoff);
            }
        }
        result.setElapsedMs((int) (System.currentTimeMillis() - began));
        result.setSuccess(false);
        result.setErrorMsg(explain(lastError));
        log.warn("LLM 调用失败 scene={} fpId={} err={}", scene, fpId, result.getErrorMsg());
        record(scene, fpId, result);
        return result;
    }

    /**
     * 懒加载模型实例。模型名变化时重建 —— 配置改动后无需重启即可切换。
     */
    private OpenAiChatModel model() {
        InspectProperties.Llm cfg = properties.getLlm();
        int temperatureMilli = configService.getInt(InspectConfigKeys.LLM_TEMPERATURE, 1000);
        int timeoutSeconds = configService.getInt(InspectConfigKeys.LLM_TIMEOUT_SECONDS, 180);
        if (needRebuild(cfg.getModel(), temperatureMilli, timeoutSeconds)) {
            synchronized (this) {
                if (needRebuild(cfg.getModel(), temperatureMilli, timeoutSeconds)) {
                    int timeout = timeoutSeconds;
                    model = OpenAiChatModel.builder()
                            .baseUrl(normalizeBaseUrl(cfg.getBaseUrl()))
                            .apiKey(cfg.getApiKey())
                            .modelName(cfg.getModel())
                            .temperature(temperatureMilli / 1000.0D)
                            .timeout(Duration.ofSeconds(timeout))
                            // 不开 logRequests：请求体里是生产日志，脱敏规则未必能覆盖全部敏感信息
                            .logRequests(false)
                            .logResponses(false)
                            .build();
                    builtWithModelName = cfg.getModel();
                    builtWithTemperature = temperatureMilli;
                    builtWithTimeout = timeoutSeconds;
                    log.info("LLM 客户端初始化 model={} baseUrl={} temperature={} timeout={}s",
                            cfg.getModel(), normalizeBaseUrl(cfg.getBaseUrl()),
                            temperatureMilli / 1000.0D, timeout);
                }
            }
        }
        return model;
    }

    /** 任一构建参数变化都要重建客户端，否则配置改了却不生效 */
    private boolean needRebuild(String modelName, int temperatureMilli, int timeoutSeconds) {
        return model == null
                || !modelName.equals(builtWithModelName)
                || !Integer.valueOf(temperatureMilli).equals(builtWithTemperature)
                || !Integer.valueOf(timeoutSeconds).equals(builtWithTimeout);
    }

    private void record(String scene, Long fpId, LlmResponse r) {
        try {
            InspectLlmUsage usage = new InspectLlmUsage();
            usage.setFpId(fpId);
            usage.setScene(scene);
            usage.setModel(r.getModel());
            usage.setInputTokens(r.getInputTokens());
            usage.setOutputTokens(r.getOutputTokens());
            usage.setCostEstimate(estimateCost(r.getInputTokens(), r.getOutputTokens()));
            usage.setElapsedMs(r.getElapsedMs());
            usage.setSuccess(r.isSuccess());
            usage.setErrorMsg(truncate(r.getErrorMsg(), 500));
            usageMapper.insert(usage);
        } catch (Exception e) {
            // 记账失败不能影响主流程
            log.warn("记录 LLM 用量失败：{}", e.getMessage());
        }
    }

    /**
     * 费用估算，仅用于观察趋势与触发预算告警，精确账单以平台为准。
     * 单价随模型而异，做成配置项由使用方按实际调整。
     */
    private BigDecimal estimateCost(int inputTokens, int outputTokens) {
        double inPrice = configService.getInt(InspectConfigKeys.LLM_PRICE_INPUT, 12) / 1000.0;
        double outPrice = configService.getInt(InspectConfigKeys.LLM_PRICE_OUTPUT, 12) / 1000.0;
        double cost = inputTokens / 1000.0 * inPrice + outputTokens / 1000.0 * outPrice;
        return BigDecimal.valueOf(cost).setScale(6, RoundingMode.HALF_UP);
    }

    private String explain(Exception e) {
        if (e == null) {
            return "未知错误";
        }
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (msg.contains("temperature")) {
            return "模型不接受当前采样温度（如 kimi-k3 仅允许 1）：调整配置 llm.temperature.milli";
        }
        if (msg.contains("401") || msg.toLowerCase().contains("unauthorized")) {
            return "LLM 鉴权失败(401)：检查 erroragent.llm.api-key";
        }
        if (msg.contains("404")) {
            return "LLM 接口不存在(404)：检查 erroragent.llm.base-url 是否需要以 /v1 结尾，以及模型名是否正确";
        }
        if (msg.contains("429")) {
            return "LLM 限流(429)：已达服务商速率上限，可降低采集频率或提升配额";
        }
        if (msg.toLowerCase().contains("timeout")) {
            return "LLM 调用超时：可调大 llm.timeout.seconds，或缩短输入长度";
        }
        return truncate(msg, 300);
    }

    /**
     * 补齐 OpenAI 兼容端点的 /v1 后缀。
     *
     * <p>配置里只填到域名是很常见的写法，但 OpenAI 兼容协议的路径是
     * {@code {baseUrl}/chat/completions}，少了 /v1 会直接 404 —— 且错误信息
     * 只说「没找到对象」，很难联想到是配置少了一段。这里做一次兜底。
     */
    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.matches(".*/v\\d+$")) {
            return url;
        }
        return url + "/v1";
    }

    /**
     * 本地限流。服务商按组织维度限制 RPM（实测某账号仅 3），
     * 不先行限流就会在批量分析时被大面积 429 拒绝。
     */
    private void acquireRateSlot() {
        int rpm = configService.getInt(InspectConfigKeys.LLM_RPM_LIMIT, 3);
        long minInterval = 60_000L / Math.max(rpm, 1);
        synchronized (rateLock) {
            long wait = lastCallAt + minInterval - System.currentTimeMillis();
            if (wait > 0) {
                sleepQuietly(wait);
            }
            lastCallAt = System.currentTimeMillis();
        }
    }

    private boolean isRateLimited(Exception e) {
        if (e == null) {
            return false;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("429") || msg.contains("rate limit") || msg.contains("rpm");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }
}
