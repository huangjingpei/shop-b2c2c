/*
 * 文件：MerchantSettlementAccountAudit.java
 * 用途：收款账户登记、核验、驳回、停用的审计记录。
 * 维护注意：只追加不修改；不得保存账号明文或密文，仅保存指纹与操作原因，供人工核对追溯。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_settlement_account_audit")
public class MerchantSettlementAccountAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private Long merchantId;

    private String action;

    private String accountType;

    private String accountFingerprint;

    private Long operatorId;

    private String reason;

    private LocalDateTime createdAt;
}
