/*
 * 文件：MerchantAccountLedgerMapper.java
 * 用途：服务费账本查询（T34），按幂等键判重并汇总账期实收/欠费。
 * 维护注意：账本只追加；查询汇总必须限定商户与时间区间，禁止全表扫描。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantAccountLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface MerchantAccountLedgerMapper extends BaseMapper<MerchantAccountLedger> {

    default Optional<MerchantAccountLedger> findByIdempotencyKey(String key) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<MerchantAccountLedger>()
                .eq(MerchantAccountLedger::getIdempotencyKey, key)));
    }

    default List<MerchantAccountLedger> findByMerchantAndPeriod(Long merchantId, LocalDateTime from, LocalDateTime to) {
        return selectList(new LambdaQueryWrapper<MerchantAccountLedger>()
                .eq(MerchantAccountLedger::getMerchantId, merchantId)
                .ge(MerchantAccountLedger::getCreatedAt, from)
                .lt(MerchantAccountLedger::getCreatedAt, to)
                .orderByAsc(MerchantAccountLedger::getId));
    }

    @Select("SELECT COALESCE(SUM(amount), 0) FROM merchant_account_ledger "
            + "WHERE merchant_id = #{merchantId} AND entry_type = 'SERVICE_FEE_CHARGE' "
            + "AND created_at >= #{from} AND created_at < #{to}")
    BigDecimal sumChargedByPeriod(@Param("merchantId") Long merchantId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM merchant_account_ledger "
            + "WHERE merchant_id = #{merchantId} AND entry_type = 'SERVICE_FEE_ARREARS' "
            + "AND created_at >= #{from} AND created_at < #{to}")
    BigDecimal sumArrearsByPeriod(@Param("merchantId") Long merchantId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);
}
