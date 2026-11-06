/*
 * 文件：SettlementDTOs.java
 * 用途：结算/充值接口的请求模型（T34）。
 * 维护注意：充值金额与凭证号在服务层再次校验；DTO 只承载入参，不包含余额或账本字段，避免客户端伪造入账结果。
 */
package com.shop.b2b2c.dto;

import lombok.Data;

import java.math.BigDecimal;

public final class SettlementDTOs {

    private SettlementDTOs() {
    }

    @Data
    public static class RechargeRequest {
        private BigDecimal amount;
        private String voucherNo;
        private String payerName;
        private String voucherProofUrl;
    }

    @Data
    public static class ConfirmRequest {
        private BigDecimal confirmedAmount;
    }

    @Data
    public static class RejectRequest {
        private String reason;
    }
}
