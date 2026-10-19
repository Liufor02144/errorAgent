package io.github.liufor02144.erroragent.mapper;

import io.github.liufor02144.erroragent.entity.InspectLlmUsage;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface InspectLlmUsageMapper {

    int insert(InspectLlmUsage record);

    /** 当日调用次数，用于每日上限熔断 */
    int countSince(@Param("since") Date since);

    /** 按天汇总 token 与费用，供成本看板 */
    List<Map<String, Object>> sumByDay(@Param("since") Date since);

    /** 失败率监控 */
    Map<String, Object> failureRate(@Param("since") Date since);

    /** 某时间段内按场景汇总（巡检记录的一轮）。to 为 null 表示到现在 */
    List<Map<String, Object>> sumBySceneBetween(@Param("from") Date from, @Param("to") Date to);

    /** 某指纹在某时刻之后的 token 用量 */
    Map<String, Object> sumByFpSince(@Param("fpId") Long fpId, @Param("since") Date since);

    int deleteBefore(@Param("before") Date before);
}
