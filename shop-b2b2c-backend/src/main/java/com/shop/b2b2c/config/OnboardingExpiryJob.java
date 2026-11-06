/*
 * 文件：OnboardingExpiryJob.java
 * 用途：D3 入驻申请逾期撤回定时任务：APPROVED 超期未签协议/未开通、SUPPLEMENT_REQUIRED 超期未补件的申请，
 *      扫描后逐条交 MerchantOnboardingService.expireStaleApplication 行锁复核并置 WITHDRAWN（审计 EXPIRE）。
 * 维护注意：
 * 1. 扫描频率 cron 由 shop.onboarding.expire-cron 控制（默认每天 03:40）；逾期天数分别由
 *    shop.onboarding.agreement-expire-days 与 shop.onboarding.supplement-expire-days 控制（默认均 30 天，D3 推荐值 A），
 *    时间口径 Asia/Shanghai（与全局 JVM 时区一致）；批量上限 shop.onboarding.expire-batch-size（默认 100）。
 * 2. 单条失败只记录 ERROR 并继续后续单据，不阻断整轮；服务层在行锁内复核状态，被人工推进的单据静默跳过。
 * 3. 本任务只撤回申请；逾期申请已建的待开通店铺与店长账号的停用/注销属 O6 店铺状态机范围，不在此处理。
 * 4. 调度开关沿用 ReservationExpiryJob 的 @EnableScheduling，本类不重复开启。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.service.MerchantOnboardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.List;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class OnboardingExpiryJob {

    private final MerchantOnboardingService onboardingService;

    @Value("${shop.onboarding.agreement-expire-days:30}")
    private long agreementExpireDays = 30L;

    @Value("${shop.onboarding.supplement-expire-days:30}")
    private long supplementExpireDays = 30L;

    @Value("${shop.onboarding.expire-batch-size:100}")
    private int batchSize = 100;

    @Scheduled(cron = "${shop.onboarding.expire-cron:0 40 3 * * ?}")
    public void expire() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = onboardingService.findExpirableIds(
                now.minusDays(agreementExpireDays), now.minusDays(supplementExpireDays), batchSize);
        int processed = 0;
        for (Long id : ids) {
            try {
                if (onboardingService.expireStaleApplication(id)) {
                    processed++;
                }
            } catch (Exception e) {
                log.error("入驻申请逾期撤回异常，跳过等待下一轮 applicationId={}", id, e);
            }
        }
        if (!ids.isEmpty()) {
            log.info("入驻申请逾期撤回完成 scanned={} withdrawn={}", ids.size(), processed);
        }
        if (ids.size() >= batchSize) {
            log.warn("入驻申请逾期扫描达到批量上限，可能存在积压 batch={}", batchSize);
        }
    }
}
