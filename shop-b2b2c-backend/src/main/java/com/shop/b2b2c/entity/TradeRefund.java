/*
 * 文件：TradeRefund.java
 * 用途：订单退款与重复付款补偿记录实体（T33），承载渠道退款状态、服务费冲正额与幂等键。
 * 维护注意：
 *  1. source=ORDER 的退款会预占 trade_order.refunded_amount/fee_refunded_amount；source=DUPLICATE_PAYMENT 不占用商品退款额度。
 *  2. status 只有 PROCESSING/SUCCESS/FAILED；PROCESSING 不能视为已退款，必须由渠道查单再确认。
 *  3. refund_no 与 (order_id, idempotency_key) 唯一；同一重复付款事件只允许一条补偿记录。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("trade_refund")
public class TradeRefund {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String refundNo;
    private Long orderId;
    private String orderSn;
    private Long merchantId;
    private String collectionMode;
    private String channel;
    private String source;          // ORDER / DUPLICATE_PAYMENT
    private Long sourceEventId;
    private String idempotencyKey;
    private BigDecimal refundAmount;
    private BigDecimal feeRefundAmount;
    /** 尾差归属：本次退款吸收的服务费舍入差；正数=多冲正，负数=少冲正。 */
    private BigDecimal feeRoundingAdjustment;
    private String status;          // PROCESSING / SUCCESS / FAILED
    private String channelTradeNo;
    private String channelRefundNo;
    private String reason;
    private String failureReason;
    private Long requestedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
