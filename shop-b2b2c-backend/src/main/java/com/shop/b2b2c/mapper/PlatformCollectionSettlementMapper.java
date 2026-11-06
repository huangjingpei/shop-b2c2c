/*
 * 文件：PlatformCollectionSettlementMapper.java
 * 用途：T40 平台代收结算台账的幂等查询、行锁与退款冲减。
 * 维护注意：
 * 1. 一笔订单只允许一条台账（uk_platform_settlement_order）；重试先查原记录。
 * 2. lockByOrderId 使用 SELECT ... FOR UPDATE，退款冲减与状态推进必须在锁内或条件 UPDATE 中完成。
 * 3. 骨架阶段不写 SETTLED；本 Mapper 不调用真实结算/分账接口。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.PlatformCollectionSettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.Optional;

@Mapper
public interface PlatformCollectionSettlementMapper extends BaseMapper<PlatformCollectionSettlement> {

    default Optional<PlatformCollectionSettlement> findByOrderId(Long orderId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<PlatformCollectionSettlement>()
                .eq(PlatformCollectionSettlement::getOrderId, orderId)));
    }

    @Select("SELECT * FROM platform_collection_settlement WHERE order_id = #{orderId} FOR UPDATE")
    PlatformCollectionSettlement lockByOrderId(@Param("orderId") Long orderId);

    @Update("UPDATE platform_collection_settlement SET refunded_amount = refunded_amount + #{amount}, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE order_id = #{orderId} AND refunded_amount + #{amount} <= merchant_payable_amount")
    int addRefundedAmount(@Param("orderId") Long orderId, @Param("amount") BigDecimal amount);
}
