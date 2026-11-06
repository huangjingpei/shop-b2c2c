/*
 * 文件：MerchantStoreUpdateTest.java
 * 用途：店铺配置定向更新的权限、归属、空请求/编号、缺失记录及重复设置单元边界。
 * 维护注意：仓储及会话使用Mock；不允许为了测试吞授权异常。SQL并发保留与清日期由隔离集成用例验证，不连接业务库。
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
            for (Runnable operation : List.<Runnable>of(() -> c.updateStatus(99L, 3),
                    () -> c.updateCommission(99L, BigDecimal.ZERO),
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
            assertEquals(400, assertThrows(BusinessException.class, () -> c.updateCommission(id, BigDecimal.ZERO)).getCode());
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
        try (var security = mockStatic(SecurityUtils.class); var roles = mockStatic(StpUtil.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            assertEquals(200, c.updateMerchantProfile(body).getCode());
            roles.verify(() -> StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN"));
            verify(stores).findById(1L);
            verify(stores).updateProfileOnly(1L, "allowed notice", "", null, null);
            verifyNoMoreInteractions(stores);
        }
        reset(stores);
        try (var roles = mockStatic(StpUtil.class)) {
            roles.when(() -> StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN"))
                    .thenThrow(new BusinessException(403, "拒绝"));
            assertEquals(403, assertThrows(BusinessException.class, () -> c.updateMerchantProfile(body)).getCode());
            verifyNoInteractions(stores);
        }
    }

    @Test
    void zeroAffectedRowsForSameValuesAreAllowedWithoutStaleEntitySave() {
        var stores = mock(MerchantStoreMapper.class); var c = controller(stores);
        when(stores.findById(1L)).thenReturn(Optional.of(new MerchantStore()));
        assertEquals(200, c.updateStatus(1L, 1).getCode());
        assertEquals(200, c.updateCommission(1L, new BigDecimal("3.00")).getCode());
        var channel = new MerchantController.PaymentChannelConfigDTO(); channel.setPaymentChannelType("C");
        channel.setCustomPayQrcode("");
        assertEquals(200, c.updatePaymentChannel(1L, channel).getCode());
        verify(stores).updateStatusOnly(1L, 1);
        verify(stores).updateCommissionOnly(1L, new BigDecimal("3.00"));
        verify(stores).updatePaymentChannelOnly(1L, "C", "");
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
