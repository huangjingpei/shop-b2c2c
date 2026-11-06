/*
 * 文件：MerchantFeeAudit.java
 * 用途：费率政策变更审计，记录前后费率类型/基点、原因、操作人/确认人与生效时间。
 * 维护注意：只追加不修改；不得只记新值而省略前后差值，否则无法还原调价历史。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_fee_audit")
public class MerchantFeeAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    private Long policyId;

    private String fromRateType;

    private Integer fromRateBps;

    private String toRateType;

    private Integer toRateBps;

    private String reason;

    private Long operatorId;

    private Long confirmedBy;

    /** 复核模式：SAME_OPERATOR=单管理员开关关闭下同人复核，DUAL_APPROVAL=双人复核；与政策记录一致。 */
    private String approvalMode;

    private LocalDateTime effectiveFrom;

    private LocalDateTime createdAt;
}
