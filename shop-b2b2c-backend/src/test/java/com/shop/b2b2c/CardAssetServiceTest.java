/*
 * 文件：CardAssetServiceTest.java
 * 用途：卡密初始库存、去重/批次写入、导入容量、作废拒绝及自然幂等单元测试。
 * 维护注意：仅使用虚构卡密与Mock事务，不能替代真实回滚/并发验证；错误断言不能输出卡密明文，数据库场景由Spring/H2和临时MySQL测试承担。
 */
package com.shop.b2b2c;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.controller.ProductController;
import com.shop.b2b2c.entity.*;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.CardAssetService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CardAssetServiceTest {
    @ParameterizedTest @NullSource @ValueSource(ints = {-1, 1, 10})
    void cardInitialStockCannotBeDeclaredWithoutCards(Integer quantity) {
        assertEquals(400, assertThrows(BusinessException.class, () -> com.shop.b2b2c.common.TradeGuards.requireInitialStock(2, quantity)).getCode());
    }
    @Test void physicalAndServiceInitialStockRemainValid() {
        assertDoesNotThrow(() -> com.shop.b2b2c.common.TradeGuards.requireInitialStock(2, 0));
        for (int type : List.of(1, 3)) assertDoesNotThrow(() -> com.shop.b2b2c.common.TradeGuards.requireInitialStock(type, 10));
    }
    final CardAssetMapper assets = mock(CardAssetMapper.class);
    final ProductEditingMapper products = mock(ProductEditingMapper.class);
    final InventoryAdjustmentMapper inventory = mock(InventoryAdjustmentMapper.class);
    final ProductVirtualCardMapper cards = mock(ProductVirtualCardMapper.class);
    final ProductSkuMapper skus = mock(ProductSkuMapper.class);
    final CardImportBatchMapper batches = mock(CardImportBatchMapper.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final CardAssetService service = new CardAssetService(assets, products, inventory, cards, skus, batches, manager);
    final Product product = new Product(); final ProductSku sku = new ProductSku(); final ProductVirtualCard card = new ProductVirtualCard();
    CardVoidRecord record;
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(assets.lockMerchant(1L)).thenReturn(1L);
        product.setId(1L); product.setMerchantId(1L); product.setGoodsType(2); when(products.lockProduct(1L, 1L)).thenReturn(product);
        sku.setId(1L); sku.setMerchantId(1L); sku.setProductId(1L); sku.setStockQuantity(1); when(inventory.lockSku(1L, 1L, 1L)).thenReturn(sku);
        when(assets.findUnfingerprinted(1L)).thenReturn(List.of());
        var nextCardId = new java.util.concurrent.atomic.AtomicLong(10);
        when(cards.insert(any(ProductVirtualCard.class))).thenAnswer(call -> { ((ProductVirtualCard) call.getArgument(0)).setId(nextCardId.incrementAndGet()); return 1; });
        when(batches.insert(any())).thenAnswer(call -> { ((CardImportBatch) call.getArgument(0)).setId(7L); return 1; });
        when(batches.link(any(), any())).thenReturn(1); when(skus.addStock(any(), any(), anyInt())).thenReturn(1);
        card.setId(1L); card.setProductId(1L); card.setSkuId(1L); card.setMerchantId(1L); card.setCardContent("fixture-private");
        when(assets.findCard(1L, 1L)).thenReturn(card); when(assets.lockCard(1L, 1L)).thenReturn(card);
        when(assets.availableCount(1L, 1L, 1L)).thenReturn(1L);
        when(assets.markVoid(1L, 1L)).thenAnswer(call -> { card.setStatus(2); return 1; });
        when(skus.deductStock(1L, 1L, 1L, 1)).thenReturn(1);
        when(assets.insertVoid(any())).thenAnswer(call -> { record = call.getArgument(0); return 1; });
        when(assets.voidRecord(1L, 1L)).thenAnswer(call -> record);
    }
    @Test void validImportKeepsCaseAndAddsOnlyInsertedQuantity() {
        var result = service.importCards(1L, 9L, 1L, 1L, List.of(" item ", "ITEM"));
        assertEquals(2, result.get("importedCount")); assertEquals(3, result.get("newStock"));
        assertEquals(7L, result.get("batchId"));
        var batch = org.mockito.ArgumentCaptor.forClass(CardImportBatch.class);
        verify(batches).insert(batch.capture());
        assertEquals(9L, batch.getValue().getActorId()); assertEquals(1, batch.getValue().getBeforeStock());
        assertEquals(3, batch.getValue().getAfterStock()); assertEquals(2, batch.getValue().getImportedCount());
        verify(batches).link(7L, 11L); verify(batches).link(7L, 12L);
        var captured = org.mockito.ArgumentCaptor.forClass(ProductVirtualCard.class);
        verify(cards, times(2)).insert(captured.capture());
        var cipher = new com.shop.b2b2c.service.CardCipherService();
        assertEquals(List.of("item", "ITEM"), captured.getAllValues().stream().map(c -> cipher.decrypt(c.getCardContent())).toList());
        assertTrue(captured.getAllValues().stream().allMatch(c -> c.getCardContent().startsWith("ENC:v1:")));
        assertTrue(captured.getAllValues().stream().allMatch(c -> c.getCardFingerprint() != null && c.getCardFingerprint().length() == 64));
        verify(skus).addStock(1L, 1L, 2);
        verify(manager).getTransaction(argThat(def -> def.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED));
    }
    @ParameterizedTest @ValueSource(strings = {"empty", "blank", "trimEmpty", "nullItem", "tooMany", "tooLong", "duplicate"})
    void malformedBatchNeverStartsTransaction(String scenario) {
        List<String> input = switch (scenario) {
            case "empty" -> List.of(); case "blank" -> List.of(" "); case "trimEmpty" -> List.of(String.valueOf((char) 0));
            case "nullItem" -> Arrays.asList((String) null);
            case "tooMany" -> Collections.nCopies(1001, "item"); case "tooLong" -> List.of("x".repeat(4097));
            default -> List.of("item", " item ");
        };
        assertEquals(400, assertThrows(BusinessException.class, () -> service.importCards(1L, 9L, 1L, 1L, input)).getCode());
        verifyNoInteractions(assets, products, inventory, cards, skus, batches, manager);
    }
    @Test void historyDuplicateIsAtomicAndErrorNeverContainsCardContent() {
        var legacy = new ProductVirtualCard(); legacy.setId(3L); legacy.setCardContent("fixture-private");
        when(assets.findUnfingerprinted(1L)).thenReturn(List.of(legacy));
        var error = assertThrows(BusinessException.class, () -> service.importCards(1L, 9L, 1L, 1L, List.of("new-item", " fixture-private ")));
        assertEquals(409, error.getCode()); assertFalse(error.getMessage().contains("fixture-private"));
        verify(cards, never()).insert(any(ProductVirtualCard.class)); verify(skus, never()).addStock(any(), any(), anyInt());
        verifyNoInteractions(batches);
    }
    @Test void encryptedHistoryWithoutFingerprintIsDecryptedBeforeDedupe() {
        var cipher = new com.shop.b2b2c.service.CardCipherService();
        var legacy = new ProductVirtualCard(); legacy.setId(3L); legacy.setCardContent(cipher.encrypt("legacy-private"));
        when(assets.findUnfingerprinted(1L)).thenReturn(List.of(legacy));
        var error = assertThrows(BusinessException.class, () -> service.importCards(1L, 9L, 1L, 1L, List.of(" legacy-private ")));
        assertEquals(409, error.getCode()); assertFalse(error.getMessage().contains("legacy-private"));
        verify(cards, never()).insert(any(ProductVirtualCard.class));
    }
    @Test void unreadableHistoricalCipherIsSkippedAndCountedWithoutBlockingImport() {
        var legacy = new ProductVirtualCard(); legacy.setId(3L); legacy.setCardContent("ENC:v1:not-base64!");
        when(assets.findUnfingerprinted(1L)).thenReturn(List.of(legacy));
        var result = service.importCards(1L, 9L, 1L, 1L, List.of("fresh-private"));
        assertEquals(1, result.get("importedCount"));
        verify(assets).markFingerprintState(1L, 3L, 1);
        verify(assets, never()).fillFingerprint(any(), any(), any());
    }
    @Test void readableHistoricalCipherGetsFingerprintBackfilled() {
        var cipher = new com.shop.b2b2c.service.CardCipherService();
        var legacy = new ProductVirtualCard(); legacy.setId(3L); legacy.setCardContent(cipher.encrypt("legacy-other"));
        when(assets.findUnfingerprinted(1L)).thenReturn(List.of(legacy));
        var result = service.importCards(1L, 9L, 1L, 1L, List.of("fresh-private"));
        assertEquals(1, result.get("importedCount"));
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(assets).fillFingerprint(eq(1L), eq(3L), captured.capture());
        assertEquals(cipher.fingerprint("legacy-other"), captured.getValue());
    }
    @Test void duplicateHistoricalPlaintextAvoidsSecondFingerprintWrite() {
        var cipher = new com.shop.b2b2c.service.CardCipherService();
        String duplicate = cipher.encrypt("duplicate-history");
        var first = new ProductVirtualCard(); first.setId(3L); first.setCardContent(duplicate);
        var second = new ProductVirtualCard(); second.setId(4L); second.setCardContent(duplicate);
        when(assets.findUnfingerprinted(1L)).thenReturn(List.of(first, second));
        Set<String> stored = new HashSet<>();
        when(assets.findExistingFingerprint(eq(1L), anyCollection())).thenAnswer(call -> {
            Collection<String> probes = call.getArgument(1);
            return probes.stream().filter(stored::contains).findFirst().orElse(null);
        });
        when(assets.fillFingerprint(eq(1L), eq(3L), anyString())).thenAnswer(call -> {
            stored.add(call.getArgument(2)); return 1;
        });
        assertEquals(1, service.importCards(1L, 9L, 1L, 1L, List.of("fresh-private")).get("importedCount"));
        verify(assets, times(1)).fillFingerprint(eq(1L), any(), anyString());
        verify(assets).markFingerprintState(1L, 4L, 0);
    }
    @Test void importWithoutActorNeverCreatesUnattributedBatch() {
        assertEquals(401, assertThrows(BusinessException.class, () -> service.importCards(1L, null, 1L, 1L, List.of("new"))).getCode());
        verifyNoInteractions(manager, batches, cards);
    }
    @Test void missingGeneratedBatchIdCannotCreateCards() {
        doReturn(1).when(batches).insert(any());
        assertThrows(IllegalStateException.class, () -> service.importCards(1L, 9L, 1L, 1L, List.of("new")));
        verify(cards, never()).insert(any(ProductVirtualCard.class)); verify(manager).rollback(any());
    }
    @Test void missingGeneratedCardIdCannotCreateUntraceableMember() {
        doReturn(1).when(cards).insert(any(ProductVirtualCard.class));
        assertThrows(IllegalStateException.class, () -> service.importCards(1L, 9L, 1L, 1L, List.of("new")));
        verify(batches, never()).link(any(), any()); verify(skus, never()).addStock(any(), any(), anyInt()); verify(manager).rollback(any());
    }
    @Test void importLeavesCapacityForHeldRelease() {
        sku.setStockQuantity(Integer.MAX_VALUE - 1); when(inventory.heldQuantity(1L, 1L)).thenReturn(1L);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.importCards(1L, 9L, 1L, 1L, List.of("new-item"))).getCode());
        verify(cards, never()).insert(any(ProductVirtualCard.class));
    }
    @Test void wrongTypeOrForeignSkuNeverImports() {
        product.setGoodsType(1);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.importCards(1L, 9L, 1L, 1L, List.of("new"))).getCode());
        product.setGoodsType(2);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.importCards(1L, 9L, 1L, 2L, List.of("new"))).getCode());
        verify(cards, never()).insert(any(ProductVirtualCard.class));
    }
    @Test void voidIsNaturallyIdempotentAndDoesNotExposeContent() {
        var first = service.voidCard(1L, 9L, 1L, " expired ");
        assertEquals(1, first.getBeforeStock()); assertEquals(0, first.getAfterStock()); assertEquals(9L, first.getActorId());
        assertSame(first, service.voidCard(1L, 9L, 1L, "expired"));
        assertFalse(new ObjectMapper().valueToTree(first).has("cardContent"));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.voidCard(1L, 9L, 1L, "changed")).getCode());
        verify(skus, times(1)).deductStock(1L, 1L, 1L, 1); verify(assets, times(1)).insertVoid(any());
    }
    @Test void legacyVoidWithoutRecordCannotDeductAgainOrInventAnAudit() {
        card.setStatus(2);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.voidCard(1L, 9L, 1L, "expired")).getCode());
        verify(assets, never()).markVoid(any(), any()); verify(assets, never()).insertVoid(any());
        verify(skus, never()).deductStock(any(), any(), any(), anyInt());
    }
    @Test void invalidVoidIdentityIdOrReasonNeverStartsTransaction() {
        assertEquals(401, assertThrows(BusinessException.class, () -> service.voidCard(1L, 0L, 1L, "reason")).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.voidCard(1L, 9L, 0L, "reason")).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.voidCard(1L, 9L, 1L, "x".repeat(256))).getCode());
        verifyNoInteractions(manager, assets, products, inventory, cards, skus);
    }
    @ParameterizedTest @ValueSource(strings = {"sold", "bound", "held", "mismatch", "zero", "unknown"})
    void unsafeVoidCannotChangeCardOrStock(String scenario) {
        switch (scenario) {
            case "sold" -> card.setStatus(1); case "bound" -> card.setOrderSn("assigned");
            case "held" -> when(inventory.heldQuantity(1L, 1L)).thenReturn(1L);
            case "mismatch" -> when(assets.availableCount(1L, 1L, 1L)).thenReturn(2L);
            case "zero" -> sku.setStockQuantity(0); case "unknown" -> card.setStatus(9);
        }
        assertEquals(409, assertThrows(BusinessException.class, () -> service.voidCard(1L, 9L, 1L, "reason")).getCode());
        verify(assets, never()).markVoid(any(), any()); verify(skus, never()).deductStock(any(), any(), any(), anyInt());
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", " "})
    void missingReasonIsRejectedBeforeTransaction(String reason) {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.voidCard(1L, 9L, 1L, reason)).getCode()); verifyNoInteractions(manager);
    }
    @ParameterizedTest @ValueSource(strings = {"1.5", "true", "\"1.5\"", "9223372036854775808"})
    void importIdsNeverTruncateInvalidJson(String value) {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> new ObjectMapper().readValue("{\"productId\":" + value + ",\"skuId\":1}", ProductController.BatchImportCardDTO.class));
    }
    @Test void oldIntegerStringIdsRemainCompatible() throws Exception {
        var dto = new ObjectMapper().readValue("{\"productId\":\"9\",\"skuId\":\"12\"}", ProductController.BatchImportCardDTO.class);
        assertEquals(9L, dto.getProductId()); assertEquals(12L, dto.getSkuId());
    }
}
