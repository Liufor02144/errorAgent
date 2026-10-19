package io.github.liufor02144.erroragent.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户，用于记录操作人。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 当前登录用户名；不在请求上下文中（如定时任务）时返回 system */
    public static String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return "system";
        }
        return auth.getName();
    }
}
