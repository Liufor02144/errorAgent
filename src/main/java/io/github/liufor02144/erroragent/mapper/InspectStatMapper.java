package io.github.liufor02144.erroragent.mapper;

import io.github.liufor02144.erroragent.dto.BaselineStat;
import io.github.liufor02144.erroragent.entity.InspectStat;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 指纹时序统计。基线计算的唯一数据源（SLS 大范围查询会采样，不可用于历史统计）。
 */
public interface InspectStatMapper {

    /** 批量写入。唯一键 (fp_id, stat_hour) 冲突时覆盖 cnt，保证采集窗口重叠时不重复累加 */
    int batchUpsert(@Param("list") List<InspectStat> list);

    /** 重跑同一批次前先清理，保证幂等 */
    int deleteByBatchId(@Param("batchId") String batchId);

    /**
     * 基线：指定指纹在近 N 天、同一小时刻度上的平均次数与样本数。
     *
     * <p>同时返回样本数是必需的 —— 冷启动初期历史不足时 AVG 仍会给出一个值，
     * 但不具统计意义，据此判突增会大量误报。
     *
     * @param excludeFrom 排除该时间点及之后的统计（传当前小时起点），
     *                    否则待判定的当前小时会被算进基线，突增被自身稀释
     */
    BaselineStat avgBaseline(@Param("fpId") Long fpId,
                             @Param("hourOfDay") int hourOfDay,
                             @Param("sinceTime") Date sinceTime,
                             @Param("excludeFrom") Date excludeFrom);

    /** 趋势图：指定指纹在时间范围内的逐小时次数 */
    List<Map<String, Object>> selectTrend(@Param("fpId") Long fpId,
                                          @Param("startTime") Date startTime,
                                          @Param("endTime") Date endTime);

    /**
     * 批量汇总多个指纹在时间范围内的次数。
     * 列表页每行都要显示近 24h 量级，逐行查就是 N+1。
     */
    List<Map<String, Object>> sumCountByFpIds(@Param("fpIds") List<Long> fpIds,
                                              @Param("startTime") Date startTime,
                                              @Param("endTime") Date endTime);

    /**
     * 多个指纹在时间范围内的总次数（单值）。
     *
     * <p>事件的累计量靠它重算 —— 采集窗口是「上一整点到现在」，
     * 同一小时会被反复采到，用「每轮相加」的方式算累计必然虚高。
     */
    Long sumTotalByFpIds(@Param("fpIds") List<Long> fpIds,
                         @Param("startTime") Date startTime,
                         @Param("endTime") Date endTime);

    /** 指定时间范围内的总次数 */
    Long sumCount(@Param("fpId") Long fpId,
                  @Param("startTime") Date startTime,
                  @Param("endTime") Date endTime);

    /** 滚动清理 */
    int deleteBefore(@Param("before") Date before);
}
