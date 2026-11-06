/*
 * 文件：PayoutVerificationExpireJob.java
 * 用途：O5 打款验证过期任务：扫描 PENDING 且超过 expires_at 的验证单，逐条交
 *      MerchantPayoutVerificationService.expireOne 行锁复核并置 EXPIRED，允许账户重新发起验证。
 * 维护注意：
 * 1. 扫描频率 cron 由 shop.payout-verify.expire-cron 控制（默认每 15 分钟）；批量上限
 *    shop.payout-verify.expire-batch-size（默认 100）；时间口径 Asia/Shanghai（与全局 JVM 时区一致）。
 * 2. 单条失败只记录 ERROR 并继续后续单据，不阻断整轮；服务层行锁内复核状态，已完结单据静默跳过。
 * 3. 过期只推进验证单状态，不改账户状态；账户驳回只发生在第 3 次金额不匹配时。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.service.MerchantPayoutVerificationService;
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
public class PayoutVerificationExpireJob {

    private final MerchantPayoutVerificationService payoutVerificationService;

    @Value("${shop.payout-verify.expire-batch-size:100}")
    private int batchSize = 100;

    @Scheduled(cron = "${shop.payout-verify.expire-cron:0 */15 * * * ?}")
    public void expire() {
        List<Long> ids = payoutVerificationService.findExpiredPendingIds(LocalDateTime.now(), batchSize);
        int processed = 0;
        for (Long id : ids) {
            try {
                if (payoutVerificationService.expireOne(id)) {
                    processed++;
                }
            } catch (Exception e) {
                log.error("打款验证过期处理异常，跳过等待下一轮 verificationId={}", id, e);
            }
        }
        if (!ids.isEmpty()) {
            log.info("打款验证过期处理完成 scanned={} expired={}", ids.size(), processed);
        }
        if (ids.size() >= batchSize) {
            log.warn("打款验证过期扫描达到批量上限，可能存在积压 batch={}", batchSize);
        }
    }
}
