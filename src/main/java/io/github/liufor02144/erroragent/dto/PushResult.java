package io.github.liufor02144.erroragent.dto;

import lombok.Data;

/**
 * 一次推送的结果。
 */
@Data
public class PushResult {
    private boolean pushed;
    /** 未推送的原因，便于在页面上解释「为什么这条没进群」 */
    private String skipReason;
    private String messageId;
    private String errorMsg;
}
