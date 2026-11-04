/*
 * 文件：IdempotencyPurgeJob.java
 * 用途：R27 — 按保留期定时清理已完成的幂等快照，防止 response_json 中的
 *       queryToken / ownerToken / 卡密领取凭证等私密字段长期留存在数据库。
 * 维护注意：
 *   - 只删除 response_json IS NOT NULL（已完成）且 created_at < now()-retentionHours 的记录。
 *   - 未完成占位（response_json IS NULL）不清除，以保护并发重试的幂等窗口。
 *   - 每批最多删除 batchSize 行（默认 500），防止单次大事务锁争用。
 *   - 若当批删满 batchSize 则立即再次运行直到清完，避免积压。
 *   - retentionHours 默认 72（3 天），可通过环境变量 IDEMPOTENCY_RETENTION_HOURS 调整。
 *   - 此保留期只是删除临界，不代表调用方对同一幂等键的重试必然在 72h 内完成；
 *     超出保留期后相同键的新请求将被当作新建单而非重放。
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

    /** 幂等快照保留时长（小时），默认 72h = 3 天 */
    @Value("${idempotency.retention-hours:72}")
    private int retentionHours;

    /** 每批最多删除行数，防止单批锁争用 */
    @Value("${idempotency.purge-batch-size:500}")
    private int batchSize;

    /**
     * 每小时整点运行一次（固定延迟 3600s）。
     * 若当批删满则立即再次执行，直到本轮无可删记录为止。
     */
    @Scheduled(fixedDelayString = "${idempotency.purge-delay-ms:3600000}")
    public void purge() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(retentionHours);
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = idempotencyMapper.deleteExpiredBefore(cutoff, batchSize);
            totalDeleted += deleted;
        } while (deleted >= batchSize);
        if (totalDeleted > 0) {
            log.info("R27 幂等快照清理完成：共删除 {} 条已完成记录（created_at < {}，保留期 {}h）",
                    totalDeleted, cutoff, retentionHours);
        }
    }

    // 供测试调用的包级辅助方法，不暴露为 API
    int purgeWithCutoff(LocalDateTime cutoff) {
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = idempotencyMapper.deleteExpiredBefore(cutoff, batchSize);
            totalDeleted += deleted;
        } while (deleted >= batchSize);
        return totalDeleted;
    }
}
