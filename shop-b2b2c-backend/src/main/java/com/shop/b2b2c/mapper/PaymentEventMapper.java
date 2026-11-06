/*
 * 文件：PaymentEventMapper.java
 * 用途：支付到账事件数据访问，支撑支付流水唯一排重、补偿扫描与核对查询 (R13/T05/G34)。
 * 维护注意：补偿查询只读取事件 SUCCESS 但订单未支付/未交付，以及超过阈值仍 PROCESSING 的记录；
 * 不得把缺失订单纳入自动补履约，避免无限重试；已 PAID/COMPLETED/SHIPPED 的订单不得再次进入两条补偿查询，
 * 避免与主事务正常履约重复处理。陈旧 PROCESSING 的退避与人工队列更新必须带 event_status = 'PROCESSING' 条件，
 * 防止把并发已成功的事件改回失败/人工态。
 * T+1：markFulfillmentFailed 必须显式传入 funds_received（明确未支付=0、查单失败/资金未知=NULL）；
 * markManualReview 一律回写 NULL（资金状态未知），对账侧据此生成 REVIEW 差异，不得静默。
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
     * G34 陈旧 PROCESSING 补偿扫描：占位事务已提交，但业务事务崩溃或未收敛，事件长期停留在 PROCESSING。
     * 只选取超过截止时间的时间点仍 PROCESSING、已到下次回查时间（NULL 视为立即可扫）、且关联订单尚未
     * PAID/COMPLETED/SHIPPED 的记录；INNER JOIN 排除订单缺失的历史脏数据，已履约订单不进入本查询，避免重复交付。
     */
    @Select("SELECT e.* FROM payment_event e INNER JOIN trade_order o ON o.order_sn = e.order_sn "
            + "WHERE e.event_status = 'PROCESSING' AND e.created_at <= #{cutoff} "
            + "AND (e.next_reconcile_at IS NULL OR e.next_reconcile_at <= #{now}) "
            + "AND o.order_status NOT IN ('PAID', 'COMPLETED', 'SHIPPED') "
            + "ORDER BY e.id LIMIT #{limit}")
    List<PaymentEvent> findStaleProcessingEvents(@Param("cutoff") java.time.LocalDateTime cutoff,
                                                @Param("now") java.time.LocalDateTime now,
                                                @Param("limit") int limit);

    /**
     * G34 可重试失败：只更新仍 PROCESSING 的事件，记录本次尝试数与下次回查时间。
     * 返回 0 表示事件已被主事务/其它路径改为 SUCCESS 等状态，不再重试也不得覆盖。
     */
    @org.apache.ibatis.annotations.Update("UPDATE payment_event "
            + "SET reconcile_attempts = #{attempts}, next_reconcile_at = #{nextReconcileAt}, "
            + "error_message = #{message}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND event_status = 'PROCESSING'")
    int recordReconcileAttempt(@Param("id") Long id,
                               @Param("attempts") int attempts,
                               @Param("nextReconcileAt") java.time.LocalDateTime nextReconcileAt,
                               @Param("message") String message);

    /**
     * G34 超过重试上限：转人工队列 MANUAL_REVIEW。只允许从 PROCESSING 迁移，已被并发成功的事件不得降级。
     * 资金到账状态未知，funds_received 必须显式回写 NULL，由 T+1 对账生成 PAYMENT_EVENT_REVIEW 差异兜住。
     */
    @org.apache.ibatis.annotations.Update("UPDATE payment_event "
            + "SET reconcile_attempts = #{attempts}, event_status = 'MANUAL_REVIEW', next_reconcile_at = NULL, "
            + "funds_received = NULL, error_message = #{message}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND event_status = 'PROCESSING'")
    int markManualReview(@Param("id") Long id,
                         @Param("attempts") int attempts,
                         @Param("message") String message);

    /**
     * G34 FULFILLMENT_FAILED：只允许从 PROCESSING 迁移。
     * fundsReceived 由调用方显式给出：渠道明确未支付（PENDING/CLOSED/FAILED/NOT_EXIST）=0；
     * 已收款后已退款（REFUNDED）=1；查单前置校验失败（400/401/403/404）资金状态未知，必须传 NULL。
     * 资金标记只增不减：已存在的 funds_received=1 是“渠道已确认到账”的既成事实，
     * 后续“未支付/退款/无法确认”答复一律不得覆盖成 0/NULL，SQL 用 CASE 保留 1；
     * 此时状态与资金标记以“FULFILLMENT_FAILED + funds_received=1”共存，由 T+1 差异要求人工核对。
     * 返回 0 表示事件已被主事务并发收敛为 SUCCESS 等状态，不得降级。
     */
    @org.apache.ibatis.annotations.Update("UPDATE payment_event "
            + "SET event_status = 'FULFILLMENT_FAILED', "
            + "funds_received = CASE WHEN funds_received = 1 THEN 1 "
            + "ELSE #{fundsReceived,jdbcType=INTEGER} END, "
            + "next_reconcile_at = NULL, error_message = #{message}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND event_status = 'PROCESSING'")
    int markFulfillmentFailed(@Param("id") Long id,
                              @Param("fundsReceived") Integer fundsReceived,
                              @Param("message") String message);

    /**
     * T34：结算明细回填支付事件ID；同一订单理论上只取最早的一条 SUCCESS 付款事件。
     */
    @Select("SELECT * FROM payment_event WHERE order_sn = #{orderSn} AND event_status = 'SUCCESS' "
            + "ORDER BY id LIMIT 1")
    PaymentEvent findFirstSuccessByOrderSn(@Param("orderSn") String orderSn);

    /**
     * T35：按商户+时间读取到账事件（含重复付款），供对账比对。
     */
    @Select("SELECT * FROM payment_event WHERE merchant_id = #{merchantId} "
            + "AND created_at >= #{from} AND created_at < #{to} "
            + "ORDER BY id")
    List<PaymentEvent> findByMerchantAndPeriod(@Param("merchantId") Long merchantId,
                                               @Param("from") java.time.LocalDateTime from,
                                               @Param("to") java.time.LocalDateTime to);
}
