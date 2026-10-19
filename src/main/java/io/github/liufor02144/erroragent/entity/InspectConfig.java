package io.github.liufor02144.erroragent.entity;

import lombok.Data;

import java.util.Date;

/**
 * 动态配置。调优参数与业务规则放这里，由管理页面的配置页管理、改完即时生效。
 *
 * <p>与部署配置（配置文件/环境变量）的分工：部署配置只放凭据、基础设施地址、急停开关；
 * 冷启动期需要频繁调整的参数（突增倍数、量级门槛、推送上限）走本表。
 */
@Data
public class InspectConfig {

    private Long id;

    private String cfgKey;

    private String cfgValue;

    private String defaultValue;

    /** INT / LONG / BOOL / STRING / LIST */
    private String valueType;

    /** JUDGE / PUSH / LLM / COLLECT / IMPACT / DEFAULT */
    private String groupName;

    private String description;

    private String operator;

    private Date createTime;

    private Date updateTime;
}
