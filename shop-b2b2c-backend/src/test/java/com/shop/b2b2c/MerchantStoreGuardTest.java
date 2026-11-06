/*
 * 文件：MerchantStoreGuardTest.java
 * 用途：O4 商家写接口营业状态闸门的契约用例：营业(1)放行、待开通(0) 409 且文案含开通引导、
 *      打烊/封禁/注销(2/3/4) 409、店铺不存在 404。
 * 维护注意：
 * 1. 使用 Mockito 隔离，不启动 Spring、不连接数据库；按 ID 加载走 selectById，与线上 MyBatis-Plus 语义一致。
 * 2. 待开通文案必须引导"完成开店协议签署"，不得退化为笼统错误；改文案口径时同步本用例。
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
}
