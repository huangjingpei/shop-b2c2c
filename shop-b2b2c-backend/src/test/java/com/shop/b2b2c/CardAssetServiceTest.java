/*
 * 文件：CardAssetServiceTest.java
 * 用途：卡密初始库存、去重/批次写入、导入容量、作废拒绝及自然幂等、存量一致性核对、已售卡补发授权单元测试。
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
    final CardAccessAuditMapper audits = mock(CardAccessAuditMapper.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final CardAssetService service = new CardAssetService(assets, products, inventory, cards, skus, batches, manager,
            new com.shop.b2b2c.service.CardCipherService(), audits);
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
        when(skus.findById(1L)).thenReturn(Optional.of(sku));
        when(cards.findMerchantCards(eq(1L), eq(1L), anyBoolean(), isNull(), anyInt())).thenReturn(List.of(card));
        when(audits.insert(any(CardAccessAudit.class))).thenReturn(1);
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
    }
    @Test void readableHistoricalCipherIsComparedInMemoryWithoutBackfill() {
        var cipher = new com.shop.b2b2c.service.CardCipherService();
        var legacy = new ProductVirtualCard(); legacy.setId(3L); legacy.setCardContent(cipher.encrypt("legacy-other"));
        when(assets.findUnfingerprinted(1L)).thenReturn(List.of(legacy));
        var result = service.importCards(1L, 9L, 1L, 1L, List.of("fresh-private"));
        assertEquals(1, result.get("importedCount"));
        verify(assets, never()).markFingerprintState(any(), any(), any());
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
    @Test void revealIsLimitedBySkuAndReturnsPlaintextOnlyWhenAuthorized() {
        var items = service.revealCards(1L, 1L, 1);
        assertEquals(1, items.size());
        assertEquals("fixture-private", items.get(0).getCardContent());
        var masked = com.shop.b2b2c.dto.MerchantCardView.masked(card);
        assertFalse(masked.getCardContent() != null);
        verify(cards).findMerchantCards(1L, 1L, false, null, 1);
    }
    @ParameterizedTest @ValueSource(ints = {0, 51, -1})
    void revealRejectsLimitsAboveCapInsteadOfTruncating(int limit) {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.revealCards(1L, 1L, limit)).getCode());
        verify(cards, never()).findMerchantCards(any(), any(), anyBoolean(), any(), anyInt());
    }
    @Test void exportRequiresExplicitConfirmationAndWritesAudit() {
        var result = service.exportCards(1L, 9L, 1L, true, false, 2);
        assertEquals(1, result.get("count"));
        var audit = org.mockito.ArgumentCaptor.forClass(com.shop.b2b2c.entity.CardAccessAudit.class);
        verify(audits).insert(audit.capture());
        assertEquals(1L, audit.getValue().getMerchantId());
        assertEquals(9L, audit.getValue().getActorId());
        assertEquals("EXPORT_PLAINTEXT", audit.getValue().getAction());
        assertEquals(1L, audit.getValue().getSkuId());
        assertEquals(1, audit.getValue().getCardCount());
    }
    @Test void exportWithoutConfirmationNeverReadsOrAudits() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.exportCards(1L, 9L, 1L, false, false, 2)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.exportCards(1L, 9L, 1L, null, false, 2)).getCode());
        verifyNoInteractions(cards, audits);
    }
    @Test void exportRejectsForeignSkuBeforeReadingPlaintext() {
        var foreign = new com.shop.b2b2c.entity.ProductSku(); foreign.setId(2L); foreign.setMerchantId(2L);
        when(skus.findById(2L)).thenReturn(Optional.of(foreign));
        assertEquals(403, assertThrows(BusinessException.class, () -> service.exportCards(1L, 9L, 2L, true, false, 2)).getCode());
        verifyNoInteractions(cards, audits);
    }
    private com.shop.b2b2c.dto.CardConsistencyRow row(long skuId, long stock, long available, long unavailable) {
        var row = new com.shop.b2b2c.dto.CardConsistencyRow();
        row.setSkuId(skuId); row.setProductId(1L); row.setStockQuantity(stock); row.setAvailableCards(available);
        row.setSoldCards(0L); row.setVoidedCards(0L); row.setHeldQuantity(0L); row.setFingerprintUnavailable(unavailable);
        return row;
    }
    @Test void consistencyCheckFlagsDriftAndUnavailableWithoutAutoRepair() {
        when(assets.consistencyRows(1L)).thenReturn(List.of(row(1L, 2, 2, 0), row(2L, 5, 3, 2)));
        var result = service.consistencyCheck(1L);
        @SuppressWarnings("unchecked") var items = (List<com.shop.b2b2c.dto.CardConsistencyRow>) result.get("items");
        assertTrue(items.get(0).getConsistent());
        assertFalse(items.get(1).getConsistent());
        assertEquals(1, result.get("abnormalCount"));
        assertEquals(2L, result.get("fingerprintUnavailableTotal"));
        verify(assets).consistencyRows(1L);
        // 只读核对：不得触碰任何写入口。
        verify(cards, never()).insert(any(ProductVirtualCard.class));
        verify(skus, never()).addStock(any(), any(), anyInt());
        verify(assets, never()).markVoid(any(), any());
    }
    @Test void consistencyCheckCountsUnavailableEvenWhenStockMatches() {
        when(assets.consistencyRows(1L)).thenReturn(List.of(row(1L, 1, 1, 1)));
        var result = service.consistencyCheck(1L);
        @SuppressWarnings("unchecked") var items = (List<com.shop.b2b2c.dto.CardConsistencyRow>) result.get("items");
        assertTrue(items.get(0).getConsistent());
        assertEquals(1, result.get("abnormalCount"));
        assertEquals(1L, result.get("fingerprintUnavailableTotal"));
    }
    @Test void consistencyCheckAllowsEmptyStoreAndRejectsUnboundAccount() {
        when(assets.consistencyRows(1L)).thenReturn(List.of());
        var result = service.consistencyCheck(1L);
        assertEquals(List.of(), result.get("items"));
        assertEquals(0, result.get("abnormalCount"));
        assertEquals(0L, result.get("fingerprintUnavailableTotal"));
        assertEquals(403, assertThrows(BusinessException.class, () -> service.consistencyCheck(null)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.consistencyCheck(0L)).getCode());
    }

    // ========== F12 补发授权 ==========
    com.shop.b2b2c.entity.CardReissue reissue;
    private ProductVirtualCard reissueCandidate() {
        var candidate = new ProductVirtualCard();
        candidate.setId(2L); candidate.setProductId(1L); candidate.setSkuId(1L); candidate.setMerchantId(1L);
        candidate.setStatus(0); candidate.setCardContent("reissue-new-card-fixture");
        return candidate;
    }
    private void prepareSuccessfulReissue(ProductVirtualCard candidate) {
        card.setStatus(1); card.setOrderSn("ORDER-1");
        when(cards.lockAvailableCards(1L, 1L, 1L, 1)).thenReturn(List.of(candidate));
        when(cards.claimCard(2L, 1L, 1L, "ORDER-1")).thenAnswer(call -> { candidate.setStatus(1); candidate.setOrderSn("ORDER-1"); return 1; });
        when(assets.insertReissue(any())).thenAnswer(call -> { reissue = call.getArgument(0); return 1; });
    }
    @Test void reissueClaimsNewCardDeductsStockAndAuditsPlaintextAccess() {
        var candidate = reissueCandidate(); prepareSuccessfulReissue(candidate);
        var result = service.reissueCard(1L, 9L, 1L, "买家未收到");
        var record = (com.shop.b2b2c.entity.CardReissue) result.get("record");
        assertEquals(1L, record.getSourceCardId()); assertEquals(2L, record.getNewCardId());
        assertEquals("ORDER-1", record.getOrderSn()); assertEquals(9L, record.getActorId());
        assertEquals(1, record.getBeforeStock()); assertEquals(0, record.getAfterStock());
        var view = (com.shop.b2b2c.dto.MerchantCardView) result.get("newCard");
        assertEquals("reissue-new-card-fixture", view.getCardContent());
        assertEquals(1, view.getStatus()); assertEquals("ORDER-1", view.getOrderSn());
        assertSame(reissue, record);
        verify(skus).deductStock(1L, 1L, 1L, 1);
        verify(audits).insert(argThat(a -> "REISSUE_PLAINTEXT".equals(a.getAction()) && a.getCardCount() == 1));
        // 源卡保持已售，交付事实不回滚。
        assertEquals(1, card.getStatus()); assertEquals("ORDER-1", card.getOrderSn());
        assertFalse(new ObjectMapper().valueToTree(record).has("cardContent"));
    }
    @Test void reissueReplayReturnsSameRecordWithoutDoubleDeductButAuditsAgain() {
        var candidate = reissueCandidate(); prepareSuccessfulReissue(candidate);
        when(assets.reissueRecord(1L, 1L)).thenAnswer(call -> reissue);
        when(cards.selectById(2L)).thenReturn(candidate);
        var first = service.reissueCard(1L, 9L, 1L, "买家未收到");
        var replay = service.reissueCard(1L, 9L, 1L, "买家未收到");
        assertSame((com.shop.b2b2c.entity.CardReissue) first.get("record"), (com.shop.b2b2c.entity.CardReissue) replay.get("record"));
        assertEquals("reissue-new-card-fixture", ((com.shop.b2b2c.dto.MerchantCardView) replay.get("newCard")).getCardContent());
        verify(skus, times(1)).deductStock(1L, 1L, 1L, 1);
        verify(assets, times(1)).insertReissue(any());
        verify(audits, times(2)).insert(any(CardAccessAudit.class));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.reissueCard(1L, 9L, 1L, "changed")).getCode());
        verify(audits, times(2)).insert(any(CardAccessAudit.class));
    }
    @ParameterizedTest @ValueSource(strings = {"unsold", "unbound", "held", "zero", "mismatch", "nocandidate", "claimrace"})
    void unsafeReissueCannotRecordOrChangeStock(String scenario) {
        var candidate = reissueCandidate(); prepareSuccessfulReissue(candidate);
        switch (scenario) {
            case "unsold" -> card.setStatus(0);
            case "unbound" -> card.setOrderSn(null);
            case "held" -> when(inventory.heldQuantity(1L, 1L)).thenReturn(1L);
            case "zero" -> sku.setStockQuantity(0);
            case "mismatch" -> when(assets.availableCount(1L, 1L, 1L)).thenReturn(2L);
            case "nocandidate" -> when(cards.lockAvailableCards(1L, 1L, 1L, 1)).thenReturn(List.of());
            case "claimrace" -> when(cards.claimCard(2L, 1L, 1L, "ORDER-1")).thenReturn(0);
        }
        assertEquals(409, assertThrows(BusinessException.class, () -> service.reissueCard(1L, 9L, 1L, "reason")).getCode());
        verify(assets, never()).insertReissue(any());
        verify(skus, never()).deductStock(any(), any(), any(), anyInt());
    }
    @Test void invalidReissueIdentityIdOrReasonNeverStartsTransaction() {
        assertEquals(401, assertThrows(BusinessException.class, () -> service.reissueCard(1L, 0L, 1L, "reason")).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.reissueCard(1L, 9L, 0L, "reason")).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.reissueCard(1L, 9L, 1L, " ")).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.reissueCard(1L, 9L, 1L, "x".repeat(256))).getCode());
        verifyNoInteractions(manager);
    }
    @Test void foreignCardReissueIsRejectedInsideTransactionAsNotFound() {
        assertEquals(404, assertThrows(BusinessException.class, () -> service.reissueCard(1L, 9L, 99L, "reason")).getCode());
        verify(manager).getTransaction(any());
        verify(manager).rollback(any());
        verifyNoInteractions(skus, cards, audits);
    }
    @Test void reissueAuditFailureRollsBackClaimStockAndRecord() {
        var candidate = reissueCandidate(); prepareSuccessfulReissue(candidate);
        when(audits.insert(any(CardAccessAudit.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.reissueCard(1L, 9L, 1L, "reason"));
        verify(manager).rollback(any());
    }
}
