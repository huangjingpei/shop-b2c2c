/*
 * 文件：PaymentChannelProperties.java
 * 用途：真实支付渠道凭据与开关配置（微信/支付宝），阶段一商家直收专用 (T32)。
 * 维护注意：
 *  1. 凭据只能来自环境变量/部署密钥，不得写入仓库或日志；本类不做任何默认密钥回退。
 *  2. mockEnabled 仅供本地联调，生产与测试默认关闭；即使开启也不存在 MOCK 落账适配器。
 *  3. enabled=false 时渠道仍可校验回调签名（便于验签排查），但 create/query/close 必须拒绝。
 *  4. notify-base-url 为空时不允许发起渠道下单，避免渠道回调到未知地址。
 */
package com.shop.b2b2c.service.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.shop.b2b2c.common.BusinessException;

@Component
@ConfigurationProperties(prefix = "payment.channel")
@Data
public class PaymentChannelProperties {

    private boolean mockEnabled = false;
    private String notifyBaseUrl = "";
    private WeChat wechat = new WeChat();
    private Alipay alipay = new Alipay();

    @Data
    public static class WeChat {
        private boolean enabled = false;
        private String appId = "";
        private String serialNo = "";
        private String apiV3Key = "";
        private String privateKey = "";
        private String platformPublicKey = "";
        private String apiBase = "https://api.mch.weixin.qq.com";
    }

    @Data
    public static class Alipay {
        private boolean enabled = false;
        private String appId = "";
        private String privateKey = "";
        private String alipayPublicKey = "";
        private String gatewayUrl = "https://openapi.alipay.com/gateway.do";
    }

    public String notifyUrl(String channel) {
        String base = notifyBaseUrl == null ? "" : notifyBaseUrl.trim();
        if (base.isEmpty()) {
            throw new BusinessException(503, "未配置 payment.channel.notify-base-url，禁止发起渠道支付");
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/api/v1/payment/callback/" + channel.toLowerCase();
    }
}
