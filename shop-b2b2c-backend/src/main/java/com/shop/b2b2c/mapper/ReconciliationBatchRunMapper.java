/*
 * 文件：ReconciliationBatchRunMapper.java
 * 用途：T+1 跑批记录的数据访问：按业务日/商户幂等查询、抢占 RUNNING、转人工与列表查询。
 * 维护注意：
 *  1. markRunning 必须带条件（PENDING/FAILED/PARTIAL 或僵尸 RUNNING），保证多实例只有一个能抢到同一店同一天的跑批。
 *  2. 完成状态用 updateById 在已抢占后写回；SUCCESS 不重复跑，MANUAL 不再自动重跑。
 *  3. markManual 只把 FAILED 升级为 MANUAL，不覆盖已 SUCCESS 的记录。
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

    /** 失败次数用尽后转人工；仅覆盖已失败/待跑或超过 2 小时的僵尸 RUNNING，避免误伤并发中的跑批。 */
    @Update("UPDATE reconciliation_batch_run SET status='MANUAL', finished_at=#{finishedAt}, "
            + "last_error=#{lastError}, updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND (status IN ('FAILED','PENDING') "
            + "OR (status='RUNNING' AND started_at IS NOT NULL AND started_at < #{staleBefore}))")
    int markManual(@Param("id") Long id, @Param("finishedAt") LocalDateTime finishedAt,
                   @Param("lastError") String lastError, @Param("staleBefore") LocalDateTime staleBefore);

    default List<ReconciliationBatchRun> find(LocalDate businessDate, Long merchantId, int limit) {
        return selectList(new LambdaQueryWrapper<ReconciliationBatchRun>()
                .eq(businessDate != null, ReconciliationBatchRun::getBusinessDate, businessDate)
                .eq(merchantId != null, ReconciliationBatchRun::getMerchantId, merchantId)
                .orderByDesc(ReconciliationBatchRun::getBusinessDate)
                .orderByDesc(ReconciliationBatchRun::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }
}
