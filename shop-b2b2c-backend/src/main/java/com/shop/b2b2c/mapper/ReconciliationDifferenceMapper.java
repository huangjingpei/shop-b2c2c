/*
 * 文件：ReconciliationDifferenceMapper.java
 * 用途：对账差异队列数据访问（T35/T+1）。
 * 维护注意：
 *  1. 差异键唯一防重；重复对账只刷新未关闭记录。
 *  2. 自动重试按商户读取 DISPUTED/RETRY，最多 MAX_RETRY_COUNT 次；MANUAL/RESOLVED/IGNORED 不再自动处理。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.ReconciliationDifference;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ReconciliationDifferenceMapper extends BaseMapper<ReconciliationDifference> {

    default Optional<ReconciliationDifference> findByKey(String key) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<ReconciliationDifference>()
                .eq(ReconciliationDifference::getDifferenceKey, key)));
    }

    default List<ReconciliationDifference> find(Long merchantId, String status, int limit) {
        return selectList(new LambdaQueryWrapper<ReconciliationDifference>()
                .eq(merchantId != null, ReconciliationDifference::getMerchantId, merchantId)
                .eq(status != null && !status.isBlank(), ReconciliationDifference::getStatus, status)
                .orderByDesc(ReconciliationDifference::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }

    /** T+1 自动重试候选：只取未关闭记录，跨业务日也纳入，避免旧差异永远躺在 RETRY。 */
    default List<ReconciliationDifference> findOpenByMerchant(Long merchantId, int limit) {
        return selectList(new LambdaQueryWrapper<ReconciliationDifference>()
                .eq(ReconciliationDifference::getMerchantId, merchantId)
                .in(ReconciliationDifference::getStatus, "DISPUTED", "RETRY")
                .orderByAsc(ReconciliationDifference::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 500)));
    }

    /** F09 账单状态：账期窗口内是否存在未关闭差异（DISPUTED/RETRY/MANUAL 待办口径与列表一致）。 */
    default long countOpenByMerchantAndBusinessDateBetween(Long merchantId, java.time.LocalDate from,
                                                           java.time.LocalDate to) {
        return selectCount(new LambdaQueryWrapper<ReconciliationDifference>()
                .eq(ReconciliationDifference::getMerchantId, merchantId)
                .in(ReconciliationDifference::getStatus, "DISPUTED", "RETRY", "MANUAL")
                .ge(ReconciliationDifference::getBusinessDate, from)
                .lt(ReconciliationDifference::getBusinessDate, to));
    }
}
