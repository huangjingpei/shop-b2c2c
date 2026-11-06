/*
 * 文件：SettlementDTOs.java
 * 用途：结算/充值接口的请求模型（T34）。
 * 维护注意：充值金额与凭证号在服务层再次校验；DTO 只承载入参，不包含余额或账本字段，避免客户端伪造入账结果。
 */
package com.shop.b2b2c.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    @Data
    public static class PolicyRequest {
        private BigDecimal creditLimit;
        private Integer graceDays;
    }

    @Data
    public static class ResolveRequest {
        private String note;
        private boolean ignore;
    }

    @Data
    public static class ChannelStatementImportRequest {
        private List<ChannelStatementItem> statements;
    }

    @Data
    public static class ChannelStatementItem {
        private String channel;
        private String channelTradeNo;
        private String outTradeNo;
        private String orderSn;
        private Long merchantId;
        private BigDecimal amount;
        private String tradeStatus;
        private LocalDateTime tradeTime;
    }
}
