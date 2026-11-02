package com.shop.b2b2c.config;

import com.shop.b2b2c.mapper.InventoryReservationMapper;
import com.shop.b2b2c.service.PeerPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class ReservationExpiryJob {
    private final InventoryReservationMapper reservations;
    private final PeerPayService service;

    @Scheduled(fixedDelayString = "${inventory.expiry-delay-ms:30000}")
    public void expire() {
        for (Long id : reservations.expiredIds()) {
            try { service.expireReservation(id); }
            catch (Exception e) { log.error("预占释放失败，下一轮重试 orderId={}", id, e); }
        }
    }
}
