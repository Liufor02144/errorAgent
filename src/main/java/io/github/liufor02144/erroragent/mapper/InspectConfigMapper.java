package io.github.liufor02144.erroragent.mapper;

import io.github.liufor02144.erroragent.entity.InspectConfig;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 动态配置。调优参数与业务规则，由管理页面的配置页管理。
 */
public interface InspectConfigMapper {

    List<InspectConfig> selectAll();

    InspectConfig selectByKey(@Param("cfgKey") String cfgKey);

    int updateValue(@Param("cfgKey") String cfgKey,
                    @Param("cfgValue") String cfgValue,
                    @Param("operator") String operator);
}
