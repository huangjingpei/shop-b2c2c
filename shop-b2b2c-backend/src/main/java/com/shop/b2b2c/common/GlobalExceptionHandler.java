/*
 * 文件：GlobalExceptionHandler.java
 * 用途：统一后端异常到API响应的映射，为缺少必需请求头返回400、未知资源返回404。
 * 维护注意：业务错误码与HTTP状态目前并非完全一致；新增异常处理须同时检查客户端兼容和敏感错误信息，不能把不存在接口包装成成功。
 */
package com.shop.b2b2c.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局统一异常捕获与熔断拦截器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingHeader(org.springframework.web.bind.MissingRequestHeaderException e) {
        return R.fail(400, "缺少必需请求头：" + e.getHeaderName());
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleMissingResource(Exception e) {
        return R.fail(404, "接口或资源不存在");
    }

    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleNotLoginException(NotLoginException e) {
        log.warn("未登录或会话失效: {}", e.getMessage());
        return R.fail(401, "登录状态已失效，请重新登录");
    }

    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleForbiddenException(Exception e) {
        log.warn("无权限访问: {}", e.getMessage());
        return R.fail(403, "无访问权限，请确认当前账户角色");
    }

    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return R.fail(400, msg);
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统未知异常", e);
        return R.fail(500, "系统繁忙，请稍后再试: " + e.getMessage());
    }
}
