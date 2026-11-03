/*
 * 文件：CardImportBatchServiceTest.java
 * 用途：批次/成员有界分页、租户拒绝、参数边界和响应字段白名单单元测试。
 * 维护注意：Mock查询不证明SQL租户过滤，Spring/H2及独立MySQL另行覆盖；不使用真实卡密，不将批次库存快照当实时数量。
 */
package com.shop.b2b2c;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.CardImportDTOs.Item;
import com.shop.b2b2c.entity.CardImportBatch;
import com.shop.b2b2c.mapper.CardImportBatchMapper;
import com.shop.b2b2c.service.CardImportBatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CardImportBatchServiceTest {
    final CardImportBatchMapper mapper = mock(CardImportBatchMapper.class);
    final CardImportBatchService service = new CardImportBatchService(mapper);
    CardImportBatch row(long id) { var batch = new CardImportBatch(); batch.setId(id); return batch; }
    @Test void batchCursorUsesLastDisplayedRowAndHasBoundedQuery() {
        when(mapper.list(1L, 5L, null, 3)).thenReturn(List.of(row(30), row(20), row(10)));
        var page = service.list(1L, 5L, null, 2);
        assertEquals(List.of(30L, 20L), page.items().stream().map(CardImportBatch::getId).toList());
        assertEquals(20L, page.nextBeforeId());
        when(mapper.list(1L, 5L, 20L, 3)).thenReturn(List.of(row(10)));
        assertNull(service.list(1L, 5L, 20L, 2).nextBeforeId());
    }
    @Test void membersHaveOwnCursorAndNoSecretFields() {
        when(mapper.find(1L, 7L)).thenReturn(row(7));
        when(mapper.members(1L, 7L, null, 2)).thenReturn(List.of(new Item(20L, 2, null), new Item(10L, 1, "order-fixture")));
        var page = service.members(1L, 7L, null, 1);
        assertEquals(20L, page.nextBeforeId()); assertEquals(1, page.items().size());
        var node = new ObjectMapper().valueToTree(page.items().get(0));
        assertEquals(3, node.size()); assertFalse(node.has("cardContent")); assertFalse(node.has("merchantId"));
    }
    @Test void foreignOrMissingBatchNeverReadsMembers() {
        assertEquals(404, assertThrows(BusinessException.class, () -> service.members(2L, 7L, null, 20)).getCode());
        verify(mapper, never()).members(any(), any(), any(), anyInt());
    }
    @Test void emptyBatchAndEmptyListHaveNoNextCursor() {
        when(mapper.find(1L, 7L)).thenReturn(row(7)); when(mapper.members(1L, 7L, null, 21)).thenReturn(List.of());
        when(mapper.list(1L, null, null, 21)).thenReturn(List.of());
        assertNull(service.members(1L, 7L, null, 20).nextBeforeId()); assertTrue(service.list(1L, null, null, 20).items().isEmpty());
    }
    @ParameterizedTest @ValueSource(ints = {0, -1, 101})
    void invalidLimitCannotQuery(int limit) {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.list(1L, null, null, limit)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.members(1L, 7L, null, limit)).getCode()); verifyNoInteractions(mapper);
    }
    @ParameterizedTest @NullSource @ValueSource(longs = {0, -1})
    void missingMerchantCannotReadAnyRecord(Long merchant) {
        assertEquals(403, assertThrows(BusinessException.class, () -> service.list(merchant, null, null, 20)).getCode());
        verifyNoInteractions(mapper);
    }
    @Test void invalidSkuBatchAndCursorAreRejectedWithoutQuery() {
        assertThrows(BusinessException.class, () -> service.list(1L, 0L, null, 20));
        assertThrows(BusinessException.class, () -> service.list(1L, null, 0L, 20));
        assertThrows(BusinessException.class, () -> service.members(1L, 0L, null, 20));
        verifyNoInteractions(mapper);
    }
}
