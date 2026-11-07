/*
 * 文件：TradeRefundMapper.java
 * 用途：退款记录与订单退款额度/服务费冲正的数据访问（T33）。
 * 维护注意：
 *  1. 预占与冲正必须用条件 UPDATE（订单必须已支付且累计退款不超过实付），禁止先查后改的无锁写法。
 *  2. 成功/失败状态迁移都以 status=PROCESSING 为条件，保证重复回调或并发同步只生效一次。
 *  3. 不提供删除接口；退款记录是资金证据，失败记录也保留。
 *  4. T+1 对账与结算冲正按不可变的 success_at 读取成功退款；updated_at 带 ON UPDATE，重复同步会改写，
 *     不能用于切日。退款↔渠道退款仍需通过渠道退款流水号反查，覆盖跨天退款。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.common.OrderFeeStatus;
import com.shop.b2b2c.entity.TradeRefund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    /**
     * T+1 对账：读取业务日窗口内成功退款（含 ORDER 与 DUPLICATE_PAYMENT）。
     * 以不可变的 success_at 作为本地成功时间，渠道侧以流水 trade_time 比对；跨天由分类器标记。
     */
    @Select("SELECT * FROM trade_refund WHERE merchant_id = #{merchantId} AND status = 'SUCCESS' "
            + "AND success_at IS NOT NULL AND success_at >= #{from} AND success_at < #{to} ORDER BY id ASC")
    List<TradeRefund> findSuccessfulRefundsByPeriod(@Param("merchantId") Long merchantId,
                                                    @Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to);

    /** 渠道退款流水反查本地退款；同一退款流水号重复出现时取最早一条，差异由对账键另行暴露。 */
    default Optional<TradeRefund> findByChannelRefundNo(String channelRefundNo) {
        if (channelRefundNo == null || channelRefundNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<TradeRefund>()
                .eq(TradeRefund::getChannelRefundNo, channelRefundNo)
                .orderByAsc(TradeRefund::getId)
                .last("LIMIT 1")));
    }

    default List<TradeRefund> findProcessingByMerchant(Long merchantId) {
        return selectList(new LambdaQueryWrapper<TradeRefund>()
                .eq(TradeRefund::getMerchantId, merchantId)
                .eq(TradeRefund::getStatus, "PROCESSING")
                .orderByAsc(TradeRefund::getId));
    }

    /**
     * 巡检：统计指定商户 status='SUCCESS' 但 success_at 缺失的退款。
     * 产生路径为旧数据（本卡上线前已成功）或异常迁移；这类记录不会进入 T+1 success_at 窗口，
     * 跑批必须在每店每轮只统计一次并告警，由人工按补数方案回填，不能当作“对账无差异”。
     * 走 (merchant_id, success_at) 索引的 merchant_id 前缀；不为巡检额外加 status 前导索引。
     */
    default long countSuccessWithoutSuccessAt(Long merchantId) {
        Long count = selectCount(new LambdaQueryWrapper<TradeRefund>()
                .eq(TradeRefund::getMerchantId, merchantId)
                .eq(TradeRefund::getStatus, "SUCCESS")
                .isNull(TradeRefund::getSuccessAt));
        return count == null ? 0L : count;
    }

    /**
     * T33：退款前预占商品退款额度与按快照比例的服务费冲正额。
     * 返回 0 表示订单状态不允许或累计退款超过实付/服务费快照，调用方必须拒绝本次退款。
     */
    @Update("UPDATE trade_order SET "
            + "fee_status = CASE WHEN fee_refunded_amount + #{feeAmount} >= commission_amount "
            + "THEN '" + OrderFeeStatus.REVERSED + "' WHEN fee_refunded_amount + #{feeAmount} > 0 "
            + "THEN '" + OrderFeeStatus.PARTIAL_REVERSED + "' ELSE '" + OrderFeeStatus.PENDING + "' END, "
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
            + "fee_status = CASE WHEN fee_refunded_amount - #{feeAmount} <= 0 THEN '" + OrderFeeStatus.PENDING + "' "
            + "ELSE '" + OrderFeeStatus.PARTIAL_REVERSED + "' END, "
            + "refunded_amount = refunded_amount - #{amount}, "
            + "fee_refunded_amount = fee_refunded_amount - #{feeAmount} "
            + "WHERE id = #{orderId} AND refunded_amount >= #{amount} AND fee_refunded_amount >= #{feeAmount}")
    int releaseRefundReservation(@Param("orderId") Long orderId, @Param("amount") BigDecimal amount,
                                 @Param("feeAmount") BigDecimal feeAmount);

    /**
     * 退款成功：仅当仍是 PROCESSING 才迁移，返回 1 表示本次调用完成了状态迁移（用于库存回退去重）。
     * success_at 只在本次迁移写入一次，后续任何重试/同步不得改写。
     */
    @Update("UPDATE trade_refund SET status = 'SUCCESS', channel_refund_no = #{channelRefundNo}, "
            + "success_at = #{successAt}, failure_reason = NULL, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'PROCESSING'")
    int markSuccess(@Param("id") Long id, @Param("channelRefundNo") String channelRefundNo,
                    @Param("successAt") LocalDateTime successAt);

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

    /**
     * T34：按账期读取已成功的订单退款明细，用于生成结算冲正行。
     */
    @org.apache.ibatis.annotations.Select("SELECT * FROM trade_refund WHERE merchant_id = #{merchantId} "
            + "AND source = 'ORDER' AND status = 'SUCCESS' "
            + "AND success_at IS NOT NULL AND success_at >= #{from} AND success_at < #{to} "
            + "AND fee_refund_amount > 0 ORDER BY id")
    java.util.List<TradeRefund> findSuccessfulOrderRefundsByPeriod(@Param("merchantId") Long merchantId,
                                                                    @Param("from") java.time.LocalDateTime from,
                                                                    @Param("to") java.time.LocalDateTime to);

    /**
     * O6 关店前置条件：统计店铺处理中的退款单；冻结/关店不阻断既有退款履约，但关店前必须清零。
     */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM trade_refund WHERE merchant_id = #{merchantId} "
            + "AND status = 'PROCESSING'")
    int countProcessingByMerchantId(@Param("merchantId") Long merchantId);

    /**
     * D25 统计口径：按不可变 success_at 汇总窗口内成功退款金额与服务费冲正。
     * merchantId 为 null 时统计全平台；PROCESSING/FAILED 不计入；空结果 COALESCE 为 0。
     */
    @Select("SELECT COALESCE(SUM(refund_amount), 0) AS refundAmountSum, "
            + "COALESCE(SUM(fee_refund_amount), 0) AS feeRefundAmountSum FROM trade_refund "
            + "WHERE status = 'SUCCESS' AND success_at IS NOT NULL "
            + "AND success_at >= #{from} AND success_at < #{to} "
            + "AND (#{merchantId} IS NULL OR merchant_id = #{merchantId})")
    com.shop.b2b2c.dto.RefundLedgerAggregate aggregateRefundLedger(@Param("merchantId") Long merchantId,
                                                                   @Param("from") LocalDateTime from,
                                                                   @Param("to") LocalDateTime to);
}
