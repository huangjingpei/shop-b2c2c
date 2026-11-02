package com.shop.b2b2c.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PeerPayDTOs {

    @Data
    public static class BuyerCreateRequest {
        private Long merchantId;
        private Long productId;
        private Long skuId;
        private Integer quantity;
        private String buyerContact;
        private String peerMessage;
        private String recipientAddress;
    }

    @Data
    public static class PushPayCreateRequest {
        private Long productId;
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
        private Integer goodsType; // 1-实物, 2-线上服务/虚拟
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
        private String serviceVoucher;
        private Long lockSecondsRemaining;
        private LocalDateTime lockExpireTime;
        private String paymentChannelType; // A-电商收付通, B-商家独立直收, C-测试桩
        private String customPayQrcode;
        private String posterUrl;
        private LocalDateTime createdAt;
    }

    @Data
    public static class PreLockResult {
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
