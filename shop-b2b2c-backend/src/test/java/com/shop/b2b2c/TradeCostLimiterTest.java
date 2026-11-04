/*
 * 文件：TradeCostLimiterTest.java
 * 用途：验证公开建单、库存预占与海报生成成本限流器的参数防御、Redis故障阻断、配额超限与摘要桶归一。
 * 维护注意：纯单元测试，使用Mock Redis，不连接外部网络；Redis故障时必须Fail-Closed阻断请求，防止未计量穿透。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.service.TradeCostLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TradeCostLimiterTest {

    @ParameterizedTest
    @CsvSource({
            "0,30,300,60,20,300,60,20,60",
            "10001,30,300,60,20,300,60,20,60",
            "60,0,300,60,20,300,60,20,60",
            "60,10001,300,60,20,300,60,20,60",
            "60,30,0,60,20,300,60,20,60",
            "60,30,3601,60,20,300,60,20,60",
            "60,30,300,0,20,300,60,20,60",
            "60,30,300,10001,20,300,60,20,60",
            "60,30,300,60,0,300,60,20,60",
            "60,30,300,60,10001,300,60,20,60",
            "60,30,300,60,20,0,60,20,60",
            "60,30,300,60,20,3601,60,20,60",
            "60,30,300,60,20,300,0,20,60",
            "60,30,300,60,20,300,10001,20,60",
            "60,30,300,60,20,300,60,0,60",
            "60,30,300,60,20,300,60,10001,60",
            "60,30,300,60,20,300,60,20,0",
            "60,30,300,60,20,300,60,20,3601"
    })
    void invalidThresholdsFailValidation(int cIp, int cContact, int cSec,
                                         int pIp, int pToken, int pSec,
                                         int posIp, int posToken, int posSec) {
        assertThrows(IllegalArgumentException.class, () -> new TradeCostLimiter(
                mock(StringRedisTemplate.class), cIp, cContact, cSec, pIp, pToken, pSec, posIp, posToken, posSec));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, 2L})
    void nonAllowRedisResponseThrowsAppropriateException(Long result) {
        var redis = mock(StringRedisTemplate.class);
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(result);
        var limiter = new TradeCostLimiter(redis, 60, 30, 300, 60, 20, 300, 60, 20, 60);

        var err1 = assertThrows(BusinessException.class, () -> limiter.checkOrderCreation("127.0.0.1", "13800000000"));
        assertEquals(result == null ? 503 : 429, err1.getCode());

        var err2 = assertThrows(BusinessException.class, () -> limiter.checkPreLock("127.0.0.1", "TOKEN123"));
        assertEquals(result == null ? 503 : 429, err2.getCode());

        var err3 = assertThrows(BusinessException.class, () -> limiter.checkPosterGeneration("127.0.0.1", "TOKEN123"));
        assertEquals(result == null ? 503 : 429, err3.getCode());
    }

    @Test
    void redisExceptionFailsClosedAndSanitizesInternalDetails() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenThrow(new IllegalStateException("secret-redis-connection-failure"));
        var limiter = new TradeCostLimiter(redis, 60, 30, 300, 60, 20, 300, 60, 20, 60);

        var err = assertThrows(BusinessException.class, () -> limiter.checkOrderCreation("192.0.2.1", "buyer@example.com"));
        assertEquals(503, err.getCode());
        assertFalse(err.getMessage().contains("secret-redis-connection-failure"));
        assertTrue(err.getMessage().contains("交易建单服务暂不可用"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void bucketsAreNormalizedHashedAndIndependent() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(1L);
        var limiter = new TradeCostLimiter(redis, 60, 30, 300, 60, 20, 300, 60, 20, 60);

        // Normalized contact variants share same contact bucket
        limiter.checkOrderCreation("192.0.2.1", " Test@Example.COM ");
        limiter.checkOrderCreation("192.0.2.1", "test@example.com");

        // Token variants share same token bucket
        limiter.checkPreLock("192.0.2.2", " pay-token-abc ");
        limiter.checkPreLock("192.0.2.2", "PAY-TOKEN-ABC");

        // Poster token variants
        limiter.checkPosterGeneration("192.0.2.3", " poster-xyz ");

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis, atLeast(8)).execute(any(), captor.capture(), any(), any());

        for (List keys : captor.getAllValues()) {
            String key = keys.get(0).toString();
            assertTrue(key.matches("^rate:cost:[a-f0-9]{64}$"), "Key must be SHA-256 hashed: " + key);
            assertFalse(key.contains("test@example.com"));
            assertFalse(key.contains("pay-token"));
            assertFalse(key.contains("192.0.2"));
        }
    }

    @Test
    void nullOrBlankAddressFallsBackGracefully() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(1L);
        var limiter = new TradeCostLimiter(redis, 60, 30, 300, 60, 20, 300, 60, 20, 60);

        assertDoesNotThrow(() -> limiter.checkOrderCreation(null, null));
        assertDoesNotThrow(() -> limiter.checkOrderCreation("   ", ""));
        assertDoesNotThrow(() -> limiter.checkPreLock(null, null));
        assertDoesNotThrow(() -> limiter.checkPosterGeneration(null, null));
    }
}
