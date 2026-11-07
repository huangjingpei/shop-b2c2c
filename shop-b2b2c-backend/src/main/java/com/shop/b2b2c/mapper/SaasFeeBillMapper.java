/*
 * 文件：SaasFeeBillMapper.java
 * 用途：F10 SaaS 月度账单数据访问：同店同月唯一查询、加锁、商家/超管列表与逾期扫描候选。
 * 维护注意：
 *  1. 缴费确认和逾期置位必须先 lockById 再改状态，禁止无锁先查后改。
 *  2. 商家查询必须按 merchantId 过滤；跨店列表只用于超管接口。
 *  3. 逾期候选只取 PENDING 且 due_date 早于扫描日，limit 由调用方限制，避免一次拉全表。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.SaasFeeBill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Mapper
public interface SaasFeeBillMapper extends BaseMapper<SaasFeeBill> {

    @Select("SELECT * FROM saas_fee_bill WHERE id = #{id} FOR UPDATE")
    SaasFeeBill lockById(@Param("id") Long id);

    default Optional<SaasFeeBill> findByMerchantMonth(Long merchantId, String billingMonth) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SaasFeeBill>()
                .eq(SaasFeeBill::getMerchantId, merchantId)
                .eq(SaasFeeBill::getBillingMonth, billingMonth)));
    }

    default Optional<SaasFeeBill> findByBillNo(String billNo) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SaasFeeBill>()
                .eq(SaasFeeBill::getBillNo, billNo)));
    }

    default List<SaasFeeBill> findByMerchant(Long merchantId, int limit) {
        return selectList(new LambdaQueryWrapper<SaasFeeBill>()
                .eq(SaasFeeBill::getMerchantId, merchantId)
                .orderByDesc(SaasFeeBill::getBillingMonth)
                .last("LIMIT " + limit));
    }

    default List<SaasFeeBill> search(Long merchantId, String status, String billingMonth, int limit, long offset) {
        LambdaQueryWrapper<SaasFeeBill> query = new LambdaQueryWrapper<SaasFeeBill>()
                .eq(merchantId != null, SaasFeeBill::getMerchantId, merchantId)
                .eq(status != null && !status.isBlank(), SaasFeeBill::getStatus, status)
                .eq(billingMonth != null && !billingMonth.isBlank(), SaasFeeBill::getBillingMonth, billingMonth)
                .orderByDesc(SaasFeeBill::getBillingMonth)
                .orderByDesc(SaasFeeBill::getId)
                .last("LIMIT " + limit + " OFFSET " + offset);
        return selectList(query);
    }

    default List<SaasFeeBill> findPendingDueBefore(LocalDate asOf, int limit) {
        return selectList(new LambdaQueryWrapper<SaasFeeBill>()
                .eq(SaasFeeBill::getStatus, "PENDING")
                .lt(SaasFeeBill::getDueDate, asOf)
                .orderByAsc(SaasFeeBill::getDueDate)
                .orderByAsc(SaasFeeBill::getId)
                .last("LIMIT " + limit));
    }
}
