/*
 * 文件：MerchantAccountLedger.java
 * 用途：服务费账本流水模型（T34），所有余额/欠费变更的唯一记账凭证。
 * 维护注意：
 *  1. 只追加，不更新、不删除；冲正用红字/反向流水表达。
 *  2. idempotencyKey 全表唯一，订单扣费、退款冲正、充值入账必须各自生成稳定键。
 *  3. amount 始终为正数；方向由 direction 表达，balanceAfter/arrearsAfter 为入账后的快照。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("merchant_account_ledger")
public class MerchantAccountLedger {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;
    private String entryType;   // SERVICE_FEE_CHARGE/SERVICE_FEE_ARREARS/SERVICE_FEE_WAIVED/REFUND_REVERSAL/RECHARGE/ADJUSTMENT
    private String direction;   // DEBIT/CREDIT
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private BigDecimal arrearsAfter;
    private String bizType;     // ORDER/REFUND/RECHARGE/ADJUSTMENT
    private Long bizId;
    private String orderSn;
    private String refundNo;
    private String idempotencyKey;
    private String remark;
    private LocalDateTime createdAt;
}
