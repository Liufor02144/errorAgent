package io.github.liufor02144.erroragent.service.code;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Java 方法体提取与行号自校验，基于 JavaParser AST。
 *
 * <p>早期版本用「括号计数 + 正则」手写解析，问题在于它会<b>静默给出错误的方法边界</b>：
 * 跨行的方法签名直接漏掉、复杂泛型匹配失败、重载方法只能取第一个 —— 这些都不报错，
 * 而错误的代码片段喂给 AI 就是错误的根因，正是本方案一直要避免的「静默错位」。
 * AST 解析在这些场景全部正确，并且能按签名区分重载。
 */
@Slf4j
@Component
public class JavaMethodExtractor {

    /**
     * JavaParser 实例非线程安全，用 ThreadLocal 复用而不是每次新建。
     *
     * <p>语言级别取 JDK 17：解析器向下兼容，级别调高只会让它能读更多语法，
     * 不会影响 JDK 8 代码的解析结果；调低反而可能因为个别新语法而整文件解析失败。
     */
    private static final ThreadLocal<JavaParser> PARSER = ThreadLocal.withInitial(() ->
            new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                    .setAttributeComments(false)));

    @Data
    public static class MethodRange {
        private String name;
        /** 1-based，含签名行 */
        private int startLine;
        /** 1-based，含收尾花括号 */
        private int endLine;
        /** 完整签名，如 {@code cancelDelivery(Long, String)}，用于区分重载 */
        private String signature;

        public boolean contains(int line) {
            return line >= startLine && line <= endLine;
        }
    }

    /**
     * 解析出文件中的全部方法（含构造器）。
     *
     * <p>解析失败时返回空列表，由调用方降级为「无法定位方法」——
     * 宁可明确说定位不到，也不给一个可能错误的范围。
     */
    public List<MethodRange> parseMethods(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }
        return parseSource(String.join("\n", lines));
    }

    public List<MethodRange> parseSource(String source) {
        List<MethodRange> methods = new ArrayList<>();
        if (source == null || source.trim().isEmpty()) {
            return methods;
        }
        try {
            ParseResult<CompilationUnit> result = PARSER.get().parse(source);
            if (!result.isSuccessful() || !result.getResult().isPresent()) {
                log.debug("源码解析失败：{}", result.getProblems().isEmpty()
                        ? "未知原因" : result.getProblems().get(0).getMessage());
                return methods;
            }
            CompilationUnit cu = result.getResult().get();
            cu.findAll(MethodDeclaration.class).forEach(m -> addRange(methods, m, m.getNameAsString()));
            cu.findAll(ConstructorDeclaration.class).forEach(c -> addRange(methods, c, c.getNameAsString()));
            // 静态代码块里也可能抛异常，行号需要能落到某个范围内
            cu.findAll(InitializerDeclaration.class).forEach(i -> {
                MethodRange r = toRange(i, i.isStatic() ? "<clinit>" : "<init-block>");
                if (r != null) {
                    r.setSignature(r.getName());
                    methods.add(r);
                }
            });
        } catch (Exception e) {
            log.debug("源码解析异常：{}", e.getMessage());
        }
        return methods;
    }

    private void addRange(List<MethodRange> methods, CallableDeclaration<?> decl, String name) {
        MethodRange r = toRange(decl, name);
        if (r != null) {
            r.setSignature(decl.getSignature().asString());
            methods.add(r);
        }
    }

    private MethodRange toRange(Node node, String name) {
        Optional<com.github.javaparser.Position> begin = node.getBegin();
        Optional<com.github.javaparser.Position> end = node.getEnd();
        if (!begin.isPresent() || !end.isPresent()) {
            return null;
        }
        MethodRange r = new MethodRange();
        r.setName(name);
        r.setStartLine(begin.get().line);
        r.setEndLine(end.get().line);
        return r;
    }

    /**
     * 目标行所在的方法。
     *
     * <p>嵌套时取<b>范围最小</b>的那个：匿名内部类、lambda 内的方法声明都嵌在外层方法里，
     * 取最内层才是异常真正发生的位置。
     */
    public MethodRange findMethodAt(List<String> lines, int lineNumber) {
        MethodRange best = null;
        for (MethodRange m : parseMethods(lines)) {
            if (!m.contains(lineNumber)) {
                continue;
            }
            if (best == null || (m.getEndLine() - m.getStartLine()) < (best.getEndLine() - best.getStartLine())) {
                best = m;
            }
        }
        return best;
    }

    /** 按名字找方法；重载时返回第一个。需要处理重载歧义时改用 {@link #findMethodsByName} */
    public MethodRange findMethodByName(List<String> lines, String methodName) {
        List<MethodRange> list = findMethodsByName(lines, methodName);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按名字找出全部同名方法。
     *
     * <p>堆栈只提供方法名、不含参数签名，因此重载方法无法从堆栈区分。
     * 返回全部候选，由调用方据此降低置信度并如实说明，而不是闷头取第一个。
     */
    public List<MethodRange> findMethodsByName(List<String> lines, String methodName) {
        if (methodName == null) {
            return Collections.emptyList();
        }
        List<MethodRange> matched = new ArrayList<>();
        for (MethodRange m : parseMethods(lines)) {
            if (methodName.equals(m.getName())) {
                matched.add(m);
            }
        }
        return matched;
    }

    /**
     * 生成带行号的源码片段，并在目标行加高亮标记。
     *
     * @param highlightLine 需要高亮的行号，0 表示不高亮
     */
    public String extractSnippet(List<String> lines, int startLine, int endLine, int highlightLine) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        int from = Math.max(1, startLine);
        int to = Math.min(lines.size(), endLine);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(i == highlightLine ? "→ " : "  ")
                    .append(String.format("%5d", i))
                    .append(" | ")
                    .append(lines.get(i - 1))
                    .append('\n');
        }
        return sb.toString();
    }

    /** 目标行前后各取若干行，用于方法解析失败时的兜底片段 */
    public String extractAround(List<String> lines, int centerLine, int radius) {
        if (lines == null || lines.isEmpty() || centerLine <= 0) {
            return null;
        }
        return extractSnippet(lines, centerLine - radius, centerLine + radius, centerLine);
    }
}
