/*
 * 文件：PaymentEventMapper.java
 * 用途：支付到账事件数据访问，支撑支付流水唯一排重、补偿扫描与核对查询 (R13/T05)。
 * 维护注意：补偿查询只读取事件 SUCCESS 但订单未支付/未交付的记录；不得把缺失订单纳入自动补履约，避免无限重试。
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

    /**
     * T33：查询重复付款补偿队列。merchantId 为空时（平台超管）返回全部商户记录。
     */
    @Select("<script>"
            + "SELECT * FROM payment_event WHERE event_status = 'DUPLICATE_PAYMENT' "
            + "<if test='merchantId != null'>AND merchant_id = #{merchantId} </if>"
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<PaymentEvent> findDuplicatePayments(@Param("merchantId") Long merchantId,
                                             @Param("limit") int limit);

    /**
     * T05 补偿扫描：事件已 SUCCESS，但关联订单仍不是 PAID/COMPLETED/SHIPPED。
     * INNER JOIN 排除订单缺失的历史脏数据，避免自动任务无休止重试。
     */
    @Select("SELECT e.* FROM payment_event e INNER JOIN trade_order o ON o.order_sn = e.order_sn "
            + "WHERE e.event_status = 'SUCCESS' "
            + "AND o.order_status NOT IN ('PAID', 'COMPLETED', 'SHIPPED') "
            + "ORDER BY e.id LIMIT #{limit}")
    List<PaymentEvent> findSuccessfulWithoutPaidOrder(@Param("limit") int limit);

    /**
     * T34：结算明细回填支付事件ID；同一订单理论上只取最早的一条 SUCCESS 付款事件。
     */
    @Select("SELECT * FROM payment_event WHERE order_sn = #{orderSn} AND event_status = 'SUCCESS' "
            + "ORDER BY id LIMIT 1")
    PaymentEvent findFirstSuccessByOrderSn(@Param("orderSn") String orderSn);
}
