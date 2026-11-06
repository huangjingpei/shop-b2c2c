/*
 * 文件：MerchantRechargeOrder.java
 * 用途：商家充值单模型（T34），承载线下转账申请与人工确认结果。
 * 维护注意：
 *  1. voucherNo 全表唯一，作为线下转账的幂等凭证；同一凭证不得重复入账。
 *  2. 只有 PENDING 可被确认或驳回；确认与账本入账必须同一事务。
 *  3. confirmedAmount 是人工核对后的实际到账金额，可与申请金额不同，入账以确认金额为准。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("merchant_recharge_order")
public class MerchantRechargeOrder {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String rechargeNo;
    private Long merchantId;
    private BigDecimal amount;
    private BigDecimal confirmedAmount;
    private String voucherNo;
    private String voucherProofUrl;
    private String payerName;
    private String status = "PENDING"; // PENDING/CONFIRMED/REJECTED
    private Long ledgerEntryId;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private String rejectReason;
    private Integer version = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
