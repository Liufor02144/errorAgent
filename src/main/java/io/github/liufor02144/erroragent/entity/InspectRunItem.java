package io.github.liufor02144.erroragent.entity;

import lombok.Data;

import java.util.Date;

/** 巡检记录明细：一条事件、一次 AI 分析或一次推送 */
@Data
public class InspectRunItem {

    public static final String STAGE_EVENT = "EVENT";
    public static final String STAGE_ANALYZE = "ANALYZE";
    public static final String STAGE_PUSH = "PUSH";

    private Long id;
    private Long runId;
    private String stage;
    /** EVENT:NEW/ACCUMULATE；ANALYZE:OK/FAIL；PUSH:SENT/FAIL/SKIP */
    private String result;
    private Long eventId;
    private Long fpId;
    private String title;
    private String severity;
    private String detail;
    private Integer inputTokens;
    private Integer outputTokens;
    private String messageId;
    private String cardJson;
    private Date createTime;
}
