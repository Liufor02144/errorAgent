package io.github.liufor02144.erroragent;

import io.github.liufor02144.erroragent.service.InspectText;
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
 * 字符串 {@code "null"} 不是内容。
 *
 * <p>空指针没有 message，日志里 {@code exc_cause} 就是字面量 {@code "null"}。
 * 用普通的 {@code isEmpty()} 判空，它会被当成有效文本一路带到界面上。
 *
 * <p>这个坑修过两次：
 * <ol>
 *   <li>事件列表标题显示成 {@code null} —— 修了 {@code InspectLibraryService}</li>
 *   <li>飞书卡片标题渲染成 {@code **null**} —— 卡片构造里各自实现了一份
 *       {@code notBlank}，没跟着改，于是在推送链路上又冒出来一次</li>
 * </ol>
 *
 * <p>根因是<b>同一个判断散在多处各自实现</b>（曾经有五份 {@code notBlank}）。
 * 所以收敛到 {@link InspectText}，并用下面的扫描钉住：
 * 谁再在 inspect 包里手写一份不排除 "null" 的判空，这个测试就会失败。
 */
public class NullLiteralTest {

    private static final Path SRC = Paths.get("src/main/java/io/github/liufor02144/erroragent");

    /** 手写判空：v != null && !v.trim().isEmpty() 这类形状 */
    private static final Pattern LOCAL_NOTBLANK = Pattern.compile(
            "boolean\\s+notBlank\\s*\\([^)]*\\)\\s*\\{\\s*return\\s+[^;]*!=\\s*null[^;]*isEmpty\\(\\)");

    /**
     * 允许自带判空的文件。
     *
     * <p>这些是配置与 SQL 拼接场景 —— 那里的 "null" 是真的字符串值，
     * 不是「异常没有 message」的占位，不该被当成空。
     */
    private static final List<String> ALLOWED = Arrays.asList(
            "InspectText.java",
            "InspectProperties.java",
            "InspectConfigService.java",
            "InspectSqlBuilder.java");

    @Test
    public void 字面量null不算有内容() {
        Assert.assertFalse(InspectText.notBlank("null"));
        Assert.assertFalse(InspectText.notBlank("NULL"));
        Assert.assertFalse(InspectText.notBlank("  null  "));
        Assert.assertFalse(InspectText.notBlank("-"));
        Assert.assertFalse(InspectText.notBlank(""));
        Assert.assertFalse(InspectText.notBlank(null));
        // 但包含 null 的正常句子是内容
        Assert.assertTrue(InspectText.notBlank("系统异常 原因：null uri:/base/info/detail"));
        Assert.assertTrue(InspectText.notBlank("nullable 字段未设置"));
    }

    @Test
    public void 空指针的标题要退到消息模版或异常类() {
        // 这就是线上那条：exc_cause 是字面量 "null"
        String headline = InspectText.firstNotBlank(
                "null",                                  // excCause
                "doRefund Exception,orderId:{NUM}",      // msgTpl
                "NullPointerException");                 // excSimple
        Assert.assertEquals("doRefund Exception,orderId:{NUM}", headline);

        // 消息模版也空时，异常类名就是它的身份
        Assert.assertEquals("NullPointerException",
                InspectText.firstNotBlank("null", null, "NullPointerException"));
    }

    @Test
    public void 兜底文案在全空时生效() {
        Assert.assertEquals("无可用描述", InspectText.or("null", "无可用描述"));
        Assert.assertEquals("正常内容", InspectText.or("正常内容", "无可用描述"));
    }

    @Test
    public void 禁止在inspect包里另写一份判空() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String name = file.getFileName().toString();
                if (ALLOWED.contains(name)) {
                    continue;
                }
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher m = LOCAL_NOTBLANK.matcher(source);
                while (m.find()) {
                    violations.add(name);
                }
            }
        }

        if (!violations.isEmpty()) {
            Assert.fail("发现自己实现的判空，请改为委托 InspectText.notBlank：\n  "
                    + String.join("\n  ", violations)
                    + "\n\n原因：空指针异常的 exc_cause 是字面量 \"null\"，"
                    + "不排除它的话会一路带到界面上 ——"
                    + "\n事件标题显示成 null、飞书卡片渲染成 **null**。"
                    + "\n这个坑已经在两个不同的地方各犯过一次，因为判空散在多处各自实现。");
        }
    }
}
