/*
 * 文件：ComplaintDTOs.java
 * 用途：F06 投诉协商与平台仲裁请求/响应契约：买家提交与申请介入、商家答复、超管裁决、工单详情。
 * 维护注意：
 *  1. 买家身份只凭 orderSn+contact+queryToken（免密）；queryToken 仅校验不入库。
 *  2. 举证只接受 http/https 链接，最多 5 条、单条 ≤300 字符；不接受 base64 或脚本协议，避免存储型 XSS。
 *  3. 裁决金额必须 ≥0 且 ≤2 位小数；REFUND 判定由服务端按可退余额推导为 FULL/PARTIAL，客户端传入值不直接落库。
 */
package com.shop.b2b2c.dto;

import com.shop.b2b2c.entity.Complaint;
import com.shop.b2b2c.entity.ComplaintEvent;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public class ComplaintDTOs {

    public record BuyerSubmitRequest(
            @NotBlank String orderSn,
            @NotBlank String contact,
            @NotBlank String queryToken,
            @NotBlank @Size(max = 32) String complaintType,
            @NotBlank @Size(min = 5, max = 1000) String content,
            @Size(max = 5) List<@Size(max = 300) String> evidenceImages) {
    }

    public record BuyerEscalateRequest(
            @NotBlank String orderSn,
            @NotBlank String contact,
            @NotBlank String queryToken,
            @Size(max = 500) String note) {
    }

    public record MerchantReplyRequest(
            @NotBlank @Size(max = 1000) String reply,
            @Size(max = 5) List<@Size(max = 300) String> evidenceImages) {
    }

    public record ArbitrateRequest(
            @NotBlank @Size(max = 16) String decision,
            @NotBlank @Size(max = 1000) String arbitration,
            @NotNull @DecimalMin("0.00") BigDecimal refundAmount) {
    }

    public record ComplaintDetail(Complaint complaint, List<ComplaintEvent> events) {
    }
}
