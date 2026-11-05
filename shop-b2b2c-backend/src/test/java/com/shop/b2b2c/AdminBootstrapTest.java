/*
 * 文件：AdminBootstrapTest.java
 * 用途：生产首次管理员受控初始化回归，覆盖关闭、半配置失败、默认口令拒绝、首次创建、已有超管与同名账号不覆盖。
 * 维护注意：使用Mockito替身，不连接数据库；断言只检查角色、散列可校验和是否保存，真实部署仍需人工验证环境变量注入与首登。
 */
package com.shop.b2b2c;

import cn.dev33.satoken.secure.BCrypt;
import com.shop.b2b2c.config.AdminBootstrap;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {

    private final SysUserMapper users = mock(SysUserMapper.class);

    @Test
    void disabledWhenBothValuesEmpty() {
        new AdminBootstrap(users, "", "").run(null);
        verify(users, never()).findByUsername(any());
        verify(users, never()).save(any());
    }

    @Test
    void rejectsHalfConfiguredCredentials() {
        for (String[] pair : new String[][]{{"ops", ""}, {"", "StrongPass!2026"}}) {
            assertThrows(IllegalStateException.class, () -> new AdminBootstrap(users, pair[0], pair[1]).run(null));
        }
        verify(users, never()).save(any());
    }

    @Test
    void rejectsDemoDefaultPassword() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AdminBootstrap(users, "ops", "Admin123").run(null));
        assertTrue(error.getMessage().contains("默认口令"));
        verify(users, never()).save(any());
    }

    @Test
    void createsFirstSuperAdminWithHashedPassword() {
        when(users.findByUsername("ops")).thenReturn(Optional.empty());
        when(users.selectCount(any())).thenReturn(0L);

        new AdminBootstrap(users, " ops ", " StrongPass!2026 ").run(null);

        ArgumentCaptor<SysUser> saved = ArgumentCaptor.forClass(SysUser.class);
        verify(users).save(saved.capture());
        SysUser admin = saved.getValue();
        assertEquals("ops", admin.getUsername());
        assertEquals("SUPERADMIN", admin.getRoleCode());
        assertTrue(admin.getIsActive());
        assertFalse(admin.getPassword().contains("StrongPass!2026"));
        assertTrue(BCrypt.checkpw("StrongPass!2026", admin.getPassword()));
    }

    @Test
    void doesNotOverwriteExistingSuperAdmin() {
        when(users.findByUsername("ops")).thenReturn(Optional.empty());
        when(users.selectCount(any())).thenReturn(1L);
        new AdminBootstrap(users, "ops", "StrongPass!2026").run(null);
        verify(users, never()).save(any());
    }

    @Test
    void doesNotOverwriteExistingUsername() {
        SysUser existing = new SysUser();
        existing.setUsername("ops");
        when(users.findByUsername("ops")).thenReturn(Optional.of(existing));
        new AdminBootstrap(users, "ops", "StrongPass!2026").run(null);
        verify(users, never()).selectCount(any());
        verify(users, never()).save(any());
    }
}
