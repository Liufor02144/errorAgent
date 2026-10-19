package io.github.liufor02144.erroragent.entity;

import lombok.Data;

import java.util.Date;

/**
 * app_id → Git 仓库映射。
 *
 * <p>一仓多服务：order-center 一个仓库对应 order-task / order-service /
 * order-listener / order-admin 四个 app_id，
 * 因此 {@link #appIds} 是 {@code |} 分隔的多值字段。
 */
@Data
public class InspectProject {

    private Long id;

    /** Git 仓库完整路径，可含多级 group，如 backend/trade/order-center */
    private String repoPath;

    private String repoName;

    /** 对应的 app_id 列表，| 分隔 */
    private String appIds;

    private String branch;

    private String localPath;

    private Date lastSyncTime;

    private String lastSyncError;

    private Integer enabled;

    private Date createTime;

    private Date updateTime;

    private Integer deleted;
}
