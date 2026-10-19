package io.github.liufor02144.erroragent.dto;

import lombok.Data;

/**
 * 代码定位结果。
 *
 * <p>{@link #confidence} 是本方案最重要的字段之一：镜像 tag 与 Jenkins buildNumber
 * 无对应关系，拿不到出事那一刻的 commit，只能读 master HEAD。
 * 因此必须把「静默错位」变成「显式降级」—— 对不上就明说对不上，
 * 而不是让 AI 基于错误代码编出一个看似合理的根因。
 */
@Data
public class CodeLocateResult {

    /** 行号落在同名方法体内，可直接引用 */
    public static final String HIGH = "HIGH";
    /** 行号已漂移，改按方法名定位整个方法体 */
    public static final String MEDIUM = "MEDIUM";
    /** 类中已无该方法（代码被重构），仅给类级上下文 */
    public static final String LOW = "LOW";
    /** 完全定位不到 */
    public static final String UNLOCATED = "UNLOCATED";

    private String confidence = UNLOCATED;

    /** 定位路径：STACK（有堆栈）/ LOGGER（无堆栈反查） */
    private String route;

    private String repoName;

    /** 相对仓库根的文件路径 */
    private String filePath;

    /** 堆栈中的原始行号 */
    private Integer stackLine;

    /** 实际定位到的行号（校验通过时等于 stackLine） */
    private Integer resolvedLine;

    private String className;

    private String methodName;

    /** 方法体源码（定位失败时为类级片段） */
    private String snippet;

    /** 片段起始行号，用于页面展示行号对齐 */
    private Integer snippetStartLine;

    /** 当前代码版本（master HEAD 短 commit） */
    private String commit;

    /** 定位过程说明，写进分析结论供人核对 */
    private String note;

    /**
     * 该日志是否由第三方库打印。
     *
     * <p>为真时不要让模型去分析 SDK 源码 —— 异常是我们的业务代码调用它触发的，
     * 而日志里没有堆栈，系统定位不到调用方，只能如实说明。
     */
    private boolean thirdParty;

    public boolean located() {
        return !UNLOCATED.equals(confidence);
    }
}
