package io.github.liufor02144.erroragent.dto;

import lombok.Data;

/**
 * SLS 原始日志样本查询返回的一行。
 */
@Data
public class SampleRow {

    /** 原始 message 全文，含完整堆栈 */
    private String message;

    private String traceId;

    private String podName;

    /** 完整镜像名，形如 registry.example.com/mycompany-order-service:v128 */
    private String imageName;

    /** 日志时间（秒级 unix 时间戳） */
    private Long logTimeSec;

    /**
     * 从完整镜像名截取 tag。
     * 发版关联检测只需要 tag（v128），不需要 registry 前缀。
     */
    public String imageTag() {
        if (imageName == null) {
            return null;
        }
        int idx = imageName.lastIndexOf(':');
        return idx >= 0 && idx < imageName.length() - 1 ? imageName.substring(idx + 1) : null;
    }
}
