package io.github.liufor02144.erroragent.dto;

import lombok.Data;

/**
 * 基线统计：近 N 天同一小时刻度的平均次数与样本数。
 *
 * <p>样本数必须一并返回 —— 冷启动初期历史不足时 AVG 依然会返回一个值，
 * 但那个值不具备统计意义，据此判突增会产生大量误报。
 */
@Data
public class BaselineStat {

    /** 平均次数，无历史时为 null */
    private Double avgCnt;

    /** 参与计算的样本数（即历史上有多少个同刻度小时） */
    private Integer samples;

    public boolean reliable(int minSamples) {
        return avgCnt != null && samples != null && samples >= minSamples;
    }

    public double avg() {
        return avgCnt == null ? 0D : avgCnt;
    }
}
