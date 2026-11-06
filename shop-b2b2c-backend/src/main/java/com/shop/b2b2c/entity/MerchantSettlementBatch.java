/*
 * 文件：MerchantSettlementBatch.java
 * 用途：商家结算批次模型（T34），按月汇总服务费应收、冲正、实收与欠费。
 * 维护注意：
 *  1. 同一店铺同一账期同一结算类型唯一；DRAFT 可重算覆盖，CONFIRMED 后禁止重算。
 *  2. 金额字段均为汇总快照，逐笔依据来自 merchant_settlement_item，不得仅凭批次合计对账。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("merchant_settlement_batch")
public class MerchantSettlementBatch {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchNo;
    private Long merchantId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String settlementType = "SERVICE_FEE";
    private BigDecimal grossAmount = BigDecimal.ZERO;
    private BigDecimal refundBaseAmount = BigDecimal.ZERO;
    private BigDecimal feeAmount = BigDecimal.ZERO;
    private BigDecimal adjustmentAmount = BigDecimal.ZERO;
    private BigDecimal netFeeAmount = BigDecimal.ZERO;
    private BigDecimal paidAmount = BigDecimal.ZERO;
    private BigDecimal arrearsAmount = BigDecimal.ZERO;
    private String status = "DRAFT"; // DRAFT/CONFIRMED
    /** 制单人/最近一次重算人；确认人必须与其不同，满足职责分离。 */
    private Long preparedBy;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    /** 复核模式：SAME_OPERATOR（开关关闭的单管理员）/DUAL_APPROVAL；由 FinanceApprovalGuard 写入。 */
    private String approvalMode;
    private Integer version = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
