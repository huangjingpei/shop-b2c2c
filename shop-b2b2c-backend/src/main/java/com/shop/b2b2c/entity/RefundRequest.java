/*
 * 文件：RefundRequest.java
 * 用途：F05 买家退款申请单实体。与 trade_refund（实际退款执行记录）分离：买家发起后 PENDING，商家审批同意才生成 trade_refund 并走渠道原路退回；拒绝/撤回即终态。
 * 维护注意：status 仅 PENDING/APPROVED/REJECTED/CANCELLED；buyer_contact 仅用于校验买家身份，不返回前端；refund_amount 不得超过订单可退余额，由服务层校验。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("refund_request")
public class RefundRequest {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String orderSn;
    private Long merchantId;
    private String buyerContact;
    private BigDecimal refundAmount;
    private String reason;
    private String status;
    private String rejectReason;
    private LocalDateTime appliedAt;
    private LocalDateTime decidedAt;
    private Long decidedBy;
    private String decidedRole;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
