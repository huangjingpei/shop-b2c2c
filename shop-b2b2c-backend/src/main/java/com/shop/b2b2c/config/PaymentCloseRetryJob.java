/*
 * 文件：PaymentCloseRetryJob.java
 * 用途：F08 渠道关单失败退避重试的定时入口。
 * 维护注意：
 *  1. 默认每 10 分钟一轮、单轮最多 100 条、同一订单最多 5 次关单尝试、只扫描 72 小时内回执；均为默认值，可用配置覆盖。
 *  2. 开关 payment.close-retry.enabled 默认 true；关闭后只停止重试，不影响订单关闭与既有回执。
 *  3. 任务只调用 PaymentCloseRetryService，不直接操作订单或渠道适配器；异常在任务层兜住，等待下一轮。
 *  4. 单实例部署假设；多实例前须补跨实例抢占，避免重复调用渠道关单。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.service.payment.PaymentCloseRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCloseRetryJob {

    private static final int BATCH_SIZE = 100;

    private final PaymentCloseRetryService retryService;

    @Value("${payment.close-retry.enabled:true}")
    private boolean enabled = true;

    @Value("${payment.close-retry.max-attempts:5}")
    private int maxAttempts = 5;

    @Value("${payment.close-retry.interval-minutes:10}")
    private long intervalMinutes = 10L;

    @Value("${payment.close-retry.window-hours:72}")
    private long windowHours = 72L;

    @Scheduled(fixedDelayString = "${payment.close-retry.delay-ms:600000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            retryService.retryPending(maxAttempts, Duration.ofMinutes(intervalMinutes),
                    Duration.ofHours(windowHours), BATCH_SIZE);
        } catch (Exception e) {
            log.error("渠道关单重试扫描异常，等待下一轮", e);
        }
    }
}
