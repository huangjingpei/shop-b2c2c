/*
 * 文件：ServiceFeeConfigTest.java
 * 用途：服务费五编码、null保留和显式清日期的定向写入单元回归。
 * 维护注意：Mock仅验证校验/参数/无整实体回写；实际SQL保留、清除与并发由H2集成测试验证，不代表真实扣费或MySQL已验收。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.controller.MerchantController;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceFeeConfigTest {
    private MerchantController controller(MerchantStoreMapper stores) {
        return new MerchantController(stores, mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "TRIAL", "OVERDUE", "EXEMPTED", "ARREARS"})
    void supportedCodesAndZeroFeeOnlyWriteFeeColumns(String code) {
        var stores = mock(MerchantStoreMapper.class);
        var snapshot = new MerchantStore(); snapshot.setStatus(3);
        when(stores.findById(1L)).thenReturn(Optional.of(snapshot));
        var dto = new MerchantController.ServiceFeeConfigDTO();
        dto.setFeeStatus(code); dto.setMonthlyFee(BigDecimal.ZERO); dto.setFeeFreeUntil(LocalDate.of(2028, 2, 29));
        assertEquals(200, controller(stores).updateServiceFee(1L, dto).getCode());
        verify(stores).updateServiceFeeOnly(1L, BigDecimal.ZERO, LocalDate.of(2028, 2, 29).atStartOfDay(), code, false);
        verify(stores, never()).save(any());
        assertEquals(3, snapshot.getStatus()); assertEquals("NORMAL", snapshot.getFeeStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "normal", " TRIAL", "ARREARS ", "UNKNOWN"})
    void invalidCodeCannotReadOrWrite(String code) {
        var stores = mock(MerchantStoreMapper.class);
        var dto = new MerchantController.ServiceFeeConfigDTO(); dto.setFeeStatus(code);
        assertEquals(400, assertThrows(BusinessException.class, () -> controller(stores).updateServiceFee(1L, dto)).getCode());
        verifyNoInteractions(stores);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void explicitClearFlagIsIndependentOfNullOptionalFields(boolean clear) {
        var stores = mock(MerchantStoreMapper.class);
        when(stores.findById(1L)).thenReturn(Optional.of(new MerchantStore()));
        var dto = new MerchantController.ServiceFeeConfigDTO(); dto.setClearFeeFreeUntil(clear);
        controller(stores).updateServiceFee(1L, dto);
        verify(stores).updateServiceFeeOnly(1L, null, null, null, clear);
        verify(stores, never()).save(any());
    }

    @Test
    void clearFlagWinsOverSimultaneousDateAndMissingFlagPreservesNulls() {
        var stores = mock(MerchantStoreMapper.class);
        when(stores.findById(1L)).thenReturn(Optional.of(new MerchantStore()));
        var dto = new MerchantController.ServiceFeeConfigDTO();
        dto.setClearFeeFreeUntil(true); dto.setFeeFreeUntil(LocalDate.of(2028, 2, 29));
        controller(stores).updateServiceFee(1L, dto);
        verify(stores).updateServiceFeeOnly(1L, null, LocalDate.of(2028, 2, 29).atStartOfDay(), null, true);
        controller(stores).updateServiceFee(1L, new MerchantController.ServiceFeeConfigDTO());
        verify(stores).updateServiceFeeOnly(1L, null, null, null, false);
        verify(stores, never()).save(any());
    }

    @Test
    void nullRequestIsRejectedBeforePersistence() {
        var stores = mock(MerchantStoreMapper.class);
        assertEquals(400, assertThrows(BusinessException.class, () -> controller(stores).updateServiceFee(1L, null)).getCode());
        verifyNoInteractions(stores);
    }
}
