/*
 * 文件：MerchantStoreGuardTest.java
 * 用途：O4 商家写接口营业状态闸门的契约用例：营业(1)放行、待开通(0) 409 且文案含开通引导、
 *      打烊/封禁/注销(2/3/4) 409、店铺不存在 404；O9 资质到期限制上新闸门（含当天到期拦截、未采集放行）。
 * 维护注意：
 * 1. 使用 Mockito 隔离，不启动 Spring、不连接数据库；按 ID 加载走 selectById，与线上 MyBatis-Plus 语义一致。
 * 2. 待开通文案必须引导"完成开店协议签署"，不得退化为笼统错误；改文案口径时同步本用例。
 * 3. O9 用例依赖真实时钟（到期判定以当天为界），夹具日期取足够远的过去/未来避免跨日翻转。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.service.MerchantStoreGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerchantStoreGuardTest {

    private final MerchantStoreMapper storeMapper = mock(MerchantStoreMapper.class);
    private final MerchantStoreGuard guard = new MerchantStoreGuard(storeMapper);

    private MerchantStore store(int status) {
        MerchantStore store = new MerchantStore();
        store.setId(7L);
        store.setStatus(status);
        return store;
    }

    @Test
    void openStorePassesByIdAndEntity() {
        when(storeMapper.selectById(7L)).thenReturn(store(1));
        assertDoesNotThrow(() -> guard.requireOpen(7L));
        assertDoesNotThrow(() -> guard.requireOpen(store(1)));
    }

    @Test
    void pendingStoreIsRejectedWithOpeningGuidance() {
        when(storeMapper.selectById(7L)).thenReturn(store(0));
        BusinessException ex = assertThrows(BusinessException.class, () -> guard.requireOpen(7L));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("待开通"));
        assertTrue(ex.getMessage().contains("开店协议"));
    }

    @Test
    void nonOperatingStatusesAreRejected() {
        for (int status : new int[]{2, 3, 4}) {
            BusinessException ex = assertThrows(BusinessException.class, () -> guard.requireOpen(store(status)));
            assertEquals(409, ex.getCode());
        }
    }

    @Test
    void missingStoreOrMerchantIsNotFound() {
        assertEquals(404, assertThrows(BusinessException.class, () -> guard.requireOpen((Long) null)).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> guard.requireOpen((MerchantStore) null)).getCode());
        when(storeMapper.selectById(8L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> guard.requireOpen(8L)).getCode());
    }

    @Test
    void qualificationValidPassesWhenExpiringInFutureOrNotCollected() {
        // O9：未采集（空）与未来有效期都放行上新。
        MerchantStore future = store(1);
        future.setIdCardExpiry(java.time.LocalDate.now().plusDays(3));
        future.setLicenseExpiry(java.time.LocalDate.now().plusYears(5));
        assertDoesNotThrow(() -> guard.requireQualificationValid(future));
        assertDoesNotThrow(() -> guard.requireQualificationValid(store(1)));
    }

    @Test
    void qualificationExpiredIncludingTodayIsRejected() {
        // O9：到期当天即拦截；文案指明资质类型与更新入口。
        MerchantStore expiredToday = store(1);
        expiredToday.setIdCardExpiry(java.time.LocalDate.now());
        BusinessException today = assertThrows(BusinessException.class,
                () -> guard.requireQualificationValid(expiredToday));
        assertEquals(409, today.getCode());
        assertTrue(today.getMessage().contains("身份证"));
        assertTrue(today.getMessage().contains("更新资质有效期"));

        MerchantStore expiredPast = store(1);
        expiredPast.setLicenseExpiry(java.time.LocalDate.now().minusDays(10));
        BusinessException past = assertThrows(BusinessException.class,
                () -> guard.requireQualificationValid(expiredPast));
        assertEquals(409, past.getCode());
        assertTrue(past.getMessage().contains("营业执照"));

        MerchantStore both = store(1);
        both.setIdCardExpiry(java.time.LocalDate.now().minusDays(1));
        both.setLicenseExpiry(java.time.LocalDate.now());
        assertTrue(assertThrows(BusinessException.class,
                () -> guard.requireQualificationValid(both)).getMessage().contains("身份证与营业执照"));
    }

    @Test
    void qualificationGuardLoadsStoreById() {
        MerchantStore expired = store(1);
        expired.setIdCardExpiry(java.time.LocalDate.now().minusDays(1));
        when(storeMapper.selectById(7L)).thenReturn(expired);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> guard.requireQualificationValid(7L)).getCode());
        when(storeMapper.selectById(8L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> guard.requireQualificationValid(8L)).getCode());
    }
}
