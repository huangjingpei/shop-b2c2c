/*
 * 文件：BusinessException.java
 * 用途：承载业务错误码与消息的异常类型。
 * 维护注意：默认构造的错误码目前为500；调用方应按契约显式选码，异常消息会到达客户端，禁止包含数据库细节或敏感凭据。
 */
package com.shop.b2b2c.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
