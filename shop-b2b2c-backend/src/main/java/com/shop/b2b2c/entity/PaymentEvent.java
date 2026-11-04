/*
 * 文件：PaymentEvent.java
 * 用途：支付渠道到账事件流水实体模型，持久化外部支付机构确认事实与资金要素 (R13)。
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
    private Long merchantId;       // 商户ID
    private BigDecimal payAmount;  // 实付金额
    private String currency;       // 币种 (默认 CNY)
    private String payerId;        // 付款人渠道标识 (OpenID/UID/CustomerId)
    private String eventStatus;    // SUCCESS, AMOUNT_MISMATCH, CURRENCY_MISMATCH, MERCHANT_MISMATCH, LATE_PAYMENT, FULFILLMENT_FAILED
    private String errorMessage;   // 异常说明
    private String rawPayload;     // 原始回调报文快照

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
