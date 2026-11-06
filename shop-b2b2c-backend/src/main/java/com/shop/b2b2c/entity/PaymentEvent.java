/*
 * 文件：PaymentEvent.java
 * 用途：支付渠道到账事件流水实体模型，持久化外部支付机构确认事实与资金要素 (R13)。
 * 维护注意：T32 新增 collection_mode（货款归集模式）、platform_trade_no（阶段二平台流水，阶段一为空）、channel_merchant_id（渠道商户号）；merchant_id 仍为平台内部店铺ID。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_event")
public class PaymentEvent {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String channel;        // 支付渠道: WECHAT, ALIPAY, STRIPE, MOCK
    private String payTradeNo;     // 第三方支付交易流水号 (与channel构成联合唯一约束)
    private String orderSn;        // 关联内部订单SN
    private Long merchantId;       // 商户ID（平台内部 merchant_store.id）
    private String collectionMode; // MERCHANT_DIRECT / PLATFORM_COLLECT（T32 计划新增，阶段一固定商家直收）
    private String channelMerchantId; // 渠道侧商户号（微信 mchid / 支付宝 seller_id），与内部商户ID分离
    private String platformTradeNo; // 阶段二平台商户号流水；阶段一为空
    private BigDecimal payAmount;  // 实付金额
    private String currency;       // 币种 (默认 CNY)
    private String payerId;        // 付款人渠道标识 (OpenID/UID/CustomerId)
    private String eventStatus;    // SUCCESS, AMOUNT_MISMATCH, CURRENCY_MISMATCH, MERCHANT_MISMATCH, LATE_PAYMENT, FULFILLMENT_FAILED
    private String errorMessage;   // 异常说明
    private String rawPayload;     // 原始回调报文快照

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
