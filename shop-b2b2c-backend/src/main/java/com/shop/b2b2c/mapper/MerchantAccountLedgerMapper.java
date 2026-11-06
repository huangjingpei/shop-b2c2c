/*
 * 文件：MerchantAccountLedgerMapper.java
 * 用途：服务费账本查询（T34），按幂等键判重并汇总账期实收/欠费；账本只允许追加，不提供业务更新/删除入口。
 * 维护注意：
 * 1. 查询汇总必须限定商户与时间区间，禁止全表扫描。
 * 2. 账本是资金证据，只能追加；更正必须新增反向/红冲分录，禁止用 BaseMapper 的 update/delete 改历史。
 * 3. schema.sql 用 trg_ledger_no_update/trg_ledger_no_delete 兜底；H2 夹具不建触发器，真实 MySQL 行为须人工验证。
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
