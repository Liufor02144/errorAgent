package io.github.liufor02144.erroragent.common;

import lombok.Data;

/**
 * 接口统一返回体。code 为 "0" 表示成功。
 */
@Data
public class Response<T> {

    private String code;
    private String msg;
    private T data;

    public Response() {
    }

    public Response(String code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Response<T> ok(T data) {
        return new Response<>("0", "success", data);
    }

    public static <T> Response<T> ok() {
        return new Response<>("0", "success", null);
    }

    public static <T> Response<T> error(String msg) {
        return new Response<>("-1", msg, null);
    }

    public static <T> Response<T> error(String code, String msg) {
        return new Response<>(code, msg, null);
    }
}
