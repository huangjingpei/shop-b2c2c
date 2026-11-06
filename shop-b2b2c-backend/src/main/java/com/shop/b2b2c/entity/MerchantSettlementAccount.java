/*
 * 文件：MerchantSettlementAccount.java
 * 用途：阶段一商家自有收款账户（银行卡/微信/支付宝）登记与人工核验模型。
 * 维护注意：
 * 1. accountNoCipher 只存 CardCipherService 密文；accountNoMasked 才是可展示值，接口禁止回传密文。
 * 2. holderName 必须等于已审核入驻主体名称（硬校验），不允许人工放行或改主体后绕过。
 * 3. status 只允许 PENDING/VERIFIED/REJECTED/DISABLED；换账户采用新增 + 停用旧账户，不原地改历史。
 * 4. merchant_payment_account.settlement_account_id 为阶段二渠道绑定预留，本表不写渠道进件号。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_settlement_account")
public class MerchantSettlementAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    private String accountType;

    private String holderName;

    private String bankName;

    private String bankBranch;

    private String accountNoCipher;

    private String accountFingerprint;

    private String accountNoMasked;

    private String status;

    private String rejectReason;

    private Boolean isDefault;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    private LocalDateTime verifiedAt;

    private Long verifiedBy;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
