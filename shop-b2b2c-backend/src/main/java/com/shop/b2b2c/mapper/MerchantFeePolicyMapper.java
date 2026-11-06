/*
 * 文件：MerchantFeePolicyMapper.java
 * 用途：费率政策的生效区间查询、未生效/未来政策查询与历史列表。
 * 维护注意：
 * 1. findEffective 以 effectiveFrom <= at 且 effectiveTo 为空或 > at 为准，按生效时间倒序取一条；
 *    调用方依赖“同一商户同一时刻只应有一套政策”，并发创建时需人工核验 MySQL 结果。
 * 2. findScheduledAfter 用于调价时作废旧的自然日预约政策，避免同一天两套费率并存。
 * 3. 查询不做金额计算，费率以整数基点返回，避免浮点误差。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantFeePolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MerchantFeePolicyMapper extends BaseMapper<MerchantFeePolicy> {

    @Select("SELECT * FROM merchant_fee_policy WHERE merchant_id = #{merchantId} "
            + "AND effective_from <= #{at} AND (effective_to IS NULL OR effective_to > #{at}) "
            + "ORDER BY effective_from DESC, id DESC LIMIT 1")
    MerchantFeePolicy findEffective(@Param("merchantId") Long merchantId, @Param("at") LocalDateTime at);

    @Select("SELECT * FROM merchant_fee_policy WHERE merchant_id = #{merchantId} "
            + "AND effective_from > #{at} AND (effective_to IS NULL OR effective_to > #{at}) "
            + "ORDER BY effective_from ASC, id ASC")
    List<MerchantFeePolicy> findScheduledAfter(@Param("merchantId") Long merchantId, @Param("at") LocalDateTime at);

    @Select("SELECT * FROM merchant_fee_policy WHERE merchant_id = #{merchantId} "
            + "ORDER BY effective_from DESC, id DESC")
    List<MerchantFeePolicy> findByMerchantId(@Param("merchantId") Long merchantId);
}
