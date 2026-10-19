package io.github.liufor02144.erroragent.dto;

import lombok.Data;

/**
 * 指纹判定结果。
 *
 * <p>判定只有两种产出值得关心：<b>不在异常库</b>（{@link #NEW}）与
 * <b>在库但突增</b>（{@link #SURGE}）。其余情况（在库且量级正常）是 {@link #QUIET}，
 * 不产生事件、不推送，只计数。
 */
@Data
public class JudgeResult {

    /** 不在异常库 —— 每次出现都要报 */
    public static final String NEW = "NEW";
    /** 在异常库但量级突增 */
    public static final String SURGE = "SURGE";
    /** 在异常库且量级正常 —— 允许发生，不报 */
    public static final String QUIET = "QUIET";

    private Long fpId;

    /** NEW / SURGE / QUIET */
    private String judgeState;

    /** 是否命中异常库 */
    private boolean inLibrary;

    private Long libraryItemId;

    /** 是否需要送 AI 分析（已综合判定结果与量级门槛） */
    private boolean needAi;

    /** 判定依据，写进日志与页面，便于回溯「为什么这条报了/没报」 */
    private String reason;

    /** 基线均值 */
    private Double baseline;

    /** 基线样本数 */
    private Integer baselineSamples;

    /** 近 24 小时累计次数 */
    private long count24h;

    /** 当前小时次数 */
    private long currentHourCount;

    /** 是否该产生异常事件 */
    public boolean isActionable() {
        return NEW.equals(judgeState) || SURGE.equals(judgeState);
    }
}
