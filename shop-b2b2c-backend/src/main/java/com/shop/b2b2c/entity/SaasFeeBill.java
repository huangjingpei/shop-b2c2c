/*
 * 文件：SaasFeeBill.java
 * 用途：F10 SaaS 月度技术服务费账单模型，与交易抽佣/结算批次完全分离。
 * 维护注意：
 *  1. 同店同月唯一（uk_saas_fee_merchant_month）；生成幂等返回既有账单，已缴费/免收账单不得被重算覆盖。
 *  2. status：PENDING 待缴、PAID 已缴、WAIVED 免收（试用/豁免/0 月费）、OVERDUE 逾期；OVERDUE 只改账单状态，不自动改店铺状态。
 *  3. 金额口径：standardAmount 为配置标准额，amount 为实际应收；WAIVED 时 amount=0.00，不用负金额表达减免。
 *  4. 缴费仅支持线下转账人工确认，paidBy 是操作人，confirmedBy 是复核人；双人复核规则由 FinanceApprovalGuard 判定。
 *  5. 本表不自动扣减 merchant_account 余额/欠费，避免与交易抽佣资金账户混算。
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
@TableName("saas_fee_bill")
public class SaasFeeBill {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String billNo;
    private Long merchantId;
    /** 账期月份 yyyy-MM（Asia/Shanghai 自然月）。 */
    private String billingMonth;
    /** 配置标准月费，保留豁免前金额。 */
    private BigDecimal standardAmount = BigDecimal.ZERO;
    /** 实际应收；WAIVED 时为 0.00。 */
    private BigDecimal amount = BigDecimal.ZERO;
    /** PENDING/PAID/WAIVED/OVERDUE。 */
    private String status = "PENDING";
    private String waiveReason;
    private LocalDate dueDate;
    private LocalDateTime issuedAt;
    private LocalDateTime paidAt;
    private Long paidBy;
    private Long confirmedBy;
    private String approvalMode;
    private String paymentMethod;
    private String voucherNo;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
