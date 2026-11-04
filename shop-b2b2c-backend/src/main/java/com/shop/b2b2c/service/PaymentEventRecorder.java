/*
 * 文件：PaymentEventRecorder.java
 * 用途：支付流水独立事务记录器 (REQUIRES_NEW)，确保审计日志在业务异常回滚时不丢失 (R13)。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventRecorder {

    private final PaymentEventMapper paymentEventMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PaymentEvent recordInitialEvent(PaymentEvent pe) {
        if (pe.getId() == null) {
            paymentEventMapper.insert(pe);
        } else {
            paymentEventMapper.updateById(pe);
        }
        return pe;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordEventStatus(Long eventId, String status, String errorMessage) {
        if (eventId == null) return;
        PaymentEvent pe = paymentEventMapper.selectById(eventId);
        if (pe != null) {
            pe.setEventStatus(status);
            pe.setErrorMessage(errorMessage);
            paymentEventMapper.updateById(pe);
        }
    }
}
