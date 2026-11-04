/*
 * 文件：PaymentEventDTOs.java
 * 用途：外部支付渠道确认事件入参与履约处理结果契约 (R13)。
 */
package com.shop.b2b2c.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

public class PaymentEventDTOs {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IncomingPaymentEvent {
        private String channel;        // WECHAT, ALIPAY, STRIPE, MOCK
        private String payTradeNo;     // 渠道外部交易流水号
        private String orderSn;        // 内部订单号
        private Long merchantId;       // 商户ID (如渠道报文提供)
        private BigDecimal payAmount;  // 实际到账金额
        private String currency;       // 币种 (默认 CNY)
        private String payerId;        // 付款人OpenID/UID
        private String rawPayload;     // 原始报文或签名摘要快照
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentProcessingResult {
        private Long eventId;
        private String channel;
        private String payTradeNo;
        private String orderSn;
        private Boolean success;
        private String eventStatus;
        private String orderStatus;
        private String message;

        public boolean isSuccess() {
            return Boolean.TRUE.equals(success);
        }
    }
}
