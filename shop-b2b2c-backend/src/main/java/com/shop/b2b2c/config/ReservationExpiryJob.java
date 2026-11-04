/*
 * 文件：ReservationExpiryJob.java
 * 用途：定时扫描到期的库存预占及超时未支付订单，并调用事务服务释放与关单。
 * 维护注意：采用游标分页扫描，单条坏账异常不阻断饿死后续正常单据；统一扫描HELD预占与无预占超期未支付意向单。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.mapper.InventoryReservationMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.service.PeerPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.List;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class ReservationExpiryJob {
    private final InventoryReservationMapper reservations;
    private final TradeOrderMapper orders;
    private final PeerPayService service;

    private volatile long reservationCursor = 0L;
    private volatile long orderCursor = 0L;

    @Scheduled(fixedDelayString = "${inventory.expiry-delay-ms:30000}")
    public void expire() {
        expireHeldReservations();
        expireUnpaidOrders();
    }

    public int expireHeldReservations() {
        int processed = 0;
        List<Long> ids = reservations.expiredIdsAfter(reservationCursor, 100);
        if (ids.isEmpty() && reservationCursor > 0L) {
            reservationCursor = 0L;
            ids = reservations.expiredIdsAfter(0L, 100);
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
        if (ids.size() < 100) {
            reservationCursor = 0L;
        }
        return processed;
    }

    public int expireUnpaidOrders() {
        int processed = 0;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusMinutes(15);
        List<Long> ids = orders.findExpiredUnpaidOrderIds(now, cutoff, orderCursor, 100);
        if (ids.isEmpty() && orderCursor > 0L) {
            orderCursor = 0L;
            ids = orders.findExpiredUnpaidOrderIds(now, cutoff, 0L, 100);
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
        if (ids.size() < 100) {
            orderCursor = 0L;
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
