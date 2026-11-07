/*
 * 文件：RefundRequestDTOs.java
 * 用途：F05 退款申请相关请求 DTO。买家申请凭 orderSn+contact+queryToken 校验身份；商家审批拒绝须填拒绝原因。
 * 维护注意：金额必须 >0 且不超过 2 位小数；queryToken 不入库，仅用于校验。
 */
package com.shop.b2b2c.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class RefundRequestDTOs {

    public record BuyerApplyRequest(
            @NotBlank String orderSn,
            @NotBlank String contact,
            @NotBlank String queryToken,
            @NotNull @DecimalMin("0.01") BigDecimal refundAmount,
            @NotBlank @Size(max = 256) String reason) {
    }

    public record BuyerCancelRequest(
            @NotBlank String orderSn,
            @NotBlank String contact,
            @NotBlank String queryToken) {
    }

    public record RejectRequest(@NotBlank @Size(max = 256) String rejectReason) {
    }
}
