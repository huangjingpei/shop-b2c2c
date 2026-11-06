/*
 * 文件：SplitDetail.java
 * 用途：T40 阶段二平台代收分账明细账本模型（骨架），按订单+分账接收方记录应收分账与平台留成。
 * 维护注意：
 * 1. 本表只记本地账本，不代表渠道已分账成功；channelSplitNo/splitStatus 只能由真实渠道结果推进。
 * 2. 金额必须守恒：splitAmount + platformRetainAmount = grossAmount；feeAmount 是留成中的服务费，来自订单快照。
 * 3. paymentAccountId 复用 merchant_payment_account 的绑定记录，不另建接收方表；未绑定/未生效不得分账。
 * 4. 退分先写 split_reverse 并累计 reversedAmount，退款流程不得绕过退分直接退款。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("split_detail")
public class SplitDetail {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;
    private String orderSn;
    private Long merchantId;
    private Long paymentEventId;
    private Long paymentAccountId;
    private String channel;
    private String platformTradeNo;
    private BigDecimal grossAmount;
    private BigDecimal platformRetainAmount;
    private BigDecimal feeAmount;
    private BigDecimal splitAmount;
    private String splitStatus = "PENDING"; // PENDING/PROCESSING/FINISHED/FAILED/REVERSED
    private LocalDateTime freezeUntil;
    private BigDecimal reversedAmount = BigDecimal.ZERO;
    private String channelSplitNo;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
