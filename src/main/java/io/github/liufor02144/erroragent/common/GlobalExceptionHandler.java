package io.github.liufor02144.erroragent.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public Response<String> handleBadRequest(Exception e, HttpServletRequest request) {
        log.warn("请求参数错误: uri={}, msg={}", request.getRequestURI(), e.getMessage());
        return Response.error("400", e.getMessage());
    }

    /** 路径不存在按 404 返回，不包装成业务错误 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Response<String>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error("404", "接口不存在"));
    }

    @ExceptionHandler(Exception.class)
    public Response<String> handleException(Exception e, HttpServletRequest request) {
        log.error("接口异常: uri={}", request.getRequestURI(), e);
        return Response.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
}
