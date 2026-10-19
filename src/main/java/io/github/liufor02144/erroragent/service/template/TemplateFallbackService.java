package io.github.liufor02144.erroragent.service.template;

import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectLibrary;
import io.github.liufor02144.erroragent.entity.InspectSample;
import io.github.liufor02144.erroragent.mapper.InspectLibraryMapper;
import io.github.liufor02144.erroragent.mapper.InspectSampleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 兜底模版：保证<b>每个异常事件都有模版归属</b>。
 *
 * <p>为什么这是硬约束，而不是「有就好」：
 * <ul>
 *   <li><b>同一异常会被拆成多条事件</b> —— 一条日志既能以裸消息出现（msg_tpl），
 *       又能被包装成异常抛出（exc_cause）。这是两个指纹，没有模版把它们收拢时，
 *       页面上就是两条一模一样的事件</li>
 *   <li><b>系统里没有可操作的载体</b> —— 标注业务预期、AI 复核、查覆盖范围
 *       全都挂在模版上。没有模版，这条异常就只能被反复看见、无法被处理</li>
 * </ul>
 *
 * <p>兜底模版是<b>临时的</b>：它直接用归一后的文本，粒度等于指纹粒度，
 * 不做泛化。AI 抽取跑过之后会产出更通用的模版，兜底的那条自然被更具体/更通用的替代。
 * 用 {@code operator = "auto"} 标出来，便于事后甄别。
 */
@Slf4j
@Service
public class TemplateFallbackService {

    /** 归一后仍然没有意义的文本 —— exc_cause 常常就是字符串 "null" */
    private static final int MIN_TEXT = 4;

    @Resource
    private InspectLibraryMapper libraryMapper;

    @Resource
    private InspectSampleMapper sampleMapper;

    @Resource
    private TemplateExtractor templateExtractor;

    @Resource
    private TemplateMatcher templateMatcher;

    /**
     * 拿到这个指纹的模版：能匹配上就用现成的，匹配不上就当场建一条。
     *
     * @return 模版；文本实在不足以生成安全正则时返回 null
     */
    public InspectLibrary ensureTemplate(InspectFingerprint fp) {
        String raw = latestSample(fp);
        InspectLibrary hit = templateMatcher.match(fp, raw);
        if (hit != null) {
            return hit;
        }

        String text = templateText(fp, raw);
        if (text == null) {
            return null;
        }
        String pattern = templateExtractor.patternFromTemplate(text);
        if (pattern == null) {
            // 固定文字不足，生成的正则会匹配一切 —— 这种模版比没有更危险
            return null;
        }

        // 空指针、SQL、超时这类绝不能默认放行，见 TechnicalFaultRule
        boolean fault = TechnicalFaultRule.isFault(
                text, fp.getExcSimple(), fp.getExcCause(), fp.getMsgTpl());
        String sign = fault ? TechnicalFaultRule.matchedSign(
                text, fp.getExcSimple(), fp.getExcCause(), fp.getMsgTpl()) : null;

        InspectLibrary item = new InspectLibrary();
        item.setMatchType(InspectLibrary.TYPE_TEMPLATE);
        item.setMatchValue(text);
        item.setTemplate(text);
        item.setPattern(pattern);
        item.setHeadline(text);
        item.setBusinessExpected(0);
        item.setReason(fault
                ? "采集时自动登记；命中技术故障特征「" + sign + "」，不参与批量放行，需要人工分析"
                : "采集时自动登记的兜底模版，默认按系统故障对待，待人工确认");
        item.setSampleMessage(raw);
        item.setOperator("auto");

        try {
            if (libraryMapper.insertIgnore(item) == 0) {
                // 并发下已被别人插进去了，refresh 后按正常路径取
                templateMatcher.refresh();
                return templateMatcher.match(fp, raw);
            }
        } catch (Exception e) {
            log.warn("兜底模版登记失败：{} —— {}", text, e.getMessage());
            return null;
        }
        templateMatcher.refresh();
        InspectLibrary created = templateMatcher.match(fp, raw);
        if (created != null) {
            log.info("【兜底模版】{}{}", fault ? "[技术故障] " : "", text);
        }
        return created;
    }

    private String latestSample(InspectFingerprint fp) {
        if (fp.getId() == null) {
            return null;
        }
        try {
            InspectSample sample = sampleMapper.selectLatestByFpId(fp.getId());
            return sample == null ? null : sample.getRawMessage();
        } catch (Exception ignored) {
            // 没样例就退回归一文本，匹配率低一些但不影响正确性
            return null;
        }
    }

    /** 模版文本上限：再长也不会提高辨识度，只会把整段 JSON 焊死进模版 */
    private static final int MAX_TEXT = 200;

    /**
     * 挑一段能代表这个异常身份的文本。
     *
     * <p><b>必须用归一后的字段，不能用原始样例</b> —— 原始日志里带着
     * {@code orderId:100000000000000000000001} 这样的具体值，拿它当模版的话
     * 每个订单号都会生成一条新模版，模版库会被撑爆而且永远匹配不到第二次。
     * 归一字段里这些已经是 {@code {NUM}} / {@code {TIME}}，正好变成通配。
     *
     * <p>形状仿照 {@link LogKeyText}：<b>消息 + 异常原因</b>。只取消息会犯
     * 「doRefund 吞掉真故障」那个错 —— 消息说「在做什么」，原因才说「为什么失败」。
     *
     * <p>字符串 {@code "null"} 一律跳过：NPE 的 exc_cause 就是字面量 "null"，
     * 此时异常类名才是它的身份。
     */
    private String templateText(InspectFingerprint fp, String raw) {
        StringBuilder sb = new StringBuilder();
        if (usable(fp.getMsgTpl()) && !isFrameworkHead(fp.getMsgTpl())) {
            sb.append(fp.getMsgTpl().trim());
        }

        // 原因段：有具体原因用原因，原因是 "null" 就用异常类名
        String cause = usable(fp.getExcCause()) ? fp.getExcCause().trim() : null;
        String simple = usable(fp.getExcSimple()) ? fp.getExcSimple().trim() : null;

        // 根因优先。SLS 侧取到的 exc_cause 有两种不够用的情形：
        //   ① 最外层包装 —— NestedServletException: Request processing failed
        //      这种通用废话，几十种故障共用一句
        //   ② 被截断 —— 实测 exc_cause 只到「Duplicate key ProductAttrMapping(id=」
        //      就没了，恰好把有区分度的实体字段砍掉
        // 原始日志里 ### Cause / Caused by / with root cause 后面才是
        // 「Field 'tenant_id' doesn't have a default value」这类能直接改代码的信息。
        String root = usable(raw) ? normalizeValues(LogKeyText.rootCauseOf(raw)) : null;
        if (usable(root) && (cause == null || isWrapper(cause) || root.contains(cause))) {
            // root 包含 cause 说明 cause 只是 root 的一截，root 信息量严格更大
            cause = root;
            simple = null;
        }

        String tail = null;
        if (cause != null) {
            tail = simple != null && !cause.startsWith(simple)
                    ? simple + ": " + cause : cause;
        } else if (simple != null) {
            tail = simple;
        }
        if (tail != null && sb.indexOf(tail) < 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(tail);
        }

        if (sb.length() == 0) {
            // 归一字段全空，只能退回原始样例的关键行
            String key = LogKeyText.of(raw);
            if (!usable(key)) {
                return null;
            }
            sb.append(key);
        }
        // 归一放在最后统一做一次：不管这段文本来自根因、exc_cause 还是 msg_tpl，
        // 具体标识都必须变成占位符。只在根因那条分支归一会漏 ——
        // 实测漏出了 Field 'tenant_id' 的字面量版本，和归一版本重复成两条模版。
        String text = normalizeValues(sb.toString().trim());
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    /**
     * 框架转译层的通用文案 —— 取到这些等于什么都没取到。
     *
     * <p>{@code NestedServletException: Request processing failed} 这一句
     * 几十种完全不同的故障都会打，拿它当模版会把它们全合成一条，
     * 然后这条模版永远「待确认」且毫无诊断价值。
     */
    private static final String[] WRAPPER_CAUSES = {
            "Request processing failed",
            "NestedServletException",
            "UndeclaredThrowableException",
            "InvocationTargetException",
            "Error updating database",
            "Error querying database",
            // Spring 把 SQLException 翻译成自己的异常体系，这些都是转译层，
            // 真正的 java.sql.SQLException 在更深一层
            "DataIntegrityViolationException",
            "DuplicateKeyException",
            "DataAccessResourceFailureException",
            "CannotAcquireLockException",
            "TransactionSystemException",
            "could not execute statement",
            "Transaction rolled back because it has been marked as rollback-only"
    };

    private static boolean isWrapper(String cause) {
        for (String w : WRAPPER_CAUSES) {
            if (cause.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 框架入口的首行 —— 它说的是「异常从哪冒出来的」，不是「出了什么事」。
     *
     * <p>同一个根因会从多个入口冒出来：Web 请求走 {@code Servlet.service()}，
     * 线程池里走 {@code Uncaught exception thrown}，定时任务又是另一句。
     * 把它拼进模版的话，<b>一个根因会散成好几条模版</b>，
     * 「这类问题一共出了多少次」就再也数不出来。入口信息在详情页的样例里有。
     */
    private static final String[] FRAMEWORK_HEADS = {
            "Servlet.service",
            "Uncaught exception thrown",
            "Unhandled failure",
            "Exception in thread",
            "Unexpected error occurred",
            "dispatcherServlet"
    };

    private static boolean isFrameworkHead(String msg) {
        String t = msg.trim();
        for (String h : FRAMEWORK_HEADS) {
            if (t.startsWith(h) || t.contains(h)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把根因里的具体值归一成占位符。
     *
     * <p>根因来自原始日志，没经过 SLS 侧的归一 —— 里面是
     * {@code Duplicate entry '100000000000000000000001-ab-20260101120000-4'}
     * 这样的具体值。不归一的话每条记录都会生成一个新模版。
     *
     * <p><b>模版是故障类型，具体标识是参数。</b>
     * {@code Field 'tenant_id' doesn't have a default value} 的故障类型是
     * 「某个字段没有默认值」，{@code tenant_id} 只是这次碰上的那个字段。
     * 留着字段名的话，下次换成 {@code user_id} 就要再建一条模版，
     * 同一个毛病散成几十条，也就看不出「这类问题一共出了多少次」。
     * 具体是哪个字段在详情页的原始样例里看得到。
     *
     * <p>但<b>引号里的中文不能动</b> ——
     * {@code BusinessException{code='500', desc='商品已下架'}} 里的
     * {@code 商品已下架} 正是区分度本身，归一掉就回到了「doRefund 吞掉真故障」那个错误。
     * 判据：<b>ASCII 标识符是参数，人话是身份。</b>
     */
    public static String normalizeValues(String cause) {
        if (cause == null) {
            return null;
        }
        // 顺序有讲究：引号内的整体先处理完，再动裸数字。
        // 反过来的话 '100000000000000000000001-ab-4' 会先被切成 '{NUM}-ab-4'，
        // 长度掉到阈值以下，引号规则就再也盖不住它了（实测踩到）。
        return cause
                // 请求流水号 / UUID / 长哈希：rid: 1a2b3c4d-5e6f7a8b-9c0d1e2f 每次都不同，
                // 留着就是一个 rid 一个模版。要求字母、数字都有：纯字母是单词，纯数字交给后面的数字规则
                .replaceAll("(?i)\\b(?=[0-9a-f-]*\\d)(?=[0-9a-f-]*[a-f])[0-9a-f]{6,}(?:-[0-9a-f]{2,})+", "{ID}")
                .replaceAll("(?i)\\b(?=[0-9a-f]*\\d)(?=[0-9a-f]*[a-f])[0-9a-f]{16,}\\b", "{HASH}")
                // 微信小程序 appId：wx + 16 位十六进制
                .replaceAll("\\bwx[0-9a-f]{16}\\b", "{appId}")
                // Java Date 的 toString：Thu Jan 01 12:00:00 CST 2026
                .replaceAll("\\w{3} \\w{3} \\d{2} \\d{2}:\\d{2}:\\d{2} \\w+ \\d{4}", "{TIME}")
                // 引号里的 ASCII 标识符：字段名、索引名、表名、枚举名
                .replaceAll("'[A-Za-z_][A-Za-z0-9_.$]*'", "'{标识}'")
                // 引号里的纯数字：错误码之类
                .replaceAll("'\\d+'", "'{NUM}'")
                // 引号里剩下的长值（含连字符的复合主键等）。
                // 排除中文与空格 —— 带空格的是人话（'not found'），不是标识
                .replaceAll("'[^'\\s\\u4e00-\\u9fa5]{8,}'", "'{值}'")
                // IP 地址
                .replaceAll("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b", "{IP}")
                // 裸的长数字串（订单号、时间戳）
                .replaceAll("\\d{6,}", "{NUM}")
                // 键值对里的数字：id=40 / channelId:40 —— 短 ID 也是参数，
                // 留着的话每个租户、每个渠道都会生成一条模版
                .replaceAll("=\\d+", "={NUM}")
                .replaceAll("([:：])\\d+", "$1{NUM}")
                .trim();
    }

    /** "null" / "-" 这类占位字符串不算有效文本 */
    private static boolean usable(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        return t.length() >= MIN_TEXT
                && !"null".equalsIgnoreCase(t)
                && !"-".equals(t);
    }
}
