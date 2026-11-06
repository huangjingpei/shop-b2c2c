/*
 * 文件：TradeRefundMapper.java
 * 用途：退款记录与订单退款额度/服务费冲正的数据访问（T33）。
 * 维护注意：
 *  1. 预占与冲正必须用条件 UPDATE（订单必须已支付且累计退款不超过实付），禁止先查后改的无锁写法。
 *  2. 成功/失败状态迁移都以 status=PROCESSING 为条件，保证重复回调或并发同步只生效一次。
 *  3. 不提供删除接口；退款记录是资金证据，失败记录也保留。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.TradeRefund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Mapper
public interface TradeRefundMapper extends BaseMapper<TradeRefund> {

    default Optional<TradeRefund> findByRefundNo(String refundNo) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradeRefund>()
                .eq(TradeRefund::getRefundNo, refundNo)));
    }

    default Optional<TradeRefund> findByOrderIdAndKey(Long orderId, String idempotencyKey) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradeRefund>()
                .eq(TradeRefund::getOrderId, orderId)
                .eq(TradeRefund::getIdempotencyKey, idempotencyKey)));
    }

    default Optional<TradeRefund> findBySourceEventId(Long sourceEventId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradeRefund>()
                .eq(TradeRefund::getSourceEventId, sourceEventId)));
    }

    default List<TradeRefund> findByOrderSn(String orderSn) {
        return selectList(new LambdaQueryWrapper<TradeRefund>()
                .eq(TradeRefund::getOrderSn, orderSn)
                .orderByDesc(TradeRefund::getId));
    }

    default List<TradeRefund> findProcessingByMerchant(Long merchantId) {
        return selectList(new LambdaQueryWrapper<TradeRefund>()
                .eq(TradeRefund::getMerchantId, merchantId)
                .eq(TradeRefund::getStatus, "PROCESSING")
                .orderByAsc(TradeRefund::getId));
    }

    /**
     * T33：退款前预占商品退款额度与按快照比例的服务费冲正额。
     * 返回 0 表示订单状态不允许或累计退款超过实付/服务费快照，调用方必须拒绝本次退款。
     */
    @Update("UPDATE trade_order SET "
            + "fee_status = CASE WHEN fee_refunded_amount + #{feeAmount} >= commission_amount "
            + "THEN 'REVERSED' WHEN fee_refunded_amount + #{feeAmount} > 0 THEN 'PARTIAL_REVERSED' ELSE 'PENDING' END, "
            + "refunded_amount = refunded_amount + #{amount}, "
            + "fee_refunded_amount = fee_refunded_amount + #{feeAmount} "
            + "WHERE id = #{orderId} AND order_status IN ('PAID','SHIPPED','COMPLETED') "
            + "AND refunded_amount + #{amount} <= pay_amount "
            + "AND fee_refunded_amount + #{feeAmount} <= commission_amount")
    int reserveRefund(@Param("orderId") Long orderId, @Param("amount") BigDecimal amount,
                      @Param("feeAmount") BigDecimal feeAmount);

    /**
     * 渠道退款失败时释放预占；不改变已成功退款记录。
     */
    @Update("UPDATE trade_order SET "
            + "fee_status = CASE WHEN fee_refunded_amount - #{feeAmount} <= 0 THEN 'PENDING' "
            + "ELSE 'PARTIAL_REVERSED' END, "
            + "refunded_amount = refunded_amount - #{amount}, "
            + "fee_refunded_amount = fee_refunded_amount - #{feeAmount} "
            + "WHERE id = #{orderId} AND refunded_amount >= #{amount} AND fee_refunded_amount >= #{feeAmount}")
    int releaseRefundReservation(@Param("orderId") Long orderId, @Param("amount") BigDecimal amount,
                                 @Param("feeAmount") BigDecimal feeAmount);

    /**
     * 退款成功：仅当仍是 PROCESSING 才迁移，返回 1 表示本次调用完成了状态迁移（用于库存回退去重）。
     */
    @Update("UPDATE trade_refund SET status = 'SUCCESS', channel_refund_no = #{channelRefundNo}, "
            + "failure_reason = NULL, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'PROCESSING'")
    int markSuccess(@Param("id") Long id, @Param("channelRefundNo") String channelRefundNo);

    @Update("UPDATE trade_refund SET status = 'FAILED', failure_reason = #{reason}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'PROCESSING'")
    int markFailed(@Param("id") Long id, @Param("reason") String reason);

    @Update("UPDATE trade_refund SET status = 'PROCESSING', failure_reason = NULL, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'FAILED'")
    int markRetry(@Param("id") Long id);

    /**
     * 累计“已成功”退款达到实付时把订单置为 REFUNDED；返回 1 的调用方负责回退库存。
     * 不能直接用 refunded_amount 判断：该列含 PROCESSING 预占，过早置 REFUNDED 会把处理中的退款当成已到账。
     */
    @Update("UPDATE trade_order AS o SET order_status = 'REFUNDED' "
            + "WHERE o.id = #{orderId} AND o.order_status IN ('PAID','SHIPPED','COMPLETED') "
            + "AND (SELECT COALESCE(SUM(r.refund_amount), 0) FROM trade_refund r "
            + "WHERE r.order_id = o.id AND r.source = 'ORDER' AND r.status = 'SUCCESS') >= o.pay_amount")
    int markOrderRefundedIfFullyRefunded(@Param("orderId") Long orderId);
}
