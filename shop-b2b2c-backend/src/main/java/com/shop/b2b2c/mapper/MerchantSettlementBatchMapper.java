/*
 * 文件：MerchantSettlementBatchMapper.java
 * 用途：结算批次数据访问（T34），按店铺+账期定位唯一批次。
 * 维护注意：批次确认后不可重算；查询必须带商户维度，避免跨店读取。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantSettlementBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Mapper
public interface MerchantSettlementBatchMapper extends BaseMapper<MerchantSettlementBatch> {

    @Select("SELECT * FROM merchant_settlement_batch WHERE id = #{id} FOR UPDATE")
    MerchantSettlementBatch lockById(@Param("id") Long id);

    default Optional<MerchantSettlementBatch> findByPeriod(Long merchantId, LocalDate start, LocalDate end) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<MerchantSettlementBatch>()
                .eq(MerchantSettlementBatch::getMerchantId, merchantId)
                .eq(MerchantSettlementBatch::getPeriodStart, start)
                .eq(MerchantSettlementBatch::getPeriodEnd, end)
                .eq(MerchantSettlementBatch::getSettlementType, "SERVICE_FEE")));
    }

    default List<MerchantSettlementBatch> findByMerchant(Long merchantId, int limit) {
        return selectList(new LambdaQueryWrapper<MerchantSettlementBatch>()
                .eq(MerchantSettlementBatch::getMerchantId, merchantId)
                .orderByDesc(MerchantSettlementBatch::getPeriodStart)
                .last("LIMIT " + limit));
    }
}
