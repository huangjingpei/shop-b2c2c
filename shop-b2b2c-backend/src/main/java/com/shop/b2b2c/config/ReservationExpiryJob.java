/*
 * 文件：ReservationExpiryJob.java
 * 用途：定时扫描到期的库存预占及超时未支付订单，并调用事务服务释放与关单；同时补偿支付事件不一致。
 * 维护注意：
 *  1. 采用游标分页扫描，单条坏账异常不阻断饿死后续正常单据；统一扫描 HELD 预占与无预占超期未支付意向单。
 *  2. 支付补偿每轮只调用一次合并扫描：同时处理“事件 SUCCESS 但订单未履约”和“超过阈值的陈旧 PROCESSING”；
 *     失败记录由补偿服务写入 FULFILLMENT_FAILED / MANUAL_REVIEW 并告警，不在此重复处理。
 *  3. 扫描频率由 inventory.expiry-delay-ms 控制（默认 30 秒）；陈旧 PROCESSING 阈值、
 *     最大重试次数与初始退避分别由 payment.reconcile.processing-threshold-minutes（默认 5 分钟）、
 *     payment.reconcile.max-attempts（默认 5 次）、payment.reconcile.retry-backoff-seconds（默认 60 秒）控制，
 *     不得为补偿另起第二套调度任务。
 *  4. T11：批量达到上限时输出 WARN 积压告警，便于监控；扫描使用固定批大小，不随请求量放大。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.mapper.InventoryReservationMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.service.PeerPayService;
import com.shop.b2b2c.service.payment.PaymentEventReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class ReservationExpiryJob {
    private static final int SCAN_BATCH_SIZE = 100;
    private static final int PAYMENT_RECONCILE_BATCH_SIZE = 50;

    private final InventoryReservationMapper reservations;
    private final TradeOrderMapper orders;
    private final PeerPayService service;
    private final PaymentEventReconcileService paymentEventReconcileService;

    @Value("${payment.reconcile.processing-threshold-minutes:5}")
    private long processingThresholdMinutes = 5L;

    @Value("${payment.reconcile.max-attempts:5}")
    private int processingMaxAttempts = 5;

    @Value("${payment.reconcile.retry-backoff-seconds:60}")
    private long processingRetryBackoffSeconds = 60L;

    private volatile long reservationCursor = 0L;
    private volatile long orderCursor = 0L;

    @Scheduled(fixedDelayString = "${inventory.expiry-delay-ms:30000}")
    public void expire() {
        expireHeldReservations();
        expireUnpaidOrders();
        reconcilePaymentEvents();
    }

    /**
     * G34 合并补偿扫描：修复“事件 SUCCESS 但订单仍 UNPAID/CLOSED”与“占位后残留 PROCESSING”。
     * 单条失败已由补偿服务转为 FULFILLMENT_FAILED，不会影响下一轮正常单据。
     */
    public int reconcilePaymentEvents() {
        try {
            return paymentEventReconcileService.reconcile(PAYMENT_RECONCILE_BATCH_SIZE,
                    Duration.ofMinutes(processingThresholdMinutes),
                    processingMaxAttempts,
                    Duration.ofSeconds(processingRetryBackoffSeconds));
        } catch (Exception e) {
            log.error("支付事件补偿扫描异常，等待下一轮重试", e);
            return 0;
        }
    }

    public int expireHeldReservations() {
        int processed = 0;
        List<Long> ids = reservations.expiredIdsAfter(reservationCursor, SCAN_BATCH_SIZE);
        if (ids.isEmpty() && reservationCursor > 0L) {
            reservationCursor = 0L;
            ids = reservations.expiredIdsAfter(0L, SCAN_BATCH_SIZE);
        }
        for (Long id : ids) {
            reservationCursor = Math.max(reservationCursor, id);
            try {
                service.expireReservation(id);
                processed++;
            } catch (Exception e) {
                log.error("预占释放异常，已记录并前移游标避免饿死后续单据 orderId={}", id, e);
            }
        }
        if (ids.size() < SCAN_BATCH_SIZE) {
            reservationCursor = 0L;
        } else {
            log.warn("库存预占到期扫描达到批量上限，可能存在积压 cursor={} batch={}", reservationCursor, SCAN_BATCH_SIZE);
        }
        return processed;
    }

    public int expireUnpaidOrders() {
        int processed = 0;
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = orders.findExpiredUnpaidOrderIds(now, orderCursor, SCAN_BATCH_SIZE);
        if (ids.isEmpty() && orderCursor > 0L) {
            orderCursor = 0L;
            ids = orders.findExpiredUnpaidOrderIds(now, 0L, SCAN_BATCH_SIZE);
        }
        for (Long id : ids) {
            orderCursor = Math.max(orderCursor, id);
            try {
                service.expireReservation(id);
                processed++;
            } catch (Exception e) {
                log.error("超时未支付订单关闭异常，已记录并前移游标避免饿死后续单据 orderId={}", id, e);
            }
        }
        if (ids.size() < SCAN_BATCH_SIZE) {
            orderCursor = 0L;
        } else {
            log.warn("超时未支付订单扫描达到批量上限，可能存在积压 cursor={} batch={}", orderCursor, SCAN_BATCH_SIZE);
        }
        return processed;
    }

    public long getReservationCursor() {
        return reservationCursor;
    }

    public long getOrderCursor() {
        return orderCursor;
    }

    public void resetCursors() {
        this.reservationCursor = 0L;
        this.orderCursor = 0L;
    }
}
