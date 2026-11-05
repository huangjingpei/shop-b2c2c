/*
 * 文件：PaymentEventService.java
 * 用途：支付到账事件持久化、资金核对与本地履约解耦服务 (R13)。
 * 维护注意：事件状态与订单履约必须在同一业务事务内提交；重放不得只凭事件 SUCCESS 短路，须回查订单真实状态；禁止恢复公开 Mock/未验签到账入口。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.dto.PaymentEventDTOs;
import com.shop.b2b2c.entity.PaymentEvent;

public interface PaymentEventService {

    /**
     * 处理外部支付渠道或可信适配器提交的到账事件：
     * 1. 唯一持久化到账事件流水 (uk_channel_trade_no 防止一单多落或重复落账)
     * 2. 资金三要素核对 (商户、实收金额、币种)；实收金额缺失或与应付不符不得落账
     * 3. 订单生命周期与迟到款检查 (超期关闭单据转退款/补偿，不执行本地资产发卡)
     * 4. CAS迁移订单至PAID并幂等触发本地交付 (卡密发放/实物待发货/服务凭证)
     * 5. 已支付订单收到不同流水号时记录 DUPLICATE_PAYMENT，进入重复支付补偿队列
     */
    PaymentEventDTOs.PaymentProcessingResult processPaymentEvent(PaymentEventDTOs.IncomingPaymentEvent event);

    /**
     * 根据渠道和流水号检索已持久化的到账事件
     */
    PaymentEvent findEvent(String channel, String payTradeNo);

    /**
     * 补偿扫描：重驱动“事件已 SUCCESS 但订单仍未支付/未交付”的历史不一致记录。
     * 返回本轮尝试处理的条数；单条失败写入 FULFILLMENT_FAILED，不阻塞后续记录。
     */
    int reconcileSuccessfulWithoutFulfillment(int limit);
}
