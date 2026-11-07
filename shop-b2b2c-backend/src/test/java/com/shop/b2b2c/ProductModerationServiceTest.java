/*
 * 文件：ProductModerationServiceTest.java
 * 用途：F11 平台商品违规处置服务纯单元测试：违规下架/审核恢复的参数边界、状态机约束、CAS 冲突与审计内容契约。
 * 维护注意：Mock持久化仅检查调用契约；同事务与并发 CAS 语义必须由 Sprint1SecurityIntegrationTest（H2）及真实 MySQL 验证，
 * 不能把单元测试当成事务/锁验收。from_status 契约：TAKEDOWN 记发起时状态(0/1)，RESTORE 的 to_status 取最近一次 TAKEDOWN 的 from_status。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductViolationAudit;
import com.shop.b2b2c.mapper.ProductModerationMapper;
import com.shop.b2b2c.service.ProductModerationService;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductModerationServiceTest {
    final ProductModerationMapper mapper = mock(ProductModerationMapper.class);
    final ProductModerationService moderation = new ProductModerationService(mapper);
    Product product;

    @BeforeEach void setup() {
        product = new Product(); product.setId(1L); product.setMerchantId(2L); product.setStatus(1);
        when(mapper.findById(1L)).thenReturn(product);
    }

    @Test void takedownOnSaleProductWritesStatusTwoAndAudit() {
        when(mapper.takedownCas(1L, 1, "涉嫌侵权")).thenReturn(1);
        assertNotNull(moderation.takedown(1L, "  涉嫌侵权  ", 9L));
        verify(mapper).takedownCas(1L, 1, "涉嫌侵权");
        var audit = org.mockito.ArgumentCaptor.forClass(ProductViolationAudit.class);
        verify(mapper).insertAudit(audit.capture());
        assertEquals(1L, audit.getValue().getProductId()); assertEquals(2L, audit.getValue().getMerchantId());
        assertEquals("TAKEDOWN", audit.getValue().getAction());
        assertEquals(1, audit.getValue().getFromStatus()); assertEquals(2, audit.getValue().getToStatus());
        assertEquals("涉嫌侵权", audit.getValue().getReason()); assertEquals(9L, audit.getValue().getOperatorId());
    }

    @Test void takedownAlreadyOffShelfProductIsAllowed() {
        product.setStatus(0);
        when(mapper.takedownCas(1L, 0, "重复铺货")).thenReturn(1);
        assertNotNull(moderation.takedown(1L, "重复铺货", 9L));
        verify(mapper).takedownCas(1L, 0, "重复铺货");
    }

    @Test void takedownRejectsBlankAndOverlongReasonBeforeDatabase() {
        for (String bad : new String[]{null, "", "   ", "x".repeat(256)}) {
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> moderation.takedown(1L, bad, 9L)).getCode(), String.valueOf(bad));
        }
        verifyNoInteractions(mapper);
    }

    @Test void takedownAcceptsReasonAtMaxLength() {
        when(mapper.takedownCas(eq(1L), eq(1), anyString())).thenReturn(1);
        assertNotNull(moderation.takedown(1L, "x".repeat(255), 9L));
        verify(mapper).takedownCas(1L, 1, "x".repeat(255));
    }

    @Test void takedownMissingProductReturns404() {
        when(mapper.findById(99L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> moderation.takedown(99L, "违规", 9L)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> moderation.takedown(0L, "违规", 9L)).getCode());
        verify(mapper, never()).takedownCas(any(), anyInt(), any());
        verify(mapper, never()).insertAudit(any());
    }

    @Test void takedownAlreadyViolationAndStaleCasReturn409() {
        product.setStatus(2);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> moderation.takedown(1L, "再次下架", 9L)).getCode());
        product.setStatus(9); // 未知状态同样拒绝
        assertEquals(409, assertThrows(BusinessException.class,
                () -> moderation.takedown(1L, "未知状态", 9L)).getCode());
        product.setStatus(1);
        when(mapper.takedownCas(1L, 1, "并发")).thenReturn(0); // CAS 0 行：并发已变化
        assertEquals(409, assertThrows(BusinessException.class,
                () -> moderation.takedown(1L, "并发", 9L)).getCode());
        verify(mapper, never()).insertAudit(any());
    }

    @Test void restoreReturnsToLatestTakedownFromStatusAndClearsSnapshot() {
        product.setStatus(2);
        var last = new ProductViolationAudit(); last.setAction("TAKEDOWN"); last.setFromStatus(1);
        when(mapper.latestTakedown(1L)).thenReturn(last);
        when(mapper.restoreCas(1L, 1)).thenReturn(1);
        assertNotNull(moderation.restore(1L, null, 9L));
        verify(mapper).restoreCas(1L, 1);
        var audit = org.mockito.ArgumentCaptor.forClass(ProductViolationAudit.class);
        verify(mapper).insertAudit(audit.capture());
        assertEquals("RESTORE", audit.getValue().getAction());
        assertEquals(2, audit.getValue().getFromStatus()); assertEquals(1, audit.getValue().getToStatus());
        assertEquals(ProductModerationService.DEFAULT_RESTORE_NOTE, audit.getValue().getReason());
    }

    @Test void restoreFallsBackToOffShelfWithoutTakedownHistory() {
        product.setStatus(2);
        when(mapper.latestTakedown(1L)).thenReturn(null);
        when(mapper.restoreCas(1L, 0)).thenReturn(1);
        assertNotNull(moderation.restore(1L, "  已整改  ", 9L));
        verify(mapper).restoreCas(1L, 0);
        var audit = org.mockito.ArgumentCaptor.forClass(ProductViolationAudit.class);
        verify(mapper).insertAudit(audit.capture());
        assertEquals(0, audit.getValue().getToStatus()); assertEquals("已整改", audit.getValue().getReason());
    }

    @Test void restoreRejectsNonViolationMissingProductAndStaleCas() {
        product.setStatus(1);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> moderation.restore(1L, "误操作", 9L)).getCode());
        when(mapper.findById(99L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> moderation.restore(99L, "误操作", 9L)).getCode());
        product.setStatus(2);
        var last = new ProductViolationAudit(); last.setFromStatus(1);
        when(mapper.latestTakedown(1L)).thenReturn(last);
        when(mapper.restoreCas(1L, 1)).thenReturn(0); // CAS 0 行
        assertEquals(409, assertThrows(BusinessException.class,
                () -> moderation.restore(1L, "并发", 9L)).getCode());
        verify(mapper, never()).insertAudit(any());
    }

    @Test void restoreRejectsOverlongNote() {
        product.setStatus(2);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> moderation.restore(1L, "x".repeat(256), 9L)).getCode());
        verify(mapper, never()).restoreCas(any(), anyInt());
        verify(mapper, never()).insertAudit(any());
    }

    @Test void auditsRequireExistingProductAndBoundedLimit() {
        when(mapper.listAudits(1L, 50)).thenReturn(List.of(new ProductViolationAudit()));
        assertEquals(1, moderation.audits(1L, 50).size());
        when(mapper.findById(99L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> moderation.audits(99L, 50)).getCode());
        for (int bad : new int[]{0, -1, 201}) {
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> moderation.audits(1L, bad)).getCode(), String.valueOf(bad));
        }
        verify(mapper, times(1)).listAudits(any(), anyInt());
    }

    @Test void listRejectsBadFiltersBeforeDatabase() {
        assertEquals(400, assertThrows(BusinessException.class, () -> moderation.list(-1L, null, null, 1, 20)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> moderation.list(null, 9, null, 1, 20)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> moderation.list(null, null, null, 0, 20)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> moderation.list(null, null, null, 1, 0)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> moderation.list(null, null, null, 1, 101)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> moderation.list(null, null, "k".repeat(65), 1, 20)).getCode());
        verifyNoInteractions(mapper);
    }

    @Test void listTrimsKeywordAndIgnoresBlank() {
        when(mapper.listAdminProducts(null, 2, "abc", 0, 20)).thenReturn(List.of());
        when(mapper.countAdminProducts(null, 2, "abc")).thenReturn(0L);
        assertNotNull(moderation.list(null, 2, "  abc  ", 1, 20));
        when(mapper.listAdminProducts(null, null, null, 0, 20)).thenReturn(List.of());
        when(mapper.countAdminProducts(null, null, null)).thenReturn(0L);
        assertNotNull(moderation.list(null, null, "   ", 1, 20));
    }
}
