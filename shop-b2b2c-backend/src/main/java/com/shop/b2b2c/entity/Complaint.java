package com.shop.b2b2c.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "complaint", indexes = {
    @Index(name = "idx_order_id", columnList = "order_id"),
    @Index(name = "idx_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_status", columnList = "status")
})
public class Complaint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_sn", nullable = false)
    private String orderSn;

    @Column(name = "buyer_id", nullable = false)
    private String buyerId; // 买家唯一标识 (手机号/UID)

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId; // 被投诉商户ID

    @Column(name = "complaint_type", nullable = false)
    private String complaintType; // QUALITY(质量), FAKE(假冒), SERVICE_NOT_DELIVERED(未按约履约), OTHER(其他)

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content; // 买家维权投诉详细说明

    @Column(name = "evidence_images", columnDefinition = "json")
    private String evidenceImages; // 买家举证凭证图数组 JSON

    @Column(name = "merchant_reply", columnDefinition = "text")
    private String merchantReply; // 商家答辩说明与证据

    @Column(name = "merchant_reply_time")
    private LocalDateTime merchantReplyTime; // 商家答复时间

    @Column(name = "admin_arbitration", columnDefinition = "text")
    private String adminArbitration; // 平台超管官方仲裁结论与整改指令

    @Column(name = "admin_arbitration_time")
    private LocalDateTime adminArbitrationTime; // 超管仲裁裁决时间

    @Column(name = "status", nullable = false)
    private String status = "PENDING"; // PENDING(待处理), MERCHANT_REPLIED(商家已答复), ARBITRATING(超管仲裁中), RESOLVED(已结案), REJECTED(已驳回)

    @Column(name = "refund_decision", nullable = false)
    private String refundDecision = "NONE"; // NONE, FULL_REFUND, PARTIAL_REFUND

    @Column(name = "refund_amount", nullable = false)
    private BigDecimal refundAmount = BigDecimal.ZERO; // 判定退款金额

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
