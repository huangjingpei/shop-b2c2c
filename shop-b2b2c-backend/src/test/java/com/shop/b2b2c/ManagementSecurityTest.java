package com.shop.b2b2c;

import com.shop.b2b2c.common.*;
import com.shop.b2b2c.controller.MerchantController;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.*;
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
    @ValueSource(strings={"-0.01", "100.01", "1.001"})
    void invalidCommissionDoesNotReachPersistence(String value) {
        var store = mock(MerchantStoreMapper.class);
        var account = mock(MerchantPaymentAccountMapper.class);
        var user = mock(SysUserMapper.class);
        var controller = new MerchantController(store, account, user);
        assertThrows(BusinessException.class, () -> controller.updateCommission(1L, value == null ? null : new BigDecimal(value)));
        verifyNoInteractions(store, account, user);
    }

    @ParameterizedTest
    @ValueSource(strings={"0", "100", "3.50"})
    void commissionBoundariesPersist(String value) {
        var store = mock(MerchantStoreMapper.class);
        var entity = new MerchantStore();
        when(store.findById(1L)).thenReturn(java.util.Optional.of(entity));
        new MerchantController(store, mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class))
                .updateCommission(1L, new BigDecimal(value));
        assertEquals(new BigDecimal(value), entity.getCommissionRate());
        verify(store).save(entity);
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
        var controller = new MerchantController(stores, mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class));
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
        var controller = new MerchantController(stores, mock(MerchantPaymentAccountMapper.class), users);
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
    void validMerchantCreationHashesExplicitPasswordAndPreservesDefaults() {
        var stores = mock(MerchantStoreMapper.class);
        var users = mock(SysUserMapper.class);
        when(stores.save(any())).thenAnswer(invocation -> {
            MerchantStore store = invocation.getArgument(0); store.setId(5L); return store;
        });
        var controller = new MerchantController(stores, mock(MerchantPaymentAccountMapper.class), users);
        var dto = new MerchantController.CreateMerchantDTO();
        dto.setStoreCode("test"); dto.setStoreName("test"); dto.setOwnerName("owner");
        dto.setIdCardNumber("identity"); dto.setContactPhone("phone"); dto.setAdminUsername("admin");
        dto.setAdminPassword("explicit-password");
        var result = controller.createMerchant(dto).getData();
        assertEquals(new BigDecimal("3.00"), result.getCommissionRate());
        var capture = org.mockito.ArgumentCaptor.forClass(com.shop.b2b2c.entity.SysUser.class);
        verify(users).save(capture.capture());
        assertTrue(cn.dev33.satoken.secure.BCrypt.checkpw("explicit-password", capture.getValue().getPassword()));
        assertEquals(5L, capture.getValue().getMerchantId());
    }
}
