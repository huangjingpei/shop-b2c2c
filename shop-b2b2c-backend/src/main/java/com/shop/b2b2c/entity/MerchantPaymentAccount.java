/*
 * 文件：MerchantPaymentAccount.java
 * 用途：阶段二渠道进件/分账绑定预留模型；阶段一收款账户见 MerchantSettlementAccount。
 * 维护注意：
 * 1. 含银行账户等敏感信息，返回时应按权限脱敏；阶段一不得再写入 SUB+时间戳、默认 PASSED 的假进件值。
 * 2. bindingStatus/channelMerchantId/boundAt/effectiveFrom/effectiveTo/rawResult 为 T39 阶段二预留字段，
 *    只允许渠道真实回调/查询结果推进，不得由平台自行改为 BOUND。
 * 3. settlementAccountId 关联阶段一登记账户，阶段一先不写值；阶段二绑定的是分账接收方，不是货款归集。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("merchant_payment_account")
public class MerchantPaymentAccount {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;
    private String channel; // WECHAT, ALIPAY
    private String subMchid;
    private String bankAccountName;
    private String bankAccountNumber;
    private String bankName;
    private String applyStatus = "PASSED"; // AUDITING, PASSED, REJECTED
    private String rejectReason;
    private Long settlementAccountId;
    private String bindingStatus = "UNBOUND"; // UNBOUND/BINDING/BOUND/REJECTED/DISABLED
    private String channelMerchantId;
    private LocalDateTime boundAt;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String rawResult;
    private LocalDateTime createdAt;
}
