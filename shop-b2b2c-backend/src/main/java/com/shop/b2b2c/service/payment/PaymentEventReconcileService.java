/*
 * 文件：PaymentEventReconcileService.java
 * 用途：G34 合并补偿扫描：在同一轮中收敛“事件 SUCCESS 但订单未履约”和“占位后崩溃残留的陈旧 PROCESSING”。
 * 维护注意：
 *  1. 陈旧 PROCESSING 必须先用事件记录的渠道回查渠道流水；只有渠道确认 SUCCESS 且流水号一致、金额币种齐全，
 *     才允许调用 PaymentEventService.processPaymentEvent 补履约，禁止仅凭本地事件状态直接交付。
 *  2. 补履约复用 processPaymentEvent 的占位/订单行锁/CAS/卡密认领幂等路径；本类不直接写订单、不直接发卡，
 *     不出现第二次交付。
 *  3. 渠道结果分三类：明确未支付（渠道返回 PENDING/CLOSED/REFUNDED/FAILED/NOT_EXIST，或 400/401/403/404）
 *     落 FULFILLMENT_FAILED 并告警；查询异常或答复不确定（超时/限流/5xx/空响应/未知状态/流水号不一致）
 *     保留 PROCESSING，按指数退避写 reconcile_attempts/next_reconcile_at 重试；超过上限转 MANUAL_REVIEW 人工队列并告警。
 *     失败态不是永久锁死：后续收到真实 SUCCESS 通知仍可由 processPaymentEvent 幂等履约。
 *  4. 已被主事务正常履约的订单（PAID/COMPLETED/SHIPPED）不进入陈旧 PROCESSING 查询，避免与主事务重复处理。
 *  5. 陈旧判定阈值、最大重试次数、初始退避由调用方传入（默认 5 分钟 / 5 次 / 60 秒）；
 *     扫描频率与库存到期任务共用 inventory.expiry-delay-ms，不另起调度任务。
 *  6. 所有失败态/重试态/人工队列更新都带 event_status='PROCESSING' 条件，并发已被主事务改为 SUCCESS 的事件不得降级。
 *  7. 单条事件失败只记录并继续同批其它事件；本轮仍会执行既有 SUCCESS 未履约补偿，两者互不覆盖。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
import com.shop.b2b2c.dto.PaymentEventDTOs;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.service.PaymentEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventReconcileService {

    private static final int MAX_BATCH_SIZE = 200;
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final Duration DEFAULT_STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(60);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofMinutes(30);
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    /** 渠道给出确定答复但没有成功资金：这些状态按“明确未支付”落失败态，不算查询异常。 */
    private static final Set<String> DEFINITIVE_NOT_PAID_STATES =
            Set.of("PENDING", "CLOSED", "REFUNDED", "FAILED", "NOT_EXIST");

    /** 查询前置校验类错误码：重试无法自愈，按明确失败处理；其余异常保留重试。 */
    private static final Set<Integer> DEFINITIVE_BUSINESS_CODES = Set.of(400, 401, 403, 404);

    private final PaymentEventMapper paymentEventMapper;
    private final PaymentEventService paymentEventService;
    private final PaymentGatewayService paymentGatewayService;

    /**
     * 合并补偿扫描。返回本轮成功处理的事件数；单条失败不抛出，由失败态/重试态/人工队列与 ERROR 日志告警。
     *
     * @param limit          单轮批量上限
     * @param staleThreshold 超过该时长仍 PROCESSING 才进入渠道回查
     * @param maxAttempts    渠道回查最大尝试次数，超过转 MANUAL_REVIEW
     * @param initialBackoff 首次退避时长，之后按 2 倍递增，最长 30 分钟
     */
    public int reconcile(int limit, Duration staleThreshold, int maxAttempts, Duration initialBackoff) {
        if (limit <= 0) return 0;
        int batch = Math.min(limit, MAX_BATCH_SIZE);
        // 阈值为 0/负数会把主事务正在处理的事件也纳入扫描，统一回退到默认窗口，避免配置误写放大风险。
        Duration threshold = (staleThreshold == null || staleThreshold.isZero() || staleThreshold.isNegative())
                ? DEFAULT_STALE_THRESHOLD : staleThreshold;
        int max = maxAttempts <= 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        Duration backoff = (initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative())
                ? DEFAULT_RETRY_BACKOFF : initialBackoff;
        int processed = 0;

        LocalDateTime now = LocalDateTime.now();
        List<PaymentEvent> staleProcessing =
                paymentEventMapper.findStaleProcessingEvents(now.minus(threshold), now, batch);
        for (PaymentEvent candidate : staleProcessing) {
            try {
                if (reconcileStaleProcessing(candidate, max, backoff, now)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                // 单条失败（如重试/失败态回写遇到数据库异常）只告警并继续同批，避免一条坏记录饿死整批补偿。
                log.error("陈旧 PROCESSING 补偿单条处理异常，继续同批其它事件 tradeNo={}",
                        candidate.getPayTradeNo(), e);
            }
        }

        try {
            processed += paymentEventService.reconcileSuccessfulWithoutFulfillment(batch);
        } catch (RuntimeException e) {
            log.error("SUCCESS 未履约补偿扫描异常，等待下一轮重试", e);
        }
        return processed;
    }

    private boolean reconcileStaleProcessing(PaymentEvent candidate, int maxAttempts,
                                             Duration initialBackoff, LocalDateTime now) {
        PaymentChannelDTOs.ChannelQueryResult query;
        try {
            query = paymentGatewayService.queryChannelPayment(
                    candidate.getMerchantId(), candidate.getChannel(), candidate.getOrderSn());
        } catch (BusinessException e) {
            if (DEFINITIVE_BUSINESS_CODES.contains(e.getCode())) {
                markFailed(candidate, "渠道查单明确失败: " + e.getMessage());
            } else {
                handleRetryable(candidate, "渠道查单异常: " + messageOf(e), maxAttempts, initialBackoff, now);
            }
            return false;
        } catch (RuntimeException e) {
            handleRetryable(candidate, "渠道查单异常: " + messageOf(e), maxAttempts, initialBackoff, now);
            return false;
        }

        if (query == null) {
            handleRetryable(candidate, "渠道查单未返回结果", maxAttempts, initialBackoff, now);
            return false;
        }
        if (query.isSuccess()) {
            String inconsistency = successInconsistency(candidate, query);
            if (inconsistency != null) {
                handleRetryable(candidate, inconsistency, maxAttempts, initialBackoff, now);
                return false;
            }
            try {
                // 确认渠道支付成功后，复用既有幂等履约：事件已存在，claimEvent 会命中同单事件，
                // 由订单行锁与 CAS 保证不会出现第二次交付；失败事件也会在此被重新驱动。
                paymentEventService.processPaymentEvent(toIncomingEvent(candidate, query));
                return true;
            } catch (RuntimeException e) {
                // 业务拒绝通常已在库中写入具体失败态；只有事件仍 PROCESSING（如临时 DB/锁异常）才进入退避重试。
                PaymentEvent current = paymentEventMapper.selectById(candidate.getId());
                if (current != null && STATUS_PROCESSING.equals(current.getEventStatus())) {
                    handleRetryable(candidate, "补履约异常: " + messageOf(e), maxAttempts, initialBackoff, now);
                } else {
                    log.error("陈旧 PROCESSING 补履约失败，事件已收敛为其它状态，不再重试 tradeNo={}, status={}",
                            candidate.getPayTradeNo(), current == null ? "MISSING" : current.getEventStatus(), e);
                }
                return false;
            }
        }
        if (DEFINITIVE_NOT_PAID_STATES.contains(query.getTradeState())) {
            markFailed(candidate, "渠道明确未支付: state=" + query.getTradeState());
            return false;
        }
        handleRetryable(candidate, "渠道答复不确定: state=" + query.getTradeState(),
                maxAttempts, initialBackoff, now);
        return false;
    }

    /** 渠道答复 SUCCESS 但资金要素不完整/流水号不一致时，不能直接交付，按不确定结果退避重试。 */
    private String successInconsistency(PaymentEvent candidate, PaymentChannelDTOs.ChannelQueryResult query) {
        if (query.getPayTradeNo() == null || !query.getPayTradeNo().equals(candidate.getPayTradeNo())) {
            return "渠道流水号与本地事件不一致: " + query.getPayTradeNo();
        }
        if (query.getPayAmount() == null || query.getCurrency() == null || query.getCurrency().isBlank()) {
            return "渠道查单缺少实收金额或币种";
        }
        return null;
    }

    /**
     * 不确定结果：保留 PROCESSING，写入尝试数与指数退避时间；达到上限转 MANUAL_REVIEW 人工队列并告警。
     * 更新均以 event_status='PROCESSING' 为条件，已被主事务改为 SUCCESS 时不覆盖。
     */
    private void handleRetryable(PaymentEvent candidate, String reason, int maxAttempts,
                                 Duration initialBackoff, LocalDateTime now) {
        int attempts = (candidate.getReconcileAttempts() == null ? 0 : candidate.getReconcileAttempts()) + 1;
        String message = truncate(reason);
        if (attempts >= maxAttempts) {
            int moved = paymentEventMapper.markManualReview(candidate.getId(), attempts, message);
            if (moved == 1) {
                log.error("【支付事件告警】陈旧 PROCESSING 超过重试上限，转人工队列 MANUAL_REVIEW "
                                + "channel={}, tradeNo={}, orderSn={}, attempts={}, reason={}",
                        candidate.getChannel(), candidate.getPayTradeNo(), candidate.getOrderSn(), attempts, reason);
            } else {
                log.info("陈旧 PROCESSING 已被其它路径收敛，跳过人工队列 tradeNo={}", candidate.getPayTradeNo());
            }
            return;
        }
        LocalDateTime nextReconcileAt = now.plus(backoff(initialBackoff, attempts));
        int updated = paymentEventMapper.recordReconcileAttempt(candidate.getId(), attempts, nextReconcileAt, message);
        if (updated == 1) {
            log.warn("陈旧 PROCESSING 渠道回查失败，保留可重试状态 channel={}, tradeNo={}, attempt={}/{}, "
                            + "nextReconcileAt={}, reason={}",
                    candidate.getChannel(), candidate.getPayTradeNo(), attempts, maxAttempts, nextReconcileAt, reason);
        } else {
            log.info("陈旧 PROCESSING 已被其它路径收敛，停止重试 tradeNo={}", candidate.getPayTradeNo());
        }
    }

    private Duration backoff(Duration initialBackoff, int attempts) {
        long factor = 1L << Math.min(attempts - 1, 6);
        Duration delay = initialBackoff.multipliedBy(factor);
        return delay.compareTo(MAX_RETRY_BACKOFF) > 0 ? MAX_RETRY_BACKOFF : delay;
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
     * 明确未支付/明确失败：只把仍 PROCESSING 的事件落 FULFILLMENT_FAILED 并告警；
     * 已被主事务并发收敛为 SUCCESS 的事件不降级。该失败态不阻止后续真实 SUCCESS 通知重新履约。
     */
    private void markFailed(PaymentEvent candidate, String reason) {
        int updated = paymentEventMapper.markFulfillmentFailed(candidate.getId(), truncate(reason));
        if (updated == 1) {
            log.error("【支付事件告警】陈旧 PROCESSING 明确无法补履约，标记 FULFILLMENT_FAILED "
                            + "channel={}, tradeNo={}, orderSn={}, reason={}",
                    candidate.getChannel(), candidate.getPayTradeNo(), candidate.getOrderSn(), reason);
        } else {
            log.info("陈旧 PROCESSING 已被其它路径收敛，跳过失败标记 tradeNo={}", candidate.getPayTradeNo());
        }
    }

    private String messageOf(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() <= 512 ? message : message.substring(0, 512);
    }
}
