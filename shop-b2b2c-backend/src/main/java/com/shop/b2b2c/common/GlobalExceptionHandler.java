/*
 * 文件：GlobalExceptionHandler.java
 * 用途：统一异常响应；业务码映射为对应HTTP状态（400/401/403/404/409/429/503），无效请求头/JSON/参数类型返回400，未知资源返回404，唯一键冲突单独409，通用错误不回显内部文本。
 * 维护注意：业务错误码与HTTP状态保持一一映射，客户端可同时按状态码与body.code判断；认证异常文本可能含完整令牌，禁止记录原文；唯一键冲突不得回显SQL/字段值；内部异常堆栈仍须日志治理；新增处理须检查客户端兼容和敏感信息，不能把不存在接口包装成成功。
 */
package com.shop.b2b2c.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleParameterTypeMismatch() {
        // 路径/查询参数可能包含凭据或隐私，禁止回显绑定异常或原始输入。
        return R.fail(400, "请求参数格式不正确");
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleUnreadableBody() {
        // Parser exceptions may embed raw JSON containing buyer credentials; never echo or log that body.
        return R.fail(400, "请求格式不正确，请检查填写内容");
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingHeader(org.springframework.web.bind.MissingRequestHeaderException e) {
        return R.fail(400, "缺少必需请求头：" + e.getHeaderName());
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingServletRequestParameter(org.springframework.web.bind.MissingServletRequestParameterException e) {
        return R.fail(400, "缺少必需查询参数：" + e.getParameterName());
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<Void> handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        return R.fail(405, "不支持当前HTTP请求方法：" + e.getMethod());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public R<Void> handleMediaTypeNotSupported(org.springframework.web.HttpMediaTypeNotSupportedException e) {
        return R.fail(415, "不支持的请求格式/媒体类型");
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleConstraintViolation(jakarta.validation.ConstraintViolationException e) {
        return R.fail(400, "请求参数校验失败");
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleMissingResource(Exception e) {
        return R.fail(404, "接口或资源不存在");
    }

    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoHandlerFound(Exception e) {
        return R.fail(404, "接口不存在");
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusinessException(BusinessException e) {
        // T18：业务码必须同时反映到 HTTP 状态，网关/监控/前端拦截器才能按状态识别失败；body.code 保持原值。
        HttpStatus status = statusOf(e.getCode());
        log.warn("业务异常[{}]: {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(status).body(R.fail(e.getCode(), e.getMessage()));
    }

    private HttpStatus statusOf(int code) {
        return switch (code) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> code >= 500 ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST;
        };
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public R<Void> handleDuplicateKeyException(DuplicateKeyException e) {
        // 唯一键冲突可能包含表名/索引名，禁止回显原始SQL异常文本。
        log.warn("数据唯一键冲突: {}", e.getClass().getSimpleName());
        return R.fail(409, "数据已存在或与唯一约束冲突");
    }

    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleNotLoginException(NotLoginException e) {
        log.warn("未登录或会话失效");
        return R.fail(401, "登录状态已失效，请重新登录");
    }

    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleForbiddenException(Exception e) {
        log.warn("无权限访问: {}", e.getMessage());
        return R.fail(403, "无访问权限，请确认当前账户角色");
    }

    @ExceptionHandler({BindException.class, org.springframework.web.bind.MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleBindException(Exception e) {
        org.springframework.validation.BindingResult result = (e instanceof org.springframework.web.bind.MethodArgumentNotValidException manve)
                ? manve.getBindingResult()
                : ((BindException) e).getBindingResult();
        String msg = result.getAllErrors().isEmpty() ? "请求参数校验失败" : result.getAllErrors().get(0).getDefaultMessage();
        return R.fail(400, msg != null ? msg : "请求参数校验失败");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e) {
        log.error("系统未知异常", e);
        return R.fail(500, "系统繁忙，请稍后再试");
    }
}
