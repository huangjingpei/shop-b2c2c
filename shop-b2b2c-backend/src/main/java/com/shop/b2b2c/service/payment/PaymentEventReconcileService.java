/*
 * 文件：PaymentEventReconcileService.java
 * 用途：G34 合并补偿扫描：在同一轮中收敛“事件 SUCCESS 但订单未履约”和“占位后崩溃残留的陈旧 PROCESSING”。
 * 维护注意：
 *  1. 陈旧 PROCESSING 必须先用事件记录的渠道回查渠道流水；只有渠道确认 SUCCESS 且流水号与本地事件一致，
 *     才允许调用 PaymentEventService.processPaymentEvent 补履约，禁止仅凭本地事件状态直接交付。
 *  2. 补履约复用 processPaymentEvent 的占位/订单行锁/CAS/卡密认领幂等路径；本类不直接写订单、不直接发卡，
 *     不出现第二次交付。
 *  3. 渠道无法确认、流水号/金额不一致或补履约异常时写 FULFILLMENT_FAILED 并输出 ERROR 告警；
 *     统一走 PaymentEventRecorder.recordFailureAudit 的独立事务与“SUCCESS 不降级”保护，避免把主事务
 *     已正常完成的事件误改为失败态。
 *  4. 已被主事务正常履约的订单（PAID/COMPLETED/SHIPPED）不进入陈旧 PROCESSING 查询，避免与主事务重复处理。
 *  5. 陈旧判定阈值由调用方传入（默认 5 分钟，配置 payment.reconcile.processing-threshold-minutes）；
 *     扫描频率与库存到期任务共用 inventory.expiry-delay-ms，不另起调度任务。
 *  6. 单条事件失败只记录并继续同批其它事件；本轮仍会执行既有 SUCCESS 未履约补偿，两者互不覆盖。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.dto.PaymentChannelDTOs;
import com.shop.b2b2c.dto.PaymentEventDTOs;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.service.PaymentEventRecorder;
import com.shop.b2b2c.service.PaymentEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventReconcileService {

    private static final int MAX_BATCH_SIZE = 200;
    private static final String STATUS_FULFILLMENT_FAILED = "FULFILLMENT_FAILED";
    private static final Duration DEFAULT_STALE_THRESHOLD = Duration.ofMinutes(5);

    private final PaymentEventMapper paymentEventMapper;
    private final PaymentEventService paymentEventService;
    private final PaymentGatewayService paymentGatewayService;
    private final PaymentEventRecorder eventRecorder;

    /**
     * 合并补偿扫描。返回本轮成功处理的事件数；单条失败不抛出，由失败态与 ERROR 日志告警。
     */
    public int reconcile(int limit, Duration staleThreshold) {
        if (limit <= 0) return 0;
        int batch = Math.min(limit, MAX_BATCH_SIZE);
        // 阈值为 0/负数会把主事务正在处理的事件也纳入扫描，统一回退到默认窗口，避免配置误写放大风险。
        Duration threshold = (staleThreshold == null || staleThreshold.isZero() || staleThreshold.isNegative())
                ? DEFAULT_STALE_THRESHOLD : staleThreshold;
        int processed = 0;

        List<PaymentEvent> staleProcessing =
                paymentEventMapper.findStaleProcessingEvents(LocalDateTime.now().minus(threshold), batch);
        for (PaymentEvent candidate : staleProcessing) {
            if (reconcileStaleProcessing(candidate)) {
                processed++;
            }
        }

        try {
            processed += paymentEventService.reconcileSuccessfulWithoutFulfillment(batch);
        } catch (RuntimeException e) {
            log.error("SUCCESS 未履约补偿扫描异常，等待下一轮重试", e);
        }
        return processed;
    }

    private boolean reconcileStaleProcessing(PaymentEvent candidate) {
        try {
            PaymentChannelDTOs.ChannelQueryResult query = paymentGatewayService.queryChannelPayment(
                    candidate.getMerchantId(), candidate.getChannel(), candidate.getOrderSn());
            if (query == null || !query.isSuccess()) {
                markFailed(candidate, "渠道查单未确认支付成功: state="
                        + (query == null ? "null" : query.getTradeState()));
                return false;
            }
            if (query.getPayTradeNo() == null || !query.getPayTradeNo().equals(candidate.getPayTradeNo())) {
                markFailed(candidate, "渠道流水号与本地事件不一致: " + query.getPayTradeNo());
                return false;
            }
            if (query.getPayAmount() == null || query.getCurrency() == null || query.getCurrency().isBlank()) {
                markFailed(candidate, "渠道查单缺少实收金额或币种");
                return false;
            }
            // 确认渠道支付成功后，复用既有幂等履约：事件已存在，claimEvent 会命中同单事件，
            // 由订单行锁与 CAS 保证不会出现第二次交付。
            paymentEventService.processPaymentEvent(toIncomingEvent(candidate, query));
            return true;
        } catch (RuntimeException e) {
            markFailed(candidate, "补履约失败: " + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()));
            return false;
        }
    }

    private PaymentEventDTOs.IncomingPaymentEvent toIncomingEvent(PaymentEvent candidate,
                                                                 PaymentChannelDTOs.ChannelQueryResult query) {
        return PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel(candidate.getChannel())
                .payTradeNo(query.getPayTradeNo())
                .orderSn(candidate.getOrderSn())
                .merchantId(candidate.getMerchantId())
                .channelMerchantId(query.getChannelMerchantId())
                .payAmount(query.getPayAmount())
                .currency(query.getCurrency())
                .payerId(query.getPayerId())
                .rawPayload(query.getRawPayload())
                .build();
    }

    /**
     * 失败态必须落库并告警；recordFailureAudit 会按 channel+payTradeNo 回查，拒绝把 SUCCESS 降级，
     * 也不会改写其他订单的事件。
     */
    private void markFailed(PaymentEvent candidate, String reason) {
        log.error("【支付事件告警】陈旧 PROCESSING 无法补履约，标记 FULFILLMENT_FAILED "
                        + "channel={}, tradeNo={}, orderSn={}, reason={}",
                candidate.getChannel(), candidate.getPayTradeNo(), candidate.getOrderSn(), reason);
        try {
            eventRecorder.recordFailureAudit(candidate, STATUS_FULFILLMENT_FAILED, reason);
        } catch (RuntimeException e) {
            log.error("陈旧 PROCESSING 支付事件失败态回写异常 tradeNo={}", candidate.getPayTradeNo(), e);
        }
    }
}
