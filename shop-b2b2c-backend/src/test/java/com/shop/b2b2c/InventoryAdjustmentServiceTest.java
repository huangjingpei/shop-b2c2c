/*
 * 文件：InventoryAdjustmentServiceTest.java
 * 用途：人工库存输入、幂等重放/冲突、预占容量边界及分页白名单的纯单元测试。
 * 维护注意：事务管理器和SQL接口均Mock，只断言隔离级别与提交/拒绝契约；实际回滚和并发由Spring/H2及独立MySQL测试承担，不连接业务库。
 */
package com.shop.b2b2c;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.InventoryAdjustmentDTOs.*;
import com.shop.b2b2c.entity.*;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.InventoryAdjustmentService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryAdjustmentServiceTest {
    final InventoryAdjustmentMapper mapper = mock(InventoryAdjustmentMapper.class);
    final ProductEditingMapper products = mock(ProductEditingMapper.class);
    final IdempotencyMapper keys = mock(IdempotencyMapper.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final InventoryAdjustmentService service = new InventoryAdjustmentService(mapper, products, keys, json, manager);
    final String key = "inventory-unit-key-1234";
    final Product product = new Product(); final ProductSku sku = new ProductSku();
    final IdempotencyRecord record = new IdempotencyRecord();
    InventoryAdjustment saved;
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        product.setId(1L); product.setMerchantId(1L); product.setGoodsType(1);
        sku.setId(1L); sku.setProductId(1L); sku.setMerchantId(1L); sku.setStockQuantity(10);
        when(products.lockProduct(1L, 1L)).thenReturn(product);
        when(mapper.lockSku(1L, 1L, 1L)).thenReturn(sku);
        doAnswer(call -> { if (record.getRequestHash() == null) record.setRequestHash(call.getArgument(1)); return null; }).when(keys).claim(any(), any());
        when(keys.lock(any())).thenReturn(record);
        when(keys.complete(any(), any())).thenAnswer(call -> { record.setResponseJson(call.getArgument(1)); return 1; });
        when(mapper.adjust(any(), any(), any(), anyInt(), anyInt())).thenReturn(1);
        when(mapper.insert(any())).thenAnswer(call -> {
            saved = call.getArgument(0); saved.setId(7L); saved.setCreatedAt(java.time.LocalDateTime.of(2026, 11, 3, 0, 0)); return 1;
        });
        when(mapper.find(any(), any())).thenAnswer(call -> saved);
    }
    Adjust valid() { return new Adjust(1L, 1L, 5, 10, " 补货 "); }
    @Test void validAdjustmentUsesReadCommittedAndRecordsActorWithSanitizedView() {
        var result = service.adjust(1L, 9L, key, valid());
        assertEquals(10, result.beforeStock()); assertEquals(15, result.afterStock()); assertEquals(9L, result.actorId()); assertEquals("补货", result.reason());
        assertTrue(saved.getRequestKey().matches("[a-f0-9]{64}")); assertFalse(json.valueToTree(result).has("requestKey"));
        verify(manager).getTransaction(argThat(def -> def.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED));
        verify(manager).commit(any());
    }
    @Test void sameKeyReplaysOriginalResultAndChangedPayloadIsRejected() {
        var first = service.adjust(1L, 9L, key, valid());
        sku.setStockQuantity(999);
        assertEquals(first, service.adjust(1L, 9L, key, valid()));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.adjust(1L, 9L, key, new Adjust(1L, 1L, 6, 10, " 补货 "))).getCode());
        verify(mapper, times(1)).adjust(any(), any(), any(), anyInt(), anyInt());
        verify(mapper, times(1)).insert(any());
    }
    @ParameterizedTest @ValueSource(strings = {"nullBody", "zero", "large", "fractionLimit", "negativeExpected", "missingProduct", "missingSku", "blankReason", "longReason"})
    void malformedRequestNeverStartsTransaction(String scenario) {
        Adjust request = switch (scenario) {
            case "nullBody" -> null;
            case "zero" -> new Adjust(1L, 1L, 0, 10, "reason");
            case "large" -> new Adjust(1L, 1L, 1000001, 10, "reason");
            case "fractionLimit" -> new Adjust(1L, 1L, -1000001, 10, "reason");
            case "negativeExpected" -> new Adjust(1L, 1L, 1, -1, "reason");
            case "missingProduct" -> new Adjust(null, 1L, 1, 10, "reason");
            case "missingSku" -> new Adjust(1L, null, 1, 10, "reason");
            case "blankReason" -> new Adjust(1L, 1L, 1, 10, " ");
            default -> new Adjust(1L, 1L, 1, 10, "x".repeat(256));
        };
        assertEquals(400, assertThrows(BusinessException.class, () -> service.adjust(1L, 9L, key, request)).getCode());
        verifyNoInteractions(mapper, products, keys, manager);
    }
    @Test void virtualSkuCannotBeManuallyChanged() {
        product.setGoodsType(2);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.adjust(1L, 9L, key, valid())).getCode());
        verify(mapper, never()).adjust(any(), any(), any(), anyInt(), anyInt()); verify(manager).rollback(any());
    }
    @Test void staleExpectedQuantityIsConflict() {
        sku.setStockQuantity(9);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.adjust(1L, 9L, key, valid())).getCode());
        verify(mapper, never()).insert(any());
    }
    @ParameterizedTest @ValueSource(strings = {"underflow", "overflow", "heldCapacity"})
    void invalidFinalQuantityOrPendingReleaseCapacityIsRejected(String scenario) {
        int before = scenario.equals("underflow") ? 0 : Integer.MAX_VALUE - 1;
        sku.setStockQuantity(before);
        if (scenario.equals("heldCapacity")) when(mapper.heldQuantity(1L, 1L)).thenReturn(2L);
        int delta = scenario.equals("underflow") ? -1 : scenario.equals("overflow") ? 2 : 1;
        assertEquals(400, assertThrows(BusinessException.class, () -> service.adjust(1L, 9L, key, new Adjust(1L, 1L, delta, before, "check"))).getCode());
        verify(mapper, never()).adjust(any(), any(), any(), anyInt(), anyInt());
    }
    @Test void foreignProductNeverReadsSkuOrWritesStock() {
        assertEquals(404, assertThrows(BusinessException.class, () -> service.adjust(2L, 9L, key, valid())).getCode());
        verifyNoInteractions(mapper);
    }
    @Test void paginationUsesExtraRowForCursorAndNeverExposesRequestKey() {
        var first = new InventoryAdjustment(); first.setId(3L); first.setRequestKey("private-key");
        var second = new InventoryAdjustment(); second.setId(2L); var third = new InventoryAdjustment(); third.setId(1L);
        when(mapper.list(1L, null, null, 3)).thenReturn(List.of(first, second, third));
        var page = service.list(1L, null, null, 2);
        assertEquals(2, page.items().size()); assertEquals(2L, page.nextBeforeId());
        assertFalse(json.valueToTree(page).toString().contains("private-key"));
    }
    @ParameterizedTest @ValueSource(ints = {0, 101, -1})
    void invalidPageLimitNeverReadsDatabase(int limit) {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.list(1L, null, null, limit)).getCode()); verifyNoInteractions(mapper);
    }
}
