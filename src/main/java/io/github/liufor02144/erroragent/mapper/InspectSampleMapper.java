package io.github.liufor02144.erroragent.mapper;

import io.github.liufor02144.erroragent.entity.InspectSample;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 原始日志样本。仅对 NEW / SURGE 指纹拉取，供卡片折叠面板与详情页展示。
 */
public interface InspectSampleMapper {

    int batchInsert(@Param("list") List<InspectSample> list);

    /** 取最近一条样本（卡片与 AI 分析用） */
    InspectSample selectLatestByFpId(@Param("fpId") Long fpId);

    List<InspectSample> selectByFpId(@Param("fpId") Long fpId, @Param("limit") int limit);

    /** 该指纹是否已有样本，避免重复拉取 */
    int countByFpId(@Param("fpId") Long fpId);

    int deleteBefore(@Param("before") Date before);
}
