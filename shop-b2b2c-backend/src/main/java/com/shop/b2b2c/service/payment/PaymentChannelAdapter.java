/*
 * 文件：PaymentChannelAdapter.java
 * 用途：真实支付渠道统一适配器契约：下单/回调验签解析/查单/关单 (T32)。
 * 维护注意：
 *  1. parseCallback 必须先完成验签与报文解密，再返回渠道事实；任何未验签数据不得进入落账链路。
 *  2. create/query/close 在渠道未启用或凭据缺失时必须明确拒绝，不得返回伪造成功。
 *  3. 适配器不写订单、不写 payment_event；本地状态只由 PaymentGatewayService + PaymentEventService 变更。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.dto.PaymentChannelDTOs;

import java.util.Map;

public interface PaymentChannelAdapter {

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
}
