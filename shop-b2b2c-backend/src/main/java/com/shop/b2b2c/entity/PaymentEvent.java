/*
 * 文件：PaymentEvent.java
 * 用途：支付渠道到账事件流水实体模型，持久化外部支付机构确认事实与资金要素 (R13/G34)。
 * 维护注意：T32 新增 collection_mode（货款归集模式）、platform_trade_no（阶段二平台流水，阶段一为空）、channel_merchant_id（渠道商户号）；merchant_id 仍为平台内部店铺ID。
 * G34 新增 reconcile_attempts（陈旧 PROCESSING 渠道回查已重试次数）与 next_reconcile_at（下次允许回查时间，NULL 表示立即可扫）。
 * event_status 中的 MANUAL_REVIEW 是超过重试上限后的人工队列状态，不得当作已履约或终态成功。
 * T+1 第五轮新增 funds_received，语义为“渠道侧存在该笔资金往来、应当有对应渠道流水”，不是“此刻仍持有资金”；
 * 1-预期渠道存在流水（新事件由 claimEvent 占位即写 1，且只增不减）、0-明确无资金往来（仅对未标注行/历史或异常行生效，
 * 新事件不可达）、NULL-未判定（必须产生可见对账信号）。不设默认值，NULL 是有意义的状态，写入方必须显式赋值。
 * 1 不得被后续“未支付/退款/无法确认”答复覆盖成 0/NULL；渠道退款也记 1（钱到过账又退回），
 * 状态仍可为 FULFILLMENT_FAILED，由 error_message 前缀区分退款类与渠道答复冲突类。
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
     * 渠道侧是否存在该笔资金往来、应当有对应渠道流水（不是“此刻仍持有资金”）：
     * 1-预期渠道存在流水（新事件占位即 1，进入“预期产生流水”判定，且只增不减）、
     * 0-明确无资金往来（仅对未标注行/历史或异常行生效，不预期流水但差异仍须可见）、
     * NULL-未判定（不预期流水但必须人工核对）。
     * 该列无默认值，漏写即为 NULL，由对账侧强制产生可见信号，避免被当成“无需流水”静默放过。
     */
    private Integer fundsReceived;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
