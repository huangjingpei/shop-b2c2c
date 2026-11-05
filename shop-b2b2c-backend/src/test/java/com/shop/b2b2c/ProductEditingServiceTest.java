/*
 * 文件：ProductEditingServiceTest.java
 * 用途：商品维护参数边界、修订冲突、租户查询、SKU价格白名单及下架预占拦截的纯单元测试。
 * 维护注意：Mock持久化仅检查调用契约；事务回滚及并发必须由Spring/H2与独立MySQL验证，不能把单元测试当成数据库锁验收。
 */
package com.shop.b2b2c;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.ProductEditingDTOs.*;
import com.shop.b2b2c.entity.*;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.ProductEditingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductEditingServiceTest {
    final ProductEditingMapper mapper = mock(ProductEditingMapper.class);
    final CategoryMapper categories = mock(CategoryMapper.class);
    final MerchantStoreMapper stores = mock(MerchantStoreMapper.class);
    final InventoryReservationMapper reservations = mock(InventoryReservationMapper.class);
    final ProductEditingService editing = new ProductEditingService(mapper, categories, stores, reservations, new ObjectMapper());
    Product product; ProductSku sku;

    @BeforeEach void setup() {
        product = new Product(); product.setId(1L); product.setMerchantId(1L); product.setCategoryId(1L);
        product.setTitle("old"); product.setMainImage("image"); product.setGoodsType(2); product.setMinPrice(BigDecimal.TEN);
        sku = new ProductSku(); sku.setId(1L); sku.setSkuCode("sku"); sku.setPrice(BigDecimal.TEN); sku.setStockQuantity(7);
        when(mapper.lockProduct(1L, 1L)).thenReturn(product); when(mapper.lockSkus(1L, 1L)).thenReturn(List.of(sku));
        when(categories.findById(1L)).thenReturn(Optional.of(new Category()));
        var store = new MerchantStore(); store.setStatus(1); when(stores.findById(1L)).thenReturn(Optional.of(store));
    }
    Update edit(String revision, List<Price> prices) { return new Update(revision, 1L, "new", null, "new-image", null, prices); }
    String revision() { return editing.get(1L, 1L).revision(); }

    @Test void revisionTracksEditableFieldsButNotLiveInventoryOrFormatting() {
        String original = revision(); sku.setStockQuantity(0); product.setUpdatedAt(java.time.LocalDateTime.now());
        sku.setPrice(new BigDecimal("10.00")); assertEquals(original, revision());
        sku.setPrice(new BigDecimal("11.00")); assertNotEquals(original, revision());
    }
    @Test void validUpdateUsesOnlyOwnedSkuPricesAndDerivedMinimum() {
        var update = edit(revision(), List.of(new Price(1L, new BigDecimal("2.50"))));
        when(mapper.updatePrice(1L, 1L, 1L, new BigDecimal("2.50"))).thenReturn(1);
        when(mapper.updateMetadata(1L, 1L, update, new BigDecimal("2.50"))).thenReturn(1);
        assertNotNull(editing.update(1L, 1L, update));
        verify(mapper).updateMetadata(1L, 1L, update, new BigDecimal("2.50"));
        assertEquals(7, sku.getStockQuantity());
    }
    @Test void unchangedFormSucceedsWithoutNoOpSqlWrites() {
        String original = revision();
        var same = new Update(original, 1L, "old", null, "image", null, List.of(new Price(1L, new BigDecimal("10.00"))));
        assertEquals(original, editing.update(1L, 1L, same).revision());
        verify(mapper, never()).updatePrice(any(), any(), any(), any());
        verify(mapper, never()).updateMetadata(any(), any(), any(), any());
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.001", "100000000"})
    void illegalPriceDoesNotReadOrWriteDatabase(String price) {
        var error = assertThrows(BusinessException.class, () -> editing.update(1L, 1L, edit("a".repeat(64), List.of(new Price(1L, new BigDecimal(price))))));
        assertEquals(400, error.getCode()); verifyNoInteractions(mapper, categories, stores);
    }
    @Test void staleSnapshotAndForeignSkuNeverWrite() {
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.update(1L, 1L, edit("a".repeat(64), List.of(new Price(1L, BigDecimal.ONE))))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.update(1L, 1L, edit(revision(), List.of(new Price(2L, BigDecimal.ONE))))).getCode());
        verify(mapper, never()).updatePrice(any(), any(), any(), any()); verify(mapper, never()).updateMetadata(any(), any(), any(), any());
    }
    @Test void foreignMerchantCannotLoadSnapshot() {
        assertEquals(404, assertThrows(BusinessException.class, () -> editing.get(2L, 1L)).getCode());
        verify(mapper, never()).lockSkus(any(), any());
    }
    @Test void missingTenantIsRejectedWithoutDatabaseAccess() {
        assertEquals(403, assertThrows(BusinessException.class, () -> editing.get(null, 1L)).getCode()); verifyNoInteractions(mapper);
    }
    @ParameterizedTest @NullSource @ValueSource(ints = {-1, 2, 3})
    void merchantCannotRequestModerationStates(Integer status) {
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.changeStatus(1L, 1L, new Status("a".repeat(64), status))).getCode());
        verifyNoInteractions(mapper);
    }
    @Test void merchantCannotRestorePlatformBlockedProduct() {
        product.setStatus(2);
        assertEquals(403, assertThrows(BusinessException.class, () -> editing.changeStatus(1L, 1L, new Status(revision(), 1))).getCode());
        verify(mapper, never()).updateStatus(any(), any(), anyInt());
    }
    @Test void closedStoreCannotListButCanTakeProductOffSale() {
        var store = new MerchantStore(); store.setStatus(2); when(stores.findById(1L)).thenReturn(Optional.of(store));
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.changeStatus(1L, 1L, new Status(revision(), 1))).getCode());
        when(mapper.updateStatus(1L, 1L, 0)).thenReturn(1);
        assertNotNull(editing.changeStatus(1L, 1L, new Status(revision(), 0)));
        verify(mapper).updateStatus(1L, 1L, 0);
    }
    @Test void activeHeldReservationBlocksOffShelfButExpiredReservationDoesNot() {
        when(reservations.countActiveHeldByProduct(1L)).thenReturn(1);
        var blocked = assertThrows(BusinessException.class,
                () -> editing.changeStatus(1L, 1L, new Status(revision(), 0)));
        assertEquals(409, blocked.getCode());
        verify(mapper, never()).updateStatus(any(), any(), anyInt());

        when(reservations.countActiveHeldByProduct(1L)).thenReturn(0);
        when(mapper.updateStatus(1L, 1L, 0)).thenReturn(1);
        assertNotNull(editing.changeStatus(1L, 1L, new Status(revision(), 0)));
        verify(mapper).updateStatus(1L, 1L, 0);
    }

    @Test void nullFieldsAndDuplicateSkuAreRejected() {
        assertThrows(BusinessException.class, () -> editing.update(1L, 1L, null));
        assertThrows(BusinessException.class, () -> editing.update(1L, 1L, new Update("a".repeat(64), 1L, " ", null, "image", null, List.of(new Price(1L, BigDecimal.ONE)))));
        assertThrows(BusinessException.class, () -> editing.update(1L, 1L, edit("a".repeat(64), List.of(new Price(1L, BigDecimal.ONE), new Price(1L, BigDecimal.TEN)))));
        verifyNoInteractions(mapper);
    }
}
