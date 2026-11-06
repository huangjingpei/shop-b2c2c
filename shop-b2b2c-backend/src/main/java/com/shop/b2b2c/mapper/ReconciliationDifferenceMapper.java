/*
 * 文件：ReconciliationDifferenceMapper.java
 * 用途：对账差异队列数据访问（T35）。
 * 维护注意：差异键唯一防重；查询按商户/状态过滤并限制条数。
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
}
