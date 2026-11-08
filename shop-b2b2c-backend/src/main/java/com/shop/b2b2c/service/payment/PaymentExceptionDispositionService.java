/*
 * 文件：PaymentExceptionDispositionService.java
 * 用途：F08 支付异常队列的人工处置入口：校验事件与结论后落一条只增审计记录。
 * 维护注意：
 *  1. 只记录人工结论，不发起退款/补偿、不改写 payment_event.event_status/funds_received，
 *     也不改订单、退款或资金账本；资金动作仍须走渠道或既有退款流程。
 *  2. 只有异常队列白名单状态可处置；同一 event_id 只允许一条记录，预查与唯一键兜底双保险，重复提交返回 409。
 *  3. note 单行化后必须 2-200 字；不得记录渠道凭据、报文正文或买家身份。日志只记 eventId/action/operatorId。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.PaymentExceptionDispositionRequest;
import com.shop.b2b2c.dto.PaymentExceptionDispositionView;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.entity.PaymentExceptionDisposition;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.mapper.PaymentExceptionDispositionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentExceptionDispositionService {

    public static final String ACTION_REFUND_CONFIRMED = "REFUND_CONFIRMED";
    public static final String ACTION_DUPLICATE_RESOLVED = "DUPLICATE_RESOLVED";
    public static final String ACTION_CHANNEL_VERIFIED = "CHANNEL_VERIFIED";
    public static final String ACTION_NO_ACTION_REQUIRED = "NO_ACTION_REQUIRED";

    /** 与 PaymentEventMapper.findPaymentExceptions 的异常态集合保持一致，避免两处口径漂移。 */
    private static final Set<String> EXCEPTION_STATUSES = Set.of(
            "LATE_PAYMENT", "DUPLICATE_PAYMENT", "AMOUNT_MISMATCH", "CURRENCY_MISMATCH",
            "MERCHANT_MISMATCH", "MANUAL_REVIEW", "FULFILLMENT_FAILED");

    private static final Set<String> ACTIONS = Set.of(
            ACTION_REFUND_CONFIRMED, ACTION_DUPLICATE_RESOLVED,
            ACTION_CHANNEL_VERIFIED, ACTION_NO_ACTION_REQUIRED);

    private static final String ALLOWED_ACTION_TEXT =
            "REFUND_CONFIRMED、DUPLICATE_RESOLVED、CHANNEL_VERIFIED、NO_ACTION_REQUIRED";

    private static final int NOTE_MIN = 2;
    private static final int NOTE_MAX = 200;

    private final PaymentEventMapper eventMapper;
    private final PaymentExceptionDispositionMapper dispositionMapper;

    /**
     * 记录一次人工处置；失败抛出 BusinessException，由全局异常处理映射为 HTTP 状态。
     */
    public PaymentExceptionDispositionView dispose(long eventId,
                                                   PaymentExceptionDispositionRequest request,
                                                   long operatorId) {
        if (eventId <= 0) {
            throw new BusinessException(400, "支付异常事件ID不合法");
        }
        PaymentEvent event = eventMapper.selectById(eventId);
        if (event == null) {
            throw new BusinessException(404, "支付异常事件不存在");
        }
        if (event.getEventStatus() == null || !EXCEPTION_STATUSES.contains(event.getEventStatus())) {
            throw new BusinessException(400, "该事件不在支付异常队列，不能记录处置");
        }
        String action = normalizeAction(request == null ? null : request.getAction());
        if (action == null || !ACTIONS.contains(action)) {
            throw new BusinessException(400, "处置结论不合法，可选：" + ALLOWED_ACTION_TEXT);
        }
        String note = normalizeNote(request == null ? null : request.getNote());
        if (note == null || note.length() < NOTE_MIN || note.length() > NOTE_MAX) {
            throw new BusinessException(400, "处置说明必须为 " + NOTE_MIN + "-" + NOTE_MAX + " 字");
        }
        if (dispositionMapper.findByEventId(eventId).isPresent()) {
            throw new BusinessException(409, "该事件已有处置记录，不能重复处置");
        }

        PaymentExceptionDisposition entity = new PaymentExceptionDisposition();
        entity.setEventId(eventId);
        entity.setOrderSn(event.getOrderSn());
        entity.setAction(action);
        entity.setNote(note);
        entity.setOperatorId(operatorId);
        entity.setCreatedAt(LocalDateTime.now());
        try {
            dispositionMapper.insert(entity);
        } catch (DuplicateKeyException duplicate) {
            // 并发重复提交时唯一键兜底，返回与预查一致的冲突语义。
            throw new BusinessException(409, "该事件已有处置记录，不能重复处置");
        }
        log.info("支付异常处置已记录 eventId={} action={} operatorId={}", eventId, action, operatorId);
        return toView(entity);
    }

    private static String normalizeAction(String action) {
        return action == null ? null : action.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        return note.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static PaymentExceptionDispositionView toView(PaymentExceptionDisposition entity) {
        PaymentExceptionDispositionView view = new PaymentExceptionDispositionView();
        view.setId(entity.getId());
        view.setEventId(entity.getEventId());
        view.setOrderSn(entity.getOrderSn());
        view.setAction(entity.getAction());
        view.setNote(entity.getNote());
        view.setOperatorId(entity.getOperatorId());
        view.setCreatedAt(entity.getCreatedAt());
        return view;
    }
}
