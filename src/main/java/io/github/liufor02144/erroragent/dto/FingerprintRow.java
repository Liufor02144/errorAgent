package io.github.liufor02144.erroragent.dto;

import lombok.Data;

/**
 * SLS 指纹聚合查询返回的一行。
 */
@Data
public class FingerprintRow {

    /** 指纹原文：app_id | 异常简名 | 业务栈帧  或  app_id | logger | message模板 */
    private String fpKey;

    /** 该指纹在该小时内的出现次数 */
    private long cnt;

    /** 所属整点小时（秒级 unix 时间戳）。用算术截断而非 date_trunc，避开 SLS 会话时区歧义 */
    private long hourSec;

    /** 该小时内最后一次出现时间（秒级 unix 时间戳） */
    private long lastTsSec;

    private String appId;

    /** 缩写 logger */
    private String loggerClass;

    /** 异常类简名，无异常类时为 NONE */
    private String excSimple;

    /** 首个业务栈帧 File.java:line，无堆栈时为 NO_STACK */
    private String bizFrame;

    /** 模板化后的 message 首行 */
    private String msgTpl;

    /** 该指纹涉及的服务数。>1 表示跨服务合并（指标型日志） */
    private Integer appCount;

    /** 异常真实原因 */
    private String excCause;

    /** 是否有堆栈 */
    public boolean hasStack() {
        return bizFrame != null && !"NO_STACK".equals(bizFrame);
    }
}
