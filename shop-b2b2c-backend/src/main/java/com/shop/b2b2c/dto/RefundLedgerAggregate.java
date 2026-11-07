/*
 * 文件：RefundLedgerAggregate.java
 * 用途：D25 统计口径的退款账本库内聚合行：窗口内 status=SUCCESS 退款的金额合计与服务费冲正合计。
 * 维护注意：窗口按不可变 success_at 切日（T+1/结算口径），不能用带 ON UPDATE 的 updated_at；
 *           SQL 已按 status='SUCCESS' 过滤，PROCESSING/FAILED 不得计入；本模型不含买家隐私字段。
 */
package com.shop.b2b2c.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class RefundLedgerAggregate {
    /** 成功退款金额合计（含 ORDER 与 DUPLICATE_PAYMENT 来源），SQL COALESCE 保证非 null。 */
    private BigDecimal refundAmountSum;
    /** 成功退款的服务费冲正合计，用于从预计佣金扣减得到扣冲正后佣金。 */
    private BigDecimal feeRefundAmountSum;
}
