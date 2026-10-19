package io.github.liufor02144.erroragent.service.code;

import io.github.liufor02144.erroragent.config.InspectProperties;
import io.github.liufor02144.erroragent.dto.StackFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 异常堆栈解析。
 */
@Slf4j
@Component
public class StackFrameParser {

    /**
     * 业务包前缀来自 {@code erroragent.naming.app-packages}，非该前缀的帧一律视为框架代码。
     *
     * <p>栈帧正则匹配 {@code at com.example.a.b.ClassName.methodName(ClassName.java:123)}：
     * 第一组贪婪匹配到最后一个点之前，因此 group(1) 是类的完整限定名、
     * group(2) 是方法名。内部类用 {@code $} 连接，仍落在 group(1) 内。
     */
    @Resource
    private InspectProperties properties;

    /** 非 Spring 场景（测试、工具）按指定的业务包前缀构造 */
    public static StackFrameParser forPackages(String... packages) {
        StackFrameParser parser = new StackFrameParser();
        parser.properties = new InspectProperties();
        parser.properties.getNaming().setAppPackages(String.join(",", packages));
        return parser;
    }

    /** 未配置业务包时返回 null，此时找不到任何业务栈帧 */
    private Pattern framePattern() {
        return properties == null ? null : properties.getNaming().javaFramePattern();
    }

    /**
     * 取第一个业务栈帧。
     *
     * <p>必须取第一个业务包的帧而不是栈顶 —— 实测栈顶大量是
     * {@code java.io.UnixFileSystem}、{@code com.xxl.job.core} 等 JDK/框架代码，
     * 用栈顶会把完全不同的业务问题聚成一个指纹。
     */
    public StackFrame firstBizFrame(String stackText) {
        if (stackText == null || stackText.isEmpty()) {
            return null;
        }
        // 从最深一层 Caused by 往外找：离真正抛出点最近的业务帧在那里。
        // 最外层的包装异常（NestedServletException）栈里全是过滤器链，
        // 第一个业务帧往往是公共包里的日志过滤器 LogFilter.doFilter，
        // 真正调用下游失败的业务类在 Caused by 里
        String[] sections = stackText.split("\n\\s*Caused by:");
        for (int i = sections.length - 1; i > 0; i--) {
            StackFrame inCause = firstBizFrameIn(sections[i]);
            if (inCause != null) {
                return inCause;
            }
        }
        return firstBizFrameIn(stackText);
    }

    private StackFrame firstBizFrameIn(String stackText) {
        Pattern pattern = framePattern();
        if (pattern == null) {
            return null;
        }
        Matcher m = pattern.matcher(stackText);
        while (m.find()) {
            StackFrame frame = toFrame(m);
            // lambda$null$N 这类嵌套 lambda 帧拿不到宿主方法名，跳过继续找下一帧，
            // 否则方法名自校验必然失败、白白降级
            if (frame.hostMethodName() != null) {
                return frame;
            }
        }
        // 全是无宿主的 lambda 帧时退回第一帧，至少还有文件与行号
        m.reset();
        return m.find() ? toFrame(m) : null;
    }

    /** 取全部业务栈帧，用于调用链上溯 */
    public List<StackFrame> bizFrames(String stackText, int limit) {
        List<StackFrame> frames = new ArrayList<>();
        Pattern pattern = framePattern();
        if (stackText == null || stackText.isEmpty() || pattern == null) {
            return frames;
        }
        Matcher m = pattern.matcher(stackText);
        while (m.find() && frames.size() < limit) {
            frames.add(toFrame(m));
        }
        return frames;
    }

    /**
     * 截断堆栈。整段堆栈动辄上百行，直接送 AI 既超 token 又稀释重点。
     */
    public String truncate(String stackText, int maxFrames) {
        if (stackText == null) {
            return null;
        }
        String[] lines = stackText.split("\n");
        StringBuilder sb = new StringBuilder();
        int frameCount = 0;
        for (String line : lines) {
            if (line.trim().startsWith("at ")) {
                if (frameCount >= maxFrames) {
                    sb.append("\t... 已截断，仅保留前 ").append(maxFrames).append(" 帧\n");
                    break;
                }
                frameCount++;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 异常消息首行（不含堆栈） */
    public String firstLine(String message) {
        if (message == null) {
            return null;
        }
        int idx = message.indexOf('\n');
        String line = idx >= 0 ? message.substring(0, idx) : message;
        return line.replaceAll("^[-\\s]+", "").trim();
    }

    public boolean isBizClass(String className) {
        return properties != null && properties.getNaming().isBizClass(className);
    }

    private StackFrame toFrame(Matcher m) {
        StackFrame frame = new StackFrame();
        frame.setClassName(m.group(1));
        frame.setMethodName(normalizeMethodName(m.group(2)));
        frame.setFileName(m.group(3));
        try {
            frame.setLineNumber(Integer.parseInt(m.group(4)));
        } catch (NumberFormatException e) {
            frame.setLineNumber(0);
        }
        return frame;
    }

    /**
     * 还原被 APM agent 改写的方法名。
     *
     * <p>SkyWalking 的字节码增强会把原方法改名为 {@code 原名$original$随机后缀}，
     * 堆栈里出现的是改写后的名字。实测生产 ERROR 日志中有 27 万条、涉及 61 个服务
     * 带这种后缀（如 {@code callDelivery$original$6TbMt25E}）。
     *
     * <p>不还原的后果不是报错，而是<b>悄悄降级</b>：拿改写后的名字去源码里找方法
     * 必然找不到，定位置信度会掉到 LOW，并给出「该方法已不存在，可能已重构」这种
     * 与事实相反的说明。
     */
    private String normalizeMethodName(String raw) {
        if (raw == null) {
            return null;
        }
        int idx = raw.indexOf("$original$");
        if (idx > 0) {
            return raw.substring(0, idx);
        }
        // 另一种形态：Lambda 与内部类会带 $ 分隔的合成后缀
        idx = raw.indexOf("$auxiliary$");
        if (idx > 0) {
            return raw.substring(0, idx);
        }
        return raw;
    }
}
