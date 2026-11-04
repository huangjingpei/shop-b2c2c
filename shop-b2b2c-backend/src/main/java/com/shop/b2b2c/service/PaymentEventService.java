/*
 * 文件：PaymentEventService.java
 * 用途：支付到账事件持久化、资金核对与本地履约解耦服务 (R13)。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.dto.PaymentEventDTOs;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.entity.TradeOrder;

public interface PaymentEventService {

    /**
     * 处理外部支付渠道或可信适配器提交的到账事件：
     * 1. 唯一持久化到账事件流水 (uk_channel_trade_no 防止一单多落或重复落账)
     * 2. 资金三要素核对 (商户、实付金额、币种)
     * 3. 订单生命周期与迟到款检查 (超期关闭单据转退款/补偿，不执行本地资产发卡)
     * 4. CAS迁移订单至PAID并幂等触发本地交付 (卡密发放/实物待发货/服务凭证)
     */
    PaymentEventDTOs.PaymentProcessingResult processPaymentEvent(PaymentEventDTOs.IncomingPaymentEvent event);

    /**
     * 根据渠道和流水号检索已持久化的到账事件
     */
    PaymentEvent findEvent(String channel, String payTradeNo);
}
