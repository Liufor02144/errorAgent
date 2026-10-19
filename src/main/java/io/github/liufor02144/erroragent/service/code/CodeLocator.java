package io.github.liufor02144.erroragent.service.code;

import io.github.liufor02144.erroragent.dto.CodeLocateResult;
import io.github.liufor02144.erroragent.dto.StackFrame;
import io.github.liufor02144.erroragent.entity.InspectFingerprint;
import io.github.liufor02144.erroragent.entity.InspectProject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.File;
import java.util.List;

/**
 * 代码定位。两条路径：
 *
 * <ul>
 *   <li><b>路径 A（有堆栈，约 45%）</b>：堆栈 → FQCN + 方法名 + 行号 → 文件 → 方法名自校验</li>
 *   <li><b>路径 B（无堆栈，约 55%）</b>：缩写 logger → 类名 → 首字母校验包路径 →
 *       类内按日志文本定位 {@code log.error} 行</li>
 * </ul>
 *
 * <p>路径 B 覆盖的量比 A 更大（实测某个支付服务 99.3% 无堆栈、
 * 某个消息监听服务仅 4 条有堆栈），是本方案最大的技术风险点。
 */
@Slf4j
@Service
public class CodeLocator {

    /** 第三方库的 logger 前缀（logback 压缩后的形态） */
    private static final java.util.List<String> THIRD_PARTY_PREFIXES = java.util.Arrays.asList(
            "c.g.b.",   // com.github.binarywang —— 微信支付 SDK
            "c.g.",     // com.github.*
            "o.s.",     // org.springframework
            "o.a.",     // org.apache
            "c.a.",     // com.alibaba
            "c.x.",     // com.xxl（xxl-job）
            "c.n.",     // com.netflix
            "i.l.",     // io.lettuce
            "o.h.",     // org.hibernate
            "c.b.",     // com.baomidou
            "org.", "com.github.", "com.alibaba.", "org.springframework.");

    /** 方法体过长时的截断上限，避免把上千行的巨型方法整段塞进 prompt */
    private static final int MAX_SNIPPET_LINES = 120;

    /** 方法解析失败时的兜底：目标行前后各取若干行 */
    private static final int FALLBACK_RADIUS = 12;

    @Resource
    private InspectProjectService projectService;

    @Resource
    private GitSyncService gitSyncService;

    @Resource
    private JavaSourceIndex sourceIndex;

    @Resource
    private JavaMethodExtractor methodExtractor;

    @Resource
    private StackFrameParser stackFrameParser;

    @Resource
    private io.github.liufor02144.erroragent.config.InspectProperties properties;

    /**
     * 定位指纹对应的源码。
     *
     * @param fp        指纹
     * @param rawSample 原始日志样本（含完整堆栈），可为 null
     */
    public CodeLocateResult locate(InspectFingerprint fp, String rawSample) {
        CodeLocateResult result = new CodeLocateResult();
        if (fp == null) {
            result.setNote("指纹为空");
            return result;
        }

        InspectProject project = projectService.findByAppId(fp.getAppId());
        if (project == null) {
            result.setNote("服务 " + fp.getAppId() + " 未登记代码仓库，无法定位");
            return result;
        }
        File repoDir = gitSyncService.repoDir(project);
        if (!gitSyncService.cloned(project)) {
            result.setNote("仓库 " + project.getRepoName() + " 尚未克隆完成");
            return result;
        }
        result.setRepoName(project.getRepoName());
        result.setCommit(gitSyncService.headCommit(project));

        StackFrame frame = stackFrameParser.firstBizFrame(rawSample);
        if (frame != null) {
            locateByStack(result, repoDir, frame);
            // 本服务仓库里找不到，多半是公共库的类 ——
            // PlatformApiClient、BusinessException 这些都在公共库（common）里，
            // 而仓库映射是按 app_id 一对一的，公共库不属于任何 app_id。
            // 实测 27% 的分析定位失败，其中一半就是这个原因：
            // 卡片上写着「代码定位不确定」，点开原始报错却满屏都是代码位置，很难不让人觉得是系统坏了
            if (!result.located()) {
                locateInSharedRepos(result, project, frame);
            }
        } else {
            locateByLogger(result, repoDir, fp);
        }
        return result;
    }

    /**
     * 按调用链给出的「服务 + 类 + 方法」定位代码。
     *
     * <p>报错服务的日志往往只有表象（全局异常处理器、Hystrix 包装），
     * 真正出问题的是链路下游的某个业务方法。链路里只有方法名、没有行号，
     * 所以按方法名取整个方法体。
     */
    public CodeLocateResult locateMethod(String appId, String className, String methodName) {
        CodeLocateResult result = new CodeLocateResult();
        if (className == null || methodName == null) {
            result.setNote("调用链未给出业务方法");
            return result;
        }
        InspectProject project = projectService.findByAppId(appId);
        String current = className;
        // 链路里记的是运行时的类，方法可能写在父类里（CouponGroupHandler.buildDetailDto
        // 实际定义在 BaseActivityHandler）。子类里找不到就顺着 extends 往上找
        for (int depth = 0; depth < 3 && current != null; depth++) {
            result = new CodeLocateResult();
            StackFrame frame = new StackFrame();
            frame.setClassName(current);
            frame.setMethodName(methodName);
            frame.setLineNumber(0);
            frame.setFileName(current.substring(current.lastIndexOf('.') + 1) + ".java");

            File repoDir = null;
            if (project != null && gitSyncService.cloned(project)) {
                result.setRepoName(project.getRepoName());
                result.setCommit(gitSyncService.headCommit(project));
                repoDir = gitSyncService.repoDir(project);
                locateByStack(result, repoDir, frame);
            }
            if (!result.located()) {
                locateInSharedRepos(result, project, frame);
            }
            if (result.located() && io.github.liufor02144.erroragent.service.InspectText.notBlank(result.getSnippet())) {
                result.setRoute("TRACE");
                result.setNote("按调用链定位：" + frame.simpleClassName() + "." + methodName
                        + " 是链路中离问题最近的业务方法（" + appId + "），片段为整个方法");
                return result;
            }
            current = parentClassOf(result, repoDir, current);
        }
        // 找不到方法体时视为未定位：空片段顶替掉按堆栈定位到的代码，AI 就只能说「未附源码」
        CodeLocateResult none = new CodeLocateResult();
        none.setNote("调用链指向的 " + className + "." + methodName + " 在代码中未找到方法体");
        return none;
    }

    private static final java.util.regex.Pattern EXTENDS = java.util.regex.Pattern.compile(
            "\\bclass\\s+\\w+(?:<[^{]*?>)?\\s+extends\\s+([\\w.]+)");

    /** 从已找到的源文件里读出父类的全限定名；读不到返回 null */
    private String parentClassOf(CodeLocateResult found, File ownRepoDir, String className) {
        if (found.getFilePath() == null) {
            return null;
        }
        File dir = ownRepoDir;
        if (found.getRepoName() != null) {
            for (InspectProject p : projectService.listEnabled()) {
                if (found.getRepoName().equals(p.getRepoName()) && gitSyncService.cloned(p)) {
                    dir = gitSyncService.repoDir(p);
                    break;
                }
            }
        }
        if (dir == null) {
            return null;
        }
        List<String> lines = sourceIndex.readLines(dir, found.getFilePath());
        String text = String.join("\n", lines);
        java.util.regex.Matcher m = EXTENDS.matcher(text);
        if (!m.find()) {
            return null;
        }
        String parent = m.group(1);
        if (parent.contains(".")) {
            return parent;
        }
        // 简单类名：先看 import，没有 import 就是同包
        java.util.regex.Matcher imp = java.util.regex.Pattern.compile(
                "import\\s+([\\w.]+\\." + java.util.regex.Pattern.quote(parent) + ")\\s*;").matcher(text);
        if (imp.find()) {
            return imp.group(1);
        }
        int dot = className.lastIndexOf('.');
        return dot > 0 ? className.substring(0, dot + 1) + parent : parent;
    }


    /**
     * 跨仓库兜底：在其他已克隆的仓库里找这个类。
     *
     * <p>公共库（common 之类）不属于任何 app_id，仓库映射表里
     * 也就没有它的位置 —— 但业务服务里到处都在用它的类，异常自然也从那里抛出。
     *
     * <p>按包路径精确匹配，<b>不退化到按类名找</b>：跨仓库时同名类的概率显著升高，
     * 定位到错误的文件比定位不到更糟 —— AI 会基于不相干的代码编出一个看似合理的根因。
     */
    private void locateInSharedRepos(CodeLocateResult result, InspectProject own, StackFrame frame) {
        for (InspectProject other : projectService.listEnabled()) {
            if (own != null && other.getId().equals(own.getId())) {
                continue;
            }
            if (!gitSyncService.cloned(other)) {
                continue;
            }
            File dir = gitSyncService.repoDir(other);
            String relative = sourceIndex.findByPackage(
                    dir, frame.packageName(), frame.simpleClassName());
            if (relative == null) {
                continue;
            }
            // 换一个仓库重新走一遍定位，行号校验等逻辑完全复用
            result.setNote(null);
            result.setRepoName(other.getRepoName());
            result.setCommit(gitSyncService.headCommit(other));
            locateByStack(result, dir, frame);
            if (result.located()) {
                log.info("【代码定位】{} 不在本服务仓库，于 {} 中找到",
                        frame.simpleClassName(), other.getRepoName());
                return;
            }
        }
        // 都没找到：把原因说清楚，别让人以为是系统坏了
        result.setNote("类 " + frame.simpleClassName() + " 不在本服务仓库，"
                + "也不在其他已同步的仓库中 —— 多半位于尚未纳入同步的公共库，"
                + "可在「代码仓库」配置里补充该仓库");
    }

    /**
     * 路径 A：按堆栈定位，并做方法名自校验。
     *
     * <p>校验存在的理由：拿不到出事那一刻的 commit（镜像 tag 与 buildNumber 无对应关系），
     * 只能读 master HEAD。若期间该文件被改动，行号就会指向不相干的代码 ——
     * 而这种错位不会报错，AI 会基于错误代码给出看似合理的根因。
     * 用方法名比对把它变成可见的降级。
     */
    private void locateByStack(CodeLocateResult result, File repoDir, StackFrame frame) {
        result.setRoute("STACK");
        result.setClassName(frame.getClassName());
        result.setMethodName(frame.getMethodName());
        result.setStackLine(frame.getLineNumber());

        String relative = sourceIndex.findByPackage(repoDir, frame.packageName(), frame.simpleClassName());
        if (relative == null) {
            // 包名对不上时退回按类名找，可能是 module 重构或类被移动
            List<String> candidates = sourceIndex.findBySimpleName(repoDir, frame.simpleClassName());
            if (candidates.isEmpty()) {
                result.setNote("仓库中未找到源文件 " + frame.simpleClassName() + ".java");
                return;
            }
            relative = candidates.get(0);
            result.setNote("包路径未精确匹配，按类名取首个候选");
        }
        result.setFilePath(relative);

        List<String> lines = sourceIndex.readLines(repoDir, relative);
        if (lines.isEmpty()) {
            result.setNote("源文件读取失败 " + relative);
            return;
        }

        int stackLine = frame.getLineNumber();
        String expectMethod = frame.hostMethodName();
        JavaMethodExtractor.MethodRange atLine = stackLine > 0 && stackLine <= lines.size()
                ? methodExtractor.findMethodAt(lines, stackLine) : null;

        // 情况一：行号落在同名方法体内 —— 行号有效
        if (atLine != null && expectMethod != null && expectMethod.equals(atLine.getName())) {
            result.setConfidence(CodeLocateResult.HIGH);
            result.setResolvedLine(stackLine);
            fillSnippet(result, lines, atLine, stackLine);
            result.setNote("行号校验通过：第 " + stackLine + " 行位于方法 " + expectMethod + " 内");
            return;
        }

        // 情况二：方法仍在但行号不在其中 —— 代码被改动过，行号已漂移
        List<JavaMethodExtractor.MethodRange> byName = expectMethod == null
                ? java.util.Collections.emptyList()
                : methodExtractor.findMethodsByName(lines, expectMethod);

        if (byName.size() == 1) {
            JavaMethodExtractor.MethodRange m = byName.get(0);
            result.setConfidence(CodeLocateResult.MEDIUM);
            result.setResolvedLine(m.getStartLine());
            fillSnippet(result, lines, m, 0);
            result.setNote("行号已漂移：第 " + stackLine + " 行不在方法 " + expectMethod
                    + " 内（该方法当前位于 " + m.getStartLine() + "-" + m.getEndLine()
                    + " 行），已按方法名定位。代码读取自 master HEAD，与出错时的版本可能不一致");
            return;
        }
        if (byName.size() > 1) {
            // 堆栈不含参数签名，重载方法无法区分。如实降级而不是闷头取第一个
            StringBuilder sigs = new StringBuilder();
            for (JavaMethodExtractor.MethodRange m : byName) {
                if (sigs.length() > 0) {
                    sigs.append("、");
                }
                sigs.append(m.getSignature()).append("(").append(m.getStartLine()).append("行)");
            }
            JavaMethodExtractor.MethodRange first = byName.get(0);
            result.setConfidence(CodeLocateResult.LOW);
            result.setResolvedLine(first.getStartLine());
            fillSnippet(result, lines, first, 0);
            result.setNote("行号已漂移且方法 " + expectMethod + " 有 " + byName.size()
                    + " 个重载，堆栈不含参数签名无法区分：" + sigs
                    + "。片段取自第一个，可能不是实际出错的重载");
            return;
        }

        // 情况三：类中已无该方法 —— 代码被重构
        result.setConfidence(CodeLocateResult.LOW);
        String around = methodExtractor.extractAround(lines, stackLine, FALLBACK_RADIUS);
        if (around != null) {
            result.setSnippet(around);
            result.setSnippetStartLine(Math.max(1, stackLine - FALLBACK_RADIUS));
            result.setResolvedLine(stackLine);
        }
        result.setNote("方法 " + expectMethod + " 在当前代码中已不存在，可能已重构。"
                + "仅提供第 " + stackLine + " 行附近片段，定位仅供参考");
    }

    /**
     * 路径 B：无堆栈时按缩写 logger 反查。
     *
     * <p>难点是 {@code logger_class} 是被 logback 压缩过的缩写包名
     * （{@code c.e.o.b.h.OrderHelper}），信息已有损，只能靠类名 + 首字母校验还原。
     */
    private void locateByLogger(CodeLocateResult result, File repoDir, InspectFingerprint fp) {
        result.setRoute("LOGGER");
        String loggerClass = fp.getLoggerClass();
        if (loggerClass == null || loggerClass.trim().isEmpty()) {
            result.setNote("无堆栈且无 logger 信息，无法定位");
            return;
        }

        // 第三方库打的日志：定位方向本身就不对。
        // 实测微信支付 SDK（c.g.b.w.b.r.WxPayOrderCloseResult）这类日志
        // 既没有业务栈帧、仓库里也没有源码，硬去定位只会让模型去分析人家的 SDK，
        // 得出「WxPayOrderCloseResult 为微信 SDK 结果类，仓库中无源码」这种废话。
        // 真正该看的是「我们哪段代码调了它」，但日志里没有堆栈，系统查不到 ——
        // 那就如实说明，把方向指对，而不是给一个错误的定位
        if (isThirdParty(loggerClass)) {
            result.setNote("该日志由第三方库 " + loggerClass + " 打印，且未附带堆栈，"
                    + "无法定位到本系统代码。异常本身是我们的业务代码调用该 SDK 触发的，"
                    + "需要结合调用方排查");
            result.setThirdParty(true);
            return;
        }

        String simpleName = lastSegment(loggerClass);
        result.setClassName(loggerClass);

        List<String> candidates = sourceIndex.findBySimpleName(repoDir, simpleName);
        if (candidates.isEmpty()) {
            result.setNote("仓库中未找到 " + simpleName + ".java（可能是框架类，如 xxl-job 的 logger）");
            return;
        }

        String matched = pickByAbbrev(candidates, loggerClass);
        if (matched == null) {
            if (candidates.size() == 1) {
                matched = candidates.get(0);
            } else {
                result.setNote("类名 " + simpleName + " 有 " + candidates.size()
                        + " 个候选且包路径均不匹配缩写 " + loggerClass + "，放弃定位以免给出错误结果");
                return;
            }
        }
        result.setFilePath(matched);

        List<String> lines = sourceIndex.readLines(repoDir, matched);
        if (lines.isEmpty()) {
            result.setNote("源文件读取失败 " + matched);
            return;
        }

        // 用模板中最长的固定文本段去匹配日志语句。
        // msg_tpl 里的可变值已被替换成占位符，而源码中对应位置是 {}，
        // 因此只有固定文本段能匹配上。
        String literal = longestLiteral(fp.getMsgTpl());
        int hit = literal == null ? -1 : indexOfLine(lines, literal);
        if (hit < 0) {
            result.setConfidence(CodeLocateResult.LOW);
            result.setNote("已定位到类 " + matched + "，但未能匹配日志文本，无法确定具体行");
            List<JavaMethodExtractor.MethodRange> methods = methodExtractor.parseMethods(lines);
            if (!methods.isEmpty()) {
                fillSnippet(result, lines, methods.get(0), 0);
            }
            return;
        }

        int lineNumber = hit + 1;
        result.setResolvedLine(lineNumber);
        result.setStackLine(null);
        JavaMethodExtractor.MethodRange method = methodExtractor.findMethodAt(lines, lineNumber);
        if (method != null) {
            result.setMethodName(method.getName());
            result.setConfidence(CodeLocateResult.HIGH);
            fillSnippet(result, lines, method, lineNumber);
            result.setNote("按日志文本定位到第 " + lineNumber + " 行，位于方法 " + method.getName() + " 内");
        } else {
            result.setConfidence(CodeLocateResult.MEDIUM);
            result.setSnippet(methodExtractor.extractAround(lines, lineNumber, FALLBACK_RADIUS));
            result.setSnippetStartLine(Math.max(1, lineNumber - FALLBACK_RADIUS));
            result.setNote("按日志文本定位到第 " + lineNumber + " 行，但未能解析所在方法");
        }
    }

    private void fillSnippet(CodeLocateResult result, List<String> lines,
                             JavaMethodExtractor.MethodRange method, int highlightLine) {
        int start = method.getStartLine();
        int end = method.getEndLine();
        if (end - start + 1 > MAX_SNIPPET_LINES) {
            // 巨型方法（实测存在 7600 行以上的类）按高亮行居中截取
            int center = highlightLine > 0 ? highlightLine : start;
            start = Math.max(method.getStartLine(), center - MAX_SNIPPET_LINES / 2);
            end = Math.min(method.getEndLine(), start + MAX_SNIPPET_LINES);
        }
        result.setSnippet(methodExtractor.extractSnippet(lines, start, end, highlightLine));
        result.setSnippetStartLine(start);
    }

    /**
     * 用缩写逐段校验包路径。
     *
     * <p>logback 的 {@code %logger{n}} 只压缩前面的包段、保留末段完整，
     * 且压缩程度不固定，因此：段长为 1 时比首字母，段长大于 1 时整段比较。
     */
    private String pickByAbbrev(List<String> candidates, String loggerClass) {
        String[] abbrev = loggerClass.split("\\.");
        if (abbrev.length < 2) {
            return null;
        }
        for (String path : candidates) {
            if (matchesAbbrev(path, abbrev)) {
                return path;
            }
        }
        return null;
    }

    private boolean matchesAbbrev(String relativePath, String[] abbrev) {
        String normalized = relativePath.replace('\\', '/');
        int srcIdx = normalized.indexOf("src/main/java/");
        if (srcIdx < 0) {
            return false;
        }
        String pkgPath = normalized.substring(srcIdx + "src/main/java/".length());
        int lastSlash = pkgPath.lastIndexOf('/');
        if (lastSlash < 0) {
            return false;
        }
        String[] segments = pkgPath.substring(0, lastSlash).split("/");
        // 缩写的最后一段是类名，前面才是包段
        int pkgSegments = abbrev.length - 1;
        if (segments.length != pkgSegments) {
            return false;
        }
        for (int i = 0; i < pkgSegments; i++) {
            String a = abbrev[i];
            String s = segments[i];
            if (a.length() == 1) {
                if (s.isEmpty() || s.charAt(0) != a.charAt(0)) {
                    return false;
                }
            } else if (!a.equals(s)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 取模板中最长的固定文本段。占位符处对应源码里的 {}，只有固定段能匹配。
     */
    private String longestLiteral(String msgTpl) {
        if (msgTpl == null || msgTpl.trim().isEmpty()) {
            return null;
        }
        String[] parts = msgTpl.split("\\{[A-Z]+\\}");
        String best = null;
        for (String p : parts) {
            String t = p.trim();
            if (t.length() >= 4 && (best == null || t.length() > best.length())) {
                best = t;
            }
        }
        return best;
    }

    private int indexOfLine(List<String> lines, String literal) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(literal)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 是否第三方库的 logger。
     *
     * <p>logger_class 被 logback 压缩成了首字母缩写，只能按前缀判断：
     * {@code c.g.b.w.*} 是 com.github.binarywang（微信支付 SDK）、
     * {@code o.s.*} 是 org.springframework、{@code c.a.*} 是 com.alibaba。
     * 业务包（{@code erroragent.naming.app-packages}）的全名和缩写都不算第三方。
     */
    private boolean isThirdParty(String loggerClass) {
        if (loggerClass == null) {
            return false;
        }
        String lc = loggerClass.trim();
        // 业务包，以及 sdk.xxx 这类无包名的业务日志，都不算第三方
        if (properties != null && properties.getNaming().isBizLogger(lc)) {
            return false;
        }
        for (String prefix : THIRD_PARTY_PREFIXES) {
            if (lc.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String lastSegment(String className) {
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }
}
