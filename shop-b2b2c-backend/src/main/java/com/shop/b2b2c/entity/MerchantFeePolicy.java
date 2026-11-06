/*
 * 文件：MerchantFeePolicy.java
 * 用途：技术服务费率政策版本，订单快照的唯一费率来源。
 * 维护注意：
 * 1. rateType 只有 NORMAL（rateBps 100–1000）与 PROMOTION（rateBps=0）；0% 不并入正常区间。
 * 2. effectiveFrom/effectiveTo 决定生效区间；同一商户同一时刻只应有一套政策，调高排到次日 00:00，降费即时。
 * 3. source 记录来源（ONBOARDING/ADMIN_ADJUST/PROMOTION/MIGRATION）；reason/promotionReason/operatorId/confirmedBy 必须可追溯。
 * 4. merchant_store.commission_rate 仅作展示镜像，由本政策服务同步，不再作为计费读取来源。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_fee_policy")
public class MerchantFeePolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    private String rateType;

    private Integer rateBps;

    private String promotionReason;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    private String source;

    private String reason;

    private Long confirmedBy;

    /** 复核模式：SAME_OPERATOR=单管理员开关关闭下同人复核，DUAL_APPROVAL=双人复核；由 FinanceApprovalGuard 写入。 */
    private String approvalMode;

    private Long operatorId;

    private LocalDateTime createdAt;
}
