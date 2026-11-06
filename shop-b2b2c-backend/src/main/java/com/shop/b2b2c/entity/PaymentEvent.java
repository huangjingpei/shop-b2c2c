/*
 * 文件：PaymentEvent.java
 * 用途：支付渠道到账事件流水实体模型，持久化外部支付机构确认事实与资金要素 (R13/G34)。
 * 维护注意：T32 新增 collection_mode（货款归集模式）、platform_trade_no（阶段二平台流水，阶段一为空）、channel_merchant_id（渠道商户号）；merchant_id 仍为平台内部店铺ID。
 * G34 新增 reconcile_attempts（陈旧 PROCESSING 渠道回查已重试次数）与 next_reconcile_at（下次允许回查时间，NULL 表示立即可扫）。
 * event_status 中的 MANUAL_REVIEW 是超过重试上限后的人工队列状态，不得当作已履约或终态成功。
 * T+1 第五轮新增 funds_received：渠道侧是否已确认收到资金，1-已到账、0-明确未到账、NULL-未标注；
 * 不设默认值，NULL 是有意义的状态（必须产生可见对账信号），写入方必须显式赋值。
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
    private String eventStatus;    // PROCESSING, SUCCESS, MANUAL_REVIEW, DUPLICATE_PAYMENT, AMOUNT_MISMATCH, CURRENCY_MISMATCH, MERCHANT_MISMATCH, LATE_PAYMENT, FULFILLMENT_FAILED
    private String errorMessage;   // 异常说明
    private String rawPayload;     // 原始回调报文快照
    private Integer reconcileAttempts; // G34：陈旧 PROCESSING 渠道回查已重试次数
    private LocalDateTime nextReconcileAt; // G34：下次允许回查时间，NULL 表示立即可扫
    /**
     * 渠道侧是否已确认收到资金：1-已到账（进入“预期产生流水”判定）、
     * 0-明确未到账（不预期流水但拒绝事件仍须可见）、NULL-未标注（不预期流水但必须人工核对）。
     * 该列无默认值，漏写即为 NULL，由对账侧强制产生可见信号，避免被当成“无需流水”静默放过。
     */
    private Integer fundsReceived;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
