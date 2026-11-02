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
    private String status = "PENDING_MERCHANT"; // PENDING_MERCHANT, PENDING_ADMIN, RESOLVED, CLOSED
    private String refundDecision; // 仲裁退款裁决
    private BigDecimal refundAmount; // 最终仲裁退还代付人金额 (0则不退)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
