/*
 * 文件：RefundRequestServiceTest.java
 * 用途：F05 退款申请服务单测：申请校验、可退余额、审批同意/拒绝、买家撤回、越权拒绝。
 * 维护注意：Mockito 隔离，不启动 Spring；可退余额计算、状态流转、身份凭证校验均在此覆盖。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.RefundRequestDTOs;
import com.shop.b2b2c.entity.RefundRequest;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.RefundRequestMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import com.shop.b2b2c.service.RefundRequestService;
import com.shop.b2b2c.service.payment.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundRequestServiceTest {

    @Mock RefundRequestMapper requests;
    @Mock TradeOrderMapper orders;
    @Mock TradeRefundMapper refunds;
    @Mock RefundService refundService;
    @InjectMocks RefundRequestService service;

    private TradeOrder order;

    @BeforeEach
    void setUp() {
        order = new TradeOrder();
        order.setId(100L);
        order.setOrderSn("ORD-1");
        order.setMerchantId(7L);
        order.setBuyerContact("13800138000");
        order.setQueryToken("token-123");
        order.setOrderStatus("PAID");
        order.setPayAmount(new BigDecimal("100.00"));
    }

    @Test
    void applyCreatesPendingRequest() {
        when(orders.findByOrderSnAndBuyerContact("ORD-1", "13800138000")).thenReturn(Optional.of(order));
        when(refunds.selectList(any())).thenReturn(List.of());
        when(requests.selectCount(any())).thenReturn(0L);

        RefundRequest rr = service.apply(new RefundRequestDTOs.BuyerApplyRequest(
                "ORD-1", "13800138000", "token-123", new BigDecimal("30.00"), "不想要了"));

        assertEquals(RefundRequest.STATUS_PENDING, rr.getStatus());
        assertEquals(new BigDecimal("30.00"), rr.getRefundAmount());
        assertEquals("不想要了", rr.getReason());
        verify(requests).insert(any(RefundRequest.class));
    }

    @Test
    void applyRejectsAmountOverRefundable() {
        TradeRefund existing = new TradeRefund();
        existing.setRefundAmount(new BigDecimal("80.00"));
        existing.setStatus("PROCESSING");
        when(orders.findByOrderSnAndBuyerContact("ORD-1", "13800138000")).thenReturn(Optional.of(order));
        when(refunds.selectList(any())).thenReturn(List.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.apply(new RefundRequestDTOs.BuyerApplyRequest(
                        "ORD-1", "13800138000", "token-123", new BigDecimal("30.00"), "x")));
        assertEquals(400, ex.getCode());
    }

    @Test
    void applyRejectsNonRefundableStatus() {
        order.setOrderStatus("UNPAID");
        when(orders.findByOrderSnAndBuyerContact("ORD-1", "13800138000")).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.apply(new RefundRequestDTOs.BuyerApplyRequest(
                        "ORD-1", "13800138000", "token-123", new BigDecimal("10.00"), "x")));
        assertEquals(409, ex.getCode());
    }

    @Test
    void applyRejectsDuplicatePending() {
        when(orders.findByOrderSnAndBuyerContact("ORD-1", "13800138000")).thenReturn(Optional.of(order));
        when(refunds.selectList(any())).thenReturn(List.of());
        when(requests.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.apply(new RefundRequestDTOs.BuyerApplyRequest(
                        "ORD-1", "13800138000", "token-123", new BigDecimal("10.00"), "x")));
        assertEquals(409, ex.getCode());
    }

    @Test
    void approveExecutesRefundAndMarksApproved() {
        RefundRequest rr = pendingRequest();
        when(requests.selectById(1L)).thenReturn(rr);
        when(orders.selectById(100L)).thenReturn(order);
        when(refunds.selectList(any())).thenReturn(List.of());

        service.approve(1L, 7L, 200L, "MERCHANT_ADMIN");

        assertEquals(RefundRequest.STATUS_APPROVED, rr.getStatus());
        verify(refundService).applyOrderRefund(eq(7L), eq("ORD-1"), any(),
                eq("APPROVE-" + String.format("%032d", 1L)), eq(200L));
        verify(requests).updateById(rr);
    }

    @Test
    void rejectMarksRejectedWithReason() {
        RefundRequest rr = pendingRequest();
        when(requests.selectById(1L)).thenReturn(rr);

        service.reject(1L, 7L, 200L, "MERCHANT_ADMIN", "商品已发货");

        assertEquals(RefundRequest.STATUS_REJECTED, rr.getStatus());
        assertEquals("商品已发货", rr.getRejectReason());
        verify(refundService, never()).applyOrderRefund(any(), any(), any(), any(), any());
    }

    @Test
    void approveByOtherMerchantForbidden() {
        RefundRequest rr = pendingRequest();
        when(requests.selectById(1L)).thenReturn(rr);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.approve(1L, 8L, 200L, "MERCHANT_ADMIN"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void cancelBuyerPendingRequest() {
        RefundRequest rr = pendingRequest();
        when(requests.selectById(1L)).thenReturn(rr);
        when(orders.selectById(100L)).thenReturn(order);

        service.cancel(1L, "ORD-1", "13800138000", "token-123");

        assertEquals(RefundRequest.STATUS_CANCELLED, rr.getStatus());
    }

    private RefundRequest pendingRequest() {
        RefundRequest rr = new RefundRequest();
        rr.setId(1L);
        rr.setOrderId(100L);
        rr.setOrderSn("ORD-1");
        rr.setMerchantId(7L);
        rr.setBuyerContact("13800138000");
        rr.setRefundAmount(new BigDecimal("30.00"));
        rr.setReason("不想要了");
        rr.setStatus(RefundRequest.STATUS_PENDING);
        return rr;
    }
}
