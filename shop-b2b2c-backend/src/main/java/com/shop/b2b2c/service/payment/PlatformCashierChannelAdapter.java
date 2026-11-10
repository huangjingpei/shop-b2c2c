/*
 * 文件：PlatformCashierChannelAdapter.java
 * 用途：问题 1 方案 A / Sprint 57 平台收银台渠道适配契约（骨架）：
 *      平台代收订单的下单、支付回调验签解析、查单与关单；签名与 PaymentChannelAdapter 对齐。
 *      M4-B 起补平台代收退款契约：refundPayment/queryRefund（平台服务商商户号原路退回买家）与
 *      returnSplit/querySplitReturn（分账回退=先退分后退款的“退分”渠道指令）。
 * 维护注意：
 *  1. 只有取得平台服务商资质、配齐凭据并完成通道联调后才允许实现真实调用；
 *     当前唯一实现为占位适配器，enabled() 必须为 false。
 *  2. 适配器只返回渠道事实，不写本地账本、不改订单/退款状态；本地状态由支付事件/退款事务服务推进。
 *  3. 未启用或凭据缺失时必须明确拒绝，不得返回伪造成功；金额换算与验签只在适配器内部完成。
 *  4. 平台代收资金必须直接进入持牌机构分账链路，适配器不得把货款落入平台自有资金账户。
 *  5. 退款资金由平台服务商商户号原路退回：适配器按 payment.platform.sp-mchid 自取发起商户号，
 *     不得使用 RefundCommand.merchantChannelId 传入的商家直收账户。
 *  6. returnSplit 必须先于 refundPayment 调用（设计 D4 先退分后退款）；退分以 refundNo 为渠道幂等键。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.dto.PaymentChannelDTOs;

import java.util.Map;

public interface PlatformCashierChannelAdapter {

    String channel();

    boolean enabled();

    PaymentChannelDTOs.PaymentAttemptView createPayment(PaymentChannelDTOs.PaymentCreateCommand command);

    /**
     * @param headers   HTTP 请求头（微信 APIv3 使用）
     * @param rawBody   HTTP 原始报文（微信 APIv3 使用）
     * @param formParams 表单参数（支付宝异步通知使用；微信回调为 null）
     */
    PaymentChannelDTOs.ChannelCallback parseCallback(Map<String, String> headers, String rawBody,
                                                     Map<String, String> formParams);

    PaymentChannelDTOs.ChannelQueryResult queryPayment(PaymentChannelDTOs.PaymentCreateCommand command);

    void closePayment(PaymentChannelDTOs.PaymentCreateCommand command);

    /** M4-B：平台代收退款（平台服务商商户号原路退回买家）；PROCESSING 必须由 queryRefund 再确认。 */
    PaymentChannelDTOs.RefundResult refundPayment(PaymentChannelDTOs.RefundCommand command);

    /** M4-B：平台代收退款查单。 */
    PaymentChannelDTOs.RefundResult queryRefund(PaymentChannelDTOs.RefundCommand command);

    /** M4-B：分账回退（先退分后退款中的退分）；必须在 refundPayment 之前调用并取得 SUCCESS。 */
    PaymentChannelDTOs.SplitReturnResult returnSplit(PaymentChannelDTOs.SplitReturnCommand command);

    /** M4-B：分账回退结果查单。 */
    PaymentChannelDTOs.SplitReturnResult querySplitReturn(PaymentChannelDTOs.SplitReturnCommand command);
}
