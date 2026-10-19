package io.github.liufor02144.erroragent.controller;

import io.github.liufor02144.erroragent.common.Response;
import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.service.InspectConfigKeys;
import io.github.liufor02144.erroragent.service.InspectConfigService;
import io.github.liufor02144.erroragent.service.push.FeishuApiClient;
import io.github.liufor02144.erroragent.dto.PushResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 推送操作入口：手动推送与连通性自检。
 */
@Slf4j
@RestController
@RequestMapping("/inspect/push")
public class InspectPushController {

    @Resource
    private io.github.liufor02144.erroragent.service.push.InspectEventPushService eventPushService;

    @Resource
    private FeishuApiClient apiClient;

    @Resource
    private InspectProperties properties;

    @Resource
    private InspectConfigService configService;

    /**
     * 推送指定指纹。
     *
     * @param force 忽略闸门强制推送，用于验证链路
     */
    @PostMapping("/{fpId}")
    public Response<PushResult> push(@PathVariable Long fpId,
                                                        @RequestParam(defaultValue = "false") boolean force) {
        // 统一推事件卡片。曾经这里推的是「指纹卡片」—— 另一套版式，
        // 而定时链路推的是事件卡片，两套并存的结果是：
        // 手动验证验的是线上根本不会发的那一张，真正会发的那张反而没人看。
        return Response.ok(eventPushService.pushByFingerprint(fpId, force));
    }

    /**
     * 连通性自检：验证凭据有效、群可达。
     *
     * <p>只发一条纯文本到目标群，不构造业务卡片，用于确认配置是否配对。
     */
    @PostMapping("/ping")
    public Response<Map<String, Object>> ping() {
        Map<String, Object> result = new HashMap<>();
        InspectProperties.Push cfg = properties.getPush();
        result.put("configReady", cfg.ready());
        result.put("chatId", cfg.getChatId());
        result.put("detailBaseUrl", configService.getString(InspectConfigKeys.PUSH_DETAIL_BASE_URL, ""));
        if (!cfg.ready()) {
            result.put("hint", "请先配置 erroragent.push.app-id / app-secret / chat-id");
            return Response.ok(result);
        }

        FeishuApiClient.ApiResult auth = apiClient.checkAuth();
        result.put("authOk", auth.isSuccess());
        result.put("authMsg", auth.getMsg());
        if (!auth.isSuccess()) {
            return Response.ok(result);
        }

        String card = "{\"schema\":\"2.0\",\"body\":{\"elements\":[{\"tag\":\"markdown\","
                + "\"content\":\"巡检推送链路自检 ✅ 凭据有效、群可达\"}]}}";
        FeishuApiClient.ApiResult send = apiClient.sendCard(cfg.getChatId(), card);
        result.put("sendOk", send.isSuccess());
        result.put("sendMsg", send.getMsg());
        result.put("messageId", send.getMessageId());
        return Response.ok(result);
    }
}
