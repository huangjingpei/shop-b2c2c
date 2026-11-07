/*
 * 文件：PaymentCloseRetryServiceTest.java
 * 用途：F08 关单回执重试的隔离规则用例：候选过滤、非关闭订单跳过、单条异常继续、参数边界。
 * 维护注意：
 *  1. 全部 Mockito 隔离，不连数据库；真实候选 SQL 与回执插入由 Sprint1SecurityIntegrationTest 的 H2 用例覆盖。
 *  2. 锁定“只重试 CLOSED/CANCELLED 订单、不改订单状态、单条失败不阻断后续”的语义；放宽前需同步台账 F08 说明。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.service.payment.PaymentCloseReceiptService;
import com.shop.b2b2c.service.payment.PaymentCloseRetryService;
import com.shop.b2b2c.service.payment.PaymentGatewayService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class PaymentCloseRetryServiceTest {

    private final PaymentCloseReceiptService receipts = mock(PaymentCloseReceiptService.class);
    private final TradeOrderMapper orders = mock(TradeOrderMapper.class);
    private final PaymentGatewayService gateway = mock(PaymentGatewayService.class);
    private final PaymentCloseRetryService service = new PaymentCloseRetryService(receipts, orders, gateway);

    @Test
    void retriesOnlyClosedOrdersAndSkipsMissingOrOpenOnes() {
        when(receipts.findRetryableOrderSn(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of("SN-CLOSED", "SN-UNPAID", "SN-MISSING"));
        when(orders.findByOrderSn("SN-CLOSED")).thenReturn(Optional.of(order("SN-CLOSED", "CLOSED")));
        when(orders.findByOrderSn("SN-UNPAID")).thenReturn(Optional.of(order("SN-UNPAID", "UNPAID")));
        when(orders.findByOrderSn("SN-MISSING")).thenReturn(Optional.empty());

        int attempted = service.retryPending(5, Duration.ofMinutes(10), Duration.ofHours(72), 100);

        assertEquals(1, attempted);
        verify(gateway, times(1)).closePaymentBestEffort(any());
        verify(gateway).closePaymentBestEffort(order("SN-CLOSED", "CLOSED"));
    }

    @Test
    void continuesAfterSingleOrderFailure() {
        when(receipts.findRetryableOrderSn(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of("SN-1", "SN-2"));
        when(orders.findByOrderSn("SN-1")).thenReturn(Optional.of(order("SN-1", "CLOSED")));
        when(orders.findByOrderSn("SN-2")).thenReturn(Optional.of(order("SN-2", "CANCELLED")));
        doThrow(new RuntimeException("adapter down")).when(gateway).closePaymentBestEffort(order("SN-1", "CLOSED"));

        int attempted = service.retryPending(5, Duration.ofMinutes(10), Duration.ofHours(72), 100);

        assertEquals(1, attempted);
        verify(gateway).closePaymentBestEffort(order("SN-2", "CANCELLED"));
    }

    @Test
    void returnsZeroWithoutQueryWhenParametersInvalid() {
        assertEquals(0, service.retryPending(0, Duration.ofMinutes(10), Duration.ofHours(72), 100));
        assertEquals(0, service.retryPending(5, Duration.ofMinutes(10), Duration.ofHours(72), 0));
        assertEquals(0, service.retryPending(5, null, Duration.ofHours(72), 100));
        assertEquals(0, service.retryPending(5, Duration.ofMinutes(10), null, 100));
        verifyNoInteractions(receipts, orders, gateway);
    }

    private static TradeOrder order(String orderSn, String status) {
        TradeOrder order = new TradeOrder();
        order.setId(1L);
        order.setOrderSn(orderSn);
        order.setMerchantId(1L);
        order.setOrderStatus(status);
        order.setPayChannel("WECHAT");
        return order;
    }
}
