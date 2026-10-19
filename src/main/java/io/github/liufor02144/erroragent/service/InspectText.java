package io.github.liufor02144.erroragent.service;

/**
 * 文本判空的唯一口径。
 *
 * <p>存在的理由只有一个：<b>字符串 {@code "null"} 不是内容。</b>
 *
 * <p>来源是真实数据 —— 空指针没有 message，日志里 {@code exc_cause} 就是字面量
 * {@code "null"}。用普通的 {@code isEmpty()} 判断，它会被当成有效文本一路带到界面上：
 * <ul>
 *   <li>事件列表标题显示成 {@code null}，完全看不出是什么异常</li>
 *   <li>飞书卡片标题渲染成 {@code **null**}，推到群里更难看</li>
 * </ul>
 *
 * <p>这个坑修过两次 —— 第一次只修了事件标题，卡片那份各自实现的
 * {@code notBlank} 没跟着改，于是在卡片上又冒出来一次。
 * 项目里曾经散着五份 {@code notBlank}，每份都得记得排除 "null" 是不现实的，
 * 所以收敛到这里。<b>新代码判空一律走这个类。</b>
 */
public final class InspectText {

    /** 这些字符串看着有内容，实际等于没有 */
    private static final String[] PLACEHOLDERS = {"null", "NULL", "-", "--", "N/A", "n/a"};

    private InspectText() {
    }

    /** 有真实内容：非空、非空白、且不是 "null" 之类的占位字符串 */
    public static boolean notBlank(String v) {
        if (v == null) {
            return false;
        }
        String t = v.trim();
        if (t.isEmpty()) {
            return false;
        }
        for (String p : PLACEHOLDERS) {
            if (p.equals(t)) {
                return false;
            }
        }
        return true;
    }

    /** 返回第一个有真实内容的值；都没有时返回 null */
    public static String firstNotBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (notBlank(v)) {
                return v;
            }
        }
        return null;
    }

    /** 取值，没有真实内容时用兜底文案 */
    public static String or(String v, String fallback) {
        return notBlank(v) ? v : fallback;
    }

    /**
     * 规整日志里的 traceId：去掉 SkyWalking 的 {@code TID:} 前缀，
     * {@code N/A}、{@code -}、{@code null} 这类占位值视为没有。
     *
     * @return 规整后的 traceId；没有有效值时返回 null
     */
    public static String normalizeTraceId(String traceId) {
        if (traceId == null) {
            return null;
        }
        String t = traceId.trim();
        if (t.toUpperCase().startsWith("TID:")) {
            t = t.substring(4).trim();
        }
        if (t.isEmpty() || "N/A".equalsIgnoreCase(t) || "-".equals(t) || "null".equalsIgnoreCase(t)) {
            return null;
        }
        return t.replace("\"", "");
    }
}
