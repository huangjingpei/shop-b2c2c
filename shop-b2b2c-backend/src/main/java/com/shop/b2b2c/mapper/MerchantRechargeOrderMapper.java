/*
 * 文件：MerchantRechargeOrderMapper.java
 * 用途：商家充值单数据访问（T34），提供单号/凭证号查询与确认前加锁。
 * 维护注意：凭证号唯一性由数据库约束兜底；确认时必须锁行并检查状态仍为 PENDING。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantRechargeOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface MerchantRechargeOrderMapper extends BaseMapper<MerchantRechargeOrder> {

    @Select("SELECT * FROM merchant_recharge_order WHERE id = #{id} FOR UPDATE")
    MerchantRechargeOrder lockById(@Param("id") Long id);

    default Optional<MerchantRechargeOrder> findByRechargeNo(String rechargeNo) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<MerchantRechargeOrder>()
                .eq(MerchantRechargeOrder::getRechargeNo, rechargeNo)));
    }

    default Optional<MerchantRechargeOrder> findByVoucherNo(String voucherNo) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<MerchantRechargeOrder>()
                .eq(MerchantRechargeOrder::getVoucherNo, voucherNo)));
    }

    default List<MerchantRechargeOrder> findByMerchant(Long merchantId) {
        return selectList(new LambdaQueryWrapper<MerchantRechargeOrder>()
                .eq(MerchantRechargeOrder::getMerchantId, merchantId)
                .orderByDesc(MerchantRechargeOrder::getId));
    }

    default List<MerchantRechargeOrder> findPending(Long merchantId) {
        return selectList(new LambdaQueryWrapper<MerchantRechargeOrder>()
                .eq(merchantId != null, MerchantRechargeOrder::getMerchantId, merchantId)
                .eq(MerchantRechargeOrder::getStatus, "PENDING")
                .orderByAsc(MerchantRechargeOrder::getId));
    }
}
