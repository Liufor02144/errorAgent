package io.github.liufor02144.erroragent.dto;

import lombok.Data;

import java.util.Arrays;
import java.util.List;

/**
 * AI 分析的结构化输出。
 *
 * <p>字段与提示词中的 JSON schema 一一对应。
 * <b>不含任何建议类字段</b> —— 结构上就不给「建议回滚」之类的输出留位置。
 */
@Data
public class AnalysisResult {

    public static final String BIZ_EXPECTED = "BIZ_EXPECTED";
    public static final String SUSPECTED_BUG = "SUSPECTED_BUG";
    public static final String INFRA = "INFRA";
    public static final String NEEDS_INFO = "NEEDS_INFO";

    private static final List<String> CATEGORIES =
            Arrays.asList(BIZ_EXPECTED, SUSPECTED_BUG, INFRA, NEEDS_INFO);
    private static final List<String> SEVERITIES = Arrays.asList("P0", "P1", "P2", "P3");
    private static final List<String> CONFIDENCES = Arrays.asList("HIGH", "MEDIUM", "LOW");

    private String category;

    private String severity;

    private String rootCause;

    private String businessImpact;

    private String confidence;



    /**
     * 不查库时的原因说明。
     *
     * <p>不是所有异常都能用数据库估算影响面 —— MQ 不可用这类故障，
     * 请求根本没走到落库那一步，查出来的数字比不查更误导人。
     * 这个字段让「查不了」变成一句可读的解释，而不是一个空结果。
     */
    private String impactNote;

    /**
     * 归一化非法取值。
     *
     * <p>模型偶尔会返回 schema 之外的值（大小写不一致、自造枚举）。
     * 与其让脏数据进库，不如就近回退到最保守的取值。
     */
    public void normalize() {
        category = pick(category, CATEGORIES, NEEDS_INFO);
        severity = pick(severity, SEVERITIES, "P3");
        confidence = pick(confidence, CONFIDENCES, "LOW");
        // 业务预期内的拒绝不是故障，不需要谈影响面
        if (BIZ_EXPECTED.equals(category) && isBlank(impactNote)) {
            impactNote = "业务预期内的拒绝，不是故障";
        }
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    public boolean suspectedBug() {
        return SUSPECTED_BUG.equals(category);
    }

    private String pick(String value, List<String> allowed, String fallback) {
        if (value == null) {
            return fallback;
        }
        String upper = value.trim().toUpperCase();
        return allowed.contains(upper) ? upper : fallback;
    }
}
