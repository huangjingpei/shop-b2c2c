/*
 * 文件：ReconciliationServiceTest.java
 * 用途：T35 渠道流水导入、对账差异入队/重试/人工处理与账单 CSV 导出的契约用例。
 * 维护注意：
 *  1. 全部使用 Mockito 隔离，不启动 Spring、不连接数据库、不调用真实渠道。
 *  2. 对账只入队差异，不得自动改订单/账本/结算单；用例同时覆盖差异幂等与跨店拒绝。
 *  3. CSV 用例覆盖公式注入防护和逗号/引号转义，避免导出打开即执行公式。
 *  4. 真实渠道流水导入与 MySQL 并发对账仍由用户手动验证。
 *  5. T+1 用例覆盖退款↔渠道退款、服务费金额、平台代收、同日重试防重复与审计留痕。
 *  6. 非成功支付事件不得静默：按 funds_received 三值分别断言——=1 进差集且按成因落 REJECTED/REFUNDED/
 *     CHANNEL_CONFLICT，=0 不进差集但仍落 REJECTED，NULL 不进差集但落 REVIEW；断言具体差异条数与类型。
 *  7. funds_received=1 按成因拆成独立差异类型：退款类 PAYMENT_EVENT_REFUNDED（键 EV|id|REFUNDED）与
 *     渠道答复冲突类 PAYMENT_EVENT_CHANNEL_CONFLICT（键 EV|id|CHANNEL_CONFLICT），与普通 REJECTED 键不同；
 *     断言具体类型名与键，文案不得出现“未收到资金”。
 *  8. retry 自动关闭必须有用例：REVIEW 要求事件 SUCCESS 且 funds_received 非 NULL；REJECTED/REFUNDED/
 *     CHANNEL_CONFLICT 仅要求 SUCCESS；断言差异状态（RESOLVED/RETRY）而不只是“不抛异常”。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantAccountLedger;
import com.shop.b2b2c.entity.MerchantSettlementBatch;
import com.shop.b2b2c.entity.MerchantSettlementItem;
import com.shop.b2b2c.entity.PaymentChannelStatement;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.entity.PlatformCollectionSettlement;
import com.shop.b2b2c.entity.ReconciliationDifference;
import com.shop.b2b2c.entity.ReconciliationDifferenceAudit;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.MerchantAccountLedgerMapper;
import com.shop.b2b2c.mapper.MerchantSettlementBatchMapper;
import com.shop.b2b2c.mapper.MerchantSettlementItemMapper;
import com.shop.b2b2c.mapper.PaymentChannelStatementMapper;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.mapper.PlatformCollectionSettlementMapper;
import com.shop.b2b2c.mapper.ReconciliationDifferenceAuditMapper;
import com.shop.b2b2c.mapper.ReconciliationDifferenceMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import com.shop.b2b2c.service.ReconciliationClassifier;
import com.shop.b2b2c.service.ReconciliationService;
import com.shop.b2b2c.service.ServiceFeeSnapshotService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationServiceTest {

    private final PaymentChannelStatementMapper statementMapper = mock(PaymentChannelStatementMapper.class);
    private final PaymentEventMapper eventMapper = mock(PaymentEventMapper.class);
    private final TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
    private final MerchantAccountLedgerMapper ledgerMapper = mock(MerchantAccountLedgerMapper.class);
    private final MerchantSettlementBatchMapper batchMapper = mock(MerchantSettlementBatchMapper.class);
    private final MerchantSettlementItemMapper itemMapper = mock(MerchantSettlementItemMapper.class);
    private final ReconciliationDifferenceMapper differenceMapper =
            mock(ReconciliationDifferenceMapper.class);
    private final ServiceFeeSnapshotService snapshotService = new ServiceFeeSnapshotService();
    private final ReconciliationDifferenceAuditMapper auditMapper =
            mock(ReconciliationDifferenceAuditMapper.class);
    private final TradeRefundMapper refundMapper = mock(TradeRefundMapper.class);
    private final PlatformCollectionSettlementMapper platformSettlementMapper =
            mock(PlatformCollectionSettlementMapper.class);

    private ReconciliationService service() {
        return new ReconciliationService(statementMapper, eventMapper, orderMapper, ledgerMapper, batchMapper,
                itemMapper, differenceMapper, snapshotService, auditMapper, refundMapper,
                platformSettlementMapper);
    }

    @Test
    void reconcileFlagsStatementWithoutPaymentEvent() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentChannelStatement statement = statement("WECHAT", "WX-1", "SN-X1", new BigDecimal("100.00"));
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(statement));
        when(eventMapper.findByChannelAndTradeNo("WECHAT", "WX-1")).thenReturn(null);
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals(1, result.get("added"));
        verify(differenceMapper).insert(any(ReconciliationDifference.class));
        ArgumentCaptor<ReconciliationDifferenceAudit> audit =
                ArgumentCaptor.forClass(ReconciliationDifferenceAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("CREATE", audit.getValue().getAction());
        assertEquals("DISPUTED", audit.getValue().getToStatus());
    }

    @Test
    void reconcileFlagsAmountMismatchBetweenStatementAndEvent() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentChannelStatement statement = statement("WECHAT", "WX-2", "SN-X2", new BigDecimal("100.00"));
        PaymentEvent event = successEvent("SN-X2", new BigDecimal("99.00"));
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(statement));
        when(eventMapper.findByChannelAndTradeNo("WECHAT", "WX-2")).thenReturn(event);
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals(1, result.get("added"));
        verify(differenceMapper).insert(any(ReconciliationDifference.class));
    }

    @Test
    void reconcileFlagsMissingFeeLedgerForPaidOrder() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentEvent event = successEvent("SN-X3", new BigDecimal("100.00"));
        TradeOrder order = paidOrder("SN-X3", new BigDecimal("3.00"));
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(event));
        when(orderMapper.findByOrderSn("SN-X3")).thenReturn(Optional.of(order));
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-X3")).thenReturn(Optional.empty());
        when(statementMapper.findByChannelAndTradeNo("WECHAT", "WX-3")).thenReturn(Optional.empty());
        when(statementMapper.countByMerchantChannelPeriod(1L, "WECHAT", from, to)).thenReturn(0);
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals(1, result.get("added"));
        verify(differenceMapper).insert(any(ReconciliationDifference.class));
    }

    @Test
    void reconcileFlagsDuplicatePaymentWithoutTouchingLedger() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentEvent event = successEvent("SN-X4", new BigDecimal("100.00"));
        event.setEventStatus("DUPLICATE_PAYMENT");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(event));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        service().reconcile(1L, from, to);

        verify(differenceMapper).insert(any(ReconciliationDifference.class));
        verify(ledgerMapper, never()).insert(any(MerchantAccountLedger.class));
    }

    @Test
    void reconcileFlagsSettlementTotalsMismatch() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        MerchantSettlementBatch batch = new MerchantSettlementBatch();
        batch.setId(10L);
        batch.setMerchantId(1L);
        batch.setStatus("CONFIRMED");
        // 批次账期是开区间：月结生成器与手工按月入口的 periodEnd 都是次月 1 日。
        batch.setPeriodStart(LocalDate.of(2026, 10, 1));
        batch.setPeriodEnd(LocalDate.of(2026, 11, 1));
        batch.setFeeAmount(new BigDecimal("4.00"));
        batch.setAdjustmentAmount(BigDecimal.ZERO);
        batch.setNetFeeAmount(new BigDecimal("5.00"));
        MerchantSettlementItem item = new MerchantSettlementItem();
        item.setItemType("ORDER_FEE");
        item.setFeeAmount(new BigDecimal("4.00"));
        item.setNetFee(new BigDecimal("4.00"));
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(batchMapper.findConfirmedByPeriod(1L, from.toLocalDate(), to.toLocalDate()))
                .thenReturn(List.of(batch));
        when(itemMapper.findByBatchId(10L)).thenReturn(List.of(item));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        service().reconcile(1L, from, to);

        verify(differenceMapper).insert(any(ReconciliationDifference.class));
    }

    @Test
    void blankChannelIsNotTreatedAsMissingChannel() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        TradeRefund blankChannel = successfulRefund("RF-4", "", new BigDecimal("10.00"), BigDecimal.ZERO);
        blankChannel.setChannel("");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(refundMapper.findSuccessfulRefundsByPeriod(1L, from, to)).thenReturn(List.of(blankChannel));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        // 空串渠道不是真实渠道：不能进入差集，否则该店每天 PARTIAL 并最终错误转 MANUAL。
        assertEquals("", result.get("incompleteChannels"));
    }

    @Test
    void amountMismatchOnlyChannelIsIncompleteAndQueuesRejectionDifference() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        // A 渠道（WECHAT）当日有流水；B 渠道（ALIPAY）未导入流水，只有一笔已收款的 AMOUNT_MISMATCH 事件。
        PaymentChannelStatement wechatGate = statement("WECHAT", "WX-GATE", "SN-GATE", new BigDecimal("10.00"));
        wechatGate.setTradeStatus("FAILED");
        PaymentEvent alipay = successEvent("SN-AP", new BigDecimal("20.00"));
        alipay.setId(2L);
        alipay.setChannel("ALIPAY");
        alipay.setEventStatus("AMOUNT_MISMATCH");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(wechatGate));
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(alipay));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        // 总流水数不为 0 也不能掩盖 B 未导入：差集必须标出 ALIPAY，跑批据此 PARTIAL 而不是 SUCCESS。
        assertEquals("ALIPAY", result.get("incompleteChannels"));
        assertEquals(1, result.get("added"));
        assertEquals(0, result.get("processingPending"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_REJECTED, captor.getValue().getDiffType());
        assertEquals("ALIPAY", captor.getValue().getChannel());
        assertEquals(new BigDecimal("20.00"), captor.getValue().getExpectedAmount());
        assertTrue(captor.getValue().getDetail().contains("AMOUNT_MISMATCH"));
    }

    @Test
    void fulfillmentFailedEventIsIncompleteAndQueuesRejectionDifference() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentChannelStatement wechatGate = statement("WECHAT", "WX-GATE", "SN-GATE", new BigDecimal("10.00"));
        wechatGate.setTradeStatus("FAILED");
        PaymentEvent unionPay = successEvent("SN-FF", new BigDecimal("30.00"));
        unionPay.setId(3L);
        unionPay.setChannel("UNIONPAY");
        unionPay.setEventStatus("FULFILLMENT_FAILED");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(wechatGate));
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(unionPay));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals("UNIONPAY", result.get("incompleteChannels"));
        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_REJECTED, captor.getValue().getDiffType());
        assertTrue(captor.getValue().getDetail().contains("FULFILLMENT_FAILED"));
    }

    @Test
    void currencyMismatchEventIsIncompleteAndQueuesRejectionDifference() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentChannelStatement wechatGate = statement("WECHAT", "WX-GATE", "SN-GATE", new BigDecimal("10.00"));
        wechatGate.setTradeStatus("FAILED");
        PaymentEvent alipay = successEvent("SN-CUR", new BigDecimal("40.00"));
        alipay.setId(4L);
        alipay.setChannel("ALIPAY");
        alipay.setEventStatus("CURRENCY_MISMATCH");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(wechatGate));
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(alipay));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals("ALIPAY", result.get("incompleteChannels"));
        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_REJECTED, captor.getValue().getDiffType());
        assertTrue(captor.getValue().getDetail().contains("CURRENCY_MISMATCH"));
    }

    @Test
    void staleProcessingIsVisibleEvenWhenOtherChannelHasStatements() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentChannelStatement wechatGate = statement("WECHAT", "WX-GATE", "SN-GATE", new BigDecimal("10.00"));
        wechatGate.setTradeStatus("FAILED");
        PaymentEvent hung = successEvent("SN-PR", new BigDecimal("50.00"));
        hung.setId(5L);
        hung.setChannel("UNIONPAY");
        hung.setEventStatus("PROCESSING");
        // 悬挂阈值与 G34 一致：created_at 早于当前时间 5 分钟。
        hung.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(10));
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(wechatGate));
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(hung));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        // PROCESSING 结果未确认，不据此判渠道未导入；但悬挂必须落差异，不能只写日志。
        assertEquals("", result.get("incompleteChannels"));
        assertEquals(1, result.get("added"));
        assertEquals(0, result.get("processingPending"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_REVIEW, captor.getValue().getDiffType());
        assertTrue(captor.getValue().getDetail().contains("PROCESSING"));
        assertTrue(captor.getValue().getDetail().contains("5分钟"));
    }

    @Test
    void freshProcessingIsCountedInSummaryWithoutNoiseDifference() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentChannelStatement wechatGate = statement("WECHAT", "WX-GATE", "SN-GATE", new BigDecimal("10.00"));
        wechatGate.setTradeStatus("FAILED");
        PaymentEvent inFlight = successEvent("SN-PR2", new BigDecimal("50.00"));
        inFlight.setId(6L);
        inFlight.setChannel("UNIONPAY");
        inFlight.setEventStatus("PROCESSING");
        inFlight.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(1));
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(wechatGate));
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(inFlight));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        // 未悬挂 PROCESSING 不逐笔入队，但必须在跑批汇总计数，不能静默。
        assertEquals(0, result.get("added"));
        assertEquals(1, result.get("processingPending"));
        assertEquals("", result.get("incompleteChannels"));
        verify(differenceMapper, never()).insert(any(ReconciliationDifference.class));
    }

    @Test
    void unknownEventStatusStillQueuesReviewDifference() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentEvent unknown = successEvent("SN-UK", new BigDecimal("60.00"));
        unknown.setId(7L);
        unknown.setChannel("ALIPAY");
        // PaymentEventServiceImpl 在收款后还会写 ORDER_STATUS_INVALID，不在七个“预期流水”状态内。
        unknown.setEventStatus("ORDER_STATUS_INVALID");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(unknown));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals(1, result.get("added"));
        assertEquals(0, result.get("processingPending"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_REVIEW, captor.getValue().getDiffType());
        assertTrue(captor.getValue().getDetail().contains("ORDER_STATUS_INVALID"));
    }

    @Test
    void explicitlyNotPaidEventIsNotIncompleteButStillHasDifference() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentChannelStatement wechatGate = statement("WECHAT", "WX-GATE", "SN-GATE", new BigDecimal("10.00"));
        wechatGate.setTradeStatus("FAILED");
        // G34 渠道明确未支付：funds_received=0，本来就不该有流水，不得据此判 ALIPAY 未导入。
        PaymentEvent notPaid = successEvent("SN-NP", new BigDecimal("20.00"));
        notPaid.setId(10L);
        notPaid.setChannel("ALIPAY");
        notPaid.setEventStatus("FULFILLMENT_FAILED");
        notPaid.setFundsReceived(0);
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(wechatGate));
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(notPaid));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        // 既不误报渠道缺失（不 PARTIAL），也不静默：拒绝事件必须落差异。
        assertEquals("", result.get("incompleteChannels"));
        assertEquals(1, result.get("added"));
        assertEquals(0, result.get("processingPending"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_REJECTED, captor.getValue().getDiffType());
        assertTrue(captor.getValue().getDetail().contains("明确未收到资金"));
    }

    @Test
    void unmarkedFundsEventIsNotIncompleteButStillHasReviewDifference() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentChannelStatement wechatGate = statement("WECHAT", "WX-GATE", "SN-GATE", new BigDecimal("10.00"));
        wechatGate.setTradeStatus("FAILED");
        // funds_received=NULL 是“未标注、需要人看”，不得当作无需流水。
        PaymentEvent unmarked = successEvent("SN-NL", new BigDecimal("20.00"));
        unmarked.setId(11L);
        unmarked.setChannel("ALIPAY");
        unmarked.setEventStatus("AMOUNT_MISMATCH");
        unmarked.setFundsReceived(null);
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(wechatGate));
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(unmarked));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals("", result.get("incompleteChannels"));
        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_REVIEW, captor.getValue().getDiffType());
        assertTrue(captor.getValue().getDetail().contains("funds_received IS NULL"));
    }

    @Test
    void mixedFundsReceivedFlagsStillMarkChannelIncomplete() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        // 同一渠道既有明确未支付(=0)，也有已到账(=1)：只要有一条 1，该渠道仍必须导入流水。
        PaymentEvent received = successEvent("SN-MX1", new BigDecimal("20.00"));
        received.setId(12L);
        received.setChannel("ALIPAY");
        received.setEventStatus("AMOUNT_MISMATCH");
        received.setFundsReceived(1);
        PaymentEvent notPaid = successEvent("SN-MX2", new BigDecimal("20.00"));
        notPaid.setId(13L);
        notPaid.setChannel("ALIPAY");
        notPaid.setEventStatus("FULFILLMENT_FAILED");
        notPaid.setFundsReceived(0);
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(received, notPaid));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals("ALIPAY", result.get("incompleteChannels"));
        assertEquals(2, result.get("added"));
    }

    @Test
    void refundedEventIsIncompleteAndDetailSaysRefundedNotUnreceived() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        // G34 渠道已收款后已退款：状态落 FULFILLMENT_FAILED，资金往来发生过，funds_received=1。
        PaymentEvent refunded = successEvent("SN-RF", new BigDecimal("20.00"));
        refunded.setId(16L);
        refunded.setChannel("ALIPAY");
        refunded.setEventStatus("FULFILLMENT_FAILED");
        refunded.setFundsReceived(1);
        refunded.setErrorMessage("渠道已收款后已退款: state=REFUNDED");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(refunded));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        // 支付流水整批未导入必须能暴露：退款不代表没有流水，仍须判 ALIPAY 缺失并落差异。
        assertEquals("ALIPAY", result.get("incompleteChannels"));
        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_REFUNDED, captor.getValue().getDiffType());
        assertEquals("EV|16|REFUNDED", captor.getValue().getDifferenceKey());
        assertTrue(captor.getValue().getDetail().contains("渠道已收款后已退款"));
        assertFalse(captor.getValue().getDetail().contains("未收到资金"));
    }

    @Test
    void channelConflictEventUsesOwnTypeAndKey() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        // 占位即 1 的已建事件，渠道却回 CLOSED：不得降级资金标记，差异类型须体现“答复与记录冲突”。
        PaymentEvent conflict = successEvent("SN-CF", new BigDecimal("30.00"));
        conflict.setId(17L);
        conflict.setChannel("ALIPAY");
        conflict.setEventStatus("FULFILLMENT_FAILED");
        conflict.setFundsReceived(1);
        conflict.setErrorMessage("渠道明确未支付: state=CLOSED");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(conflict));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        // 资金往来有记录但渠道答复未支付：仍预期流水，未导入必须 PARTIAL。
        assertEquals("ALIPAY", result.get("incompleteChannels"));
        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_CHANNEL_CONFLICT,
                captor.getValue().getDiffType());
        assertEquals("EV|17|CHANNEL_CONFLICT", captor.getValue().getDifferenceKey());
        assertTrue(captor.getValue().getDetail().contains("渠道答复与已收款记录冲突"));
        assertTrue(captor.getValue().getDetail().contains("state=CLOSED"));
    }

    @Test
    void alipayClosedUndeterminedReasonUsesConflictTypeAndKey() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        // 支付宝 TRADE_CLOSED 无退款证据：原因不是“渠道明确未支付”，但仍属冲突类，类型/键必须固定。
        PaymentEvent conflict = successEvent("SN-CF3", new BigDecimal("31.00"));
        conflict.setId(20L);
        conflict.setChannel("ALIPAY");
        conflict.setEventStatus("FULFILLMENT_FAILED");
        conflict.setFundsReceived(1);
        conflict.setErrorMessage("渠道已关闭，可能为全额退款，需核对退款流水: state=CLOSED");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(conflict));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals("ALIPAY", result.get("incompleteChannels"));
        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_CHANNEL_CONFLICT,
                captor.getValue().getDiffType());
        assertEquals("EV|20|CHANNEL_CONFLICT", captor.getValue().getDifferenceKey());
        assertTrue(captor.getValue().getDetail().contains("渠道答复与已收款记录冲突"));
        assertTrue(captor.getValue().getDetail().contains("state=CLOSED"));
    }

    @Test
    void refundedAndConflictDifferencesUseDistinctTypesAndKeys() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentEvent refunded = successEvent("SN-RF2", new BigDecimal("20.00"));
        refunded.setId(18L);
        refunded.setChannel("ALIPAY");
        refunded.setEventStatus("FULFILLMENT_FAILED");
        refunded.setFundsReceived(1);
        refunded.setErrorMessage("渠道已收款后已退款: state=REFUNDED");
        PaymentEvent conflict = successEvent("SN-CF2", new BigDecimal("30.00"));
        conflict.setId(19L);
        conflict.setChannel("ALIPAY");
        conflict.setEventStatus("FULFILLMENT_FAILED");
        conflict.setFundsReceived(1);
        conflict.setErrorMessage("渠道明确未支付: state=FAILED");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(refunded, conflict));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        service().reconcile(1L, from, to);

        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper, times(2)).insert(captor.capture());
        List<ReconciliationDifference> inserted = captor.getAllValues();
        assertEquals(2, inserted.size());
        assertTrue(inserted.stream().anyMatch(d ->
                ReconciliationService.PAYMENT_EVENT_REFUNDED.equals(d.getDiffType())
                        && "EV|18|REFUNDED".equals(d.getDifferenceKey())));
        assertTrue(inserted.stream().anyMatch(d ->
                ReconciliationService.PAYMENT_EVENT_CHANNEL_CONFLICT.equals(d.getDiffType())
                        && "EV|19|CHANNEL_CONFLICT".equals(d.getDifferenceKey())));
    }

    @Test
    void manualReviewEventIsVisibleButNotTreatedAsExpectedStatement() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        // G34 重试耗尽转 MANUAL_REVIEW 时 funds_received 回写 NULL：不进差集，但必须有 REVIEW 差异。
        PaymentEvent manual = successEvent("SN-MR", new BigDecimal("20.00"));
        manual.setId(14L);
        manual.setChannel("ALIPAY");
        manual.setEventStatus("MANUAL_REVIEW");
        manual.setFundsReceived(null);
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(manual));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals("", result.get("incompleteChannels"));
        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_REVIEW, captor.getValue().getDiffType());
        assertTrue(captor.getValue().getDetail().contains("MANUAL_REVIEW"));
    }

    @Test
    void successEventWithoutFundsAnnotationStillProducesReview() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        // SUCCESS 但 funds_received 漏写为 NULL：不进差集，但必须落 REVIEW，且 retry 不应自动关闭。
        PaymentEvent event = successEvent("SN-FN", new BigDecimal("100.00"));
        event.setId(15L);
        event.setFundsReceived(null);
        TradeOrder order = paidOrder("SN-FN", BigDecimal.ZERO);
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(event));
        when(orderMapper.findByOrderSn("SN-FN")).thenReturn(Optional.of(order));
        when(statementMapper.findByChannelAndTradeNo("WECHAT", "WX-EVENT")).thenReturn(Optional.empty());
        when(statementMapper.countByMerchantChannelPeriod(1L, "WECHAT", from, to)).thenReturn(0);
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals("", result.get("incompleteChannels"));
        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PAYMENT_EVENT_REVIEW, captor.getValue().getDiffType());
        assertTrue(captor.getValue().getDetail().contains("funds_received IS NULL"));
    }

    @Test
    void nonStatementUnknownChannelDoesNotMarkChannelIncomplete() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        // 结果未确认或渠道未知的事件天然不能证明“本地已收款”，不进入缺失渠道差集。
        PaymentEvent merchantMismatch = successEvent("SN-MM", new BigDecimal("10.00"));
        merchantMismatch.setId(8L);
        merchantMismatch.setChannel("");
        merchantMismatch.setEventStatus("MERCHANT_MISMATCH");
        PaymentEvent unknown = successEvent("SN-UK2", new BigDecimal("10.00"));
        unknown.setId(9L);
        unknown.setChannel("ALIPAY");
        unknown.setEventStatus("ORDER_STATUS_INVALID");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(merchantMismatch, unknown));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        // 空串渠道与未知状态不计入差集；但两者仍分别生成 PAYMENT_EVENT_REJECTED / REVIEW 差异，不静默。
        assertEquals("", result.get("incompleteChannels"));
        assertEquals(2, result.get("added"));
    }

    @Test
    void crossWindowMatchedRefundChannelIsNotIncomplete() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        TradeRefund refund = successfulRefund("RF-5", "WX-R5", new BigDecimal("50.00"), BigDecimal.ZERO);
        PaymentChannelStatement adjacent = statement("WECHAT", "WX-R5", "SN-R1", new BigDecimal("50.00"));
        adjacent.setId(9L);
        adjacent.setTradeStatus("REFUND");
        adjacent.setTradeTime(LocalDateTime.of(2026, 10, 31, 23, 0));
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(refundMapper.findSuccessfulRefundsByPeriod(1L, from, to)).thenReturn(List.of(refund));
        when(statementMapper.findByChannelAndTradeNo("WECHAT", "WX-R5"))
                .thenReturn(Optional.of(adjacent));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        // 流水落在相邻业务日但已匹配：该渠道已导入，不能留下永久 PARTIAL。
        assertEquals("", result.get("incompleteChannels"));
    }

    @Test
    void dailyWindowDoesNotValidateMonthlySettlementBatchTotals() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 5, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 6, 0, 0);
        MerchantSettlementBatch batch = new MerchantSettlementBatch();
        batch.setId(11L);
        batch.setMerchantId(1L);
        batch.setStatus("CONFIRMED");
        // 真实月结批次账期 10-01~11-01（次月 1 日），与 T+1 单日窗口 10-05~10-06 不一致，
        // 不得把月结合计盘成单日差异。
        batch.setPeriodStart(LocalDate.of(2026, 10, 1));
        batch.setPeriodEnd(LocalDate.of(2026, 11, 1));
        batch.setFeeAmount(new BigDecimal("4.00"));
        batch.setAdjustmentAmount(BigDecimal.ZERO);
        batch.setNetFeeAmount(new BigDecimal("5.00"));
        MerchantSettlementItem item = new MerchantSettlementItem();
        item.setItemType("ORDER_FEE");
        item.setFeeAmount(new BigDecimal("4.00"));
        item.setNetFee(new BigDecimal("4.00"));
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(batchMapper.findConfirmedByPeriod(1L, from.toLocalDate(), to.toLocalDate()))
                .thenReturn(List.of(batch));
        when(itemMapper.findByBatchId(11L)).thenReturn(List.of(item));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals(0, result.get("added"));
        verify(differenceMapper, never()).insert(any(ReconciliationDifference.class));
    }

    @Test
    void differenceKeyIsIdempotentForOpenRecord() {
        ReconciliationDifference existing = new ReconciliationDifference();
        existing.setId(5L);
        existing.setStatus("DISPUTED");
        when(statementMapper.findByMerchantAndPeriod(eq(1L), any(), any())).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(eq(1L), any(), any())).thenReturn(List.of());
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.of(existing));

        service().reconcile(1L, LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 11, 1, 0, 0));

        verify(differenceMapper, never()).insert(any(ReconciliationDifference.class));
    }

    @Test
    void retryResolvesFeeLedgerDifferenceWhenLedgerAppears() {
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setId(6L);
        difference.setDiffType(ReconciliationService.FEE_LEDGER_MISSING);
        difference.setOrderSn("SN-X5");
        difference.setStatus("DISPUTED");
        when(differenceMapper.selectById(6L)).thenReturn(difference);
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-X5"))
                .thenReturn(Optional.of(new MerchantAccountLedger()));

        ReconciliationDifference result = service().retry(6L, 3L);

        assertEquals("RESOLVED", result.getStatus());
        assertEquals(1, result.getRetryCount());
        assertEquals(3L, result.getHandledBy());
        assertNotNull(result.getHandledAt());
        verify(differenceMapper).updateById(difference);
    }

    @Test
    void retryKeepsDifferenceOpenWhenEvidenceStillMissing() {
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setId(7L);
        difference.setDiffType(ReconciliationService.FEE_LEDGER_MISSING);
        difference.setOrderSn("SN-X6");
        difference.setStatus("DISPUTED");
        when(differenceMapper.selectById(7L)).thenReturn(difference);
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-X6")).thenReturn(Optional.empty());

        ReconciliationDifference result = service().retry(7L, 3L);

        assertEquals("RETRY", result.getStatus());
        verify(differenceMapper).updateById(difference);
    }

    @Test
    void retryEscalatesToManualAfterMaxAttempts() {
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setId(9L);
        difference.setDiffType(ReconciliationService.FEE_LEDGER_MISSING);
        difference.setOrderSn("SN-X7");
        difference.setStatus("RETRY");
        difference.setRetryCount(ReconciliationService.MAX_RETRY_COUNT - 1);
        when(differenceMapper.selectById(9L)).thenReturn(difference);
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-X7")).thenReturn(Optional.empty());

        ReconciliationDifference result = service().retry(9L, 3L);

        assertEquals("MANUAL", result.getStatus());
        assertEquals(ReconciliationService.MAX_RETRY_COUNT, result.getRetryCount());
        assertTrue(result.getHandleNote().contains("转人工"));
        assertEquals(3L, result.getHandledBy());
        assertNotNull(result.getHandledAt());
        verify(differenceMapper).updateById(difference);
        ArgumentCaptor<ReconciliationDifferenceAudit> audit =
                ArgumentCaptor.forClass(ReconciliationDifferenceAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("ESCALATE", audit.getValue().getAction());
        assertEquals("RETRY", audit.getValue().getFromStatus());
        assertEquals("MANUAL", audit.getValue().getToStatus());
    }

    @Test
    void retryOnManualDifferenceIsRejected() {
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setId(10L);
        difference.setStatus("MANUAL");
        difference.setRetryCount(ReconciliationService.MAX_RETRY_COUNT);
        when(differenceMapper.selectById(10L)).thenReturn(difference);

        BusinessException ex = assertThrows(BusinessException.class, () -> service().retry(10L, 3L));

        assertEquals(409, ex.getCode());
        verify(differenceMapper, never()).updateById(any(ReconciliationDifference.class));
    }

    @Test
    void retryResolvesPaymentEventReviewOnlyWhenEventSuccessAndFundsAnnotated() {
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setId(20L);
        difference.setDiffType(ReconciliationService.PAYMENT_EVENT_REVIEW);
        difference.setBizId(101L);
        difference.setStatus("DISPUTED");
        PaymentEvent event = new PaymentEvent();
        event.setId(101L);
        event.setEventStatus("SUCCESS");
        event.setFundsReceived(1);
        when(differenceMapper.selectById(20L)).thenReturn(difference);
        when(eventMapper.selectById(101L)).thenReturn(event);

        ReconciliationDifference result = service().retry(20L, 3L);

        assertEquals("RESOLVED", result.getStatus());
        assertTrue(result.getHandleNote().contains("自动关闭"));
        verify(differenceMapper).updateById(difference);
    }

    @Test
    void retryKeepsPaymentEventReviewOpenWhenFundsStillUnmarked() {
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setId(21L);
        difference.setDiffType(ReconciliationService.PAYMENT_EVENT_REVIEW);
        difference.setBizId(102L);
        difference.setStatus("DISPUTED");
        PaymentEvent event = new PaymentEvent();
        event.setId(102L);
        event.setEventStatus("SUCCESS");
        // 事件已成功但资金仍为 NULL：REVIEW 不能自动关闭，必须继续人工确认到账。
        event.setFundsReceived(null);
        when(differenceMapper.selectById(21L)).thenReturn(difference);
        when(eventMapper.selectById(102L)).thenReturn(event);

        ReconciliationDifference result = service().retry(21L, 3L);

        assertEquals("RETRY", result.getStatus());
        assertEquals(1, result.getRetryCount());
        verify(differenceMapper).updateById(difference);
    }

    @Test
    void retryKeepsPaymentEventReviewOpenWhenEventNotSuccess() {
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setId(22L);
        difference.setDiffType(ReconciliationService.PAYMENT_EVENT_REVIEW);
        difference.setBizId(103L);
        difference.setStatus("DISPUTED");
        PaymentEvent event = new PaymentEvent();
        event.setId(103L);
        event.setEventStatus("FULFILLMENT_FAILED");
        event.setFundsReceived(1);
        when(differenceMapper.selectById(22L)).thenReturn(difference);
        when(eventMapper.selectById(103L)).thenReturn(event);

        ReconciliationDifference result = service().retry(22L, 3L);

        assertEquals("RETRY", result.getStatus());
        verify(differenceMapper).updateById(difference);
    }

    @Test
    void retryResolvesPaymentEventRejectedOnlyWhenEventSuccess() {
        ReconciliationDifference resolvedCase = new ReconciliationDifference();
        resolvedCase.setId(23L);
        resolvedCase.setDiffType(ReconciliationService.PAYMENT_EVENT_REJECTED);
        resolvedCase.setBizId(104L);
        resolvedCase.setStatus("DISPUTED");
        PaymentEvent success = new PaymentEvent();
        success.setId(104L);
        success.setEventStatus("SUCCESS");
        when(differenceMapper.selectById(23L)).thenReturn(resolvedCase);
        when(eventMapper.selectById(104L)).thenReturn(success);

        ReconciliationDifference result = service().retry(23L, 3L);

        assertEquals("RESOLVED", result.getStatus());
        verify(differenceMapper).updateById(resolvedCase);

        ReconciliationDifference openCase = new ReconciliationDifference();
        openCase.setId(24L);
        openCase.setDiffType(ReconciliationService.PAYMENT_EVENT_REJECTED);
        openCase.setBizId(105L);
        openCase.setStatus("DISPUTED");
        PaymentEvent rejected = new PaymentEvent();
        rejected.setId(105L);
        rejected.setEventStatus("AMOUNT_MISMATCH");
        rejected.setFundsReceived(1);
        when(differenceMapper.selectById(24L)).thenReturn(openCase);
        when(eventMapper.selectById(105L)).thenReturn(rejected);

        ReconciliationDifference stillOpen = service().retry(24L, 3L);

        assertEquals("RETRY", stillOpen.getStatus());
        verify(differenceMapper).updateById(openCase);
    }

    @Test
    void retryResolvesRefundedAndConflictDifferencesWhenEventSuccess() {
        ReconciliationDifference refundedDiff = new ReconciliationDifference();
        refundedDiff.setId(25L);
        refundedDiff.setDiffType(ReconciliationService.PAYMENT_EVENT_REFUNDED);
        refundedDiff.setBizId(106L);
        refundedDiff.setStatus("DISPUTED");
        ReconciliationDifference conflictDiff = new ReconciliationDifference();
        conflictDiff.setId(26L);
        conflictDiff.setDiffType(ReconciliationService.PAYMENT_EVENT_CHANNEL_CONFLICT);
        conflictDiff.setBizId(107L);
        conflictDiff.setStatus("DISPUTED");
        PaymentEvent success = new PaymentEvent();
        success.setId(106L);
        success.setEventStatus("SUCCESS");
        success.setFundsReceived(1);
        PaymentEvent successSecond = new PaymentEvent();
        successSecond.setId(107L);
        successSecond.setEventStatus("SUCCESS");
        successSecond.setFundsReceived(1);
        when(differenceMapper.selectById(25L)).thenReturn(refundedDiff);
        when(differenceMapper.selectById(26L)).thenReturn(conflictDiff);
        when(eventMapper.selectById(106L)).thenReturn(success);
        when(eventMapper.selectById(107L)).thenReturn(successSecond);

        assertEquals("RESOLVED", service().retry(25L, 3L).getStatus());
        assertEquals("RESOLVED", service().retry(26L, 3L).getStatus());
        verify(differenceMapper).updateById(refundedDiff);
        verify(differenceMapper).updateById(conflictDiff);
    }

    @Test
    void retryKeepsRefundedAndConflictDifferencesOpenWhenEventNotSuccess() {
        ReconciliationDifference refundedDiff = new ReconciliationDifference();
        refundedDiff.setId(27L);
        refundedDiff.setDiffType(ReconciliationService.PAYMENT_EVENT_REFUNDED);
        refundedDiff.setBizId(108L);
        refundedDiff.setStatus("DISPUTED");
        ReconciliationDifference conflictDiff = new ReconciliationDifference();
        conflictDiff.setId(28L);
        conflictDiff.setDiffType(ReconciliationService.PAYMENT_EVENT_CHANNEL_CONFLICT);
        conflictDiff.setBizId(109L);
        conflictDiff.setStatus("DISPUTED");
        PaymentEvent notSuccess = new PaymentEvent();
        notSuccess.setId(108L);
        notSuccess.setEventStatus("FULFILLMENT_FAILED");
        notSuccess.setFundsReceived(1);
        PaymentEvent notSuccessSecond = new PaymentEvent();
        notSuccessSecond.setId(109L);
        notSuccessSecond.setEventStatus("FULFILLMENT_FAILED");
        notSuccessSecond.setFundsReceived(1);
        when(differenceMapper.selectById(27L)).thenReturn(refundedDiff);
        when(differenceMapper.selectById(28L)).thenReturn(conflictDiff);
        when(eventMapper.selectById(108L)).thenReturn(notSuccess);
        when(eventMapper.selectById(109L)).thenReturn(notSuccessSecond);

        assertEquals("RETRY", service().retry(27L, 3L).getStatus());
        assertEquals("RETRY", service().retry(28L, 3L).getStatus());
        verify(differenceMapper).updateById(refundedDiff);
        verify(differenceMapper).updateById(conflictDiff);
    }

    @Test
    void resolveRequiresNoteAndRecordsOperator() {
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setId(8L);
        when(differenceMapper.selectById(8L)).thenReturn(difference);

        assertThrows(BusinessException.class, () -> service().resolve(8L, 3L, "  ", false));

        ReconciliationDifference result = service().resolve(8L, 3L, "渠道已确认，人工平衡", true);
        assertEquals("IGNORED", result.getStatus());
        assertEquals(3L, result.getHandledBy());
        assertEquals("渠道已确认，人工平衡", result.getHandleNote());
        verify(differenceMapper).updateById(difference);
        ArgumentCaptor<ReconciliationDifferenceAudit> audit =
                ArgumentCaptor.forClass(ReconciliationDifferenceAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("IGNORE", audit.getValue().getAction());
        assertEquals(3L, audit.getValue().getOperatorId());
    }

    @Test
    void settlementCsvEscapesFormulaAndQuotes() {
        MerchantSettlementBatch batch = new MerchantSettlementBatch();
        batch.setId(10L);
        batch.setMerchantId(1L);
        MerchantSettlementItem item = new MerchantSettlementItem();
        item.setItemType("ORDER_FEE");
        item.setOrderSn("=1+2");
        item.setRefundNo("RF,\"1\"");
        item.setBillableBase(new BigDecimal("100.00"));
        item.setFeeRateBps(300);
        item.setFeeAmount(new BigDecimal("3.00"));
        item.setFeeRefundedAmount(BigDecimal.ZERO);
        item.setNetFee(new BigDecimal("3.00"));
        when(batchMapper.selectById(10L)).thenReturn(batch);
        when(itemMapper.search(eq(10L), any(), any(), any(), any())).thenReturn(List.of(item));

        String csv = service().settlementCsv(10L, 1L, null, null, null, null);

        assertTrue(csv.startsWith("item_type,order_sn,goods_type"));
        assertTrue(csv.contains(",'=1+2,"));
        assertTrue(csv.contains("\"RF,\"\"1\"\"\""));
    }

    @Test
    void settlementCsvRejectsCrossMerchantBatch() {
        MerchantSettlementBatch batch = new MerchantSettlementBatch();
        batch.setId(10L);
        batch.setMerchantId(2L);
        when(batchMapper.selectById(10L)).thenReturn(batch);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().settlementCsv(10L, 1L, null, null, null, null));
        assertEquals(404, ex.getCode());
        verify(itemMapper, never()).search(any(), any(), any(), any(), any());
    }

    @Test
    void ledgerCsvContainsLedgerHeaderAndEscapedRemark() {
        MerchantAccountLedger entry = new MerchantAccountLedger();
        entry.setEntryType("SERVICE_FEE_CHARGE");
        entry.setDirection("DEBIT");
        entry.setAmount(new BigDecimal("3.00"));
        entry.setBalanceAfter(new BigDecimal("97.00"));
        entry.setArrearsAfter(BigDecimal.ZERO);
        entry.setBizType("ORDER");
        entry.setOrderSn("SN-X7");
        entry.setRemark("备注,含逗号");
        when(ledgerMapper.findByMerchantAndPeriod(eq(1L), any(), any())).thenReturn(List.of(entry));

        String csv = service().ledgerCsv(1L, LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 11, 1, 0, 0));

        assertTrue(csv.startsWith("created_at,entry_type,direction,amount"));
        assertTrue(csv.contains("\"备注,含逗号\""));
    }

    @Test
    void importStatementsSkipsDuplicateChannelTradeNo() {
        PaymentChannelStatement source = statement("WECHAT", "WX-8", "SN-X8", new BigDecimal("10.00"));
        when(statementMapper.findByChannelAndTradeNo("WECHAT", "WX-8"))
                .thenReturn(Optional.of(new PaymentChannelStatement()));

        Map<String, Object> result = service().importStatements(List.of(source), 3L);

        assertEquals(0, result.get("imported"));
        assertEquals(1, result.get("skipped"));
        verify(statementMapper, never()).insert(any(PaymentChannelStatement.class));
    }

    @Test
    void reconcileFlagsRefundWithoutChannelStatementOnlyWhenChannelHasStatements() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        TradeRefund refund = successfulRefund("RF-1", "WX-R1", new BigDecimal("50.00"), BigDecimal.ZERO);
        // 该渠道当日已导入流水（非退款状态），门禁通过后才允许判退款单边账。
        PaymentChannelStatement gate = statement("WECHAT", "WX-OTHER", "SN-OTHER", new BigDecimal("10.00"));
        gate.setTradeStatus("FAILED");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(gate));
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(refundMapper.findSuccessfulRefundsByPeriod(1L, from, to)).thenReturn(List.of(refund));
        when(statementMapper.findByChannelAndTradeNo("WECHAT", "WX-R1")).thenReturn(Optional.empty());
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.REFUND_WITHOUT_STATEMENT, captor.getValue().getDiffType());
        assertEquals(ReconciliationClassifier.ONE_SIDED_LOCAL, captor.getValue().getDiffCategory());
    }

    @Test
    void zeroChannelStatementsDoesNotCreateAnyRefundSideDifference() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        // 无渠道/无渠道退款号（REF|id|NO）与服务费冲正（REFFEE）也必须受流水门禁约束。
        TradeRefund noChannel = successfulRefund("RF-2", null, new BigDecimal("50.00"),
                new BigDecimal("2.00"));
        noChannel.setChannel(null);
        TradeRefund channelNoStatement = successfulRefund("RF-3", "WX-R2", new BigDecimal("50.00"),
                new BigDecimal("3.00"));
        channelNoStatement.setId(41L);
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(refundMapper.findSuccessfulRefundsByPeriod(1L, from, to))
                .thenReturn(List.of(noChannel, channelNoStatement));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals(0, result.get("added"));
        verify(differenceMapper, never()).insert(any(ReconciliationDifference.class));
    }

    @Test
    void partiallyImportedMultiChannelLocalDocsAreReportedIncomplete() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        // WECHAT 当日有流水，ALIPAY 只有本地支付事件、没有导入流水。
        PaymentChannelStatement gate = statement("WECHAT", "WX-OTHER", "SN-OTHER", new BigDecimal("10.00"));
        gate.setTradeStatus("FAILED");
        PaymentEvent alipayEvent = successEvent("SN-AP", new BigDecimal("20.00"));
        alipayEvent.setId(2L);
        alipayEvent.setChannel("ALIPAY");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(gate));
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(alipayEvent));
        when(statementMapper.countByMerchantChannelPeriod(1L, "ALIPAY", from, to)).thenReturn(0);
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        // 总流水数不为 0，但 ALIPAY 必须被标记为未导入，跑批据此转 PARTIAL 而不是静默成功。
        assertEquals("ALIPAY", result.get("incompleteChannels"));
    }

    @Test
    void crossDayRefundUsesChannelTradeDateAndRefreshKeepsSettledBusinessDate() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 4, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 6, 0, 0);
        TradeRefund refund = successfulRefund("RF-3", "WX-R3", new BigDecimal("50.00"), BigDecimal.ZERO);
        refund.setSuccessAt(LocalDateTime.of(2026, 11, 4, 23, 30));
        PaymentChannelStatement channelRefund = statement("WECHAT", "WX-R3", "SN-R1",
                new BigDecimal("50.00"));
        channelRefund.setTradeStatus("REFUND");
        channelRefund.setTradeTime(LocalDateTime.of(2026, 11, 5, 0, 10));
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(channelRefund));
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(refundMapper.findSuccessfulRefundsByPeriod(1L, from, to)).thenReturn(List.of(refund));
        when(statementMapper.findByChannelAndTradeNo("WECHAT", "WX-R3"))
                .thenReturn(Optional.of(channelRefund));
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.TIME_CROSS_DAY, captor.getValue().getDiffType());
        // 归属日优先渠道 trade_time（11-05），不是本地 success_at（11-04）。
        assertEquals(LocalDate.of(2026, 11, 5), captor.getValue().getBusinessDate());
    }

    @Test
    void refreshingOpenDifferenceDoesNotOverwriteEstablishedBusinessDate() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        TradeRefund refund = successfulRefund("RF-4", "WX-R4", new BigDecimal("50.00"), BigDecimal.ZERO);
        PaymentChannelStatement gate = statement("WECHAT", "WX-OTHER", "SN-OTHER", new BigDecimal("10.00"));
        gate.setTradeStatus("FAILED");
        ReconciliationDifference existing = new ReconciliationDifference();
        existing.setId(60L);
        existing.setDifferenceKey("REF|40|ST");
        existing.setStatus("DISPUTED");
        existing.setBusinessDate(LocalDate.of(2026, 11, 5));
        existing.setExpectedAmount(new BigDecimal("50.00"));
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(gate));
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(refundMapper.findSuccessfulRefundsByPeriod(1L, from, to)).thenReturn(List.of(refund));
        when(statementMapper.findByChannelAndTradeNo("WECHAT", "WX-R4")).thenReturn(Optional.empty());
        when(differenceMapper.findByKey("REF|40|ST")).thenReturn(Optional.of(existing));

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals(1, result.get("refreshed"));
        assertEquals(LocalDate.of(2026, 11, 5), existing.getBusinessDate());
        verify(differenceMapper).updateById(existing);
        verify(differenceMapper, never()).insert(any(ReconciliationDifference.class));
    }

    @Test
    void reconcileFlagsFeeAmountMismatchInsteadOfOnlyMissingLedger() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PaymentEvent event = successEvent("SN-X9", new BigDecimal("100.00"));
        TradeOrder order = paidOrder("SN-X9", new BigDecimal("3.00"));
        MerchantAccountLedger charge = ledger("ORDER_FEE:SN-X9", new BigDecimal("2.00"));
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(event));
        when(orderMapper.findByOrderSn("SN-X9")).thenReturn(Optional.of(order));
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-X9")).thenReturn(Optional.of(charge));
        when(statementMapper.findByChannelAndTradeNo("WECHAT", "WX-EVENT")).thenReturn(Optional.empty());
        when(statementMapper.countByMerchantChannelPeriod(1L, "WECHAT", from, to)).thenReturn(0);
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.FEE_AMOUNT_MISMATCH, captor.getValue().getDiffType());
        // 应收 3.00、账本 2.00，实际少 1.00 → 短款口径。
        assertEquals(ReconciliationClassifier.SHORT, captor.getValue().getDiffCategory());
        assertEquals(new BigDecimal("3.00"), captor.getValue().getExpectedAmount());
        assertEquals(new BigDecimal("2.00"), captor.getValue().getActualAmount());
    }

    @Test
    void reconcileFlagsPlatformSettlementMismatch() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 1, 0, 0);
        PlatformCollectionSettlement settlement = new PlatformCollectionSettlement();
        settlement.setId(30L);
        settlement.setMerchantId(1L);
        settlement.setOrderSn("SN-P1");
        settlement.setPaymentEventId(5L);
        settlement.setGrossAmount(new BigDecimal("100.00"));
        PaymentEvent event = successEvent("SN-P1", new BigDecimal("99.00"));
        event.setId(5L);
        event.setPayTradeNo("PT-1");
        when(statementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(eventMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of());
        when(platformSettlementMapper.findByMerchantAndPeriod(1L, from, to)).thenReturn(List.of(settlement));
        when(eventMapper.selectById(5L)).thenReturn(event);
        when(differenceMapper.findByKey(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service().reconcile(1L, from, to);

        assertEquals(1, result.get("added"));
        ArgumentCaptor<ReconciliationDifference> captor =
                ArgumentCaptor.forClass(ReconciliationDifference.class);
        verify(differenceMapper).insert(captor.capture());
        assertEquals(ReconciliationService.PLATFORM_SETTLEMENT_MISMATCH, captor.getValue().getDiffType());
        assertEquals(ReconciliationClassifier.LONG, captor.getValue().getDiffCategory());
    }

    @Test
    void retryOnSameBusinessDateDoesNotDoubleCount() {
        LocalDate businessDate = LocalDate.of(2026, 11, 5);
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setId(11L);
        difference.setDiffType(ReconciliationService.FEE_LEDGER_MISSING);
        difference.setOrderSn("SN-X8");
        difference.setStatus("RETRY");
        difference.setRetryCount(1);
        difference.setLastRetryDate(businessDate);
        when(differenceMapper.selectById(11L)).thenReturn(difference);

        ReconciliationDifference result = service().retry(11L, 3L, businessDate);

        assertEquals(1, result.getRetryCount());
        assertEquals(businessDate, result.getLastRetryDate());
        verify(differenceMapper, never()).updateById(any(ReconciliationDifference.class));
        verify(auditMapper, never()).insert(any(ReconciliationDifferenceAudit.class));
    }

    private TradeRefund successfulRefund(String refundNo, String channelRefundNo, BigDecimal amount,
                                         BigDecimal feeRefund) {
        TradeRefund refund = new TradeRefund();
        refund.setId(40L);
        refund.setRefundNo(refundNo);
        refund.setOrderSn("SN-R1");
        refund.setMerchantId(1L);
        refund.setChannel("WECHAT");
        refund.setChannelRefundNo(channelRefundNo);
        refund.setSource("ORDER");
        refund.setRefundAmount(amount);
        refund.setFeeRefundAmount(feeRefund);
        refund.setStatus("SUCCESS");
        // updated_at 会被后续同步改写，测试故意设成不同日期，确保对账只用 success_at。
        refund.setSuccessAt(LocalDateTime.of(2026, 10, 5, 10, 0));
        refund.setUpdatedAt(LocalDateTime.of(2026, 11, 20, 10, 0));
        return refund;
    }

    private MerchantAccountLedger ledger(String idempotencyKey, BigDecimal amount) {
        MerchantAccountLedger entry = new MerchantAccountLedger();
        entry.setId(50L);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setAmount(amount);
        return entry;
    }

    private PaymentChannelStatement statement(String channel, String tradeNo, String orderSn, BigDecimal amount) {
        PaymentChannelStatement statement = new PaymentChannelStatement();
        statement.setId(1L);
        statement.setChannel(channel);
        statement.setChannelTradeNo(tradeNo);
        statement.setOrderSn(orderSn);
        statement.setMerchantId(1L);
        statement.setAmount(amount);
        statement.setTradeStatus("SUCCESS");
        return statement;
    }

    private PaymentEvent successEvent(String orderSn, BigDecimal amount) {
        PaymentEvent event = new PaymentEvent();
        event.setId(1L);
        event.setChannel("WECHAT");
        event.setPayTradeNo("WX-EVENT");
        event.setOrderSn(orderSn);
        event.setMerchantId(1L);
        event.setPayAmount(amount);
        event.setEventStatus("SUCCESS");
        // 默认按“渠道侧存在资金往来（占位即 1，应有对应流水）”构造；明确未支付/未标注的用例自行改回 0/null。
        event.setFundsReceived(1);
        return event;
    }

    private TradeOrder paidOrder(String orderSn, BigDecimal fee) {
        TradeOrder order = new TradeOrder();
        order.setId(20L);
        order.setOrderSn(orderSn);
        order.setMerchantId(1L);
        order.setPayAmount(new BigDecimal("100.00"));
        order.setFeeAmount(fee);
        order.setOrderStatus("PAID");
        return order;
    }
}
