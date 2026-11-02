/*
 * 文件：MerchantPaymentAccount.java
 * 用途：商户渠道进件与结算账户的持久化模型。
 * 维护注意：含银行账户等敏感信息，返回时应按权限脱敏；当前开通记录存在本地模拟，PASSED不证明真实渠道已审核或可结算。
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
    private LocalDateTime createdAt;
}
