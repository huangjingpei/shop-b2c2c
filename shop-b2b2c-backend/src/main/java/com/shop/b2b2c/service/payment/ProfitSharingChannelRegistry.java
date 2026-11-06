/*
 * 文件：ProfitSharingChannelRegistry.java
 * 用途：T40 按真实渠道编码路由分账适配器，并在入口统一拒绝未启用渠道（骨架）。
 * 维护注意：
 * 1. 只注册真实渠道（WECHAT/ALIPAY），不存在 MOCK 分账适配器。
 * 2. 渠道未启用时直接拒绝，不得回退到模拟成功或阶段一商家直收逻辑。
 * 3. 不得按请求传入的渠道名动态反射或实例化适配器。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PayChannel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProfitSharingChannelRegistry {

    private final Map<String, ProfitSharingChannelAdapter> adapters = new HashMap<>();

    public ProfitSharingChannelRegistry(List<ProfitSharingChannelAdapter> adapterList) {
        for (ProfitSharingChannelAdapter adapter : adapterList) {
            adapters.put(adapter.channel(), adapter);
        }
    }

    public ProfitSharingChannelAdapter require(String channel) {
        String normalized = PayChannel.normalize(channel);
        ProfitSharingChannelAdapter adapter = adapters.get(normalized);
        if (adapter == null) {
            throw new BusinessException(409, "分账渠道未接入: " + normalized);
        }
        if (!adapter.enabled()) {
            throw new BusinessException(409, normalized + " 分账通道待资质与通道联调，当前未启用");
        }
        return adapter;
    }
}
