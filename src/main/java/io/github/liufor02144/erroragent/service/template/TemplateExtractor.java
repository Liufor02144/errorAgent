package io.github.liufor02144.erroragent.service.template;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.service.analyze.LlmClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 异常模版抽取：把一批没见过的异常交给大模型，产出「模版文本 + 匹配正则」。
 *
 * <p>为什么非用模型不可 —— 以下三类实测靠正则治不了，而且是补一个漏一个：
 * <ul>
 *   <li>{@code 商品"夏季限定 冰美式（大杯）"未同步到渠道商城}
 *       —— 商品名是中文变量，没有固定格式可匹配</li>
 *   <li>{@code 无效的应用授权令牌，解决方案：https://...errCheck?traceId=0b443f}
 *       —— 变量藏在 URL 参数里</li>
 *   <li>{@code 返回代码：[} —— 正则砍尾把有效内容砍没了，剩个残句</li>
 * </ul>
 *
 * <p><b>批量抽取</b>：RPM 限制通常只有个位数，逐条抽几百个模版要跑几小时，
 * 一次给模型十几条能把调用次数压到几十次。
 *
 * <p><b>正则必须校验</b>：模型生成的正则写太宽比碎片化更危险 ——
 * 它会把不同异常吞进同一个模版，真问题被藏进「业务预期」里再也看不见。
 * 所以入库前强制要求：能编译、能匹配自己的样例、长度不低于下限。
 */
@Slf4j
@Component
public class TemplateExtractor {

    private static final String SCENE = "TEMPLATE_EXTRACT";

    /**
     * 占位符对应的通配长度上限。
     *
     * <p>放到 300 是因为变量可能是长 JSON —— 实测
     * {@code syncMemberByQueryPlatform fail code :{请求参数}}
     * 的请求参数是几百字符的 JSON，用 80 装不下，模版就永远命中不了。
     * 放宽仍然安全：{@code .} 不匹配换行，天然限制在一行内，
     * 而 LogKeyText 拼出的身份总长也就 360 左右。
     */
    private static final String WILDCARD = ".{0,300}";

    /** 单条样例截断长度。模版只需要首行语义，整段堆栈纯属浪费 token */
    private static final int MAX_SAMPLE_CHARS = 300;

    @Resource
    private LlmClient llmClient;

    @Data
    public static class ExtractedTemplate {
        /** 对应输入的序号，用于把结果映射回指纹 */
        private int index;
        private String template;
        /** 实际生效的正则，由模版生成 */
        private String pattern;
        /** 模型给的正则，仅留作对照 */
        private String modelPattern;
        /** 模型认为这是否属于业务预期内（仅作建议，最终由人确认） */
        private boolean businessExpectedHint;
        private String hintReason;
    }

    @Data
    public static class ExtractResult {
        private List<ExtractedTemplate> templates = new ArrayList<>();
        private boolean success;
        private String errorMsg;
        private int inputTokens;
        private int outputTokens;
    }

    /**
     * 批量抽取。
     *
     * @param fingerprints 待抽取的粗指纹，顺序与返回的 index 对应
     */
    public ExtractResult extract(List<InspectFingerprint> fingerprints,
                                 List<String> rawSamples, int minPatternLength) {
        ExtractResult result = new ExtractResult();
        if (fingerprints == null || fingerprints.isEmpty()) {
            result.setSuccess(true);
            return result;
        }

        String userPrompt = buildUserPrompt(fingerprints, rawSamples);
        LlmClient.LlmResponse resp = llmClient.chat(SCENE, null, systemPrompt(), userPrompt);
        result.setInputTokens(resp.getInputTokens());
        result.setOutputTokens(resp.getOutputTokens());

        if (!resp.isSuccess()) {
            result.setErrorMsg(resp.getErrorMsg());
            log.warn("模版抽取调用失败：{}", resp.getErrorMsg());
            return result;
        }

        List<ExtractedTemplate> parsed = parse(resp.getContent(), fingerprints.size());
        // 校验后再返回：宁可少收一个模版，也不能让写歪的正则进库
        for (ExtractedTemplate t : parsed) {
            String reject = validate(t, rawSamples, minPatternLength);
            if (reject != null) {
                log.warn("模版被拒绝：{} | template={} pattern={}", reject, t.getTemplate(), t.getPattern());
                continue;
            }
            result.getTemplates().add(t);
        }
        result.setSuccess(true);
        log.info("【模版抽取】输入 {} 条，模型产出 {} 个，校验通过 {} 个，tokens={}+{}",
                fingerprints.size(), parsed.size(), result.getTemplates().size(),
                resp.getInputTokens(), resp.getOutputTokens());
        return result;
    }

    private String systemPrompt() {
        return "你是一个 Java 生产异常日志的模版抽取器。输入若干条异常，"
                + "为每一条抽出「异常模版」和「匹配正则」。\n\n"
                + "模版的定义：把日志中的<可变部分>替换成占位符，保留<固定语义>。\n"
                + "可变部分包括但不限于：商品名、用户名、订单号、ID、金额、"
                + "时间、IP、traceId、URL 参数、错误码后的具体数值。\n\n"
                + "示例：\n"
                + "输入：商品“夏季限定 冰美式（大杯）”未同步到渠道商城，请同步成功后再下单\n"
                + "模版：商品{商品名}未同步到渠道商城，请同步成功后再下单\n"
                + "正则：商品.{1,60}未同步到渠道商城\n\n"
                + "输入：无效的应用授权令牌，解决方案：https://open.example.com/api/errCheck?traceId=0b443f\n"
                + "模版：无效的应用授权令牌，解决方案：{URL}\n"
                + "正则：无效的应用授权令牌\n\n"
                + "输入：优惠券订单已退款，请人工对账。订单总额21.00元，优惠券抵扣20.00元\n"
                + "模版：优惠券订单已退款，请人工对账。订单总额{金额}元，优惠券抵扣{金额}元\n"
                + "正则：优惠券订单已退款\n\n"
                + "输入的每条内容是「首行 + 异常行」拼接而成，例如：\n"
                + "  doRefund Exception,orderId:123456 java.lang.RuntimeException: 支付渠道未开通退款权限\n"
                + "首行说明在做什么，异常行说明为什么失败。\n"
                + "异常行里的原因必须保留在模版里，它才是区分不同问题的关键：\n"
                + "  doRefund Exception,orderId:{订单ID} java.lang.RuntimeException: 支付渠道未开通退款权限\n"
                + "  doRefund Exception,orderId:{订单ID} java.lang.RuntimeException: 第三方接口超时\n"
                + "这是两个不同的模版，前者是业务拒绝、后者是故障，绝不能合成一个。\n\n"
                + "## 判断一个值要不要抹掉（最重要）\n"
                + "只问一个问题：同一种异常下一次再出现时，这个值会不会变？会变就必须写成占位符。\n"
                + "模版要能匹配这种异常以后的每一次出现，而不只是眼前这一条。\n"
                + "以下几类每次都不一样，一律抹掉，一个字符都不要留：\n"
                + "- 请求流水号、链路号：rid、requestId、traceId、logId、msgId、UUID，"
                + "形如 1a2b3c4d-5e6f7a8b-9c0d1e2f、550e8400-e29b-41d4-a716-446655440000\n"
                + "- 十六进制串、哈希、签名、token：形如 9f86d081884c7d659a2feaa0c55ad015\n"
                + "- 租户 / 应用 / 用户的标识：appId（形如 wx0123456789abcdef、2021000000000001）、"
                + "tenantId、userId、openId\n"
                + "- 请求参数整体：「请求信息：」后面跟的 JSON 或参数串\n"
                + "- 订单号、金额、数量、时间、IP、URL 参数\n\n"
                + "反例（真实发生过，一个 rid 裂出一个模版，每个只能匹配它自己，命中次数全是 0）：\n"
                + "输入：调用开放平台删除成员失败，请求信息：{\"appId\":\"wx0123456789abcdef\"}，错误信息："
                + " BusinessException: system error rid: 1a2b3c4d-5e6f7a8b-9c0d1e2f\n"
                + "错误的模版：调用开放平台删除成员失败，请求信息： BusinessException: system error rid: 1a2b3c4d-5e6f7a8b-9c0d1e2f\n"
                + "正确的模版：调用开放平台删除成员失败，请求信息：{参数} BusinessException: system error rid:{rid}\n\n"
                + "反例（写死了某个应用的 appId，换一个租户就匹配不上）：\n"
                + "输入：发布应用版本失败，请求信息：wx0123456789abcdef，平台响应:{\"errcode\":85013}\n"
                + "错误的模版：发布应用版本失败，请求信息：wx0123456789abcdef，平台响应:\n"
                + "正确的模版：发布应用版本失败，请求信息：{appId}，平台响应:{响应}\n\n"
                + "## 其他要求\n"
                + "1. 固定语义要保留足够长（至少 6 个字符的实词），否则无法区分不同异常\n"
                + "2. 不要把整句都抹成占位符，那样等于没有模版\n"
                + "3. 模版不超过 60 个字符。像 a=1|b=2|c=3 这种长串指标日志，"
                + "只保留开头能表明身份的部分，后面整体写成一个占位符，"
                + "不要逐个字段列出来 —— 那会把输出撑爆导致整批结果丢失\n"
                + "4. 逐字符保留原文的标点与空格，不要把半角逗号改成全角\n"
                + "5. 具体的数字和大写状态值一律抹掉：「超过4000bytes」要写成「超过{字节数}bytes」，"
                + "「dealPayResult UN_KNOWN status」要写成「dealPayResult {状态} status」。"
                + "错误码例外：「商品已售罄（601）」里的 601 是一类原因的编号，保留\n"
                + "6. 不要把框架包装当模版：「Request processing failed; nested exception is ...」"
                + "「Uncaught exception thrown」只是外壳，要写出里面真正的异常和原因\n\n"
                + "同时判断它是否属于「业务预期内」：业务规则正常拒绝（如库存不足、"
                + "账号未设置、优惠券已使用）属于业务预期；连接超时、空指针、SQL 异常、"
                + "第三方服务不可用不属于。\n\n"
                + "只输出 JSON，不要解释：\n"
                + "{\"templates\":[{\"index\":0,\"template\":\"...\","
                + "\"businessExpected\":true,\"reason\":\"一句话说明\"}]}";
    }

    private String buildUserPrompt(List<InspectFingerprint> fps, List<String> rawSamples) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下 ").append(fps.size()).append(" 条异常各抽一个模版：\n\n");
        for (int i = 0; i < fps.size(); i++) {
            InspectFingerprint fp = fps.get(i);
            sb.append("[").append(i).append("] ");
            if (notBlank(fp.getExcSimple())) {
                sb.append("异常类=").append(fp.getExcSimple()).append("　");
            }
            sb.append("\n内容：").append(pickText(fp, sample(rawSamples, i))).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 挑给模型看的文本。
     *
     * <p>优先用原始日志首行 —— 归一后的 msg_tpl 已经被正则破坏过
     * （{@code 返回代码：[} 就是砍尾砍出来的残句），拿它去抽模版等于
     * 让模型在残缺输入上猜。原始样例取不到时才退回归一结果。
     */
    private String pickText(InspectFingerprint fp, String rawSample) {
        if (notBlank(rawSample)) {
            // 必须带上异常行：首行只说「在做什么」，异常行才说「为什么失败」。
            // 只给首行的话，「支付渠道未开通退款权限」和「第三方接口超时」
            // 会被压成同一个 doRefund 模版
            String key = LogKeyText.of(rawSample);
            if (key.length() > 1) {
                return truncate(key, MAX_SAMPLE_CHARS);
            }
        }
        if (notBlank(fp.getExcCause())) {
            return truncate(LogKeyText.unwrap(fp.getExcCause()), MAX_SAMPLE_CHARS);
        }
        return truncate(nv(fp.getMsgTpl()), MAX_SAMPLE_CHARS);
    }

    private List<ExtractedTemplate> parse(String raw, int inputSize) {
        List<ExtractedTemplate> out = new ArrayList<>();
        String json = extractJson(raw);
        if (json == null) {
            log.warn("模版抽取输出中未找到 JSON：{}", truncate(raw, 200));
            return out;
        }
        try {
            JSONArray arr = JSON.parseObject(json).getJSONArray("templates");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ExtractedTemplate t = new ExtractedTemplate();
                // index 缺失时按顺序兜底，否则整批结果都对不上号
                Integer idx = o.getInteger("index");
                t.setIndex(idx == null ? i : idx);
                if (t.getIndex() < 0 || t.getIndex() >= inputSize) {
                    continue;
                }
                t.setTemplate(o.getString("template"));
                // 模型给的正则只作参考，实际用模版生成的 —— 见 patternFromTemplate
                String generated = patternFromTemplate(o.getString("template"));
                t.setPattern(generated != null ? generated : o.getString("pattern"));
                t.setModelPattern(o.getString("pattern"));
                t.setBusinessExpectedHint(Boolean.TRUE.equals(o.getBoolean("businessExpected")));
                t.setHintReason(o.getString("reason"));
                out.add(t);
            }
        } catch (Exception e) {
            log.warn("解析模版抽取结果失败：{} | 原文：{}", e.getMessage(), truncate(raw, 200));
        }
        return out;
    }

    /**
     * 校验模版。
     *
     * @return 拒绝原因；通过返回 null
     */
    /**
     * 每次都不一样的标识：带连字符的十六进制流水号（rid / requestId / UUID）、16 位以上的十六进制串。
     * 要求字母、数字都有：纯字母是单词（deadbeef），纯数字是订单号之类，由数字规则处理。
     */
    public static final Pattern VOLATILE_ID = Pattern.compile(
            "(?i)\\b(?=[0-9a-f-]*\\d)(?=[0-9a-f-]*[a-f])[0-9a-f]{6,}(?:-[0-9a-f]{2,})+"
                    + "|\\b(?=[0-9a-f]*\\d)(?=[0-9a-f]*[a-f])[0-9a-f]{16,}\\b");

    private String validate(ExtractedTemplate t, List<String> rawSamples, int minLength) {
        if (!notBlank(t.getTemplate()) || !notBlank(t.getPattern())) {
            return "模版或正则为空";
        }
        // 模型会把包装层当模版：「Request processing failed; nested exception is {nested}」
        // 只剩框架套话，唯一键冲突、第三方超时、Feign 超时会全归到它下面
        if (LogKeyText.isWrapperOnly(t.getTemplate(), 6)) {
            return "模版只剩框架包装套话，无法区分不同故障";
        }
        // 模型会把请求流水号当固定文字留下：「调用开放平台删除成员失败 … rid: 1a2b3c4d-5e6f7a8b-9c0d1e2f」
        // 一个 rid 一个模版，只能匹配它自己 —— 实测同一个异常这样裂出了 4 个永远命中 0 次的模版
        java.util.regex.Matcher id = VOLATILE_ID.matcher(t.getTemplate());
        if (id.find()) {
            return "模版里留着请求流水号/哈希「" + id.group() + "」，只能匹配它自己";
        }
        String pattern = t.getPattern().trim();
        if (pattern.length() < minLength) {
            return "正则过短(" + pattern.length() + " < " + minLength + ")，匹配面太宽";
        }
        // 只由通配构成的正则会吞掉一切。\Q...\E 里是字面量，把它们取出来数长度
        if (literalLength(pattern) < minLength) {
            return "正则中固定文字不足（" + literalLength(pattern) + " < " + minLength + "），等于通配";
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "正则无法编译：" + e.getDescription();
        }
        // 必须能匹配自己的样例，否则这个正则以后永远命中不了，形同虚设
        String sample = sample(rawSamples, t.getIndex());
        if (notBlank(sample) && !compiled.matcher(sample).find()) {
            return "正则匹配不上自身样例";
        }
        return null;
    }

    /**
     * 由模版生成匹配正则。
     *
     * <p><b>不用模型给的正则</b> —— 实测它会把模版里已抹成占位符的值又写回正则：
     * 模版是 {@code BusinessException&#123;code='&#123;错误码&#125;'&#125;}，
     * 正则却是 {@code BusinessException\&#123;code='500'\&#125;}，
     * 于是这个模版只能匹配 code=500 那一条，形同虚设。
     * 而我的校验只查「能否匹配自身样例」，恰好放过这种。
     *
     * <p>从模版生成则天然一致：占位符变通配，其余原文转义。
     *
     * @return 正则；模版里没有足够的固定文字时返回 null
     */
    public String patternFromTemplate(String template) {
        if (template == null || template.trim().isEmpty()) {
            return null;
        }
        String t = template.trim();
        StringBuilder sb = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '{') {
                int end = t.indexOf('}', i);
                if (end > i) {
                    flushLiteral(sb, literal);
                    // 占位符代表变量，而变量可能是很长的 JSON ——
                    // 实测 syncMemberByQueryPlatform 的请求参数是几百字符的 JSON，
                    // 用 .{0,80} 装不下，整个模版就永远匹配不上。
                    // 放宽到 300 仍然安全：'.' 不匹配换行，天然被限制在一行内，
                    // 而 LogKeyText 拼出的身份总长也就 360 左右
                    sb.append(WILDCARD);
                    i = end + 1;
                    continue;
                }
            }
            literal.append(c);
            i++;
        }
        flushLiteral(sb, literal);

        String pattern = trimWildcards(sb.toString());
        return pattern.isEmpty() ? null : pattern;
    }

    /**
     * 全角/半角容易被模型写串的标点，一律生成为字符类。
     *
     * <p>实测模型在中文语境里会把半角标点「顺手」改成全角：
     * 原文 {@code 内容超过4000bytes, deviceInfo:}，抽出的模版却是
     * {@code 内容超过&#123;大小&#125;bytes，deviceInfo:} —— 逗号变全角，
     * 于是生成的正则匹配不上原文，模版当场作废。
     * 模型的标点习惯约束不住，只能在匹配侧放宽。
     */
    private static final java.util.Map<Character, String> PUNCT_CLASS = new java.util.HashMap<>();

    static {
        PUNCT_CLASS.put(',', "[,，]");
        PUNCT_CLASS.put('，', "[,，]");
        PUNCT_CLASS.put(':', "[:：]");
        PUNCT_CLASS.put('：', "[:：]");
        PUNCT_CLASS.put(';', "[;；]");
        PUNCT_CLASS.put('；', "[;；]");
        PUNCT_CLASS.put('(', "[(（]");
        PUNCT_CLASS.put('（', "[(（]");
        PUNCT_CLASS.put(')', "[)）]");
        PUNCT_CLASS.put('）', "[)）]");
        PUNCT_CLASS.put('!', "[!！]");
        PUNCT_CLASS.put('！', "[!！]");
        PUNCT_CLASS.put('?', "[?？]");
        PUNCT_CLASS.put('？', "[?？]");
    }

    /**
     * 追加字面量。标点放宽成字符类，空白放宽成 {@code \s*}，其余原样转义。
     *
     * <p>连续的普通字符攒在一起再 quote，避免生成 {@code \Qa\E\Qb\E} 这种冗长形式。
     */
    private void flushLiteral(StringBuilder sb, StringBuilder literal) {
        if (literal.length() == 0) {
            return;
        }
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < literal.length()) {
            char c = literal.charAt(i);
            String cls = PUNCT_CLASS.get(c);
            boolean isSpace = Character.isWhitespace(c);
            if (cls != null || isSpace) {
                if (buf.length() > 0) {
                    appendQuoted(sb, buf.toString());
                    buf.setLength(0);
                }
                // 标点后跟不跟空格同样不该影响判定：
                // 模版写的是「bytes，deviceInfo」，原文却是「bytes, deviceInfo」，
                // 逗号后多一个空格就匹配不上了
                sb.append(cls == null ? "" : cls).append("\\s*");
                i++;
                while (i < literal.length() && Character.isWhitespace(literal.charAt(i))) {
                    i++;
                }
                continue;
            }
            buf.append(c);
            i++;
        }
        if (buf.length() > 0) {
            appendQuoted(sb, buf.toString());
        }
        literal.setLength(0);
    }

    /** 模版里写的「不带包名的异常类名」 */
    private static final Pattern BARE_EXC_CLASS = Pattern.compile(
            "(?<![\\w.$])[A-Z][\\w$]*(?:Exception|Error|Throwable)(?![\\w$])");

    /** 可选的包名前缀：{@code com.vendor.openapi.sdk.exception.} 这一段 */
    private static final String OPTIONAL_PACKAGE = "(?:[\\w$]+\\.)*";

    /**
     * 转义字面量；遇到不带包名的异常类名时，允许它前面带包名。
     *
     * <p>日志里的异常行是全限定名 {@code com.vendor.openapi.sdk.exception.BusinessException: …}，
     * 模型写模版时几乎总会省掉包名，只写 {@code BusinessException}。
     * 原样转义的话，正则要求类名前紧挨着空白或上一段文字，对不上包名，
     * 模版当场被判「匹配不上自身样例」丢弃 —— 实测抽模版经常「产出 3 个、通过 0 个」就是这个原因，
     * 钱花了，模版一个没留下。
     */
    private static void appendQuoted(StringBuilder sb, String text) {
        java.util.regex.Matcher m = BARE_EXC_CLASS.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                sb.append(Pattern.quote(text.substring(last, m.start())));
            }
            sb.append(OPTIONAL_PACKAGE).append(Pattern.quote(m.group()));
            last = m.end();
        }
        if (last < text.length()) {
            sb.append(Pattern.quote(text.substring(last)));
        }
    }

    /**
     * 去掉首尾的通配。
     *
     * <p>{@code .{0,300}未同步到渠道商城} 里前面那段通配毫无约束力，
     * 留着只会让正则更长、匹配更慢，还会虚增「正则长度」这个具体度指标。
     */
    private String trimWildcards(String pattern) {
        String p = pattern;
        boolean changed = true;
        while (changed) {
            changed = false;
            if (p.startsWith(WILDCARD)) {
                p = p.substring(WILDCARD.length());
                changed = true;
            }
            if (p.startsWith("\\s*")) {
                p = p.substring(3);
                changed = true;
            }
            if (p.endsWith(WILDCARD)) {
                p = p.substring(0, p.length() - WILDCARD.length());
                changed = true;
            }
            if (p.endsWith("\\s*")) {
                p = p.substring(0, p.length() - 3);
                changed = true;
            }
        }
        return p;
    }

    /** 截取首个 '{' 到末个 '}'，兼容裸 JSON 与被代码块包裹的情况 */
    private String extractJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : null;
    }

    /** 正则里真正起约束作用的字面量总长度。标点类不算 —— 它们不区分异常 */
    private int literalLength(String pattern) {
        int total = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\\\Q(.*?)\\\\E", java.util.regex.Pattern.DOTALL).matcher(pattern);
        boolean found = false;
        while (m.find()) {
            found = true;
            total += m.group(1).trim().length();
        }
        if (found) {
            return total;
        }
        // 模型给的正则没有 \Q\E 包裹，退回粗略统计
        return pattern.replaceAll("[.*+?\\\\\\[\\]{}()|^$\\s]", "").length();
    }

    private String sample(List<String> samples, int index) {
        if (samples == null || index < 0 || index >= samples.size()) {
            return null;
        }
        return samples.get(index);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private boolean notBlank(String s) {
        // 统一口径：字面量 "null" 不算内容（空指针的 exc_cause 就是它）
        return io.github.liufor02144.erroragent.service.InspectText.notBlank(s);
    }

    private String nv(String s) {
        return s == null ? "" : s;
    }

    /** 模版条目的构造。matchValue 用模版文本本身做标识，保证同模版不重复入库 */
    public InspectLibrary toLibrary(ExtractedTemplate t, InspectFingerprint fp, String rawSample) {
        InspectLibrary item = new InspectLibrary();
        item.setMatchType(InspectLibrary.TYPE_TEMPLATE);
        item.setMatchValue(t.getTemplate().trim());
        item.setTemplate(t.getTemplate().trim());
        item.setPattern(t.getPattern().trim());
        item.setHeadline(t.getTemplate().trim());
        // 模型的判断只作为建议落在 reason 里，business_expected 一律默认 0 ——
        // 「这个异常允不允许发生」是业务决定，不能让模型替人拍板
        item.setBusinessExpected(0);
        item.setReason(t.isBusinessExpectedHint()
                ? "AI 建议：业务预期内（" + nv(t.getHintReason()) + "），待人工确认"
                : nv(t.getHintReason()));
        item.setSampleMessage(truncate(rawSample == null ? nv(fp.getMsgTpl()) : rawSample, 2000));
        item.setFirstSeenTime(fp.getFirstSeenTime());
        item.setOperator("ai");
        return item;
    }
}
