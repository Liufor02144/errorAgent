package io.github.liufor02144.erroragent.entity;

import lombok.Data;

import java.util.Date;

/**
 * AI 分析结果。
 *
 * <p>刻意不含「修复建议」字段：AI 只呈现证据，处置决策留给人。
 * 建议会产生权威错觉 —— 值班的人在紧急情况下容易直接照做，
 * 而 AI 拿不到 commit diff，并不具备判断该不该回滚的信息基础。
 * 结构上不留存放建议的地方，避免日后被顺手加回来。
 */
@Data
public class InspectAnalysis {

    private Long id;

    private Long fpId;

    /** BIZ_EXPECTED / SUSPECTED_BUG / INFRA / NEEDS_INFO */
    private String category;

    /** P0 / P1 / P2 / P3 */
    private String severity;

    /** 是否历史首次出现（系统判定，非 AI 输出） */
    private Boolean isFirstSeen;

    /** 根因：该行代码在什么条件下抛出此异常，仅限代码可见逻辑 */
    private String rootCause;

    /** 业务面影响 */
    private String businessImpact;

    /** 不查库时的原因说明。并非所有异常都能用数据库估算影响面 */
    private String impactNote;

    /** AI 自评置信度 HIGH / MEDIUM / LOW */
    private String confidence;

    /** 代码定位置信度 HIGH / MEDIUM / LOW / UNLOCATED */
    private String locateConfidence;

    private String codeSnippet;

    /** 代码位置：仓库/文件:行号 */
    private String codeRef;

    /** 同一次请求里的其他报错（按 traceId 从日志查出），卡片上展示 */
    private String traceChain;

    private String model;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer elapsedMs;

    private Date createTime;

    private Date updateTime;
}
