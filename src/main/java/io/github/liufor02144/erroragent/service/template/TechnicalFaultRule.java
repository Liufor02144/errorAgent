package io.github.liufor02144.erroragent.service.template;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 技术故障判别：这条异常是不是「程序或依赖坏了」，而不是业务规则拒绝。
 *
 * <p><b>为什么要有这么一条硬规则</b>：业务预期标记是可以批量打的 —— 系统平稳时，
 * 把存量异常一次性认作业务预期是合理的省事做法。但这里有一类绝不能被顺手带进去：
 * <ul>
 *   <li>空指针 —— 空指针永远不是业务分支，它是「这里少判了一个 null」</li>
 *   <li>SQL 异常、死锁、连接失败 —— 数据层坏了，业务代码无从预期</li>
 *   <li>各类超时 —— 对端不可用的信号，今天 2 次不代表明天不是 2 万次</li>
 * </ul>
 * 这些一旦被标成业务预期就<b>永久静默</b>，而它们恰恰是最该盯的。
 * 所以批量标记必须绕过它们，AI 复核也不许把它们标成业务预期。
 *
 * <p>判据只看异常的技术特征（类名 / 关键字），不看业务文案 ——
 * 业务文案里出现「超时」两个字（如「超时退回后再验证」）不算技术故障。
 */
public final class TechnicalFaultRule {

    private TechnicalFaultRule() {
    }

    /** 堆栈帧行：at com.foo.Bar.baz(Bar.java:12) */
    private static final java.util.regex.Pattern STACK_FRAME =
            java.util.regex.Pattern.compile("(?m)^\\s*at\\s+[\\w.$]+\\(.*\\)\\s*$");

    /**
     * 剔除堆栈帧后再比对。
     *
     * <p><b>判据只能看异常身份，不能看堆栈。</b>
     * 实测「拉取第三方平台商品失败 BusinessException: 拉取商品列表异常」这条纯业务异常，
     * 因为样例里有一行 {@code at java.util.concurrent.ThreadPoolExecutor.runWorker}
     * 就被判成了技术故障 —— 而这一帧几乎每个 Java 堆栈都有。
     *
     * <p>调用方本来也不该把原始样例传进来，这里再兜一道。
     */
    private static String[] stripFrames(String[] texts) {
        String[] out = new String[texts.length];
        for (int i = 0; i < texts.length; i++) {
            out[i] = texts[i] == null ? null
                    : STACK_FRAME.matcher(texts[i]).replaceAll("");
        }
        return out;
    }

    /**
     * 技术故障的异常类名与特征串。
     *
     * <p>用词根而非全名：{@code NullPointer} 能同时覆盖
     * {@code java.lang.NullPointerException} 和框架包装后的各种写法。
     */
    private static final List<String> FAULT_SIGNS = Arrays.asList(
            // 代码缺陷
            "NullPointerException", "NullPointer",
            "IndexOutOfBounds", "ArrayIndexOutOfBounds", "StringIndexOutOfBounds",
            "ClassCastException", "NumberFormatException", "ArithmeticException",
            "ConcurrentModificationException", "UnsupportedOperationException",
            "StackOverflowError", "NoSuchMethodError", "NoClassDefFoundError",
            "IllegalStateException",
            // 数据层
            "SQLException", "SQLSyntaxError", "SQLIntegrityConstraint",
            "DataIntegrityViolation", "BadSqlGrammar", "MyBatisSystemException",
            "Deadlock", "Lock wait timeout", "Communications link failure",
            "Duplicate entry", "Duplicate key", "CannotGetJdbcConnection",
            // MySQL 的数据层错误未必以 Exception 结尾，也未必带 SQL 字样 ——
            // MysqlDataTruncation 就两样都不占。之前这条能判对纯属侥幸：
            // 靠的是堆栈里的 SQLError.createSQLException 帧，而那本就不该参与判定
            "MysqlDataTruncation", "Data truncation", "Data too long",
            "Incorrect string value", "Out of range value", "cannot be null",
            // 超时与连接
            "TimeoutException", "SocketTimeout", "ConnectTimeout", "ReadTimeout",
            "Read timed out", "connect timed out", "timeout of ",
            "ConnectException", "NoHttpResponse", "Broken pipe",
            "UnknownHostException", "SocketException", "ClosedChannelException",
            "Connection refused", "Connection reset", "Connection has been closed",
            // 资源与运行时
            "OutOfMemoryError", "GC overhead", "Too many open files",
            // 不能只写 "ThreadPool"：几乎每个 Java 堆栈都有
            // at java.util.concurrent.ThreadPoolExecutor.runWorker，见谁判谁
            "RejectedExecutionException",
            "RedisConnectionFailure", "RedisCommandTimeout", "JedisConnection",
            "UnexpectedRollbackException", "rollback-only",
            // 序列化
            "JsonParseException", "JsonMappingException", "SerializationException",
            "InvalidFormatException"
    );

    /**
     * 明确不是技术故障的例外：业务文案里含技术词。
     *
     * <p>「该券已赠出但还未被领取，领取后或<b>超时</b>退回后再进行验证」——
     * 这是业务规则，不是超时故障。不排除掉的话它会被永久挡在批量标记之外。
     */
    private static final Pattern BUSINESS_WORDING = Pattern.compile(
            "超时退回|超时未|超时后|超时取消|超时自动|已超时|订单超时|支付超时|超期");

    /**
     * 这条文本是否代表技术故障。
     *
     * @param texts 模版、异常类名、异常原因等，任一命中即算
     */
    public static boolean isFault(String... texts) {
        if (texts == null) {
            return false;
        }
        texts = stripFrames(texts);
        for (String text : texts) {
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            if (BUSINESS_WORDING.matcher(text).find()) {
                // 业务文案优先：整条都按业务处理，不再看技术特征
                return false;
            }
        }
        for (String text : texts) {
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            for (String sign : FAULT_SIGNS) {
                if (text.contains(sign)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 代码缺陷类异常：空指针、越界、类型转换……
     *
     * <p>这类异常<b>不同代码位置就是不同的 bug</b>，不能按模版跨位置合成一个事件。
     * 实测：报表服务导出报表时的空指针（出现即客诉），因为模版只有
     * 「NullPointerException」一个词，被并进了上午网关的那个空指针事件，
     * 走的是旧事件的重推节奏（4 小时后），一次都没推出来。
     *
     * <p>与之相对，超时、连接失败这类基础设施问题跨服务是同一件事，仍按模版合并。
     */
    private static final List<String> CODE_DEFECTS = Arrays.asList(
            "NullPointerException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException",
            "StringIndexOutOfBoundsException", "ClassCastException", "NumberFormatException",
            "ArithmeticException", "ConcurrentModificationException", "IllegalStateException",
            "IllegalArgumentException", "UnsupportedOperationException", "NoSuchElementException",
            "NoSuchMethodError", "NoClassDefFoundError", "StackOverflowError");

    /** 异常类（简名）是否属于代码缺陷 */
    public static boolean isCodeDefect(String excSimple) {
        return excSimple != null && CODE_DEFECTS.contains(excSimple.trim());
    }

    /** 命中的是哪一条特征，用于在说明里讲清为什么不放行 */
    public static String matchedSign(String... texts) {
        if (texts == null) {
            return null;
        }
        texts = stripFrames(texts);
        for (String text : texts) {
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            for (String sign : FAULT_SIGNS) {
                if (text.contains(sign)) {
                    return sign;
                }
            }
        }
        return null;
    }
}
