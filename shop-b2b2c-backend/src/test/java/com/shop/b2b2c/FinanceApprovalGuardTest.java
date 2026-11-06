/*
 * 文件：FinanceApprovalGuardTest.java
 * 用途：G31 复核人资质校验回归：启用 SUPERADMIN 放行，禁用超管/非超管/不存在账号一律 403。
 * 维护注意：
 *  1. 使用 Mockito 隔离 SysUserMapper，不启动 Spring、不连接数据库。
 *  2. 资质校验必须位于 resolveReviewer，服务层所有财务动作共用；不要为单个入口另写校验。
 *  3. 同人复核与缺复核人的开关口径仍由 verify 覆盖；本用例聚焦“不同人复核”的账号资质。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.SysUser;
import com.shop.b2b2c.mapper.SysUserMapper;
import com.shop.b2b2c.service.FinanceApprovalGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinanceApprovalGuardTest {

    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final FinanceApprovalGuard loose = new FinanceApprovalGuard(false, userMapper);
    private final FinanceApprovalGuard strict = new FinanceApprovalGuard(true, userMapper);

    private SysUser account(Long id, String roleCode, boolean active) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRoleCode(roleCode);
        user.setIsActive(active);
        return user;
    }

    private BusinessException rejected(Long reviewerId) {
        return assertThrows(BusinessException.class, () -> loose.resolveReviewer(7L, reviewerId));
    }

    @Test
    void activeSuperAdminReviewerIsAccepted() {
        when(userMapper.selectById(8L)).thenReturn(account(8L, "SUPERADMIN", true));

        assertEquals(FinanceApprovalGuard.MODE_DUAL_APPROVAL, loose.verify("调价", 7L, 8L));
        assertEquals(8L, loose.resolveReviewer(7L, 8L));
        assertEquals(8L, strict.resolveReviewer(7L, 8L));
    }

    @Test
    void disabledSuperAdminReviewerIsRejected() {
        when(userMapper.selectById(8L)).thenReturn(account(8L, "SUPERADMIN", false));

        BusinessException ex = rejected(8L);
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("启用状态的超级管理员"));
    }

    @Test
    void nonSuperAdminReviewerIsRejected() {
        when(userMapper.selectById(8L)).thenReturn(account(8L, "MERCHANT_ADMIN", true));

        BusinessException ex = rejected(8L);
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("启用状态的超级管理员"));
    }

    @Test
    void missingReviewerAccountIsRejected() {
        when(userMapper.selectById(8L)).thenReturn(null);

        BusinessException ex = rejected(8L);
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("启用状态的超级管理员"));
    }

    @Test
    void sameOperatorInLooseModeSkipsEligibilityLookup() {
        assertEquals(FinanceApprovalGuard.MODE_SAME_OPERATOR, loose.verify("调价", 7L, 7L));
        assertEquals(7L, loose.resolveReviewer(7L, 7L));
        verify(userMapper, never()).selectById(anyLong());
    }

    @Test
    void strictModeMissingReviewerReturnsExplicitCode() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> strict.verify("调价", 7L, null));

        assertEquals(FinanceApprovalGuard.CODE_MISSING_REVIEWER, ex.getCode());
        assertTrue(ex.getMessage().contains("必须选择复核人"));
    }

    @Test
    void strictModeSameOperatorIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> strict.verify("调价", 7L, 7L));

        assertEquals(403, ex.getCode());
    }
}
