package io.github.liufor02144.erroragent.controller;

import io.github.liufor02144.erroragent.common.Response;
import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.entity.InspectAnalysis;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.mapper.InspectFingerprintMapper;
import io.github.liufor02144.erroragent.mapper.InspectLlmUsageMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import io.github.liufor02144.erroragent.service.analyze.AnalysisPromptBuilder;
import io.github.liufor02144.erroragent.service.analyze.InspectAnalyzeService;
import io.github.liufor02144.erroragent.service.analyze.LlmClient;
import io.github.liufor02144.erroragent.service.code.CodeLocator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 分析验证与成本查询。
 */
@Slf4j
@RestController
@RequestMapping("/inspect/analyze")
public class InspectAnalyzeController {

    @Resource
    private InspectAnalyzeService analyzeService;

    @Resource
    private InspectFingerprintMapper fingerprintMapper;

    @Resource
    private InspectSampleMapper sampleMapper;

    @Resource
    private InspectLlmUsageMapper usageMapper;

    @Resource
    private AnalysisPromptBuilder promptBuilder;

    @Resource
    private CodeLocator codeLocator;

    @Resource
    private InspectProperties properties;

    @Resource
    private LlmClient llmClient;

    /**
     * 分析指定指纹。
     *
     * @param force 忽略开关与幂等窗口强制重新分析，用于反复调 prompt
     */
    @PostMapping("/{fpId}")
    public Response<InspectAnalysis> analyze(@PathVariable Long fpId,
                                             @RequestParam(defaultValue = "true") boolean force) {
        InspectAnalysis result = analyzeService.analyze(fpId, null, force);
        if (result == null) {
            return Response.error("分析未产出结果，请查看日志（可能被闸门拦截、调用失败或输出无法解析）");
        }
        return Response.ok(result);
    }

    /**
     * 预览将要发送给模型的完整提示词。
     *
     * <p>调 prompt 时最费时间的是「改一版跑一次花一次钱」，
     * 这个接口让提示词可以先肉眼检查：材料齐不齐、有没有把 token 浪费在无关内容上。
     */
    @GetMapping("/{fpId}/prompt")
    public Response<Map<String, Object>> previewPrompt(@PathVariable Long fpId) {
        // 复用分析服务的组装逻辑，保证预览到的就是实际会发出去的内容
        String[] prompts = analyzeService.buildPrompts(fpId);
        if (prompts == null) {
            return Response.error("指纹不存在");
        }
        String system = prompts[0];
        String user = prompts[1];

        Map<String, Object> result = new HashMap<>();
        result.put("systemPrompt", system);
        result.put("userPrompt", user);
        result.put("systemChars", system.length());
        result.put("userChars", user.length());
        // 中英混排下约 1 token ≈ 1.5 字符，仅用于粗估
        result.put("estimatedTokens", (system.length() + user.length()) / 3 * 2);
        return Response.ok(result);
    }

    /** 最近一次分析结果 */
    @GetMapping("/{fpId}/latest")
    public Response<InspectAnalysis> latest(@PathVariable Long fpId) {
        return Response.ok(analyzeService.latest(fpId));
    }

    /** 连通性自检：不读业务数据，只验证模型可达、鉴权正确、能返回 JSON */
    @PostMapping("/ping")
    public Response<Map<String, Object>> ping() {
        Map<String, Object> result = new HashMap<>();
        result.put("model", properties.getLlm().getModel());
        result.put("baseUrl", properties.getLlm().getBaseUrl());
        result.put("configReady", properties.getLlm().ready());
        if (!properties.getLlm().ready()) {
            result.put("hint", "请先配置 erroragent.llm.base-url / api-key / model");
            return Response.ok(result);
        }
        LlmClient.LlmResponse r = llmClient.chat("PING", null,
                "你是一个健康检查探针。只返回 JSON，不要其他文字。",
                "返回 {\"ok\":true}");
        result.put("success", r.isSuccess());
        result.put("content", r.getContent());
        result.put("errorMsg", r.getErrorMsg());
        result.put("elapsedMs", r.getElapsedMs());
        result.put("inputTokens", r.getInputTokens());
        result.put("outputTokens", r.getOutputTokens());
        return Response.ok(result);
    }

    /** 成本看板：按天汇总 token 与费用 */
    @GetMapping("/cost")
    public Response<Map<String, Object>> cost(@RequestParam(defaultValue = "30") int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -days);

        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> byDay = usageMapper.sumByDay(cal.getTime());
        result.put("byDay", byDay);
        result.put("failureRate", usageMapper.failureRate(cal.getTime()));

        Calendar dayStart = Calendar.getInstance();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        result.put("todayCalls", usageMapper.countSince(dayStart.getTime()));
        return Response.ok(result);
    }
}
