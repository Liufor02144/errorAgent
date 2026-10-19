package io.github.liufor02144.erroragent.mapper;

import io.github.liufor02144.erroragent.entity.InspectAnalysis;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface InspectAnalysisMapper {

    int insert(InspectAnalysis record);

    /** 最近一次分析结果。用于 24h 幂等窗口内复用，避免重复花钱 */
    InspectAnalysis selectLatestByFpId(@Param("fpId") Long fpId);

    /** 幂等判断：指定时间之后是否已分析过 */
    int countSince(@Param("fpId") Long fpId, @Param("since") Date since);

    List<InspectAnalysis> selectByFpIds(@Param("fpIds") List<Long> fpIds);
}
