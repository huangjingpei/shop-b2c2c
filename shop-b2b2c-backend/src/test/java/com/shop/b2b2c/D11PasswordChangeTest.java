/*
 * 文件：D11PasswordChangeTest.java
 * 用途：D11 首次登录强制改密的后端规则验证：改密接口强度/原密码校验、428 拦截开关、schema 列与幂等迁移断言。
 * 维护注意：
 * 1. 全部隔离 Mockito，不起 Spring/H2；StpUtil 静态注销用 mockStatic 断言，BCrypt 用真实算法生成散列。
 * 2. 改密成功路径必须同时断言清标记、落库与注销全部会话；只改其一属于回归。
 * 3. schema 断言只保证结构存在；真实 MySQL 列升级由 V11 幂等迁移负责，用户手动验证。
 */
package com.shop.b2b2c;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.controller.AuthController;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.SysUserMapper;
import com.shop.b2b2c.service.AuthLoginLimiter;
import com.shop.b2b2c.service.AuthSessionService;
import com.shop.b2b2c.service.CaptchaService;
import com.shop.b2b2c.service.ClientIpResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class D11PasswordChangeTest {

    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final MerchantStoreMapper storeMapper = mock(MerchantStoreMapper.class);
    private final AuthLoginLimiter loginLimiter = mock(AuthLoginLimiter.class);
    private final ClientIpResolver ipResolver = mock(ClientIpResolver.class);
    private final CaptchaService captchaService = mock(CaptchaService.class);
    private final AuthController controller =
            new AuthController(userMapper, sessions, storeMapper, loginLimiter, ipResolver, captchaService);

    private SysUser flaggedUser() {
        SysUser user = new SysUser();
        user.setId(300L);
        user.setUsername("shopadmin");
        user.setPassword(BCrypt.hashpw("InitPass123"));
        user.setRoleCode("MERCHANT_ADMIN");
        user.setMerchantId(1L);
        user.setIsActive(true);
        user.setMustChangePassword(Boolean.TRUE);
        return user;
    }

    private AuthController.ChangePasswordDTO dto(String oldPassword, String newPassword) {
        AuthController.ChangePasswordDTO dto = new AuthController.ChangePasswordDTO();
        dto.setOldPassword(oldPassword);
        dto.setNewPassword(newPassword);
        return dto;
    }

    @Test
    void changePasswordRejectsWeakNewPassword() {
        SysUser user = flaggedUser();
        when(sessions.requireCurrentUser()).thenReturn(user);
        try (var stp = mockStatic(StpUtil.class)) {
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> controller.changePassword(dto("InitPass123", "Ab1"))).getCode());
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> controller.changePassword(dto("InitPass123", "onlyletters"))).getCode());
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> controller.changePassword(dto("InitPass123", "12345678"))).getCode());
            verify(userMapper, never()).updateById(any());
        }
    }

    @Test
    void changePasswordRejectsWrongOldPassword() {
        SysUser user = flaggedUser();
        when(sessions.requireCurrentUser()).thenReturn(user);
        try (var stp = mockStatic(StpUtil.class)) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> controller.changePassword(dto("WrongPass123", "NewPass456")));
            assertEquals(400, error.getCode());
            assertTrue(error.getMessage().contains("原密码"));
            verify(userMapper, never()).updateById(any());
        }
    }

    @Test
    void changePasswordRejectsSameAsOldPassword() {
        SysUser user = flaggedUser();
        when(sessions.requireCurrentUser()).thenReturn(user);
        try (var stp = mockStatic(StpUtil.class)) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> controller.changePassword(dto("InitPass123", "InitPass123")));
            assertEquals(400, error.getCode());
            verify(userMapper, never()).updateById(any());
        }
    }

    @Test
    void changePasswordSuccessClearsFlagPersistsAndRevokesAllSessions() {
        SysUser user = flaggedUser();
        when(sessions.requireCurrentUser()).thenReturn(user);
        try (var stp = mockStatic(StpUtil.class)) {
            controller.changePassword(dto("InitPass123", "NewPass456"));
            assertEquals(Boolean.FALSE, user.getMustChangePassword());
            assertTrue(BCrypt.checkpw("NewPass456", user.getPassword()));
            verify(userMapper).updateById(user);
            // 改密后旧口令签发的会话一律作废，含当前会话。
            stp.verify(() -> StpUtil.logout(300L));
        }
    }

    @Test
    void requirePasswordChangeClearedBlocksFlaggedAccountWith428() {
        SysUser user = flaggedUser();
        AuthSessionService service = new AuthSessionService(userMapper);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.requirePasswordChangeCleared(user));
        assertEquals(428, error.getCode());
    }

    @Test
    void requirePasswordChangeClearedAllowsNormalAccount() {
        AuthSessionService service = new AuthSessionService(userMapper);
        SysUser cleared = flaggedUser();
        cleared.setMustChangePassword(Boolean.FALSE);
        assertDoesNotThrow(() -> service.requirePasswordChangeCleared(cleared));
        SysUser legacy = flaggedUser();
        legacy.setMustChangePassword(null);
        assertDoesNotThrow(() -> service.requirePasswordChangeCleared(legacy));
    }

    @Test
    void schemaDeclaresMustChangePasswordColumnWithDefaultZero() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"));
        assertTrue(schema.contains("`must_change_password` TINYINT(1) NOT NULL DEFAULT 0"),
                "sys_user 必须有 must_change_password 列且默认 0");
    }

    @Test
    void migrationV11AddsColumnIdempotently() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migrations/V11__d11_must_change_password.sql"));
        assertTrue(migration.contains("information_schema.columns"), "V11 必须先查列是否存在");
        assertTrue(migration.contains("must_change_password"), "V11 必须作用于 must_change_password 列");
        assertTrue(migration.contains("PREPARE"), "V11 必须用动态 DDL 保证可重复执行");
    }
}
