package io.github.liufor02144.erroragent.entity;

import lombok.Data;

import java.util.Date;

/**
 * 巡检记录：每轮采集一条。
 *
 * <p>分析与推送是独立的定时任务，它们的动作记在明细表里、归入当时最近的这一条，
 * 即「从本轮采集开始到下一轮采集开始之间发生的事」。
 */
@Data
public class InspectRun {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    private Long id;
    private String batchId;
    private String status;
    /** AUTO 定时 / MANUAL 手动 */
    private String triggerType;
    private String windowText;
    private Long totalLogs;
    private Integer slsRows;
    private Integer fingerprints;
    private Integer newCount;
    private Integer surgeCount;
    private Integer quietCount;
    private Integer templateCount;
    private Integer matchedCount;
    private Integer unmatchedCount;
    private Integer templateExtracted;
    private Integer eventCount;
    private Long elapsedMs;
    private String message;
    private Date startTime;
    private Date endTime;
    private Date createTime;

    // ---- 以下由查询汇总，不落库 ----
    private Integer analyzeCount;
    private Integer analyzeFailed;
    private Integer pushSent;
    private Integer pushSkipped;
    /** 本轮新建的事件 */
    private Integer eventNew;
    /** 本轮累计到已有未处理事件上的 */
    private Integer eventAccumulated;
    private Integer llmCalls;
    /** 本轮用到的模型，多个时逗号分隔（本轮中途切换过模型） */
    private String models;
    private Long inputTokens;
    private Long outputTokens;
    private java.math.BigDecimal cost;
}
