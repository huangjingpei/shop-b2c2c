/*
 * 文件：MerchantSettlementItem.java
 * 用途：结算明细模型（T34），逐笔记录订单服务费与退款冲正，便于追溯支付事件与退款单。
 * 维护注意：
 *  1. refundId 非退款明细固定为 0，避免唯一索引因 NULL 失去约束。
 *  2. 退款冲正明细的 feeAmount 为红字（负数），netFee 与批次合计需同口径。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("merchant_settlement_item")
public class MerchantSettlementItem {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;
    private Long merchantId;
    private String itemType; // ORDER_FEE/REFUND_REVERSAL
    private Long orderId;
    private String orderSn;
    private Integer goodsType = 1;
    private Long paymentEventId;
    private Long refundId = 0L;
    private String refundNo;
    private BigDecimal billableBase = BigDecimal.ZERO;
    private Integer feeRateBps = 0;
    private BigDecimal feeAmount = BigDecimal.ZERO;
    private BigDecimal feeRefundedAmount = BigDecimal.ZERO;
    private BigDecimal netFee = BigDecimal.ZERO;
    private LocalDateTime createdAt;
}
