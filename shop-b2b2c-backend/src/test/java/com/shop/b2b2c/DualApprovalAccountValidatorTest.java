/*
 * 文件：DualApprovalAccountValidatorTest.java
 * 用途：双人复核开关账号数量守卫回归：prod 下启用超管不足 2 名阻断启动，dev 只告警，开关关闭不查库。
 * 维护注意：
 *  1. 使用 Mockito 隔离 SysUserMapper 与 MockEnvironment，不启动完整应用、不连接数据库。
 *  2. 本类只验证启动期数量守卫；业务链路“初审前拒绝”由 MerchantOnboardingServiceTest 覆盖。
 *  3. 只统计启用 SUPERADMIN，不得把商户账号或禁用账号计入。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.config.DualApprovalAccountValidator;
import com.shop.b2b2c.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DualApprovalAccountValidatorTest {

    private final SysUserMapper users = mock(SysUserMapper.class);

    private DualApprovalAccountValidator validator(boolean dual, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        if (profiles.length > 0) {
            environment.setActiveProfiles(profiles);
        }
        return new DualApprovalAccountValidator(environment, dual, users);
    }

    @Test
    void prodWithDualApprovalAndSingleSuperAdminBlocksStartup() {
        when(users.selectCount(any())).thenReturn(1L);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator(true, "prod").run(null));

        assertTrue(ex.getMessage().contains("超级管理员不足"));
        assertTrue(ex.getMessage().contains("已阻断启动"));
    }

    @Test
    void prodWithDualApprovalAndTwoSuperAdminsStartsNormally() {
        when(users.selectCount(any())).thenReturn(2L);

        assertDoesNotThrow(() -> validator(true, "prod").run(null));
    }

    @Test
    void devWithDualApprovalAndSingleSuperAdminOnlyWarns() {
        when(users.selectCount(any())).thenReturn(1L);

        assertDoesNotThrow(() -> validator(true, "dev").run(null));
    }

    @Test
    void switchOffSkipsAccountCountLookup() {
        assertDoesNotThrow(() -> validator(false, "prod").run(null));

        verify(users, never()).selectCount(any());
    }
}
