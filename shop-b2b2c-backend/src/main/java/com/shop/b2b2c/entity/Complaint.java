/*
 * 文件：Complaint.java
 * 用途：买家投诉、商家答复及平台仲裁的当前状态模型（F06）。
 * 维护注意：
 *  1. 状态机：PENDING（买家已提交待商家答复）→ MERCHANT_REPLIED（商家已答复）→ ARBITRATING（买家申请平台介入）
 *     → RESOLVED（平台裁决完成，可含退款）/ REJECTED（平台驳回诉求）。终态不再接受答复或再次介入。
 *  2. 本表只保存当前快照；每次状态变化的操作人、说明与举证快照写入 complaint_event 追加表，禁止更新/删除，
 *     也不得用本表字段覆盖历史记录。evidence_images/merchant_reply 含买家举证与联系方式，不能对公网匿名返回。
 *  3. buyer_id 存下单联系方式（与 F05 退款申请口径一致），不是登录用户ID；queryToken 不入库，仅用于校验。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("complaint")
public class Complaint {

    /** 买家已提交，等待商家答复。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 商家已答复，等待买家接受或申请平台介入。 */
    public static final String STATUS_MERCHANT_REPLIED = "MERCHANT_REPLIED";
    /** 买家已申请平台介入，等待超管裁决。 */
    public static final String STATUS_ARBITRATING = "ARBITRATING";
    /** 平台已裁决完成（可能含退款）。 */
    public static final String STATUS_RESOLVED = "RESOLVED";
    /** 平台驳回买家诉求（终态）。 */
    public static final String STATUS_REJECTED = "REJECTED";

    public static final String DECISION_NONE = "NONE";
    public static final String DECISION_FULL_REFUND = "FULL_REFUND";
    public static final String DECISION_PARTIAL_REFUND = "PARTIAL_REFUND";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;
    private String orderSn;
    private String buyerId; // 买家唯一标识 (手机号/UID)
    private Long merchantId; // 被投诉商户ID
    private String complaintType; // QUALITY(质量), FAKE(假冒), SERVICE_NOT_DELIVERED(未按约履约), OTHER(其他)
    private String content; // 买家维权投诉详细说明
    private String evidenceImages; // 买家举证凭证图数组 JSON
    private String merchantReply; // 商家答辩说明与证据
    private LocalDateTime merchantReplyTime; // 商家答复时间
    private String adminArbitration; // 平台超管官方仲裁结论与整改指令
    private LocalDateTime adminArbitrationTime; // 超管仲裁下达时间
    private String status = STATUS_PENDING; // PENDING, MERCHANT_REPLIED, ARBITRATING, RESOLVED, REJECTED
    private String refundDecision = DECISION_NONE; // NONE, FULL_REFUND, PARTIAL_REFUND
    private BigDecimal refundAmount; // 最终仲裁退还付款人金额（0 则不退）
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
