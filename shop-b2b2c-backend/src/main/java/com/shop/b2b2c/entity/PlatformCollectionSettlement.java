/*
 * 文件：PlatformCollectionSettlement.java
 * 用途：T40 阶段二平台代收结算台账模型（骨架），一笔平台代收订单一条，记录实收、平台留成与商家应结。
 * 维护注意：
 * 1. 金额必须守恒：merchantPayableAmount + platformRetainAmount = grossAmount；差额必须进差异队列，不得静默平衡。
 * 2. 骨架阶段 settledAmount 保持 0，settlementStatus 只允许 PENDING/HOLD/DIFFERENCE，未联调不得置 SETTLED。
 * 3. settleBatchId 在阶段二真正出账时回填既有 merchant_settlement_batch，不新建第二套结算单。
 * 4. 本表不产生真实资金流转，不得作为买家可见的“已分账/已结算”凭证。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("platform_collection_settlement")
public class PlatformCollectionSettlement {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;
    private String orderSn;
    private Long merchantId;
    private Long paymentEventId;
    private String platformTradeNo;
    private BigDecimal grossAmount;
    private BigDecimal platformRetainAmount;
    private BigDecimal merchantPayableAmount;
    private BigDecimal settledAmount = BigDecimal.ZERO;
    private BigDecimal refundedAmount = BigDecimal.ZERO;
    private String settlementStatus = "PENDING"; // PENDING/HOLD/SETTLED/DIFFERENCE
    private Long settleBatchId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
