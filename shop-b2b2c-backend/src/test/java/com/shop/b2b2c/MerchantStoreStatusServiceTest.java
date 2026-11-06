/*
 * 文件：MerchantStoreStatusServiceTest.java
 * 用途：O6 店铺状态机规则单元测试：合法迁移、终态保护、原因码字典、解封双人复核、
 *      关店前置条件、CAS 冲突、注销副作用（停用账号）与审计写入。
 * 维护注意：
 *  1. Mockito 隔离测试，不连接数据库；真实并发下 CAS 与行锁行为由用户在 MySQL 验证。
 *  2. 状态语义 0-待开通/1-营业/2-打烊/3-冻结/4-已注销；迁移表变更时同步服务与本测试。
 *  3. 双人复核只验证与 FinanceApprovalGuard 的协作契约，开关矩阵见 FinanceApprovalGuard 自身测试。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.MerchantStoreStatusAudit;
import com.shop.b2b2c.mapper.MerchantSettlementBatchMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.MerchantStoreStatusAuditMapper;
import com.shop.b2b2c.mapper.ProductMapper;
import com.shop.b2b2c.mapper.SysUserMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import com.shop.b2b2c.service.FinanceApprovalGuard;
import com.shop.b2b2c.service.MerchantStoreStatusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MerchantStoreStatusServiceTest {

    @Mock
    private MerchantStoreMapper storeMapper;
    @Mock
    private MerchantStoreStatusAuditMapper statusAuditMapper;
    @Mock
    private TradeOrderMapper orderMapper;
    @Mock
    private TradeRefundMapper refundMapper;
    @Mock
    private MerchantSettlementBatchMapper batchMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private FinanceApprovalGuard approvalGuard;
    @Mock
    private com.shop.b2b2c.service.MerchantNotificationService notificationService;
    @InjectMocks
    private MerchantStoreStatusService service;

    @Test
    void pauseAndResumeWriteAuditWithFromTo() {
        MerchantStore store = store(10L, 1);
        when(storeMapper.lockById(10L)).thenReturn(store);
        when(storeMapper.updateStatusCas(10L, List.of(1), 2)).thenReturn(1);

        Map<String, Object> view = service.applyAction(10L, "PAUSE", null, null, 7L, null);

        assertEquals(1, view.get("fromStatus"));
        assertEquals(2, view.get("toStatus"));
        MerchantStoreStatusAudit audit = captureAudit();
        assertEquals("PAUSE", audit.getAction());
        assertEquals(1, audit.getFromStatus());
        assertEquals(2, audit.getToStatus());
        assertEquals(7L, audit.getOperatorId());

        store.setStatus(2);
        when(storeMapper.updateStatusCas(10L, List.of(2), 1)).thenReturn(1);

        view = service.applyAction(10L, "RESUME", null, null, 7L, null);

        assertEquals(2, view.get("fromStatus"));
        assertEquals(1, view.get("toStatus"));
    }

    @Test
    void freezeRequiresReasonCodeFromDictionary() {
        MerchantStore store = store(10L, 1);
        when(storeMapper.lockById(10L)).thenReturn(store);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyAction(10L, "FREEZE", null, null, 7L, null));
        assertEquals(400, ex.getCode());

        ex = assertThrows(BusinessException.class,
                () -> service.applyAction(10L, "FREEZE", "NOT_A_CODE", null, 7L, null));
        assertEquals(400, ex.getCode());

        ex = assertThrows(BusinessException.class,
                () -> service.applyAction(10L, "FREEZE", "OTHER", null, 7L, null));
        assertEquals(400, ex.getCode());
        verify(storeMapper, never()).updateStatusCas(any(), any(), any());
    }

    @Test
    void freezeFromOpenOrPausedTransitionsToFrozen() {
        MerchantStore store = store(10L, 1);
        when(storeMapper.lockById(10L)).thenReturn(store);
        when(storeMapper.updateStatusCas(10L, List.of(1, 2), 3)).thenReturn(1);

        Map<String, Object> view = service.applyAction(10L, "FREEZE", "VIOLATION", "售假投诉成立", 7L, null);

        assertEquals(3, view.get("toStatus"));
        MerchantStoreStatusAudit audit = captureAudit();
        assertEquals("FREEZE", audit.getAction());
        assertEquals("VIOLATION", audit.getReasonCode());
        assertEquals("售假投诉成立", audit.getReason());
    }

    @Test
    void unfreezeRequiresDualReviewAndRestoresOpen() {
        // 解封必须经 FinanceApprovalGuard 校验并解析复核人；通过后才 CAS 3->1，审计落 DUAL_APPROVAL。
        MerchantStore store = store(10L, 3);
        when(storeMapper.lockById(10L)).thenReturn(store);
        when(approvalGuard.verify("店铺解封", 7L, 20L)).thenReturn("DUAL_APPROVAL");
        when(approvalGuard.resolveReviewer(7L, 20L)).thenReturn(20L);
        when(storeMapper.updateStatusCas(10L, List.of(3), 1)).thenReturn(1);

        Map<String, Object> view = service.applyAction(10L, "UNFREEZE", "MERCHANT_REQUEST", "整改完成", 7L, 20L);

        assertEquals(1, view.get("toStatus"));
        verify(approvalGuard).verify("店铺解封", 7L, 20L);
        MerchantStoreStatusAudit audit = captureAudit();
        assertEquals("UNFREEZE", audit.getAction());
        assertEquals(20L, audit.getConfirmedBy());
        assertEquals("DUAL_APPROVAL", audit.getApprovalMode());
    }

    @Test
    void unfreezeWithoutReviewerFallsBackToSameOperatorMode() {
        // 非严格模式允许缺复核人（开发/试点），审计必须落 SAME_OPERATOR 口径，confirmedBy 按操作人记录。
        MerchantStore store = store(10L, 3);
        when(storeMapper.lockById(10L)).thenReturn(store);
        when(approvalGuard.verify("店铺解封", 7L, null)).thenReturn("SAME_OPERATOR");
        when(approvalGuard.resolveReviewer(7L, null)).thenReturn(7L);
        when(storeMapper.updateStatusCas(10L, List.of(3), 1)).thenReturn(1);

        service.applyAction(10L, "UNFREEZE", "MERCHANT_REQUEST", null, 7L, null);

        MerchantStoreStatusAudit audit = captureAudit();
        assertEquals(7L, audit.getConfirmedBy());
        assertEquals("SAME_OPERATOR", audit.getApprovalMode());
    }

    @Test
    void closeIsBlockedByEachPrecondition() {
        // 前置条件逐项：未完结订单 -> 处理中退款 -> 未确认批次/欠费 -> 在售商品；任一命中 409 且不写状态。
        MerchantStore store = store(10L, 1);
        when(storeMapper.lockById(10L)).thenReturn(store);
        when(orderMapper.countOngoingByMerchantId(10L)).thenReturn(2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyAction(10L, "CLOSE", "MERCHANT_REQUEST", null, 7L, null));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("未完结订单"));
        verify(storeMapper, never()).updateStatusCas(any(), any(), any());

        when(orderMapper.countOngoingByMerchantId(10L)).thenReturn(0);
        when(refundMapper.countProcessingByMerchantId(10L)).thenReturn(1);

        ex = assertThrows(BusinessException.class,
                () -> service.applyAction(10L, "CLOSE", "MERCHANT_REQUEST", null, 7L, null));
        assertTrue(ex.getMessage().contains("退款"));

        when(refundMapper.countProcessingByMerchantId(10L)).thenReturn(0);
        when(batchMapper.countBlockingByMerchantId(10L)).thenReturn(1);

        ex = assertThrows(BusinessException.class,
                () -> service.applyAction(10L, "CLOSE", "MERCHANT_REQUEST", null, 7L, null));
        assertTrue(ex.getMessage().contains("结算批次"));

        when(batchMapper.countBlockingByMerchantId(10L)).thenReturn(0);
        when(productMapper.countByMerchantIdAndStatus(10L, 1)).thenReturn(3L);

        ex = assertThrows(BusinessException.class,
                () -> service.applyAction(10L, "CLOSE", "MERCHANT_REQUEST", null, 7L, null));
        assertTrue(ex.getMessage().contains("在售商品"));
    }

    @Test
    void closeSuccessCancelsStoreAndDisablesMerchantAccounts() {
        // 前置条件全过：CAS 1->4，同事务停用店铺全部商家账号，审计落 CLOSE。
        MerchantStore store = store(10L, 1);
        when(storeMapper.lockById(10L)).thenReturn(store);
        when(orderMapper.countOngoingByMerchantId(10L)).thenReturn(0);
        when(refundMapper.countProcessingByMerchantId(10L)).thenReturn(0);
        when(batchMapper.countBlockingByMerchantId(10L)).thenReturn(0);
        when(productMapper.countByMerchantIdAndStatus(10L, 1)).thenReturn(0L);
        when(storeMapper.updateStatusCas(10L, List.of(1, 2), 4)).thenReturn(1);
        when(userMapper.disableByMerchantId(10L)).thenReturn(2);

        Map<String, Object> view = service.applyAction(10L, "CLOSE", "MERCHANT_REQUEST", "商家申请关店", 7L, null);

        assertEquals(4, view.get("toStatus"));
        verify(userMapper).disableByMerchantId(10L);
        MerchantStoreStatusAudit audit = captureAudit();
        assertEquals("CLOSE", audit.getAction());
        assertEquals(4, audit.getToStatus());
    }

    @Test
    void cancelledStoreIsTerminalForAllActions() {
        MerchantStore store = store(10L, 4);
        when(storeMapper.lockById(10L)).thenReturn(store);

        for (String action : List.of("PAUSE", "RESUME", "FREEZE", "UNFREEZE", "CLOSE")) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.applyAction(10L, action, "MERCHANT_REQUEST", null, 7L, null));
            assertEquals(409, ex.getCode(), action);
        }
        verify(storeMapper, never()).updateStatusCas(any(), any(), any());
    }

    @Test
    void casConflictTranslatesTo409WithoutAudit() {
        // 并发下 CAS 影响 0 行：必须 409 且不写审计，禁止静默成功。
        MerchantStore store = store(10L, 1);
        when(storeMapper.lockById(10L)).thenReturn(store);
        when(storeMapper.updateStatusCas(10L, List.of(1), 2)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyAction(10L, "PAUSE", null, null, 7L, null));

        assertEquals(409, ex.getCode());
        verify(statusAuditMapper, never()).insert(any());
    }

    @Test
    void unknownActionRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyAction(10L, "DELETE", null, null, 7L, null));
        assertEquals(400, ex.getCode());
    }

    private MerchantStoreStatusAudit captureAudit() {
        ArgumentCaptor<MerchantStoreStatusAudit> captor = ArgumentCaptor.forClass(MerchantStoreStatusAudit.class);
        verify(statusAuditMapper, atLeastOnce()).insert(captor.capture());
        return captor.getValue();
    }

    private MerchantStore store(Long id, int status) {
        MerchantStore store = new MerchantStore();
        store.setId(id);
        store.setStatus(status);
        return store;
    }
}
