/*
 * 文件：AuthSessionServiceTest.java
 * 用途：验证账号状态、角色和租户授权快照的失效处理、合法访问及认证日志去敏。
 * 维护注意：静态会话与Mapper均隔离Mock；数据库故障必须拒绝调用，不能降级用缓存授权；真实登录注销链路另由Spring集成测试覆盖。
 */
package com.shop.b2b2c;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.SysUserMapper;
import com.shop.b2b2c.service.AuthSessionService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthSessionServiceTest {
    private SysUser user() {
        SysUser user = new SysUser();
        user.setId(200L); user.setIsActive(true);
        user.setRoleCode("MERCHANT_ADMIN"); user.setMerchantId(1L);
        return user;
    }

    @ParameterizedTest
    @ValueSource(strings = {"MERCHANT_ADMIN", "MERCHANT_STAFF", "SUPERADMIN"})
    void validIdentityIsAllowed(String role) {
        var mapper = mock(SysUserMapper.class);
        var user = user(); user.setRoleCode(role);
        when(mapper.findById(200L)).thenReturn(Optional.of(user));
        try (var stp = mockStatic(StpUtil.class); var security = mockStatic(SecurityUtils.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(200L);
            security.when(SecurityUtils::getCurrentRoleCode).thenReturn(role);
            security.when(SecurityUtils::getCurrentMerchantId).thenReturn(1L);
            assertSame(user, new AuthSessionService(mapper).requireCurrentUser());
            stp.verify(StpUtil::checkLogin);
            stp.verify(StpUtil::logout, never());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "disabled", "nullRole", "unknownRole", "roleChanged", "merchantChanged", "unbound", "legacyToken"})
    void changedOrInvalidIdentityRevokesCurrentToken(String scenario) {
        var mapper = mock(SysUserMapper.class);
        var user = user();
        switch (scenario) {
            case "disabled" -> user.setIsActive(false);
            case "nullRole" -> user.setRoleCode(null);
            case "unknownRole" -> user.setRoleCode("UNKNOWN");
            case "roleChanged" -> user.setRoleCode("SUPERADMIN");
            case "merchantChanged" -> user.setMerchantId(2L);
            case "unbound" -> user.setMerchantId(null);
        }
        when(mapper.findById(200L)).thenReturn(scenario.equals("deleted") ? Optional.empty() : Optional.of(user));
        try (var stp = mockStatic(StpUtil.class); var security = mockStatic(SecurityUtils.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(200L);
            security.when(SecurityUtils::getCurrentRoleCode).thenReturn(scenario.equals("legacyToken") ? null : "MERCHANT_ADMIN");
            security.when(SecurityUtils::getCurrentMerchantId).thenReturn(1L);
            assertEquals(401, assertThrows(BusinessException.class, () -> new AuthSessionService(mapper).requireCurrentUser()).getCode());
            stp.verify(StpUtil::logout);
        }
    }

    @Test
    void databaseFailureDoesNotFallBackToCachedIdentity() {
        var mapper = mock(SysUserMapper.class);
        when(mapper.findById(200L)).thenThrow(new IllegalStateException("isolated database failure"));
        try (var stp = mockStatic(StpUtil.class); var security = mockStatic(SecurityUtils.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(200L);
            assertThrows(IllegalStateException.class, () -> new AuthSessionService(mapper).requireCurrentUser());
            security.verifyNoInteractions();
        }
    }

    @Test
    void anonymousRequestNeverReadsAccount() {
        var mapper = mock(SysUserMapper.class);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::checkLogin).thenThrow(new cn.dev33.satoken.exception.NotLoginException("expired", "login", "invalid"));
            assertThrows(cn.dev33.satoken.exception.NotLoginException.class, () -> new AuthSessionService(mapper).requireCurrentUser());
            verifyNoInteractions(mapper);
        }
    }

    @Test
    void authenticationFailureNeverLogsRawTokenExceptionText() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(com.shop.b2b2c.common.GlobalExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var error = new cn.dev33.satoken.exception.NotLoginException("token=private-token-fixture", "login", "invalid");
            assertTrue(error.getMessage().contains("private-token-fixture"));
            var response = new com.shop.b2b2c.common.GlobalExceptionHandler().handleNotLoginException(error);
            assertEquals(401, response.getCode());
            assertFalse(response.getMessage().contains("private-token-fixture"));
            assertFalse(appender.list.isEmpty());
            for (var event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("private-token-fixture"));
                assertNull(event.getThrowableProxy());
            }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
