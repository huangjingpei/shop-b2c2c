/*
 * 文件：ManagementSecurityTest.java
 * 用途：管理参数、显式密码及公开店铺白名单的纯单元测试。
 * 维护注意：仓储使用Mock，非法参数断言无读写，合法参数覆盖定向列更新，禁止保存旧实体；公开record测试锁定字段白名单。不得接入真实数据库或外部进件服务。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.*;
import com.shop.b2b2c.controller.MerchantController;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.MerchantFeePolicyService;
import com.shop.b2b2c.service.MerchantOnboardingService;
import com.shop.b2b2c.service.MerchantSettlementAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagementSecurityTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings={"-0.01", "10.01", "100.01", "1.001", "0.50"})
    void invalidCommissionDoesNotReachPersistence(String value) {
        var store = mock(MerchantStoreMapper.class);
        var account = mock(MerchantPaymentAccountMapper.class);
        var user = mock(SysUserMapper.class);
        var feePolicy = mock(MerchantFeePolicyService.class);
        var controller = new MerchantController(store, account, user,
                mock(MerchantOnboardingService.class), mock(MerchantSettlementAccountService.class), feePolicy,
                new com.shop.b2b2c.service.MerchantStoreGuard(store));
        assertThrows(BusinessException.class, () -> controller.updateCommission(1L,
                value == null ? null : new BigDecimal(value), "测试费率调整", 3L));
        verifyNoInteractions(store, account, user, feePolicy);
    }

    @ParameterizedTest
    @ValueSource(strings={"0", "1", "10", "3.50"})
    void commissionBoundariesPersistThroughPolicyOnly(String value) {
        var store = mock(MerchantStoreMapper.class);
        var feePolicy = mock(MerchantFeePolicyService.class);
        var entity = new MerchantStore();
        when(store.findById(1L)).thenReturn(java.util.Optional.of(entity));
        try (var security = mockStatic(SecurityUtils.class)) {
            // G31 起调价操作人取自服务端会话，纯单元测试必须显式提供登录ID，不能依赖SaToken上下文。
            security.when(SecurityUtils::getCurrentUserId).thenReturn(3L);
            new MerchantController(store, mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class),
                    mock(MerchantOnboardingService.class), mock(MerchantSettlementAccountService.class), feePolicy,
                    new com.shop.b2b2c.service.MerchantStoreGuard(store))
                    .updateCommission(1L, new BigDecimal(value), "测试费率调整", 3L);
        }
        verify(feePolicy).adjust(eq(1L), argThat(form -> form.getRateBps() != null
                && new BigDecimal(form.getRateBps()).compareTo(new BigDecimal(value).multiply(new BigDecimal("100"))) == 0),
                any());
        verify(store, never()).save(any());
        verify(store, never()).updateCommissionOnly(anyLong(), any());
        assertEquals(new BigDecimal("3.00"), entity.getCommissionRate(), "读取快照不应被修改或回写");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints={-1,4})
    void rejectsUnknownStoreStatus(Integer value) { assertThrows(BusinessException.class, () -> ManagementGuards.status(value)); }

    @ParameterizedTest
    @ValueSource(strings={"-1", "0.001", "100000000"})
    void rejectsInvalidMonthlyFees(String value) { assertThrows(BusinessException.class, () -> ManagementGuards.monthlyFee(new BigDecimal(value))); }

    @Test
    void optionalTextLimitsAndEnumsAreEnforced() {
        assertDoesNotThrow(() -> ManagementGuards.text(null, 3, false));
        assertThrows(BusinessException.class, () -> ManagementGuards.text(" ", 3, true));
        assertThrows(BusinessException.class, () -> ManagementGuards.text("long", 3, false));
        assertThrows(BusinessException.class, () -> ManagementGuards.choice("invalid", Set.of("A","B","C")));
        assertDoesNotThrow(() -> ManagementGuards.monthlyFee(BigDecimal.ZERO));
        assertDoesNotThrow(() -> ManagementGuards.status(0));
        assertDoesNotThrow(() -> ManagementGuards.status(3));
    }

    @Test
    void invalidConfigurationsNeverReadOrWriteTheStore() {
        var stores = mock(MerchantStoreMapper.class);
        var controller = new MerchantController(stores, mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class),
                mock(MerchantOnboardingService.class), mock(MerchantSettlementAccountService.class), mock(MerchantFeePolicyService.class),
                new com.shop.b2b2c.service.MerchantStoreGuard(stores));
        var fee = new MerchantController.ServiceFeeConfigDTO(); fee.setMonthlyFee(new BigDecimal("-1"));
        assertThrows(BusinessException.class, () -> controller.updateServiceFee(1L, fee));
        fee.setMonthlyFee(BigDecimal.ZERO); fee.setFeeStatus("invented");
        assertThrows(BusinessException.class, () -> controller.updateServiceFee(1L, fee));
        var channel = new MerchantController.PaymentChannelConfigDTO(); channel.setPaymentChannelType("MOCK");
        assertThrows(BusinessException.class, () -> controller.updatePaymentChannel(1L, channel));
        channel.setPaymentChannelType("C"); channel.setCustomPayQrcode("x".repeat(513));
        assertThrows(BusinessException.class, () -> controller.updatePaymentChannel(1L, channel));
        var profile = new MerchantStore(); profile.setNotice("x".repeat(513));
        assertThrows(BusinessException.class, () -> controller.updateMerchantProfile(profile));
        verifyNoInteractions(stores);
    }

    @Test
    void missingMerchantCredentialsCannotCreateDefaultPasswordAccount() {
        var stores = mock(MerchantStoreMapper.class);
        var users = mock(SysUserMapper.class);
        var controller = new MerchantController(stores, mock(MerchantPaymentAccountMapper.class), users,
                mock(MerchantOnboardingService.class), mock(MerchantSettlementAccountService.class), mock(MerchantFeePolicyService.class),
                new com.shop.b2b2c.service.MerchantStoreGuard(stores));
        var dto = new MerchantController.CreateMerchantDTO();
        dto.setStoreCode("test"); dto.setStoreName("test"); dto.setOwnerName("owner");
        dto.setIdCardNumber("identity"); dto.setContactPhone("phone"); dto.setAdminUsername("admin");
        assertThrows(BusinessException.class, () -> controller.createMerchant(dto));
        dto.setAdminPassword("short");
        assertThrows(BusinessException.class, () -> controller.createMerchant(dto));
        dto.setAdminPassword("中".repeat(25));
        assertThrows(BusinessException.class, () -> controller.createMerchant(dto));
        verifyNoInteractions(stores, users);
    }

    @Test
    void publicStoreSerializationHasOnlyAllowlistedFields() {
        var store = new MerchantStore(); store.setId(1L); store.setOwnerName("private-owner");
        store.setIdCardNumber("private-id"); store.setStoreName("shop");
        var json = new ObjectMapper().valueToTree(PublicStoreView.from(store));
        assertEquals(6, json.size());
        for (String field : Set.of("id","storeCode","storeName","logoUrl","notice","contactPhone")) assertTrue(json.has(field));
        assertFalse(json.has("ownerName")); assertFalse(json.has("idCardNumber"));
        assertFalse(json.has("commissionRate")); assertFalse(json.has("feeStatus"));
        assertNull(PublicStoreView.from(null));
    }

    @Test
    void validMerchantCreationGoesThroughOnboardingAndKeepsExplicitPassword() {
        var stores = mock(MerchantStoreMapper.class);
        var onboarding = mock(MerchantOnboardingService.class);
        var application = new com.shop.b2b2c.entity.MerchantOnboardingApplication();
        application.setId(1L); application.setMerchantId(5L);
        when(onboarding.create(any(), any())).thenReturn(application);
        when(onboarding.submit(any(), any())).thenReturn(application);
        when(onboarding.approve(any(), any(), any(), any())).thenReturn(application);
        var provisioned = new MerchantStore();
        provisioned.setId(5L); provisioned.setCommissionRate(new BigDecimal("3.00"));
        when(stores.findById(5L)).thenReturn(java.util.Optional.of(provisioned));
        var controller = new MerchantController(stores, mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class),
                onboarding, mock(MerchantSettlementAccountService.class), mock(MerchantFeePolicyService.class),
                new com.shop.b2b2c.service.MerchantStoreGuard(stores));
        var dto = new MerchantController.CreateMerchantDTO();
        dto.setStoreCode("test"); dto.setStoreName("test"); dto.setOwnerName("owner");
        dto.setIdCardNumber("identity"); dto.setContactPhone("phone"); dto.setAdminUsername("admin");
        dto.setAdminPassword("explicit-password");
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(7L);
            var result = controller.createMerchant(dto).getData();
            assertEquals(new BigDecimal("3.00"), result.getCommissionRate());
        }
        // 建店与建账号副作用已移入入驻审核，这里只验证明文密码与默认费率被原样带入开店参数。
        var capture = org.mockito.ArgumentCaptor.forClass(MerchantOnboardingService.Provisioning.class);
        verify(onboarding).approve(eq(1L), capture.capture(), any(), eq(7L));
        assertEquals("explicit-password", capture.getValue().getAdminPassword());
        assertEquals(300, capture.getValue().getRateBps());
    }
}
