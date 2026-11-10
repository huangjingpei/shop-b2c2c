/*
 * 文件：PlatformCashierChannelRegistry.java
 * 用途：问题 1 方案 A / Sprint 57 平台收银台适配器路由：按真实渠道编码路由，
 *      并在入口给出“未接入 / 未配置 / 已配置未联调”的细化拒绝原因。
 *      M4-B：新增 requireForRefund 退款入口，同一检查链、文案细化到退款语义（先退分后退款）。
 * 维护注意：
 *  1. 只注册真实渠道（当前仅 WECHAT 占位）；不存在 MOCK 落账路径，enabled()=false 时直接拒绝。
 *  2. 细化文案语义：凭据缺失=“平台收银台未配置”；凭据配齐但开关未开=“已配置未完成通道联调”；
 *     两者都必须 409 拒绝，不得回退商家直收或伪造成功。
 *  3. 不得按请求传入的渠道名动态反射或实例化适配器；真实联调完成前 payment.platform.enabled 保持 false。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PayChannel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PlatformCashierChannelRegistry {

    private final Map<String, PlatformCashierChannelAdapter> adapters = new HashMap<>();
    private final PlatformCashierProperties properties;

    public PlatformCashierChannelRegistry(List<PlatformCashierChannelAdapter> adapterList,
                                          PlatformCashierProperties properties) {
        for (PlatformCashierChannelAdapter adapter : adapterList) {
            adapters.put(adapter.channel(), adapter);
        }
        this.properties = properties;
    }

    /** 平台代收发起支付入口：任何未就绪情形都 409 并给出可操作的细化原因。 */
    public PlatformCashierChannelAdapter require(String channel) {
        return require(channel, "发起支付");
    }

    /**
     * M4-B 平台代收退款入口：与发起支付同一检查链，文案细化到退款语义。
     * 平台代收退款必须先退分后退款，任何未就绪情形都 409，不得回退商家直收渠道退款。
     */
    public PlatformCashierChannelAdapter requireForRefund(String channel) {
        return require(channel, "退款（先退分后退款）");
    }

    private PlatformCashierChannelAdapter require(String channel, String action) {
        String normalized = PayChannel.normalize(channel);
        PlatformCashierChannelAdapter adapter = adapters.get(normalized);
        if (adapter == null) {
            throw new BusinessException(409, "平台收银台未接入渠道: " + normalized);
        }
        if (!properties.configured()) {
            throw new BusinessException(409, "平台收银台未配置：缺少平台服务商商户号/证书/APIv3 密钥"
                    + "（payment.platform.* 环境变量），平台代收订单暂不能" + action);
        }
        if (!properties.isEnabled() || !adapter.enabled()) {
            throw new BusinessException(409, "平台收银台已配置但未完成通道联调"
                    + "（payment.platform.enabled=false），平台代收订单暂不能" + action);
        }
        return adapter;
    }
}
