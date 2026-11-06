/*
 * 文件：PaymentEventDTOs.java
 * 用途：外部支付渠道确认事件入参与履约处理结果契约 (R13)。
 * 维护注意：T32 新增 channelMerchantId（渠道侧商户号）与 merchantId（平台内部店铺ID）分离；实收金额必须由渠道报文提供。
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
        private Long merchantId;       // 平台内部店铺ID (与订单 merchant_id 一致)
        private String channelMerchantId; // T32 渠道侧商户号（微信 mchid / 支付宝 seller_id），用于审计与商户核验
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
