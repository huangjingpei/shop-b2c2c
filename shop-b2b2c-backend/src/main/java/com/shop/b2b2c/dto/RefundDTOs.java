/*
 * 文件：RefundDTOs.java
 * 用途：T33 商家退款、退款查单同步与重复付款补偿的接口入参/出参。
 * 维护注意：
 *  1. RefundView 只暴露资金证据字段，不含渠道密钥、银行卡或进件隐私字段。
 *  2. status=PROCESSING 表示渠道已受理但未确认到账，调用方不能把它当成已退款。
 *  3. duplicatePayment 的金额来自渠道重复付款事件，不能改写订单商品退款额度。
 */
package com.shop.b2b2c.dto;

import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.entity.TradeRefund;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class RefundDTOs {

    /** 订单退款申请：金额由商家填写，服务端按订单快照校验上限与费用冲正比例。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefundRequest {
        private BigDecimal amount;
        private String reason;
    }

    /** 退款记录视图。source=ORDER 为商品退款；source=DUPLICATE_PAYMENT 为重复付款补偿。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefundView {
        private String refundNo;
        private String orderSn;
        private String source;
        private String status;
        private String collectionMode;
        private String channel;
        private BigDecimal refundAmount;
        private BigDecimal feeRefundAmount;
        private String channelRefundNo;
        private String failureReason;
        private LocalDateTime createdAt;

        public static RefundView from(TradeRefund refund) {
            return RefundView.builder()
                    .refundNo(refund.getRefundNo())
                    .orderSn(refund.getOrderSn())
                    .source(refund.getSource())
                    .status(refund.getStatus())
                    .collectionMode(refund.getCollectionMode())
                    .channel(refund.getChannel())
                    .refundAmount(refund.getRefundAmount())
                    .feeRefundAmount(refund.getFeeRefundAmount())
                    .channelRefundNo(refund.getChannelRefundNo())
                    .failureReason(refund.getFailureReason())
                    .createdAt(refund.getCreatedAt())
                    .build();
        }

        public static List<RefundView> from(List<TradeRefund> refunds) {
            return refunds.stream().map(RefundView::from).collect(Collectors.toList());
        }
    }

    /** 重复付款补偿队列项；eventStatus 固定为 DUPLICATE_PAYMENT。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DuplicatePaymentView {
        private Long eventId;
        private String orderSn;
        private String channel;
        private String channelMerchantId;
        private String payTradeNo;
        private BigDecimal payAmount;
        private String eventStatus;
        private String refundNo;
        private String refundStatus;
        private LocalDateTime createdAt;

        public static DuplicatePaymentView from(PaymentEvent event, TradeRefund refund) {
            return DuplicatePaymentView.builder()
                    .eventId(event.getId())
                    .orderSn(event.getOrderSn())
                    .channel(event.getChannel())
                    .channelMerchantId(event.getChannelMerchantId())
                    .payTradeNo(event.getPayTradeNo())
                    .payAmount(event.getPayAmount())
                    .eventStatus(event.getEventStatus())
                    .refundNo(refund == null ? null : refund.getRefundNo())
                    .refundStatus(refund == null ? null : refund.getStatus())
                    .createdAt(event.getCreatedAt())
                    .build();
        }
    }
}
