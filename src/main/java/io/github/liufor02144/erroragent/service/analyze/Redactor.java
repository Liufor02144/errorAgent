package io.github.liufor02144.erroragent.service.analyze;

import java.util.regex.Pattern;

/**
 * 发给大模型前的脱敏。
 *
 * <p>生产日志里常有手机号、身份证号、邮箱，以及误打进日志的密钥。大模型通道通常在外部，
 * 这些内容不该原样发出去。规则宁可多抹：抹多了只影响分析的细节，漏抹就是数据外泄。
 *
 * <p>数字类规则用前后不是数字作为边界，避免把长流水号的中间一段当成手机号。
 */
public final class Redactor {

    private Redactor() {
    }

    private static final String[][] RULES = {
            // URL 里的账号密码 //user:pass@
            {"//[^/@\\s:]+:[^/@\\s]+@", "//***:***@"},
            // 键值形式的密钥：password=xxx、"token": "xxx"
            {"(?i)((?:password|passwd|pwd|secret|token|access[_-]?key(?:[_-]?secret)?|api[_-]?key|app[_-]?secret)"
                    + "[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',;&}]{4,}", "$1{SECRET}"},
            {"(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{16,}", "$1{SECRET}"},
            {"eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}", "{SECRET}"},
            {"LTAI[A-Za-z0-9]{12,30}", "{SECRET}"},
            {"AKIA[0-9A-Z]{16}", "{SECRET}"},
            {"sk-[A-Za-z0-9_-]{16,}", "{SECRET}"},
            {"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "{EMAIL}"},
            // 身份证号先于手机号：18 位里可能含 11 位手机号形态的片段
            {"(?<![0-9Xx])[1-9]\\d{16}[0-9Xx](?![0-9Xx])", "{ID_CARD}"},
            {"(?<!\\d)1[3-9]\\d{9}(?!\\d)", "{PHONE}"},
    };

    private static final Pattern[] PATTERNS = new Pattern[RULES.length];

    static {
        for (int i = 0; i < RULES.length; i++) {
            PATTERNS[i] = Pattern.compile(RULES[i][0]);
        }
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (int i = 0; i < PATTERNS.length; i++) {
            out = PATTERNS[i].matcher(out).replaceAll(RULES[i][1]);
        }
        return out;
    }
}
