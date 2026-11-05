/*
 * 文件：PeerPayDTOs.java
 * 用途：代付、推单、预占与履约的请求和响应契约。
 * 维护注意：
 *  1. peerStatus 保持 String 并遵循 PeerStatus 固定编码；ownerToken 由服务端生成，仅从本次预占响应交给持有人，
 *     恢复预占/取消时必须原样回传，不能使用客户端幂等键替代 (T10)。
 *  2. 公开详情不得包含领取 token、卡密或服务凭证。类型 3 表示定制服务；MockPayRequest 为历史未使用 DTO，
 *     不代表存在可用 Mock 支付接口。
 */
package com.shop.b2b2c.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PeerPayDTOs {

    @Data
    public static class BuyerCreateRequest {
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long merchantId;
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long productId;
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long skuId;
        private Integer quantity;
        private String buyerContact;
        private String peerMessage;
        private String recipientAddress;
    }

    @Data
    public static class PushPayCreateRequest {
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long productId;
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long skuId;
        private Integer quantity;
        private BigDecimal negotiatedPrice;
        private String customerContact;
        private String sellerNote;
    }

    @Data
    public static class DetailResponse {
        private String token;
        private String orderSn;
        private Long productId;
        private Integer goodsType; // 1-实物, 2-自动卡密, 3-定制服务
        private Integer peerPayType; // 1-买家求助, 2-卖家代客推单
        private String peerStatus; // INIT, RESERVED_LOCK, PAID, EXPIRED, CANCELLED
        private String orderStatus;
        private BigDecimal payAmount;
        private Long storeId;
        private String storeName;
        private String storeLogo;
        private String storeNotice;
        private String contactPhone;
        private String productTitle;
        private String productImage;
        private String skuSpec;
        private Integer quantity;
        private String maskedBuyerContact;
        private String peerMessage;
        private String serviceDesc;
        private Boolean isOnlineService;
        private Long lockSecondsRemaining;
        private LocalDateTime lockExpireTime;
        private String paymentChannelType; // A-电商收付通, B-商家独立直收, C-测试桩
        private String customPayQrcode;
        private String posterUrl;
        private LocalDateTime createdAt;
    }

    @Data
    public static class PreLockResult {
        private String ownerToken;
        private Boolean success;
        private String peerStatus;
        private Long lockSeconds;
        private LocalDateTime expireTime;
        private String message;
    }

    @Data
    public static class MockPayRequest {
        private String payerId;
        private String channel; // WECHAT, ALIPAY, MOCK
    }

    @Data
    public static class VirtualFulfillRequest {
        private String serviceVoucher;
    }
}
