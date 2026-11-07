/*
 * 文件：ProductEditingServiceTest.java
 * 用途：商品维护参数边界、修订冲突、租户查询、SKU价格白名单、下架预占拦截及SKU增删的纯单元测试。
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
    Update edit(String revision, List<Price> prices) { return new Update(revision, 1L, "new", null, "new-image", null, null, prices); }
    String revision() { return editing.get(1L, 1L).revision(); }

    @Test void revisionTracksEditableFieldsButNotLiveInventoryOrFormatting() {
        String original = revision(); sku.setStockQuantity(0); product.setUpdatedAt(java.time.LocalDateTime.now());
        sku.setPrice(new BigDecimal("10.00")); assertEquals(original, revision());
        sku.setPrice(new BigDecimal("11.00")); assertNotEquals(original, revision());
    }
    @Test void validUpdateUsesOnlyOwnedSkuPricesAndDerivedMinimum() {
        var update = edit(revision(), List.of(new Price(1L, new BigDecimal("2.50"))));
        when(mapper.updatePrice(1L, 1L, 1L, new BigDecimal("2.50"))).thenReturn(1);
        when(mapper.updateMetadata(1L, 1L, update, null, new BigDecimal("2.50"))).thenReturn(1);
        assertNotNull(editing.update(1L, 1L, update));
        verify(mapper).updateMetadata(1L, 1L, update, null, new BigDecimal("2.50"));
        assertEquals(7, sku.getStockQuantity());
    }
    @Test void unchangedFormSucceedsWithoutNoOpSqlWrites() {
        String original = revision();
        var same = new Update(original, 1L, "old", null, "image", null, null, List.of(new Price(1L, new BigDecimal("10.00"))));
        assertEquals(original, editing.update(1L, 1L, same).revision());
        verify(mapper, never()).updatePrice(any(), any(), any(), any());
        verify(mapper, never()).updateMetadata(any(), any(), any(), any(), any());
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.001", "100000000"})
    void illegalPriceDoesNotReadOrWriteDatabase(String price) {
        var error = assertThrows(BusinessException.class, () -> editing.update(1L, 1L, edit("a".repeat(64), List.of(new Price(1L, new BigDecimal(price))))));
        assertEquals(400, error.getCode()); verifyNoInteractions(mapper, categories, stores);
    }
    @Test void staleSnapshotAndForeignSkuNeverWrite() {
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.update(1L, 1L, edit("a".repeat(64), List.of(new Price(1L, BigDecimal.ONE))))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.update(1L, 1L, edit(revision(), List.of(new Price(2L, BigDecimal.ONE))))).getCode());
        verify(mapper, never()).updatePrice(any(), any(), any(), any()); verify(mapper, never()).updateMetadata(any(), any(), any(), any(), any());
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
        assertThrows(BusinessException.class, () -> editing.update(1L, 1L, new Update("a".repeat(64), 1L, " ", null, "image", null, null, List.of(new Price(1L, BigDecimal.ONE)))));
        assertThrows(BusinessException.class, () -> editing.update(1L, 1L, edit("a".repeat(64), List.of(new Price(1L, BigDecimal.ONE), new Price(1L, BigDecimal.TEN)))));
        verifyNoInteractions(mapper);
    }

    SkuDraft draft(String revision, String code, String spec, String price, Integer stock) {
        return new SkuDraft(revision, code, spec, price == null ? null : new BigDecimal(price), stock);
    }
    @Test void addSkuPersistsAndLowersDerivedMinimumOnlyWhenCheaper() {
        when(mapper.insertSku(eq(1L), eq(1L), any(ProductSku.class))).thenAnswer(call -> { call.getArgument(2, ProductSku.class).setId(2L); return 1; });
        when(mapper.updateMinPrice(1L, 1L, new BigDecimal("5.00"))).thenReturn(1);
        assertNotNull(editing.addSku(1L, 1L, draft(revision(), "sku-2", "{\"规格\":\"季卡\"}", "5.00", 0)));
        var inserted = org.mockito.ArgumentCaptor.forClass(ProductSku.class);
        verify(mapper).insertSku(eq(1L), eq(1L), inserted.capture());
        assertEquals("sku-2", inserted.getValue().getSkuCode()); assertEquals(0, inserted.getValue().getStockQuantity());
        verify(mapper).updateMinPrice(1L, 1L, new BigDecimal("5.00"));

        when(mapper.insertSku(eq(1L), eq(1L), any(ProductSku.class))).thenAnswer(call -> { call.getArgument(2, ProductSku.class).setId(3L); return 1; });
        assertNotNull(editing.addSku(1L, 1L, draft(revision(), "sku-3", "{\"规格\":\"年卡\"}", "99.00", 0)));
        verify(mapper, times(1)).updateMinPrice(any(), any(), any()); // 更高价不重复写最低价
    }
    @Test void addSkuRejectsBlankCodeBadSpecBadPriceAndStaleRevisionBeforeWriting() {
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, draft("a".repeat(64), " ", "{}", "1.00", 0))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, draft("a".repeat(64), "c".repeat(65), "{}", "1.00", 0))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, draft("a".repeat(64), "x", "[1]", "1.00", 0))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, draft("a".repeat(64), "x", "not-json", "1.00", 0))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, draft("a".repeat(64), "x", "{}", "1.001", 0))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, draft("bad-revision", "x", "{}", "1.00", 0))).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, draft("a".repeat(64), "x", "{}", "1.00", 0))).getCode());
        verify(mapper, never()).insertSku(any(), any(), any()); verify(mapper, never()).updateMinPrice(any(), any(), any());
    }
    @Test void addSkuRejectsVirtualInitialStockDuplicateCodeAndSkuCap() {
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, draft(revision(), "sku-2", "{}", "5.00", 1))).getCode());
        when(mapper.countSkuCode(1L, "sku-2")).thenReturn(1L);
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, draft(revision(), "sku-2", "{}", "5.00", 0))).getCode());
        var hundred = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 100).mapToObj(i -> {
            var s = new ProductSku(); s.setId((long) (i + 1)); s.setSkuCode("c" + i); s.setPrice(BigDecimal.TEN); s.setStockQuantity(0); return s;
        }).toList());
        when(mapper.lockSkus(1L, 1L)).thenReturn(hundred);
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L,
                new SkuDraft(editing.get(1L, 1L).revision(), "sku-new", "{}", new BigDecimal("5.00"), 0))).getCode());
        verify(mapper, never()).insertSku(any(), any(), any()); verify(mapper, never()).updateMinPrice(any(), any(), any());
    }
    @Test void removeSkuDeletesOnlyUnreferencedZeroStockSkuAndRaisesMinimum() {
        var extra = new ProductSku(); extra.setId(2L); extra.setSkuCode("cheap"); extra.setPrice(new BigDecimal("1.00")); extra.setStockQuantity(0);
        sku.setStockQuantity(0);
        product.setMinPrice(new BigDecimal("1.00")); // 与cheap(1.00)为最低价时的一致快照
        when(mapper.lockSkus(1L, 1L)).thenReturn(List.of(sku, extra));
        when(mapper.deleteSku(1L, 1L, 2L)).thenReturn(1);
        when(mapper.updateMinPrice(1L, 1L, BigDecimal.TEN)).thenReturn(1);
        assertNotNull(editing.removeSku(1L, 1L, 2L, new SkuRemoval(editing.get(1L, 1L).revision())));
        verify(mapper).deleteSku(1L, 1L, 2L);
        verify(mapper).updateMinPrice(1L, 1L, BigDecimal.TEN); // 删除最低价后按剩余SKU重算
    }
    @Test void removeSkuKeepsMinimumWhenDeletingNonLowest() {
        var extra = new ProductSku(); extra.setId(2L); extra.setSkuCode("premium"); extra.setPrice(new BigDecimal("99.00")); extra.setStockQuantity(0);
        sku.setStockQuantity(0);
        when(mapper.lockSkus(1L, 1L)).thenReturn(List.of(sku, extra));
        when(mapper.deleteSku(1L, 1L, 2L)).thenReturn(1);
        assertNotNull(editing.removeSku(1L, 1L, 2L, new SkuRemoval(editing.get(1L, 1L).revision())));
        verify(mapper, never()).updateMinPrice(any(), any(), any());
    }
    @Test void removeSkuRejectsLastSkuStockedSkuAndReferencedSkuWithoutDeleting() {
        // 最后一个SKU
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.removeSku(1L, 1L, 1L, new SkuRemoval(revision()))).getCode());
        var extra = new ProductSku(); extra.setId(2L); extra.setSkuCode("other"); extra.setPrice(BigDecimal.TEN); extra.setStockQuantity(0);
        when(mapper.lockSkus(1L, 1L)).thenReturn(List.of(sku, extra));
        // 有库存（fixtures中sku库存为7）
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.removeSku(1L, 1L, 1L, new SkuRemoval(editing.get(1L, 1L).revision()))).getCode());
        // 有历史记录
        when(mapper.countSkuReferences(2L)).thenReturn(3L);
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.removeSku(1L, 1L, 2L, new SkuRemoval(editing.get(1L, 1L).revision()))).getCode());
        // 不属于本商品
        assertEquals(404, assertThrows(BusinessException.class, () -> editing.removeSku(1L, 1L, 9L, new SkuRemoval(editing.get(1L, 1L).revision()))).getCode());
        verify(mapper, never()).deleteSku(any(), any(), any()); verify(mapper, never()).updateMinPrice(any(), any(), any());
    }
    @Test void skuMutationRejectsBadIdentityAndStaleRevisionBeforeDatabase() {
        assertEquals(403, assertThrows(BusinessException.class, () -> editing.addSku(null, 1L, draft("a".repeat(64), "x", "{}", "1.00", 0))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.removeSku(1L, 1L, null, new SkuRemoval("a".repeat(64)))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.removeSku(1L, 1L, 1L, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.removeSku(1L, 1L, 1L, new SkuRemoval("bad"))).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.removeSku(1L, 1L, 1L, new SkuRemoval("a".repeat(64)))).getCode());
        verify(mapper, never()).insertSku(any(), any(), any()); verify(mapper, never()).deleteSku(any(), any(), any());
    }
    @Test void updateSpecPersistsOnlyChangedSpecWithoutTouchingPriceOrMinimum() {
        when(mapper.updateSpec(1L, 1L, 1L, "{\"规格\":\"季卡\"}")).thenReturn(1);
        assertNotNull(editing.updateSpec(1L, 1L, 1L, new SpecUpdate(revision(), "{\"规格\":\"季卡\"}")));
        verify(mapper).updateSpec(1L, 1L, 1L, "{\"规格\":\"季卡\"}");
        verify(mapper, never()).updatePrice(any(), any(), any(), any()); verify(mapper, never()).updateMinPrice(any(), any(), any());
    }
    @Test void updateSpecSkipsWriteWhenUnchanged() {
        sku.setSpecData("{}");
        assertNotNull(editing.updateSpec(1L, 1L, 1L, new SpecUpdate(editing.get(1L, 1L).revision(), "{}")));
        verify(mapper, never()).updateSpec(any(), any(), any(), any());
    }
    @Test void updateSpecRejectsBadInputStaleRevisionAndForeignSku() {
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.updateSpec(1L, 1L, 1L, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.updateSpec(1L, 1L, 1L, new SpecUpdate("bad", "{}"))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.updateSpec(1L, 1L, null, new SpecUpdate(revision(), "{}"))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.updateSpec(1L, 1L, 1L, new SpecUpdate(revision(), null))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.updateSpec(1L, 1L, 1L, new SpecUpdate(revision(), "[1]"))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.updateSpec(1L, 1L, 1L, new SpecUpdate(revision(), "x".repeat(256)))).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.updateSpec(1L, 1L, 1L, new SpecUpdate("a".repeat(64), "{}"))).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> editing.updateSpec(1L, 1L, 9L, new SpecUpdate(revision(), "{}"))).getCode());
        verify(mapper, never()).updateSpec(any(), any(), any(), any());
    }
    @Test void specParticipatesInRevisionFingerprint() {
        sku.setSpecData("{}");
        String before = editing.get(1L, 1L).revision();
        when(mapper.updateSpec(1L, 1L, 1L, "{\"规格\":\"年卡\"}"))
                .thenAnswer(call -> { sku.setSpecData("{\"规格\":\"年卡\"}"); return 1; }); // 模拟真实写后读到的最新快照
        var after = editing.updateSpec(1L, 1L, 1L, new SpecUpdate(before, "{\"规格\":\"年卡\"}"));
        assertNotEquals(before, after.revision());
    }

    @Test void detailImagesPersistAsCanonicalJsonAndEnterRevision() {
        String before = revision();
        var items = List.of(new DetailItem("image", " https://cdn.example.com/a.jpg ", null),
                new DetailItem("text", null, "  产地直发，支持验货  "));
        var update = new Update(before, 1L, "old", null, "image", null, items, List.of(new Price(1L, new BigDecimal("10.00"))));
        String canonical = "[{\"type\":\"image\",\"url\":\"https://cdn.example.com/a.jpg\"}," +
                "{\"type\":\"text\",\"content\":\"产地直发，支持验货\"}]";
        when(mapper.updateMetadata(1L, 1L, update, canonical, new BigDecimal("10.00")))
                .thenAnswer(call -> { product.setDetailImages(canonical); return 1; });
        var after = editing.update(1L, 1L, update);
        verify(mapper).updateMetadata(1L, 1L, update, canonical, new BigDecimal("10.00"));
        assertNotEquals(before, after.revision()); // 图文纳入revision指纹
    }

    @Test void detailImagesUnchangedSkipsWriteAndNullClears() {
        String canonical = "[{\"type\":\"image\",\"url\":\"/a.jpg\"}]";
        product.setDetailImages(canonical);
        String before = revision();
        // 相同内容（即使字段顺序不同、含空白）不落库
        var same = new Update(before, 1L, "old", null, "image", null,
                List.of(new DetailItem("image", " /a.jpg ", null)), List.of(new Price(1L, new BigDecimal("10.00"))));
        assertEquals(before, editing.update(1L, 1L, same).revision());
        // null 清空
        var clearing = new Update(revision(), 1L, "old", null, "image", null, null, List.of(new Price(1L, new BigDecimal("10.00"))));
        when(mapper.updateMetadata(1L, 1L, clearing, null, new BigDecimal("10.00")))
                .thenAnswer(call -> { product.setDetailImages(null); return 1; });
        assertNotNull(editing.update(1L, 1L, clearing));
        verify(mapper).updateMetadata(1L, 1L, clearing, null, new BigDecimal("10.00"));
        verify(mapper, times(1)).updateMetadata(any(), any(), any(), any(), any());
    }

    @Test void detailImagesRejectInvalidEntriesBeforeDatabase() {
        String rev = revision();
        List<Price> prices = List.of(new Price(1L, new BigDecimal("10.00")));
        List<List<DetailItem>> bad = List.of(
                List.of(new DetailItem(null, "/a.jpg", null)),
                List.of(new DetailItem("video", "/a.mp4", null)),
                List.of(new DetailItem("image", null, null)),
                List.of(new DetailItem("image", "   ", null)),
                List.of(new DetailItem("image", "ftp://a.jpg", null)),
                List.of(new DetailItem("image", "/" + "x".repeat(255), null)),
                List.of(new DetailItem("text", null, null)),
                List.of(new DetailItem("text", null, "  ")),
                List.of(new DetailItem("text", null, "y".repeat(2001))),
                java.util.Arrays.asList((DetailItem) null),
                java.util.Collections.nCopies(21, new DetailItem("text", null, "段落")));
        for (var items : bad) {
            assertEquals(400, assertThrows(BusinessException.class, () -> editing.update(1L, 1L,
                    new Update(rev, 1L, "old", null, "image", null, items, prices))).getCode(), String.valueOf(items));
        }
        verify(mapper, never()).updateMetadata(any(), any(), any(), any(), any());
        // 边界：恰好20条通过
        var ok = new Update(revision(), 1L, "old", null, "image", null,
                java.util.Collections.nCopies(20, new DetailItem("text", null, "段落")), prices);
        when(mapper.updateMetadata(eq(1L), eq(1L), eq(ok), anyString(), eq(new BigDecimal("10.00")))).thenReturn(1);
        assertNotNull(editing.update(1L, 1L, ok));
    }
}
