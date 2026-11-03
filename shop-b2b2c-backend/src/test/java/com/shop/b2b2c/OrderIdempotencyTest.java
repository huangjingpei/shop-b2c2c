/*
 * 文件：OrderIdempotencyTest.java
 * 用途：建单HTTP幂等键输入边界的纯单元测试。
 * 维护注意：键值应随机且保密；格式检查不是认证，数据库并发与回滚须另由集成测试验证。
 */
package com.shop.b2b2c;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.service.OrderIdempotencyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderIdempotencyTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings={"", "short", "123456789012345!", "123456789012345 ", "123456789012345中"})
    void rejectsMissingShortOrUnsafeKey(String value) {
        assertThrows(BusinessException.class, () -> OrderIdempotencyService.requireKey(value));
    }
    @Test
    void acceptsBoundariesAndRejectsOversizeKey() {
        assertDoesNotThrow(() -> OrderIdempotencyService.requireKey("a".repeat(16)));
        assertDoesNotThrow(() -> OrderIdempotencyService.requireKey("A_1-".repeat(32)));
        assertThrows(BusinessException.class, () -> OrderIdempotencyService.requireKey("a".repeat(129)));
    }
}
