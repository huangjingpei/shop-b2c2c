/*
 * 文件：GlobalExceptionHandlerTest.java
 * 用途：验证 T18 的唯一键冲突被映射为 409 业务码，且不回显底层 SQL/索引文本。
 * 维护注意：本测试不启动 Spring 容器，只校验响应体；HTTP 状态映射由 Sprint1SecurityIntegrationTest 的 MockMvc 用例覆盖。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.GlobalExceptionHandler;
import com.shop.b2b2c.common.R;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    @Test
    void duplicateKeyReturnsConflictWithoutLeakingSqlDetail() {
        R<Void> response = new GlobalExceptionHandler().handleDuplicateKeyException(
                new DuplicateKeyException("Duplicate entry for key 'uk_merchant_sku_code' with value 'SECRET'"));
        assertEquals(409, response.getCode());
        assertFalse(response.getMessage().contains("uk_merchant_sku_code"));
        assertFalse(response.getMessage().contains("SECRET"));
    }
}
