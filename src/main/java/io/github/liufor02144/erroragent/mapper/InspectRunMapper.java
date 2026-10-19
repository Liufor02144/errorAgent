package io.github.liufor02144.erroragent.mapper;

import io.github.liufor02144.erroragent.entity.InspectRun;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface InspectRunMapper {

    int insert(InspectRun run);

    int updateFinish(InspectRun run);

    /** 把超时仍在 RUNNING 的记录收口为失败（进程中途被杀的那一轮） */
    int closeStaleRunning(@Param("before") Date before);

    /** 最近一轮的 id，分析与推送据此归属 */
    Long selectLatestId();

    InspectRun selectById(@Param("id") Long id);

    /** 下一轮的开始时间，用于界定本轮的时间段；没有下一轮返回 null */
    Date selectNextStart(@Param("id") Long id);

    List<InspectRun> selectPage(@Param("status") String status,
                                @Param("offset") int offset, @Param("size") int size);

    int count(@Param("status") String status);

    int deleteBefore(@Param("before") Date before);
}
