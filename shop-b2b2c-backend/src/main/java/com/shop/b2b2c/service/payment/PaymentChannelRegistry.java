/*
 * 文件：PaymentChannelRegistry.java
 * 用途：按真实渠道编码路由适配器，并在入口统一拒绝模拟渠道 (T32)。
 * 维护注意：
 *  1. 只注册真实渠道（WECHAT/ALIPAY）；不存在 MOCK 适配器，生产禁用模拟支付由 PayChannel 与这里双重拒绝。
 *  2. 渠道未启用时仍返回适配器用于回调验签排查，但适配器自身的下单/查单/关单会拒绝。
 *  3. 不得按商户自定义渠道名动态反射或从请求直接实例化适配器。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PayChannel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentChannelRegistry {

    private final Map<String, PaymentChannelAdapter> adapters = new HashMap<>();

    public PaymentChannelRegistry(List<PaymentChannelAdapter> adapterList) {
        for (PaymentChannelAdapter adapter : adapterList) {
            adapters.put(adapter.channel(), adapter);
        }
    }

    public PaymentChannelAdapter require(String channel) {
        String normalized = PayChannel.normalize(channel);
        PaymentChannelAdapter adapter = adapters.get(normalized);
        if (adapter == null) {
            throw new BusinessException(409, "支付渠道未接入: " + normalized);
        }
        return adapter;
    }
}
