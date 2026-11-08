/*
 * 文件：PaymentExceptionDispositionServiceTest.java
 * 用途：F08 支付异常人工处置的隔离规则用例：事件/状态白名单、结论与说明校验、重复处置 409、只插入不改事件。
 * 维护注意：
 *  1. 全部 Mockito 隔离，不连数据库；真实唯一键、队列 LEFT JOIN 与权限由 Sprint1SecurityIntegrationTest 的 H2 用例覆盖。
 *  2. 锁定“处置只落审计记录、不改 payment_event 状态/资金标记”的语义；若未来允许修改资金状态，必须同步台账与资金流程测试。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.PaymentExceptionDispositionRequest;
import com.shop.b2b2c.dto.PaymentExceptionDispositionView;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.entity.PaymentExceptionDisposition;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.mapper.PaymentExceptionDispositionMapper;
import com.shop.b2b2c.service.payment.PaymentExceptionDispositionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentExceptionDispositionServiceTest {

    private final PaymentEventMapper events = mock(PaymentEventMapper.class);
    private final PaymentExceptionDispositionMapper dispositions = mock(PaymentExceptionDispositionMapper.class);
    private final PaymentExceptionDispositionService service =
            new PaymentExceptionDispositionService(events, dispositions);

    @Test
    void rejectsMissingEventWithNotFound() {
        when(events.selectById(7L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.dispose(7L, request("REFUND_CONFIRMED", "已走退款流程"), 200L));

        assertEquals(404, error.getCode());
        verifyNoInteractions(dispositions);
    }

    @Test
    void rejectsNonExceptionStatus() {
        when(events.selectById(7L)).thenReturn(event("SN-1", "SUCCESS"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.dispose(7L, request("REFUND_CONFIRMED", "已走退款流程"), 200L));

        assertEquals(400, error.getCode());
        verify(dispositions, never()).insert(any());
    }

    @Test
    void rejectsMissingBodyOrActionWithBadRequest() {
        when(events.selectById(7L)).thenReturn(event("SN-1", "LATE_PAYMENT"));

        BusinessException missingBody = assertThrows(BusinessException.class,
                () -> service.dispose(7L, null, 200L));
        assertEquals(400, missingBody.getCode());
        BusinessException missingAction = assertThrows(BusinessException.class,
                () -> service.dispose(7L, request(null, "核对后无需处理"), 200L));
        assertEquals(400, missingAction.getCode());

        verify(dispositions, never()).insert(any());
    }

    @Test
    void rejectsActionOutsideWhitelist() {
        when(events.selectById(7L)).thenReturn(event("SN-1", "LATE_PAYMENT"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.dispose(7L, request("REFUND_PAYMENT", "已走退款流程"), 200L));

        assertEquals(400, error.getCode());
        verify(dispositions, never()).insert(any());
    }

    @Test
    void rejectsNoteOutsideTwoToTwoHundredCharacters() {
        when(events.selectById(7L)).thenReturn(event("SN-1", "LATE_PAYMENT"));

        assertThrows(BusinessException.class,
                () -> service.dispose(7L, request("NO_ACTION_REQUIRED", "x"), 200L));
        assertThrows(BusinessException.class,
                () -> service.dispose(7L, request("NO_ACTION_REQUIRED", "x".repeat(201)), 200L));
        assertThrows(BusinessException.class,
                () -> service.dispose(7L, request("NO_ACTION_REQUIRED", "  \n  "), 200L));

        verify(dispositions, never()).insert(any());
    }

    @Test
    void rejectsDuplicateDispositionWithConflict() {
        when(events.selectById(7L)).thenReturn(event("SN-1", "LATE_PAYMENT"));
        when(dispositions.findByEventId(7L)).thenReturn(Optional.of(new PaymentExceptionDisposition()));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.dispose(7L, request("CHANNEL_VERIFIED", "渠道流水已核对"), 200L));

        assertEquals(409, error.getCode());
        verify(dispositions, never()).insert(any());
    }

    @Test
    void mapsConcurrentUniqueKeyViolationToConflict() {
        when(events.selectById(7L)).thenReturn(event("SN-1", "LATE_PAYMENT"));
        when(dispositions.findByEventId(7L)).thenReturn(Optional.empty());
        when(dispositions.insert(any())).thenThrow(new DuplicateKeyException("uk_payment_disposition_event"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.dispose(7L, request("CHANNEL_VERIFIED", "渠道流水已核对"), 200L));

        assertEquals(409, error.getCode());
    }

    @Test
    void recordsNormalizedDispositionWithoutTouchingPaymentEvent() {
        when(events.selectById(7L)).thenReturn(event("SN-1", "LATE_PAYMENT"));
        when(dispositions.findByEventId(7L)).thenReturn(Optional.empty());

        PaymentExceptionDispositionView view = service.dispose(7L,
                request(" refund_confirmed ", "  已走退款\n流程  "), 200L);

        ArgumentCaptor<PaymentExceptionDisposition> captor =
                ArgumentCaptor.forClass(PaymentExceptionDisposition.class);
        verify(dispositions).insert(captor.capture());
        PaymentExceptionDisposition saved = captor.getValue();
        assertEquals(7L, saved.getEventId());
        assertEquals("SN-1", saved.getOrderSn());
        assertEquals("REFUND_CONFIRMED", saved.getAction());
        assertEquals("已走退款 流程", saved.getNote());
        assertEquals(200L, saved.getOperatorId());
        assertNotNull(saved.getCreatedAt());

        assertEquals("REFUND_CONFIRMED", view.getAction());
        assertEquals(200L, view.getOperatorId());
        // 处置只查事件用于校验，不得回调任何更新方法改写状态或资金标记。
        verify(events, times(1)).selectById(7L);
        verify(events, never()).updateById(any());
    }

    private static PaymentExceptionDispositionRequest request(String action, String note) {
        PaymentExceptionDispositionRequest request = new PaymentExceptionDispositionRequest();
        request.setAction(action);
        request.setNote(note);
        return request;
    }

    private static PaymentEvent event(String orderSn, String status) {
        PaymentEvent event = new PaymentEvent();
        event.setId(7L);
        event.setOrderSn(orderSn);
        event.setMerchantId(1L);
        event.setEventStatus(status);
        event.setFundsReceived(1);
        return event;
    }
}
