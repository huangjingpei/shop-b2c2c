/*
 * 文件：PaymentChannelDTOs.java
 * 用途：真实支付渠道适配器、回调与查单的内部契约 DTO (T32)；M4-B 起含平台代收退分（分账回退）契约。
 * 维护注意：
 *  1. ChannelCallback 只能由验签通过后的适配器构造；payAmount 必须来自渠道报文，禁止由订单金额反填。
 *  2. merchantChannelId 是商户在渠道侧的商户号（微信 mchid / 支付宝 seller_id），与平台内部 merchantId 不是同一概念；
 *     平台代收退款命令中该字段留空，由平台收银台适配器按 payment.platform.sp-mchid 自取，不得填入商家直收账户。
 *  3. 金额统一使用元、两位小数 BigDecimal；渠道分/币种换算只在适配器内部完成。
 *  4. SplitReturnCommand.refundNo 兼作渠道退分幂等键（微信 out_return_no），同一退款重试不得生成新键。
 */
package com.shop.b2b2c.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

public class PaymentChannelDTOs {

    /** 发起支付尝试所需的最小上下文；不含密钥，只含商户渠道号。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentCreateCommand {
        private String orderSn;
        private BigDecimal amount;
        private String subject;
        private String merchantChannelId;
        private String clientIp;
    }

    /** 支付尝试结果：codeUrl 用于二维码收银，payParams 留给后续 JSAPI/APP 扩展。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentAttemptView {
        private String orderSn;
        private String payChannel;
        private String collectionMode;
        private BigDecimal amount;
        private String currency;
        private String tradeType;
        private String codeUrl;
        private Map<String, String> payParams;
    }

    /** 验签并解密后的渠道到账事实；只在服务端内部流转。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelCallback {
        private String channel;
        private String payTradeNo;
        private String orderSn;
        private String channelMerchantId;
        private BigDecimal payAmount;
        private String currency;
        private String payerId;
        private String rawPayload;
    }

    /** 主动查单结果；SUCCESS 才允许进入本地落账。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelQueryResult {
        private String channel;
        private String orderSn;
        private String payTradeNo;
        private String tradeState;
        private String channelMerchantId;
        private BigDecimal payAmount;
        private String currency;
        private String payerId;
        private String rawPayload;

        public boolean isSuccess() {
            return "SUCCESS".equals(tradeState);
        }
    }

    /** 买家端发起支付尝试的入参。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatePaymentRequest {
        private String orderSn;
        private String accessToken;
        private String payChannel;
    }

    /**
     * 渠道退款命令：source=ORDER 时按订单原交易退款；source=DUPLICATE_PAYMENT 时按重复付款流水号退款。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefundCommand {
        private String orderSn;
        private String refundNo;
        private String channelTradeNo; // 重复付款补偿时必须传渠道原流水号；普通退款可为空
        private String merchantChannelId;
        private BigDecimal payAmount;
        private BigDecimal refundAmount;
        private String reason;
    }

    /** 渠道退款结果；PROCESSING 需后续查单确认，不能直接标记订单已退款。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefundResult {
        private String channel;
        private String refundNo;
        private String channelRefundNo;
        private String status; // SUCCESS / PROCESSING / FAILED
        private BigDecimal refundAmount;
        private String rawPayload;

        public boolean isSuccess() {
            return "SUCCESS".equals(status);
        }

        public boolean isProcessing() {
            return "PROCESSING".equals(status);
        }
    }

    /**
     * M4-B 平台代收分账回退（退分）命令：先退分后退款中的“退分”渠道指令。
     * 只有 PLATFORM_COLLECT 订单的 source=ORDER 退款使用；重复付款补偿资金未分账，不发起退分。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SplitReturnCommand {
        private String orderSn;
        private String refundNo; // 兼作渠道退分幂等键（微信 out_return_no）
        private String platformTradeNo; // 平台代收渠道流水号
        private String receiverChannelId; // 分账接收方渠道商户号（商家二级商户 sub_mchid）
        private BigDecimal amount; // 退分金额 = 退款额 − 平台留成冲正，金额守恒
        private String reason;
    }

    /** M4-B 分账回退渠道结果；非 SUCCESS 一律视为退分未完成，不得继续发起退款。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SplitReturnResult {
        private String channel;
        private String refundNo;
        private String channelReturnNo;
        private String status; // SUCCESS / PROCESSING / FAILED
        private BigDecimal amount;
        private String rawPayload;

        public boolean isSuccess() {
            return "SUCCESS".equals(status);
        }
    }
}
