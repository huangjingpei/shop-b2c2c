/*
 * 文件：AuthBoundaryTest.java
 * 用途：验证精确CORS白名单、登录限流故障关闭、账号桶归一和参数拒绝先后顺序。
 * 维护注意：仅使用内存请求及Mock Redis/Mapper，不访问外部服务；Lua部署及真实代理IP拓扑需独立联调，Mock允许不代表Redis原子性已实测。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.config.CorsOrigins;
import com.shop.b2b2c.controller.AuthController;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.SysUserMapper;
import com.shop.b2b2c.service.AuthLoginLimiter;
import com.shop.b2b2c.service.AuthSessionService;
import com.shop.b2b2c.service.CaptchaService;
import com.shop.b2b2c.service.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "null", "file:///tmp", "ftp://example.com", "https://example.com/",
            "https://example.com/path", "https://user:private@example.com", "https://example.com?x=1", "https://example.com#x",
            "https://example.com:0", "https://example.com:65536", ",https://example.com", "https://example.com,", "https://example.com,invalid"})
    void invalidOriginsFailClosedWithoutEchoingConfig(String value) {
        var error = assertThrows(IllegalArgumentException.class, () -> new CorsOrigins(value));
        assertFalse(error.getMessage().contains("private"));
    }

    @Test
    void preciseOriginsAreTrimmedDeduplicatedAndDefensivelyCopied() {
        var config = new CorsOrigins("https://shop.example.com, http://localhost:3001,https://shop.example.com");
        assertArrayEquals(new String[]{"https://shop.example.com", "http://localhost:3001"}, config.allowedOrigins());
        config.allowedOrigins()[0] = "*";
        assertEquals("https://shop.example.com", config.allowedOrigins()[0]);
        assertEquals(0, new CorsOrigins("").allowedOrigins().length);
        assertEquals(0, new CorsOrigins("  ").allowedOrigins().length);
    }

    @Test
    void automaticOriginsUseConfiguredPublicHostAndDropFrontendPath() {
        assertArrayEquals(new String[]{"http://localhost:3001", "http://localhost:3002", "https://shop.example.com:8443"},
                new CorsOrigins("auto", "https://shop.example.com:8443/m/").allowedOrigins());
        assertArrayEquals(new String[]{"https://explicit.example.com"},
                new CorsOrigins("https://explicit.example.com", "invalid-unused-frontend").allowedOrigins());
        assertEquals(0, new CorsOrigins("", "https://shop.example.com").allowedOrigins().length);
        assertThrows(IllegalArgumentException.class, () -> new CorsOrigins("auto", "https://user:private@example.com/m/"));
        assertThrows(IllegalArgumentException.class, () -> new CorsOrigins("auto", "file:///tmp"));
    }

    @Test
    void emptyCorsPolicyDoesNotInheritSpringWildcardDefaults() {
        class Registry extends org.springframework.web.servlet.config.annotation.CorsRegistry {
            org.springframework.web.cors.CorsConfiguration policy() { return getCorsConfigurations().get("/**"); }
        }
        var registry = new Registry();
        new com.shop.b2b2c.config.SaTokenConfigure(mock(AuthSessionService.class), new CorsOrigins("")).addCorsMappings(registry);
        assertNull(registry.policy().checkOrigin("https://unlisted.example.com"));
        assertEquals(Boolean.FALSE, registry.policy().getAllowCredentials());
    }

    @ParameterizedTest
    @CsvSource({"0,100,300", "10001,100,300", "20,0,300", "20,10001,300", "20,100,0", "20,100,3601"})
    void invalidRateConfigCannotDisableProtection(int account, int ip, int seconds) {
        assertThrows(IllegalArgumentException.class, () -> new AuthLoginLimiter(mock(StringRedisTemplate.class), account, ip, seconds));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, 2})
    void limiterDeniesNullAndNonAllowResponses(Long result) {
        var redis = mock(StringRedisTemplate.class);
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(result);
        var limiter = new AuthLoginLimiter(redis, 20, 100, 300);
        var error = assertThrows(BusinessException.class, () -> limiter.checkAddress("127.0.0.1"));
        assertEquals(result == null ? 503 : 429, error.getCode());
    }

    @Test
    void redisFailureIsSanitizedAndCannotReachCredentials() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenThrow(new IllegalStateException("private-redis-detail"));
        var users = mock(SysUserMapper.class);
        var controller = new AuthController(users, mock(AuthSessionService.class), mock(MerchantStoreMapper.class), new AuthLoginLimiter(redis, 20, 100, 300), new ClientIpResolver("127.0.0.1,::1"), new CaptchaService(false, 300));
        var dto = new AuthController.LoginDTO(); dto.setUsername("fixture"); dto.setPassword("fixture-password");
        var error = assertThrows(BusinessException.class, () -> controller.login(dto, new MockHttpServletRequest()));
        assertEquals(503, error.getCode()); assertFalse(error.getMessage().contains("private-redis-detail"));
        verifyNoInteractions(users);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void normalizedAccountVariantsShareBucketWhileIpAndOtherAccountsDoNot() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(1L);
        var limiter = new AuthLoginLimiter(redis, 20, 100, 300);
        limiter.checkAccount(" ÁdMiN "); limiter.checkAccount("admin"); limiter.checkAccount("other"); limiter.checkAddress("admin");
        var buckets = org.mockito.ArgumentCaptor.forClass(List.class);
        var limits = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis, times(4)).execute(any(), buckets.capture(), limits.capture(), eq("300"));
        assertEquals(buckets.getAllValues().get(0), buckets.getAllValues().get(1));
        assertNotEquals(buckets.getAllValues().get(1), buckets.getAllValues().get(2));
        assertNotEquals(buckets.getAllValues().get(1), buckets.getAllValues().get(3));
        for (var keys : buckets.getAllValues()) assertTrue(keys.get(0).toString().matches("rate:auth:login:[a-f0-9]{64}"));
        assertEquals(List.of("20", "20", "20", "100"), limits.getAllValues());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nullBody", "nullUsername", "blankUsername", "longUsername", "nullPassword", "emptyPassword", "longPassword"})
    void malformedCredentialsAreRejectedBeforeAccountLookup(String scenario) {
        var limiter = mock(AuthLoginLimiter.class); var users = mock(SysUserMapper.class);
        var controller = new AuthController(users, mock(AuthSessionService.class), mock(MerchantStoreMapper.class), limiter, new ClientIpResolver("127.0.0.1,::1"), new CaptchaService(false, 300));
        var dto = new AuthController.LoginDTO(); dto.setUsername("fixture"); dto.setPassword("fixture-password");
        switch (scenario) {
            case "nullUsername" -> dto.setUsername(null);
            case "blankUsername" -> dto.setUsername("  ");
            case "longUsername" -> dto.setUsername("x".repeat(65));
            case "nullPassword" -> dto.setPassword(null);
            case "emptyPassword" -> dto.setPassword("");
            case "longPassword" -> dto.setPassword("x".repeat(1025));
        }
        assertEquals(400, assertThrows(BusinessException.class,
                () -> controller.login(scenario.equals("nullBody") ? null : dto, new MockHttpServletRequest())).getCode());
        verify(limiter).checkAddress(any()); verify(limiter, never()).checkAccount(any()); verifyNoInteractions(users);
    }
}
