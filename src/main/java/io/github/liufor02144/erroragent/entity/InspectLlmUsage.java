package io.github.liufor02144.erroragent.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * LLM 调用用量与成本。
 *
 * <p>逐次记录是成本可观测的基础：没有它就只能等月底看账单，
 * 而闸门失效（例如幂等窗口配错导致重复分析）在账单出来前是看不见的。
 */
@Data
public class InspectLlmUsage {

    private Long id;

    private Long fpId;

    /** ANALYZE / IMPACT_SQL / IMPACT_READ */
    private String scene;

    private String model;

    private Integer inputTokens;

    private Integer outputTokens;

    private BigDecimal costEstimate;

    private Integer elapsedMs;

    private Boolean success;

    private String errorMsg;

    private Date createTime;
}
