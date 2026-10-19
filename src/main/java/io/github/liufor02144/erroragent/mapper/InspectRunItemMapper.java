package io.github.liufor02144.erroragent.mapper;

import io.github.liufor02144.erroragent.entity.InspectRunItem;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface InspectRunItemMapper {

    int insert(InspectRunItem item);

    /** 明细列表，不带卡片原文（列表里用不上，体积大） */
    List<InspectRunItem> selectByRunId(@Param("runId") Long runId);

    String selectCardJson(@Param("id") Long id);

    int countSkip(@Param("runId") Long runId, @Param("eventId") Long eventId);

    int deleteBefore(@Param("before") Date before);
}
