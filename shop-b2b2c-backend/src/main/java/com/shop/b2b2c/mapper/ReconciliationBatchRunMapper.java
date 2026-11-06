/*
 * 文件：ReconciliationBatchRunMapper.java
 * 用途：T+1 跑批记录的数据访问：按业务日/商户幂等查询、抢占 RUNNING、转人工与列表查询。
 * 维护注意：
 *  1. markRunning 必须带条件（PENDING/FAILED/PARTIAL 或僵尸 RUNNING），保证多实例只有一个能抢到同一店同一天的跑批。
 *  2. 完成状态必须用 finishRun 条件回写（status=RUNNING + attempts + started_at），防止超时被接管后
 *     原节点覆盖接管方结果；SUCCESS 不重复跑，MANUAL 不再自动重跑。
 *  3. markManual 覆盖 FAILED/PENDING/PARTIAL 或僵尸 RUNNING，不覆盖已 SUCCESS 的记录。
 *  4. addRetryTally 只累加重试计数与异常摘要，不改跑批主状态。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.ReconciliationBatchRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ReconciliationBatchRunMapper extends BaseMapper<ReconciliationBatchRun> {

    default Optional<ReconciliationBatchRun> findByDateAndMerchant(LocalDate businessDate, Long merchantId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<ReconciliationBatchRun>()
                .eq(ReconciliationBatchRun::getBusinessDate, businessDate)
                .eq(ReconciliationBatchRun::getMerchantId, merchantId)));
    }

    /**
     * 抢占跑批：PENDING/FAILED/PARTIAL 可重跑；RUNNING 超过 staleBefore 视为僵尸可接管；SUCCESS/MANUAL 不允许。
     */
    @Update("UPDATE reconciliation_batch_run SET status='RUNNING', attempts=#{attempts}, "
            + "started_at=#{startedAt}, finished_at=NULL, last_error=NULL "
            + "WHERE id=#{id} AND (status IN ('PENDING','FAILED','PARTIAL') "
            + "OR (status='RUNNING' AND started_at IS NOT NULL AND started_at < #{staleBefore}))")
    int markRunning(@Param("id") Long id, @Param("attempts") int attempts,
                    @Param("startedAt") LocalDateTime startedAt, @Param("staleBefore") LocalDateTime staleBefore);

    /** 失败次数用尽后转人工；覆盖 FAILED/PENDING/PARTIAL 或超过 2 小时的僵尸 RUNNING，避免误伤并发中的跑批。 */
    @Update("UPDATE reconciliation_batch_run SET status='MANUAL', finished_at=#{finishedAt}, "
            + "last_error=#{lastError}, updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND (status IN ('FAILED','PENDING','PARTIAL') "
            + "OR (status='RUNNING' AND started_at IS NOT NULL AND started_at < #{staleBefore}))")
    int markManual(@Param("id") Long id, @Param("finishedAt") LocalDateTime finishedAt,
                   @Param("lastError") String lastError, @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 完成回写：只有仍持有同一 status=RUNNING、attempts、started_at 的节点才能写成功，实现无租约字段的防覆盖。
     * 返回 0 表示已被其他节点/人工接管，调用方不得覆盖。
     */
    @Update("UPDATE reconciliation_batch_run SET status=#{status}, statement_count=#{statementCount}, "
            + "event_count=#{eventCount}, refund_count=#{refundCount}, difference_added=#{differenceAdded}, "
            + "difference_refreshed=#{differenceRefreshed}, last_error=#{lastError}, "
            + "finished_at=#{finishedAt}, updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND status='RUNNING' AND attempts=#{attempts} AND started_at=#{startedAt}")
    int finishRun(@Param("id") Long id, @Param("attempts") int attempts,
                  @Param("startedAt") LocalDateTime startedAt, @Param("status") String status,
                  @Param("statementCount") int statementCount, @Param("eventCount") int eventCount,
                  @Param("refundCount") int refundCount, @Param("differenceAdded") int differenceAdded,
                  @Param("differenceRefreshed") int differenceRefreshed,
                  @Param("lastError") String lastError, @Param("finishedAt") LocalDateTime finishedAt);

    /** 自动重试计数与异常摘要单独累加；last_error 仅在本次有异常时才追加。 */
    @Update("UPDATE reconciliation_batch_run SET retry_processed=retry_processed+#{processed}, "
            + "retry_failed=retry_failed+#{failed}, manual_escalated=manual_escalated+#{escalated}, "
            + "last_error=COALESCE(#{lastError}, last_error), updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id}")
    int addRetryTally(@Param("id") Long id, @Param("processed") int processed,
                      @Param("failed") int failed, @Param("escalated") int escalated,
                      @Param("lastError") String lastError);

    default List<ReconciliationBatchRun> find(LocalDate businessDate, Long merchantId, int limit) {
        return selectList(new LambdaQueryWrapper<ReconciliationBatchRun>()
                .eq(businessDate != null, ReconciliationBatchRun::getBusinessDate, businessDate)
                .eq(merchantId != null, ReconciliationBatchRun::getMerchantId, merchantId)
                .orderByDesc(ReconciliationBatchRun::getBusinessDate)
                .orderByDesc(ReconciliationBatchRun::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }
}
