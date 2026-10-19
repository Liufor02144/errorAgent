package io.github.liufor02144.erroragent.entity;

import lombok.Data;

import java.util.Date;

/**
 * 指纹时序统计（小时粒度），基线计算的唯一数据源。
 *
 * <p>为什么不直接查 SLS 算基线：实测 SLS 大范围查询会自动采样
 * （7 天窗口返回 622 万条，比 24 小时的 1220 万还少），历史统计必须本地累积。
 *
 * <p>唯一键 (fpId, statHour) 保证采集窗口重叠时覆盖而非累加。
 */
@Data
public class InspectStat {

    private Long id;

    private Long fpId;

    /** 统计小时（整点） */
    private Date statHour;

    private Integer cnt;

    /** 采集批次ID，重跑时按此清理保证幂等 */
    private String batchId;

    private Date createTime;

    private Date updateTime;
}
