package io.github.liufor02144.erroragent.entity;

import lombok.Data;

import java.util.Date;

/**
 * 异常指纹（核心实体）。
 *
 * <p>指纹构成分两类，由 {@link #hasStack} 决定：
 * <ul>
 *   <li>有堆栈：{@code app_id | 异常简名 | 首个业务栈帧类:行号}，logger 与 message 均不参与</li>
 *   <li>无堆栈：{@code app_id | logger_class | message模板}</li>
 * </ul>
 *
 * <p>有堆栈时排除 message 的原因：同一行代码打的日志固定部分必然相同，
 * 让 message 参与只会被可变值（对象 toString、pod 名）撕裂成多个指纹。
 */
@Data
public class InspectFingerprint {

    private Long id;

    /** fpKey 的 MD5，唯一标识 */
    private String fpHash;

    /** 指纹原文 */
    private String fpKey;

    private String appId;

    /** 是否有堆栈，决定指纹构成方式 */
    private Boolean hasStack;

    /** 异常类简名（末段），无异常类时为 NONE */
    private String excSimple;

    /** 缩写 logger，如 c.e.o.b.h.OrderHelper；仅无堆栈时参与指纹 */
    private String loggerClass;

    /** 首个业务栈帧，格式 File.java:line；无堆栈时为 NO_STACK */
    private String bizFrame;

    /** 模板化后的 message 首行 */
    private String msgTpl;

    /**
     * 异常真实原因，如「物流单不存在」「账户未绑定」。
     *
     * <p>{@code BusinessException} 是业务主动抛出的通用基类，光看类名毫无信息量 ——
     * 同一类名下实测有十余种完全不同的问题。这个字段才是定位的关键。
     */
    private String excCause;

    private Date firstSeenTime;

    private Date lastSeenTime;

    private Long totalCount;

    /** ACTIVE / SUPPRESSED / ARCHIVED */
    private String status;

    /** 所属异常模版ID。NULL 表示还没匹配上任何模版，待抽取 */
    private Long templateId;

    /** 涉及服务数。>1 表示跨服务合并的指标型日志，app_id 只是任取的一个 */
    private Integer appCount;

    /** 最近一轮判定：NEW(库外) / SURGE(库内突增) / QUIET(库内正常) */
    private String judgeState;

    /** AI 分类：BIZ_EXPECTED / SUSPECTED_BUG / INFRA / NEEDS_INFO */
    private String category;

    /** P0 / P1 / P2 / P3 */
    private String severity;

    /** 是否已人工标注 */
    private Boolean handled;

    /** 最近推送时间，null 表示从未推送 */
    private Date pushedTime;

    private Integer pushCount;

    /** 最近一张卡片的飞书消息 ID，用于页面标注后同步更新群里的卡片 */
    private String lastMessageId;

    private Date createTime;

    private Date updateTime;

    private Integer deleted;
}
