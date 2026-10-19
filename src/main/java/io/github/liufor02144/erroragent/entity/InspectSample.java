package io.github.liufor02144.erroragent.entity;

import lombok.Data;

import java.util.Date;

/**
 * 原始日志样本。仅对判定为 NEW / SURGE 的指纹拉取，用于卡片折叠面板与详情页展示。
 *
 * <p>样本原样入库，排查时要看原文；发给大模型前由 {@code Redactor} 脱敏（erroragent.llm.redact）。
 */
@Data
public class InspectSample {

    private Long id;

    private Long fpId;

    /** 原始 message 全文，含完整堆栈 */
    private String rawMessage;

    /** SkyWalking TID */
    private String traceId;

    private String podName;

    /** 镜像 tag，发版关联检测用 */
    private String imageTag;

    private Date logTime;

    private Date createTime;
}
