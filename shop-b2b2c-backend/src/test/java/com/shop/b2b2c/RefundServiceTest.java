/*
 * 文件：RefundServiceTest.java
 * 用途：T33 退款/重复付款补偿契约用例：超退拒绝、幂等复用、服务费比例冲正、渠道失败释放、PROCESSING 不置退款、重复付款不占订单额度。
 * 维护注意：
 *  1. 全部使用 Mockito 隔离，不启动 Spring、不连接数据库、不调用真实渠道。
 *  2. 只验证服务层状态机与金额口径；真实 MySQL 事务、并发行锁与渠道联调仍需用户手动执行。
 *  3. 修改退款上限、费用冲正公式或状态迁移条件时必须同步更新本用例。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
import com.shop.b2b2c.dto.RefundDTOs;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeOrderItem;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.MerchantPaymentAccountMapper;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.mapper.ProductSkuMapper;
import com.shop.b2b2c.mapper.ProductVirtualCardMapper;
import com.shop.b2b2c.mapper.TradeOrderItemMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import com.shop.b2b2c.service.payment.PaymentChannelAdapter;
import com.shop.b2b2c.service.payment.PaymentChannelRegistry;
import com.shop.b2b2c.service.payment.RefundService;
import com.shop.b2b2c.service.payment.RefundTransactionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundServiceTest {

    private static final String KEY = "refund-key-0000001";
    private static final Long MERCHANT_ID = 1L;

    private final TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
    private final TradeOrderItemMapper itemMapper = mock(TradeOrderItemMapper.class);
    private final TradeRefundMapper refundMapper = mock(TradeRefundMapper.class);
    private final ProductSkuMapper skuMapper = mock(ProductSkuMapper.class);
    private final PaymentEventMapper eventMapper = mock(PaymentEventMapper.class);
    private final ProductVirtualCardMapper cardMapper = mock(ProductVirtualCardMapper.class);
    private final com.shop.b2b2c.service.MerchantAccountService accountService =
            mock(com.shop.b2b2c.service.MerchantAccountService.class);

    private RefundTransactionService transactionService() {
        return new RefundTransactionService(orderMapper, itemMapper, refundMapper, skuMapper, eventMapper,
                cardMapper, accountService);
    }

    @Test
    void fullRefundReversesRemainingFeeAndCreatesProcessingRecord() {
        TradeOrder order = paidOrder();
        when(orderMapper.findByOrderSn("SN-R1")).thenReturn(Optional.of(order));
        when(orderMapper.lockById(11L)).thenReturn(order);
        when(refundMapper.findByOrderIdAndKey(11L, KEY)).thenReturn(Optional.empty());
        when(refundMapper.reserveRefund(eq(11L), eq(new BigDecimal("100.00")), eq(new BigDecimal("3.00"))))
                .thenReturn(1);

        TradeRefund refund = transactionService()
                .prepareOrderRefund(MERCHANT_ID, "SN-R1", new BigDecimal("100.00"), "买家申请", KEY, 5L);

        assertEquals(new BigDecimal("3.00"), refund.getFeeRefundAmount());
        assertEquals(RefundTransactionService.STATUS_PROCESSING, refund.getStatus());
        assertEquals(RefundTransactionService.SOURCE_ORDER, refund.getSource());
        assertEquals("WECHAT", refund.getChannel());
        assertEquals("WX-TX-1", refund.getChannelTradeNo());
        verify(refundMapper).insert(any(TradeRefund.class));
    }

    @Test
    void partialRefundReversesFeeBySnapshotRatio() {
        TradeOrder order = paidOrder();
        when(orderMapper.findByOrderSn("SN-R1")).thenReturn(Optional.of(order));
        when(orderMapper.lockById(11L)).thenReturn(order);
        when(refundMapper.findByOrderIdAndKey(11L, KEY)).thenReturn(Optional.empty());
        when(refundMapper.reserveRefund(eq(11L), eq(new BigDecimal("25.00")), eq(new BigDecimal("0.75"))))
                .thenReturn(1);

        TradeRefund refund = transactionService()
                .prepareOrderRefund(MERCHANT_ID, "SN-R1", new BigDecimal("25.00"), "部分退", KEY, 5L);

        assertEquals(new BigDecimal("0.75"), refund.getFeeRefundAmount());
        assertEquals(new BigDecimal("0.00"), refund.getFeeRoundingAdjustment());
    }

    @Test
    void finalPartialRefundAbsorbsRoundingRemainderAndRecordsOwner() {
        TradeOrder order = paidOrder();
        order.setPayAmount(new BigDecimal("3.00"));
        order.setCommissionAmount(new BigDecimal("0.10"));
        order.setRefundedAmount(new BigDecimal("2.00"));
        order.setFeeRefundedAmount(new BigDecimal("0.06"));
        when(orderMapper.findByOrderSn("SN-R1")).thenReturn(Optional.of(order));
        when(orderMapper.lockById(11L)).thenReturn(order);
        when(refundMapper.findByOrderIdAndKey(11L, KEY)).thenReturn(Optional.empty());
        when(refundMapper.reserveRefund(eq(11L), eq(new BigDecimal("1.00")), eq(new BigDecimal("0.04"))))
                .thenReturn(1);

        TradeRefund refund = transactionService()
                .prepareOrderRefund(MERCHANT_ID, "SN-R1", new BigDecimal("1.00"), "最后一次部分退", KEY, 5L);

        assertEquals(new BigDecimal("0.04"), refund.getFeeRefundAmount());
        assertEquals(new BigDecimal("0.01"), refund.getFeeRoundingAdjustment());
    }

    @Test
    void refundAboveRemainingPayableIsRejected() {
        TradeOrder order = paidOrder();
        order.setRefundedAmount(new BigDecimal("20.00"));
        when(orderMapper.findByOrderSn("SN-R1")).thenReturn(Optional.of(order));
        when(orderMapper.lockById(11L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> transactionService()
                .prepareOrderRefund(MERCHANT_ID, "SN-R1", new BigDecimal("80.01"), "超退", KEY, 5L));
        assertTrue(ex.getMessage().contains("可退余额"));
        verify(refundMapper, never()).reserveRefund(anyLong(), any(), any());
    }

    @Test
    void unpaidOrderRefundIsRejected() {
        TradeOrder order = paidOrder();
        order.setOrderStatus("UNPAID");
        when(orderMapper.findByOrderSn("SN-R1")).thenReturn(Optional.of(order));
        when(orderMapper.lockById(11L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> transactionService()
                .prepareOrderRefund(MERCHANT_ID, "SN-R1", new BigDecimal("1.00"), "未支付", KEY, 5L));
        assertTrue(ex.getMessage().contains("不允许退款"));
        verify(refundMapper, never()).reserveRefund(anyLong(), any(), any());
    }

    @Test
    void sameIdempotencyKeyReturnsExistingRecordWithoutSecondReserve() {
        TradeOrder order = paidOrder();
        TradeRefund existing = new TradeRefund();
        existing.setId(99L);
        existing.setRefundNo("RF-EXIST");
        existing.setStatus(RefundTransactionService.STATUS_SUCCESS);
        existing.setRefundAmount(new BigDecimal("10.00"));
        existing.setFeeRefundAmount(new BigDecimal("0.30"));
        when(orderMapper.findByOrderSn("SN-R1")).thenReturn(Optional.of(order));
        when(orderMapper.lockById(11L)).thenReturn(order);
        when(refundMapper.findByOrderIdAndKey(11L, KEY)).thenReturn(Optional.of(existing));

        TradeRefund result = transactionService()
                .prepareOrderRefund(MERCHANT_ID, "SN-R1", new BigDecimal("10.00"), "重复点击", KEY, 5L);

        assertEquals("RF-EXIST", result.getRefundNo());
        verify(refundMapper, never()).reserveRefund(anyLong(), any(), any());
    }

    @Test
    void fullRefundSuccessMarksOrderRefundedAndRestocksOnce() {
        TradeRefund refund = orderRefund(7L);
        TradeOrderItem item = new TradeOrderItem();
        item.setSkuId(31L);
        item.setQuantity(2);
        when(refundMapper.selectById(7L)).thenReturn(refund);
        when(refundMapper.markSuccess(eq(7L), eq("WX-RF-1"), any())).thenReturn(1);
        when(refundMapper.markOrderRefundedIfFullyRefunded(11L)).thenReturn(1);
        when(itemMapper.findByOrderId(11L)).thenReturn(List.of(item));

        transactionService().finalizeSuccess(7L, "WX-RF-1");

        verify(skuMapper).addStock(31L, MERCHANT_ID, 2);
    }

    @Test
    void alreadyFinalizedRefundDoesNotRestockAgain() {
        TradeRefund refund = orderRefund(7L);
        when(refundMapper.selectById(7L)).thenReturn(refund);
        when(refundMapper.markSuccess(eq(7L), any(), any())).thenReturn(0);

        transactionService().finalizeSuccess(7L, "WX-RF-1");

        verify(refundMapper, never()).markOrderRefundedIfFullyRefunded(anyLong());
        verify(skuMapper, never()).addStock(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(cardMapper, never()).voidSoldCardsByOrderSn(any(), any());
    }

    @Test
    void fullRefundOfVirtualCardVoidsSoldCardsAndSkipsRestock() {
        TradeOrder order = paidOrder();
        order.setGoodsType(2);
        TradeRefund refund = orderRefund(7L);
        when(refundMapper.selectById(7L)).thenReturn(refund);
        when(refundMapper.markSuccess(eq(7L), any(), any())).thenReturn(1);
        when(orderMapper.selectById(11L)).thenReturn(order);
        when(refundMapper.markOrderRefundedIfFullyRefunded(11L)).thenReturn(1);

        transactionService().finalizeSuccess(7L, "WX-RF-1");

        verify(cardMapper).voidSoldCardsByOrderSn("SN-R1", MERCHANT_ID);
        verify(skuMapper, never()).addStock(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(itemMapper, never()).findByOrderId(anyLong());
    }

    @Test
    void partialRefundOfVirtualCardDoesNotVoidCards() {
        TradeOrder order = paidOrder();
        order.setGoodsType(2);
        TradeRefund refund = orderRefund(7L);
        when(refundMapper.selectById(7L)).thenReturn(refund);
        when(refundMapper.markSuccess(eq(7L), any(), any())).thenReturn(1);
        when(orderMapper.selectById(11L)).thenReturn(order);
        when(refundMapper.markOrderRefundedIfFullyRefunded(11L)).thenReturn(0);

        transactionService().finalizeSuccess(7L, "WX-RF-1");

        verify(cardMapper, never()).voidSoldCardsByOrderSn(any(), any());
        verify(skuMapper, never()).addStock(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void failureReleasesOrderRefundReservation() {
        TradeRefund refund = orderRefund(8L);
        refund.setStatus(RefundTransactionService.STATUS_FAILED);
        when(refundMapper.selectById(8L)).thenReturn(refund);
        when(refundMapper.markFailed(eq(8L), any())).thenReturn(1);

        transactionService().finalizeFailure(8L, "渠道拒绝");

        verify(refundMapper).releaseRefundReservation(11L, refund.getRefundAmount(), refund.getFeeRefundAmount());
    }

    @Test
    void duplicatePaymentCompensationDoesNotConsumeOrderRefundQuota() {
        PaymentEvent event = duplicateEvent();
        TradeOrder order = paidOrder();
        when(eventMapper.selectById(21L)).thenReturn(event);
        when(orderMapper.findByOrderSn("SN-R1")).thenReturn(Optional.of(order));
        when(refundMapper.findBySourceEventId(21L)).thenReturn(Optional.empty());

        TradeRefund refund = transactionService()
                .prepareDuplicateCompensation(MERCHANT_ID, 21L, KEY, 5L);

        assertEquals(RefundTransactionService.SOURCE_DUPLICATE_PAYMENT, refund.getSource());
        assertEquals(new BigDecimal("100.00"), refund.getRefundAmount());
        assertEquals(new BigDecimal("0.00"), refund.getFeeRefundAmount());
        assertEquals("ALIPAY", refund.getChannel());
        assertEquals("ALI-DUP-TX", refund.getChannelTradeNo());
        verify(refundMapper, never()).reserveRefund(anyLong(), any(), any());
    }

    @Test
    void processingChannelResultKeepsRefundProcessing() {
        TradeRefund refund = orderRefund(9L);
        refund.setStatus(RefundTransactionService.STATUS_PROCESSING);
        PaymentChannelAdapter adapter = mock(PaymentChannelAdapter.class);
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
        RefundTransactionService tx = mock(RefundTransactionService.class);
        RefundService service = new RefundService(refundMapper, orderMapper, eventMapper, accounts, registry, tx);
        when(tx.prepareOrderRefund(eq(MERCHANT_ID), eq("SN-R1"), any(), any(), eq(KEY), anyLong()))
                .thenReturn(refund);
        when(registry.require("WECHAT")).thenReturn(adapter);
        when(accounts.findByMerchantIdAndChannel(MERCHANT_ID, "WECHAT"))
                .thenReturn(Optional.of(passedAccount("WECHAT", "MCH-1")));
        when(orderMapper.findByOrderSn("SN-R1")).thenReturn(Optional.of(paidOrder()));
        when(adapter.refundPayment(any())).thenReturn(PaymentChannelDTOs.RefundResult.builder()
                .channel("WECHAT").refundNo(refund.getRefundNo())
                .status("PROCESSING").refundAmount(new BigDecimal("100.00")).build());

        RefundDTOs.RefundView view = service.applyOrderRefund(MERCHANT_ID, "SN-R1",
                request("100.00", "部分退"), KEY, 5L);

        assertEquals("PROCESSING", view.getStatus());
        verify(tx, never()).finalizeSuccess(anyLong(), any());
        verify(tx, never()).finalizeFailure(anyLong(), any());
    }

    @Test
    void channelFailureFinalizesFailedBeforeThrow() {
        TradeRefund refund = orderRefund(10L);
        refund.setStatus(RefundTransactionService.STATUS_PROCESSING);
        PaymentChannelAdapter adapter = mock(PaymentChannelAdapter.class);
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
        RefundTransactionService tx = mock(RefundTransactionService.class);
        RefundService service = new RefundService(refundMapper, orderMapper, eventMapper, accounts, registry, tx);
        when(tx.prepareOrderRefund(eq(MERCHANT_ID), eq("SN-R1"), any(), any(), eq(KEY), anyLong()))
                .thenReturn(refund);
        when(registry.require("WECHAT")).thenReturn(adapter);
        when(accounts.findByMerchantIdAndChannel(MERCHANT_ID, "WECHAT"))
                .thenReturn(Optional.of(passedAccount("WECHAT", "MCH-1")));
        when(orderMapper.findByOrderSn("SN-R1")).thenReturn(Optional.of(paidOrder()));
        when(adapter.refundPayment(any())).thenThrow(new BusinessException(409, "渠道余额不足"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.applyOrderRefund(MERCHANT_ID,
                "SN-R1", request("10.00", "失败退款"), KEY, 5L));

        assertEquals("渠道余额不足", ex.getMessage());
        verify(tx).finalizeFailure(eq(10L), eq("渠道余额不足"));
    }

    @Test
    void invalidIdempotencyKeyIsRejectedBeforePrepare() {
        RefundService service = new RefundService(refundMapper, orderMapper, eventMapper,
                mock(MerchantPaymentAccountMapper.class), mock(PaymentChannelRegistry.class),
                mock(RefundTransactionService.class));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.applyOrderRefund(
                MERCHANT_ID, "SN-R1", request("1.00", "bad key"), "short", 5L));
        assertTrue(ex.getMessage().contains("Idempotency-Key"));
    }

    private TradeOrder paidOrder() {
        TradeOrder order = new TradeOrder();
        order.setId(11L);
        order.setOrderSn("SN-R1");
        order.setMerchantId(MERCHANT_ID);
        order.setPayAmount(new BigDecimal("100.00"));
        order.setCommissionAmount(new BigDecimal("3.00"));
        order.setCommissionRate(new BigDecimal("3.00"));
        order.setRefundedAmount(new BigDecimal("0.00"));
        order.setFeeRefundedAmount(new BigDecimal("0.00"));
        order.setOrderStatus("PAID");
        order.setCollectionMode(CollectionMode.MERCHANT_DIRECT);
        order.setPayChannel("WECHAT");
        order.setPayTradeNo("WX-TX-1");
        return order;
    }

    private TradeRefund orderRefund(Long id) {
        TradeRefund refund = new TradeRefund();
        refund.setId(id);
        refund.setRefundNo("RF-" + id);
        refund.setOrderId(11L);
        refund.setOrderSn("SN-R1");
        refund.setMerchantId(MERCHANT_ID);
        refund.setSource(RefundTransactionService.SOURCE_ORDER);
        refund.setChannel("WECHAT");
        refund.setChannelTradeNo("WX-TX-1");
        refund.setRefundAmount(new BigDecimal("100.00"));
        refund.setFeeRefundAmount(new BigDecimal("3.00"));
        refund.setStatus(RefundTransactionService.STATUS_PROCESSING);
        return refund;
    }

    private PaymentEvent duplicateEvent() {
        PaymentEvent event = new PaymentEvent();
        event.setId(21L);
        event.setOrderSn("SN-R1");
        event.setMerchantId(MERCHANT_ID);
        event.setChannel("ALIPAY");
        event.setPayTradeNo("ALI-DUP-TX");
        event.setPayAmount(new BigDecimal("100.00"));
        event.setEventStatus("DUPLICATE_PAYMENT");
        return event;
    }

    private RefundDTOs.RefundRequest request(String amount, String reason) {
        return new RefundDTOs.RefundRequest(new BigDecimal(amount), reason);
    }

    private MerchantPaymentAccount passedAccount(String channel, String subMchid) {
        MerchantPaymentAccount account = new MerchantPaymentAccount();
        account.setMerchantId(MERCHANT_ID);
        account.setChannel(channel);
        account.setSubMchid(subMchid);
        account.setApplyStatus("PASSED");
        return account;
    }

    @Test
    void preparedRefundHasRefundNoAndCreatedAt() {
        TradeOrder order = paidOrder();
        when(orderMapper.findByOrderSn("SN-R1")).thenReturn(Optional.of(order));
        when(orderMapper.lockById(11L)).thenReturn(order);
        when(refundMapper.findByOrderIdAndKey(11L, KEY)).thenReturn(Optional.empty());
        when(refundMapper.reserveRefund(anyLong(), any(), any())).thenReturn(1);

        TradeRefund refund = transactionService()
                .prepareOrderRefund(MERCHANT_ID, "SN-R1", new BigDecimal("1.00"), "小额", KEY, 5L);

        assertNotNull(refund.getRefundNo());
        assertNotNull(refund.getCreatedAt());
        assertTrue(refund.getRefundNo().startsWith("RF"));
    }
}
