package io.github.liufor02144.erroragent.service.template;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从原始日志里取出「决定异常身份」的那段文本。
 *
 * <p><b>只取首行是不够的</b> —— 首行往往只是上下文，真正的原因在异常行：
 * <pre>
 * doRefund Exception,orderId:123456                      ← 首行：在做什么
 * java.lang.RuntimeException: 商户未开通微信支付，请联系商家   ← 异常行：为什么失败
 * </pre>
 * 只拿首行抽模版，会把「商户未开通微信支付」和「第三方接口超时」
 * 压成同一个模版 {@code doRefund Exception,orderId:&#123;订单ID&#125;}。
 * 一旦它被标记为业务预期，真正的退款故障就再也不会报 —— 而那意味着用户收不到钱。
 *
 * <p><b>取第一条异常行同样不够</b> —— 框架会层层包装，最外层永远是那句通用废话：
 * <pre>
 * Servlet.service() ... threw exception [Request processing failed;
 *     nested exception is org.springframework.dao.DataIntegrityViolationException:
 * ### Cause: java.sql.SQLException: Field 'tenant_id' doesn't have a default value ← 根因在这
 * ; ... ] with root cause
 * java.sql.SQLException: Field 'tenant_id' doesn't have a default value            ← 或者这
 * </pre>
 * 取到 {@code NestedServletException: Request processing failed} 等于什么都没取到：
 * 几十种完全不同的故障会共用这一个模版。<b>必须取到最深的那层根因</b>，
 * 「哪张表少了默认值」才是能拿去改代码的信息。
 *
 * <p>所以身份 = 首行（做什么）+ 根因（为什么），两者拼成<b>单行</b>。
 * 拼成单行是必须的：生成的正则里用 {@code .} 做通配，而 Java 正则的
 * {@code .} 默认不跨换行。
 *
 * <p>抽取与匹配<b>必须共用这一份逻辑</b>，否则模版抽自 A 形式、匹配却按 B 形式，
 * 永远对不上。
 */
public final class LogKeyText {

    /**
     * 异常行。
     *
     * <p><b>不能假设异常类都以 Exception / Error 结尾</b> ——
     * {@code com.mysql.jdbc.MysqlDataTruncation} 就不是，于是
     * 「Data too long for column 'push_content'」这句真正的根因被整条跳过，
     * 模版退化成最外层那个连 message 都没有的 DataIntegrityViolationException。
     *
     * <p>所以按两种形状识别：
     * <ol>
     *   <li><b>全限定类名</b>：有包名、且最后一段首字母大写 ——
     *       {@code com.mysql.jdbc.MysqlDataTruncation:}。
     *       要求末段大写是为了排除 {@code https://api.example.com:443}
     *       这类 URL 里的冒号</li>
     *   <li><b>裸类名 + 异常后缀</b>：{@code BusinessException:} /
     *       {@code StackOverflowError:} —— 没有包名时只能靠后缀认</li>
     * </ol>
     */
    private static final Pattern EXC_LINE = Pattern.compile(
            "[a-z][a-zA-Z0-9_$]*(?:\\.[a-zA-Z0-9_$]+)*\\.[A-Z][a-zA-Z0-9_$]*\\s*:"
                    + "|[A-Za-z0-9_$]*(?:Exception|Error|Throwable)\\s*:");

    /** 根因标记：Java 的 Caused by、Spring 的 with root cause、MyBatis 的 ### Cause */
    private static final Pattern CAUSED_BY = Pattern.compile("^Caused by\\s*:\\s*");
    private static final Pattern MYBATIS_CAUSE = Pattern.compile("^#+\\s*Cause\\s*:\\s*");
    private static final String ROOT_CAUSE_MARK = "with root cause";

    /**
     * 首行里的包装噪音起点。
     *
     * <p>{@code threw exception [Request processing failed; nested exception is ...]}
     * 这一整段是框架转译层，不同故障的这段文本完全一样，留着只会把模版撑长、
     * 并把 200 字上限吃光，导致真正的根因被截断。
     */
    private static final Pattern WRAPPER_TAIL = Pattern.compile(
            "\\s*(?:threw exception \\[|nested exception is|: Request processing failed).*$");

    /**
     * 异常行开头的框架包装层。
     *
     * <p>{@code NestedServletException: Request processing failed; nested exception is X: ...}、
     * {@code MyBatisSystemException: nested exception is X: ...} 这类前缀只是框架转译，
     * 各种完全不同的故障共用同一段文本。不剥掉的话，模版会被抽成
     * 「Request processing failed; nested exception is &#123;nested&#125;」，
     * 唯一键冲突、第三方接口超时、Feign 超时全被归成一个事件。
     *
     * <p>只剥「没有自己原因」的包装层：{@code ResourceAccessException: I/O error on POST ...;
     * nested exception is ...} 的前半段带着请求地址，是有用的信息，保留。
     */
    private static final Pattern WRAPPER_HEAD = Pattern.compile(
            "^(?:[\\w$.]+\\s*:\\s*)?(?:Request processing failed\\s*;\\s*)?nested exception is\\s+");

    /**
     * 只由框架套话构成的文本。模版去掉占位符和这些套话后没剩实质内容，
     * 就无法区分不同故障，不能入库。
     */
    private static final Pattern WRAPPER_WORDS = Pattern.compile(
            "(?i)Request processing failed|nested exception is|Uncaught exception thrown"
                    + "|NestedServletException|Servlet\\.service\\(\\)|for servlet|threw exception"
                    + "|with root cause|[\\w$.]*(?:NestedServletException|MyBatisSystemException"
                    + "|UndeclaredThrowableException)");

    /** 包装套话本身：模版里出现了它们，才需要检查是否只剩套话 */
    private static final Pattern WRAPPER_MARK = Pattern.compile(
            "(?i)Request processing failed|nested exception is|Uncaught exception thrown|threw exception");

    /** 前导符号：采集侧会给部分行加 "- "，同一条日志因此出现两种形态 */
    private static final Pattern LEADING = Pattern.compile("^[-\\s]+");

    /**
     * 往下找根因的行数上限。
     *
     * <p>比原来的 8 行放宽了：MyBatis 的报错会先铺 {@code ### Error updating database}
     * 、{@code ### The error may involve}、{@code ### SQL: ...}（SQL 本身可能占好几行），
     * 真正的 {@code ### Cause} 和 {@code with root cause} 排在后面。8 行够不到。
     */
    private static final int MAX_LOOKAHEAD = 40;

    private static final int MAX_FIRST = 200;
    private static final int MAX_CAUSE = 160;

    private LogKeyText() {
    }

    /**
     * 提取关键文本。
     *
     * @param rawMessage 原始日志（可能多行含堆栈）
     * @return 「首行 + 根因」拼成的单行；取不到时返回空串
     */
    public static String of(String rawMessage) {
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            return "";
        }
        String[] lines = rawMessage.split("\n");

        String first = strip(lines.length > 0 ? lines[0] : "");
        // 部分 SDK（如微信支付）首行只有一个空格，内容在第二行
        if (first.length() <= 1 && lines.length > 1) {
            first = strip(lines[1]);
        }
        String head = stripWrapper(first);

        String cause = rootCause(lines, first);
        if (cause.isEmpty()) {
            return truncate(head, MAX_FIRST);
        }
        if (head.isEmpty() || cause.contains(head)) {
            return truncate(cause, MAX_FIRST);
        }
        return truncate(head, MAX_FIRST) + " " + truncate(cause, MAX_CAUSE);
    }

    /**
     * 这条日志的根因文本，供兜底模版直接使用。
     *
     * @return 根因；找不到时返回空串
     */
    public static String rootCauseOf(String rawMessage) {
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            return "";
        }
        String[] lines = rawMessage.split("\n");
        String first = strip(lines.length > 0 ? lines[0] : "");
        return rootCause(lines, first);
    }

    /**
     * 找最深一层的根因。
     *
     * <p>优先级从高到低 —— 越靠后的标记越接近真正的原因：
     * <ol>
     *   <li>{@code with root cause} 之后的异常行 —— Spring 明确标出的根因</li>
     *   <li>最后一个 {@code Caused by:} —— Java 异常链，最后一条即最深</li>
     *   <li>最后一个 {@code ### Cause:} —— MyBatis 把真正的 SQLException 放这</li>
     *   <li>第一条异常行 —— 没有链的简单情况</li>
     * </ol>
     */
    private static String rootCause(String[] lines, String first) {
        int limit = Math.min(lines.length, MAX_LOOKAHEAD);
        String firstExc = "";
        String lastCausedBy = "";
        String lastMyBatis = "";
        boolean rootMarkSeen = false;

        for (int i = 0; i < limit; i++) {
            String line = strip(lines[i]);
            if (line.isEmpty() || line.startsWith("at ")) {
                continue;
            }

            // Spring 把根因标在方括号收尾处，紧随其后的异常行就是根因
            if (line.contains(ROOT_CAUSE_MARK)) {
                rootMarkSeen = true;
                continue;
            }
            if (rootMarkSeen && EXC_LINE.matcher(line).find()) {
                return unwrap(line);
            }

            Matcher causedBy = CAUSED_BY.matcher(line);
            if (causedBy.find()) {
                lastCausedBy = line.substring(causedBy.end()).trim();
                continue;
            }
            // ### Cause: 后面的内容【按定义】就是根因，不必再拿正则去验它像不像异常行。
            // 之前在这里加了 EXC_LINE 校验，结果 MysqlDataTruncation 这种
            // 不以 Exception 结尾的类名被判成「不是异常」，整条根因被丢掉。
            Matcher mybatis = MYBATIS_CAUSE.matcher(line);
            if (mybatis.find()) {
                String tail = line.substring(mybatis.end()).trim();
                if (usableCause(tail)) {
                    lastMyBatis = tail;
                }
                continue;
            }
            // MyBatis 首句：### Error updating database.  Cause: java.sql.SQLException: ...
            int inlineCause = line.indexOf("Cause:");
            if (line.startsWith("#") && inlineCause >= 0) {
                String tail = line.substring(inlineCause + "Cause:".length()).trim();
                if (usableCause(tail)) {
                    lastMyBatis = tail;
                }
                continue;
            }

            if (firstExc.isEmpty() && EXC_LINE.matcher(line).find() && !line.equals(first)) {
                firstExc = line;
            }
        }

        if (!lastCausedBy.isEmpty()) {
            return unwrap(lastCausedBy);
        }
        if (!lastMyBatis.isEmpty()) {
            return unwrap(lastMyBatis);
        }
        return unwrap(firstExc);
    }

    /**
     * 剥掉异常行开头的框架包装层，只留真正的异常。
     *
     * <p>{@code NestedServletException: Request processing failed; nested exception is
     * org.springframework.dao.DuplicateKeyException: ...} → {@code org.springframework.dao.DuplicateKeyException: ...}
     *
     * @return 剥完的文本；剥完为空时原样返回
     */
    public static String unwrap(String line) {
        if (line == null) {
            return null;
        }
        String cur = line.trim();
        // 包装可能多层：Servlet 包 MyBatis，MyBatis 再包真正的异常
        for (int i = 0; i < 5; i++) {
            Matcher m = WRAPPER_HEAD.matcher(cur);
            if (!m.find() || m.end() >= cur.length()) {
                break;
            }
            cur = cur.substring(m.end()).trim();
        }
        return cur.isEmpty() ? line.trim() : cur;
    }

    /**
     * 模版是否只剩框架套话：含包装套话，且去掉占位符、套话、包装类名和标点后，实质文字不足 minChars。
     *
     * <p>不含套话的模版一律放过 —— {@code java.lang.NullPointerException} 这种按代码位置区分的
     * 模版文字虽短，但不是包装层，不归这里管。
     */
    public static boolean isWrapperOnly(String template, int minChars) {
        if (template == null || !WRAPPER_MARK.matcher(template).find()) {
            return false;
        }
        String rest = template.replaceAll("\\{[^{}]*\\}", " ");
        rest = WRAPPER_WORDS.matcher(rest).replaceAll(" ");
        rest = rest.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
        return rest.length() < minChars;
    }

    /** 根因段是否有内容。只挡空串和残句，不对形状做假设 */
    private static boolean usableCause(String tail) {
        return tail != null && tail.trim().length() >= 4;
    }

    /** 砍掉首行里的框架转译段，只留「在做什么」 */
    private static String stripWrapper(String first) {
        String cut = WRAPPER_TAIL.matcher(first).replaceFirst("").trim();
        // 砍完只剩标点或空壳时宁可保留原文
        return cut.length() >= 4 ? cut : first;
    }

    private static String strip(String line) {
        return line == null ? "" : LEADING.matcher(line).replaceFirst("").trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
