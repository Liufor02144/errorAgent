package io.github.liufor02144.erroragent;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 约束：从原始日志提取异常身份时，<b>只能走 {@link io.github.liufor02144.erroragent.service.template.LogKeyText}</b>。
 *
 * <p>这条规则是用<b>四次</b>同类错误换来的：
 * <ol>
 *   <li>SLS 侧的 msg_tpl 只取首行 —— 后来补了 exc_cause 才发现
 *       大量 ERROR 的真正原因藏在第二行</li>
 *   <li>模版抽取只取首行 —— 导致「商户未开通微信支付」（业务拒绝）和
 *       「第三方接口超时」（故障）被压成同一个 doRefund 模版。
 *       它一旦被标记为业务预期，真正的退款失败就永久静默，用户收不到钱</li>
 *   <li>模版复核又只取首行 —— 模型在信息缺失的前提下判断是否业务预期</li>
 *   <li>取到了异常行，但取的是<b>最外层包装</b> ——
 *       {@code NestedServletException: Request processing failed} 这句话
 *       几十种故障都会打，「Field 'tenant_id' doesn't have a default value」
 *       才是能拿去改代码的信息。详见 {@code RootCauseTest}</li>
 * </ol>
 *
 * <p>根因都一样：<b>拿到的不是最有信息量的那段文本</b>。
 * 首行只说「在做什么」，最外层只说「转译层失败了」，
 * 只有最深一层的根因同时说清「为什么」和「在哪」。
 * 每次都是新写一处代码时重新踩一遍，靠记性防不住，所以用测试钉死。
 *
 * <p>新增代码如果直接对原始日志取首行，这个测试会失败并告诉你用什么替代。
 */
public class LogTextConventionTest {

    private static final Path SRC = Paths.get("src/main/java/io/github/liufor02144/erroragent");

    /** 直接取首行的几种写法 */
    private static final Pattern FIRST_LINE_ONLY = Pattern.compile(
            "split\\(\"\\\\\\\\n\"\\)\\s*\\[\\s*0\\s*\\]"      // split("\\n")[0]
                    + "|split\\(\"\\\\n\"\\)\\s*\\[\\s*0\\s*\\]"  // split("\n")[0]
                    + "|lines\\s*\\[\\s*0\\s*\\]");               // lines[0]

    /**
     * 允许直接操作行的文件。
     *
     * <p>LogKeyText 是这条规则的实现者本身；
     * InspectSqlBuilder 里的 split_part 是 SLS 侧的粗聚合，
     * 它和 exc_cause 配套使用，不单独承担身份判定。
     */
    private static final List<String> ALLOWED = Arrays.asList(
            "LogKeyText.java",
            "InspectSqlBuilder.java");

    @Test
    public void 禁止绕过LogKeyText直接取原始日志首行() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String name = file.getFileName().toString();
                if (ALLOWED.contains(name)) {
                    continue;
                }
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher m = FIRST_LINE_ONLY.matcher(source);
                while (m.find()) {
                    violations.add(name + " → " + m.group());
                }
            }
        }

        if (!violations.isEmpty()) {
            Assert.fail("发现直接取原始日志首行的代码，请改用 LogKeyText.of(rawMessage)：\n  "
                    + String.join("\n  ", violations)
                    + "\n\n原因：首行只说明「在做什么」，异常行才说明「为什么失败」。"
                    + "\n只取首行会把「商户未开通微信支付」（业务拒绝）和"
                    + "「第三方接口超时」（故障）压成同一个模版，"
                    + "\n一旦标记为业务预期，真正的故障将被永久静默。"
                    + "\n这个错误已经犯过四次（第四次是取了最外层包装而不是根因），"
                    + "别再犯第五次。");
        }
    }

    @Test
    public void 约束文件本身存在() {
        // 防止 LogKeyText 被改名或删除后，上面的白名单静默失效
        Assert.assertTrue("LogKeyText 是这条约束的唯一实现，不应被移除",
                Files.exists(SRC.resolve("service/template/LogKeyText.java")));
    }
}
