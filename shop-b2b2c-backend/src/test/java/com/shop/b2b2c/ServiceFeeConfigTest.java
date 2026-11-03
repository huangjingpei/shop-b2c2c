/*
 * 文件：ServiceFeeConfigTest.java
 * 用途：服务费五种编码保存、缺省保留及非法请求无副作用的单元回归。
 * 维护注意：Mock仓储，不连接数据库；TRIAL/EXEMPTED和OVERDUE/ARREARS必须分别存储，不用本用例推定扣费、到期转换或停服已实现。
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
    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "TRIAL", "OVERDUE", "EXEMPTED", "ARREARS"})
    void allSupportedCodesSaveExactlyWithZeroMonthlyFee(String code) {
        var stores = mock(MerchantStoreMapper.class);
        var store = new MerchantStore(); store.setStatus(1);
        when(stores.findById(1L)).thenReturn(Optional.of(store));
        var dto = new MerchantController.ServiceFeeConfigDTO();
        dto.setFeeStatus(code); dto.setMonthlyFee(BigDecimal.ZERO); dto.setFeeFreeUntil(LocalDate.of(2028, 2, 29));
        var response = new MerchantController(stores, mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class))
                .updateServiceFee(1L, dto);
        assertEquals(200, response.getCode()); assertEquals(code, store.getFeeStatus());
        assertEquals(BigDecimal.ZERO, store.getMonthlyFee());
        assertEquals(LocalDate.of(2028, 2, 29).atStartOfDay(), store.getFeeFreeUntil());
        assertEquals(1, store.getStatus()); verify(stores).save(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "normal", " TRIAL", "ARREARS ", "UNKNOWN"})
    void invalidCodeCannotReadOrWrite(String code) {
        var stores = mock(MerchantStoreMapper.class);
        var dto = new MerchantController.ServiceFeeConfigDTO(); dto.setFeeStatus(code);
        var ex = assertThrows(BusinessException.class, () -> new MerchantController(stores,
                mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class)).updateServiceFee(1L, dto));
        assertEquals(400, ex.getCode()); verifyNoInteractions(stores);
    }

    @Test
    void absentStatusAndDatePreserveExistingValuesIncludingUnknownHistoricalCode() {
        var stores = mock(MerchantStoreMapper.class);
        var store = new MerchantStore(); store.setFeeStatus("HISTORICAL");
        store.setFeeFreeUntil(LocalDate.of(2026, 12, 31).atStartOfDay());
        when(stores.findById(1L)).thenReturn(Optional.of(store));
        var dto = new MerchantController.ServiceFeeConfigDTO(); dto.setMonthlyFee(BigDecimal.TEN);
        new MerchantController(stores, mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class)).updateServiceFee(1L, dto);
        assertEquals("HISTORICAL", store.getFeeStatus());
        assertEquals(LocalDate.of(2026, 12, 31).atStartOfDay(), store.getFeeFreeUntil());
        assertEquals(BigDecimal.TEN, store.getMonthlyFee()); verify(stores).save(store);
    }

    @Test
    void nullRequestIsRejectedBeforePersistence() {
        var stores = mock(MerchantStoreMapper.class);
        var ex = assertThrows(BusinessException.class, () -> new MerchantController(stores,
                mock(MerchantPaymentAccountMapper.class), mock(SysUserMapper.class)).updateServiceFee(1L, null));
        assertEquals(400, ex.getCode()); verifyNoInteractions(stores);
    }
}
