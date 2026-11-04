/*
 * 文件：PaymentEventMapper.java
 * 用途：支付到账事件数据访问，支撑支付流水唯一排重与核对查询 (R13)。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.PaymentEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface PaymentEventMapper extends BaseMapper<PaymentEvent> {

    @Select("SELECT * FROM payment_event WHERE channel = #{channel} AND pay_trade_no = #{payTradeNo} LIMIT 1")
    PaymentEvent findByChannelAndTradeNo(@Param("channel") String channel, @Param("payTradeNo") String payTradeNo);

    default Optional<PaymentEvent> findByTradeNo(String channel, String payTradeNo) {
        return Optional.ofNullable(findByChannelAndTradeNo(channel, payTradeNo));
    }

    default List<PaymentEvent> findByOrderSn(String orderSn) {
        return selectList(new LambdaQueryWrapper<PaymentEvent>()
                .eq(PaymentEvent::getOrderSn, orderSn)
                .orderByDesc(PaymentEvent::getId));
    }
}
