/*
 * 文件：MerchantOnboardingAudit.java
 * 用途：入驻申请审核审计实体，记录每次状态推进、驳回、补件与操作人。
 * 维护注意：只追加不修改；materialHash 仅保存材料摘要，禁止把身份证/营业执照原文写进 reason 或摘要。
 * 审核动作与状态迁移必须与 MerchantOnboardingService 保持一致，便于事后追溯“谁在何时因何放行”。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_onboarding_audit")
public class MerchantOnboardingAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long applicationId;

    private Long merchantId;

    private String action;

    private String fromStatus;

    private String toStatus;

    private Long operatorId;

    private String reason;

    /** 驳回/补件原因码快照，与申请单 rejectCode 同字典。 */
    private String rejectCode;

    /** 动作完成后的审核阶段 NONE/PRIMARY/SECONDARY/PASSED。 */
    private String reviewStage;

    private String materialHash;

    private LocalDateTime createdAt;
}
