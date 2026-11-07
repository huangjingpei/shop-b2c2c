/*
 * 文件：ComplaintServiceTest.java
 * 用途：F06 投诉协商与平台仲裁服务单元测试：提交校验、商家答复、平台介入、裁决退款联动与不可覆盖事件记录。
 * 维护注意：只使用 Mockito 模拟持久层与退款服务，不连接数据库；集成行为由 Sprint1SecurityIntegrationTest 覆盖。
 * 退款幂等键必须与生产实现一致（COMPLAINT- + 32 位零填充工单ID），修改规则须同步断言。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.ComplaintDTOs;
import com.shop.b2b2c.dto.RefundDTOs;
import com.shop.b2b2c.entity.Complaint;
import com.shop.b2b2c.entity.ComplaintEvent;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.ComplaintEventMapper;
import com.shop.b2b2c.mapper.ComplaintMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import com.shop.b2b2c.service.payment.RefundService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComplaintServiceTest {

    @Mock ComplaintMapper complaints;
    @Mock ComplaintEventMapper events;
    @Mock TradeOrderMapper orders;
    @Mock TradeRefundMapper refunds;
    @Mock RefundService refundService;
    @Mock MerchantNotificationService notificationService;
    @InjectMocks ComplaintService service;

    private TradeOrder paidOrder(long id, long merchantId, String pay) {
        TradeOrder o = new TradeOrder();
        o.setId(id);
        o.setOrderSn("C-" + id);
        o.setMerchantId(merchantId);
        o.setBuyerContact("13900000000");
        o.setQueryToken("tk");
        o.setOrderStatus("PAID");
        o.setPayAmount(new BigDecimal(pay));
        return o;
    }

    private Complaint complaint(long id, long merchantId, String status) {
        Complaint c = new Complaint();
        c.setId(id);
        c.setOrderId(10L + id);
        c.setOrderSn("C-" + (10L + id));
        c.setMerchantId(merchantId);
        c.setStatus(status);
        c.setRefundAmount(BigDecimal.ZERO);
        c.setRefundDecision(Complaint.DECISION_NONE);
        return c;
    }

    @Test
    @DisplayName("买家提交：拒绝非法举证与重复活动工单")
    void submitRejectsInvalidEvidenceAndDuplicate() {
        TradeOrder order = paidOrder(1L, 1L, "100.00");
        when(orders.findByOrderSnAndBuyerContact("C-1", "13900000000")).thenReturn(Optional.of(order));
        when(complaints.countActiveByOrderId(1L)).thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.submit(new ComplaintDTOs.BuyerSubmitRequest(
                "C-1", "13900000000", "tk", "QUALITY", "商品有质量问题", List.of("javascript:alert(1)"))));

        BusinessException duplicate = assertThrows(BusinessException.class, () -> service.submit(
                new ComplaintDTOs.BuyerSubmitRequest("C-1", "13900000000", "tk", "QUALITY",
                        "商品有质量问题", Collections.emptyList())));
        assertEquals(409, duplicate.getCode());
        verify(complaints, never()).insert(any());
    }

    @Test
    @DisplayName("买家提交：成功落 PENDING 并写 SUBMITTED 事件与商家通知")
    void submitCreatesPendingAndRecordsEvent() {
        TradeOrder order = paidOrder(1L, 1L, "100.00");
        when(orders.findByOrderSnAndBuyerContact("C-1", "13900000000")).thenReturn(Optional.of(order));
        when(complaints.countActiveByOrderId(1L)).thenReturn(0L);

        Complaint created = service.submit(new ComplaintDTOs.BuyerSubmitRequest(
                "C-1", "13900000000", "tk", "quality", "商品与描述不符", List.of("https://cdn.example.com/a.jpg")));

        assertEquals(Complaint.STATUS_PENDING, created.getStatus());
        assertEquals("QUALITY", created.getComplaintType());
        assertTrue(created.getEvidenceImages().contains("https://cdn.example.com/a.jpg"));
        ArgumentCaptor<ComplaintEvent> event = ArgumentCaptor.forClass(ComplaintEvent.class);
        verify(events).insert(event.capture());
        assertEquals(ComplaintEvent.TYPE_SUBMITTED, event.getValue().getEventType());
        assertEquals(ComplaintEvent.ACTOR_BUYER, event.getValue().getActorRole());
        verify(notificationService).notify(eq(MerchantNotificationService.BIZ_COMPLAINT_SUBMITTED), any(),
                eq(MerchantNotificationService.CHANNEL_INAPP), eq("COMPLAINT_SUBMITTED"), eq("MERCHANT:1"), anyMap());
    }

    @Test
    @DisplayName("商家答复：跨店拒绝、状态流转与多次答复留痕")
    void replyEnforcesTenantAndStatus() {
        Complaint c = complaint(3L, 1L, Complaint.STATUS_PENDING);
        when(complaints.selectById(3L)).thenReturn(c);

        BusinessException cross = assertThrows(BusinessException.class,
                () -> service.reply(3L, 2L, 200L, "商家2", new ComplaintDTOs.MerchantReplyRequest("答复", null)));
        assertEquals(403, cross.getCode());

        Complaint replied = service.reply(3L, 1L, 200L, "商家1", new ComplaintDTOs.MerchantReplyRequest("已核实，补偿5元", null));
        assertEquals(Complaint.STATUS_MERCHANT_REPLIED, replied.getStatus());
        assertNotNull(replied.getMerchantReplyTime());

        c.setStatus(Complaint.STATUS_ARBITRATING);
        BusinessException closed = assertThrows(BusinessException.class,
                () -> service.reply(3L, 1L, 200L, "商家1", new ComplaintDTOs.MerchantReplyRequest("再答复", null)));
        assertEquals(409, closed.getCode());
    }

    @Test
    @DisplayName("买家申请介入：凭证不符拒绝，成功后置 ARBITRATING 并通知超管")
    void escalateRequiresCredentials() {
        Complaint c = complaint(3L, 1L, Complaint.STATUS_MERCHANT_REPLIED);
        TradeOrder order = paidOrder(13L, 1L, "100.00");
        when(complaints.selectById(3L)).thenReturn(c);
        when(orders.selectById(13L)).thenReturn(order);

        assertThrows(BusinessException.class, () -> service.escalate(3L,
                new ComplaintDTOs.BuyerEscalateRequest("C-13", "13900000000", "bad", null)));

        Complaint escalated = service.escalate(3L,
                new ComplaintDTOs.BuyerEscalateRequest("C-13", "13900000000", "tk", "商家拒不处理"));
        assertEquals(Complaint.STATUS_ARBITRATING, escalated.getStatus());
        verify(notificationService).notify(eq(MerchantNotificationService.BIZ_COMPLAINT_ESCALATED), eq(3L),
                eq(MerchantNotificationService.CHANNEL_INAPP), eq("COMPLAINT_ESCALATED"), eq("ROLE:SUPERADMIN"), anyMap());
    }

    @Test
    @DisplayName("平台裁决：支持诉求按可退余额执行退款并置 RESOLVED")
    void arbitrateExecutesRefundAndResolves() {
        Complaint c = complaint(7L, 1L, Complaint.STATUS_ARBITRATING);
        TradeOrder order = paidOrder(17L, 1L, "100.00");
        order.setOrderSn("C-17");
        when(complaints.selectById(7L)).thenReturn(c);
        when(orders.selectById(17L)).thenReturn(order);
        when(refunds.selectList(any())).thenReturn(Collections.emptyList());

        Complaint resolved = service.arbitrate(7L, 900L,
                new ComplaintDTOs.ArbitrateRequest("RESOLVE", "证据成立，全额退款", new BigDecimal("100.00")));

        assertEquals(Complaint.STATUS_RESOLVED, resolved.getStatus());
        assertEquals(Complaint.DECISION_FULL_REFUND, resolved.getRefundDecision());
        verify(refundService).applyOrderRefund(eq(1L), eq("C-17"), any(RefundDTOs.RefundRequest.class),
                eq("COMPLAINT-" + String.format("%032d", 7L)), eq(900L));
    }

    @Test
    @DisplayName("平台裁决：驳回不得同时退款；退款不得超过可退余额")
    void arbitrateRejectsInvalidAmountAndState() {
        Complaint c = complaint(7L, 1L, Complaint.STATUS_ARBITRATING);
        TradeOrder order = paidOrder(17L, 1L, "100.00");
        when(complaints.selectById(7L)).thenReturn(c);
        when(orders.selectById(17L)).thenReturn(order);
        when(refunds.selectList(any())).thenReturn(List.of(successRefund("50.00")));

        BusinessException rejectWithRefund = assertThrows(BusinessException.class, () -> service.arbitrate(7L, 900L,
                new ComplaintDTOs.ArbitrateRequest("REJECT", "证据不足", new BigDecimal("10.00"))));
        assertEquals(400, rejectWithRefund.getCode());

        BusinessException overRefundable = assertThrows(BusinessException.class, () -> service.arbitrate(7L, 900L,
                new ComplaintDTOs.ArbitrateRequest("RESOLVE", "部分成立", new BigDecimal("60.00"))));
        assertEquals(400, overRefundable.getCode());

        c.setStatus(Complaint.STATUS_MERCHANT_REPLIED);
        BusinessException notArbitrating = assertThrows(BusinessException.class, () -> service.arbitrate(7L, 900L,
                new ComplaintDTOs.ArbitrateRequest("RESOLVE", "结论", BigDecimal.ZERO)));
        assertEquals(409, notArbitrating.getCode());
        verify(refundService, never()).applyOrderRefund(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("平台裁决：驳回无退款置 REJECTED，写入追加事件")
    void arbitrateRejectWithoutRefund() {
        Complaint c = complaint(7L, 1L, Complaint.STATUS_ARBITRATING);
        when(complaints.selectById(7L)).thenReturn(c);
        when(orders.selectById(17L)).thenReturn(paidOrder(17L, 1L, "100.00"));

        Complaint rejected = service.arbitrate(7L, 900L,
                new ComplaintDTOs.ArbitrateRequest("REJECT", "举证不足，不支持退款", BigDecimal.ZERO));

        assertEquals(Complaint.STATUS_REJECTED, rejected.getStatus());
        assertEquals(Complaint.DECISION_NONE, rejected.getRefundDecision());
        verify(events).insert(argThat(e -> ComplaintEvent.TYPE_REJECTED.equals(e.getEventType())));
        verify(refundService, never()).applyOrderRefund(any(), any(), any(), any(), any());
    }

    private TradeRefund successRefund(String amount) {
        TradeRefund r = new TradeRefund();
        r.setRefundAmount(new BigDecimal(amount));
        r.setStatus("SUCCESS");
        return r;
    }
}
