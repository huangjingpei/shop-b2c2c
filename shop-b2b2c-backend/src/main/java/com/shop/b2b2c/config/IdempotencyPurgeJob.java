/*
 * 文件：IdempotencyPurgeJob.java
 * 用途：R27/T27 — 按保留期定时清理 order_idempotency 中已完成的轻量映射，保留键→订单号 30 天，
 *       避免 queryToken/ownerToken/买家联系方式/海报大字段长期留存在数据库。
 * 维护注意：
 *   - Q18-B：默认保留 720h（30 天）；快照由 OrderIdempotencyService 脱敏后写入，不含 queryToken、buyerContact、posterBase64。
 *   - 只删除 response_json IS NOT NULL（已完成）且 created_at 早于截止时间的记录；未完成占位不清除，保护并发重试窗口。
 *   - 每轮最多删除 batchSize 行（默认 500），且受最大轮次与时间预算双重约束；达到上限即退出，剩余积压交给下一周期。
 *   - 与迁移/备份顺序：SchemaMigrationRunner 在应用就绪前完成迁移；本任务 initialDelay 默认 10 分钟，
 *     避免与启动迁移、恢复后的首次校验重叠；T21 备份在迁移前用 --single-transaction 生成一致性快照，
 *     清理只删 30 天前已完成记录，不改变备份内容；从备份恢复后重启仍先迁移、再按延迟清理。
 *   - 超过保留期的同一幂等键会被当作新请求而非重放，调用方须在此之前完成重试。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.mapper.IdempotencyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyPurgeJob {

    private final IdempotencyMapper idempotencyMapper;

    /** T27/Q18-B：幂等轻量映射保留时长（小时），默认 720h = 30 天 */
    @Value("${idempotency.retention-hours:720}")
    private int retentionHours = 720;

    /** 每批最多删除行数，防止单批锁争用 */
    @Value("${idempotency.purge-batch-size:500}")
    private int batchSize = 500;

    /** 单次运行最多清理轮次，防止积压时长时间占用连接与锁 */
    @Value("${idempotency.purge-max-rounds:20}")
    private int maxRounds = 20;

    /** 单次运行时间预算（毫秒），即使未达最大轮次也在预算耗尽后退出 */
    @Value("${idempotency.purge-time-budget-ms:30000}")
    private long timeBudgetMs = 30_000L;

    /**
     * 每小时整点运行一次（固定延迟 3600s），首次延迟 10 分钟以避开启动迁移与备份窗口。
     */
    @Scheduled(fixedDelayString = "${idempotency.purge-delay-ms:3600000}",
            initialDelayString = "${idempotency.purge-initial-delay-ms:600000}")
    public void purge() {
        purgeWithCutoff(LocalDateTime.now().minusHours(retentionHours));
    }

    // 供测试调用的包级辅助方法，不暴露为 API
    int purgeWithCutoff(LocalDateTime cutoff) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeBudgetMs);
        int totalDeleted = 0;
        int rounds = 0;
        while (true) {
            int deleted = idempotencyMapper.deleteExpiredBefore(cutoff, batchSize);
            totalDeleted += deleted;
            rounds++;
            if (deleted < batchSize) break;
            if (rounds >= Math.max(1, maxRounds)) {
                log.warn("T27 幂等清理达到最大轮次 {}，本次删除 {} 条，剩余积压待下一周期", maxRounds, totalDeleted);
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                log.warn("T27 幂等清理达到时间预算 {}ms，本次删除 {} 条，剩余积压待下一周期", timeBudgetMs, totalDeleted);
                break;
            }
        }
        if (totalDeleted > 0) {
            log.info("T27 幂等轻量映射清理完成：共删除 {} 条已完成记录（created_at < {}，保留期 {}h）",
                    totalDeleted, cutoff, retentionHours);
        }
        return totalDeleted;
    }
}
