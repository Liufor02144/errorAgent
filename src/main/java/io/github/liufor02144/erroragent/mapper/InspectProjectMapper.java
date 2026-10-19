package io.github.liufor02144.erroragent.mapper;

import io.github.liufor02144.erroragent.entity.InspectProject;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 代码仓库映射。
 */
public interface InspectProjectMapper {

    List<InspectProject> selectEnabled();

    List<InspectProject> selectAll();

    InspectProject selectById(@Param("id") Long id);

    int insert(InspectProject record);

    int update(InspectProject record);

    int deleteById(@Param("id") Long id);

    /** 同步成功：记录时间并清除上次错误 */
    int markSyncSuccess(@Param("id") Long id,
                        @Param("localPath") String localPath,
                        @Param("syncTime") Date syncTime);

    /** 同步失败：保留 lastSyncTime 不动，让「超时未同步」的监控仍能发现问题 */
    int markSyncError(@Param("id") Long id, @Param("error") String error);
}
