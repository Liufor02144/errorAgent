package io.github.liufor02144.erroragent.service.analyze;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.github.liufor02144.erroragent.dto.AnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型输出解析。
 *
 * <p>即便提示词要求「只返回 JSON」，模型仍可能包上 markdown 代码块、
 * 或在 JSON 前后附一句说明。解析必须容错，否则一次格式抖动就浪费一次调用。
 */
@Slf4j
@Component
public class AnalysisResponseParser {

    public AnalysisResult parse(String raw) {
        String json = extractJson(raw);
        if (json == null) {
            log.warn("模型输出中未找到 JSON：{}", abbreviate(raw));
            return null;
        }
        try {
            JSONObject obj = JSON.parseObject(json);
            AnalysisResult r = new AnalysisResult();
            r.setCategory(obj.getString("category"));
            r.setSeverity(obj.getString("severity"));
            r.setRootCause(obj.getString("rootCause"));
            r.setBusinessImpact(obj.getString("businessImpact"));
            r.setConfidence(obj.getString("confidence"));
            r.setImpactNote(obj.getString("impactNote"));
            r.normalize();
            return r;
        } catch (Exception e) {
            log.warn("解析模型 JSON 失败：{} | 原文：{}", e.getMessage(), abbreviate(raw));
            return null;
        }
    }

    /** 截取首个 '{' 到末个 '}'，可同时兼容裸 JSON 与被代码块或说明文字包裹的情况 */
    private String extractJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
