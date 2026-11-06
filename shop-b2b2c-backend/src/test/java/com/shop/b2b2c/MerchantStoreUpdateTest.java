/*
 * 文件：MerchantStoreUpdateTest.java
 * 用途：店铺配置定向更新的权限、归属、空请求/编号、缺失记录及重复设置单元边界。
 * 维护注意：仓储及会话使用Mock；不允许为了测试吞授权异常。SQL并发保留与清日期由隔离集成用例验证，不连接业务库。
 * 错误码口径（G33）：先身份/授权后资源存在性——无权限 403（不泄露资源是否存在），有权限但资源不存在 404，
 * 参数非法 400，内部不变量失败 500。因此本用例必须先补管理角色桩，才能验证“有权但记录缺失”返回 404。
 */
package com.shop.b2b2c;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.controller.MerchantController;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.MerchantFeePolicyService;
import com.shop.b2b2c.service.MerchantOnboardingService;
import com.shop.b2b2c.service.MerchantSettlementAccountService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MerchantStoreUpdateTest {
    private MerchantController controller(MerchantStoreMapper stores) {
        return new MerchantController(stores, mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class),
                mock(MerchantOnboardingService.class), mock(MerchantSettlementAccountService.class), mock(MerchantFeePolicyService.class));
    }

    @Test
    void everyExistingStoreOperationRejectsMissingRecordWithoutAnyUpdate() {
        var stores = mock(MerchantStoreMapper.class); when(stores.findById(99L)).thenReturn(Optional.empty());
        var c = controller(stores);
        try (var security = mockStatic(SecurityUtils.class); var roles = mockStatic(StpUtil.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(99L);
            // G33 口径：先授权后存在性。补店长角色桩后，资料更新才会进入“记录缺失”判定并返回 404；
            // 无角色仍应由 updateMerchantProfile 自身用例断言 403，不能在本用例混同。
            security.when(SecurityUtils::getCurrentRoleCode).thenReturn("MERCHANT_ADMIN");
            for (Runnable operation : List.<Runnable>of(() -> c.updateStatus(99L, 3),
                    () -> c.updateCommission(99L, BigDecimal.ZERO, "测试费率调整", 2L),
                    () -> c.updateServiceFee(99L, new MerchantController.ServiceFeeConfigDTO()),
                    () -> c.updatePaymentChannel(99L, new MerchantController.PaymentChannelConfigDTO()),
                    () -> c.updateMerchantProfile(new MerchantStore()))) {
                assertEquals(404, assertThrows(BusinessException.class, operation::run).getCode());
            }
        }
        verify(stores, times(5)).findById(99L); verifyNoMoreInteractions(stores);
    }

    @Test
    void malformedIdentifiersAndNullBodiesCannotReachPersistence() {
        var stores = mock(MerchantStoreMapper.class); var c = controller(stores);
        for (Long id : new Long[] {null, 0L, -1L}) {
            assertEquals(400, assertThrows(BusinessException.class, () -> c.updateStatus(id, 3)).getCode());
            assertEquals(400, assertThrows(BusinessException.class, () -> c.updateCommission(id, BigDecimal.ZERO, "测试费率调整", 2L)).getCode());
        }
        assertEquals(400, assertThrows(BusinessException.class, () -> c.updateMerchantProfile(null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> c.updatePaymentChannel(1L, null)).getCode());
        verifyNoInteractions(stores);
    }

    @Test
    void profileUsesSessionTenantIgnoresInjectedFieldsAndDoesNotSwallowAuthorizationFailure() {
        var stores = mock(MerchantStoreMapper.class); var c = controller(stores);
        when(stores.findById(1L)).thenReturn(Optional.of(new MerchantStore()));
        var body = new MerchantStore(); body.setId(2L); body.setStatus(1); body.setCommissionRate(BigDecimal.ZERO);
        body.setNotice("allowed notice"); body.setLogoUrl("");
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            // Q13-A 起资料更新在接口层校验店长/平台管理员角色，不再直接调用 Sa-Token checkRoleOr。
            security.when(SecurityUtils::getCurrentRoleCode).thenReturn("MERCHANT_ADMIN");
            assertEquals(200, c.updateMerchantProfile(body).getCode());
            verify(stores).findById(1L);
            verify(stores).updateProfileOnly(1L, "allowed notice", "", null, null);
            verifyNoMoreInteractions(stores);
        }
        reset(stores);
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentRoleCode).thenReturn("MERCHANT_STAFF");
            assertEquals(403, assertThrows(BusinessException.class, () -> c.updateMerchantProfile(body)).getCode());
            verifyNoInteractions(stores);
        }
        // 会话缺少角色时必须按无权限拒绝；旧实现 Set.contains(null) 会抛 NPE 并映射为 500。
        try (var security = mockStatic(SecurityUtils.class)) {
            assertEquals(403, assertThrows(BusinessException.class, () -> c.updateMerchantProfile(body)).getCode());
            verifyNoInteractions(stores);
        }
    }

    @Test
    void zeroAffectedRowsForSameValuesAreAllowedWithoutStaleEntitySave() {
        var stores = mock(MerchantStoreMapper.class);
        var feePolicy = mock(MerchantFeePolicyService.class);
        when(stores.findById(1L)).thenReturn(Optional.of(new MerchantStore()));
        var c = new MerchantController(stores, mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class),
                mock(MerchantOnboardingService.class), mock(MerchantSettlementAccountService.class), feePolicy);
        assertEquals(200, c.updateStatus(1L, 1).getCode());
        try (var security = mockStatic(SecurityUtils.class)) {
            // G31 起调价操作人取自服务端会话；本用例聚焦定向更新，显式注入登录ID。
            security.when(SecurityUtils::getCurrentUserId).thenReturn(2L);
            assertEquals(200, c.updateCommission(1L, new BigDecimal("3.00"), "测试费率调整", 2L).getCode());
        }
        var channel = new MerchantController.PaymentChannelConfigDTO(); channel.setPaymentChannelType("C");
        channel.setCustomPayQrcode("");
        assertEquals(200, c.updatePaymentChannel(1L, channel).getCode());
        verify(stores).updateStatusOnly(1L, 1);
        verify(stores).updatePaymentChannelOnly(1L, "C", "");
        // T39：费率调整统一走政策服务，控制器不得再用旧实体回写整行或直接改展示镜像。
        verify(feePolicy).adjust(eq(1L), any(MerchantFeePolicyService.AdjustForm.class), any());
        verify(stores, never()).updateCommissionOnly(any(), any());
        verify(stores, never()).save(any());
    }
    @Test
    void persistenceFailurePropagatesInsteadOfRetryingWithWholeEntity() {
        var stores = mock(MerchantStoreMapper.class);
        when(stores.findById(1L)).thenReturn(Optional.of(new MerchantStore()));
        var failure = new org.springframework.dao.DataAccessResourceFailureException("isolated persistence failure");
        when(stores.updateStatusOnly(1L, 3)).thenThrow(failure);
        assertSame(failure, assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> controller(stores).updateStatus(1L, 3)));
        verify(stores, never()).save(any());
    }

}
