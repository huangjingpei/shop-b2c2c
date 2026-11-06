/*
 * 文件：SettlementDTOs.java
 * 用途：结算/充值接口的请求模型（T34）；含双人复核的复核人入参。
 * 维护注意：
 *  1. 充值金额与凭证号在服务层再次校验；DTO 只承载入参，不包含余额或账本字段，避免客户端伪造入账结果。
 *  2. confirmedBy 仅为复核人标识，是否允许缺省/同人由后端 FinanceApprovalGuard 判定，前端不得自行放行。
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
        /** 复核人；生产严格模式下必填且不能等于当前操作人。 */
        private Long confirmedBy;
    }

    @Data
    public static class BatchConfirmRequest {
        /** 复核人；生产严格模式下必填且不能等于当前操作人。 */
        private Long confirmedBy;
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
