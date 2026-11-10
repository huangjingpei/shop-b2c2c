/*
 * 文件：PlatformCashierProperties.java
 * 用途：问题 1 方案 A / Sprint 57 平台收银台（平台代收支付发起）配置契约：
 *      平台服务商商户号、AppID、证书、APIv3 密钥、平台公钥与回调路径；Sprint 59 起供
 *      真实微信电商收付通适配器（WeChatPlatformCashierChannelAdapter）使用。
 * 维护注意：
 *  1. 凭据只能来自环境变量/部署密钥，默认全部为空=未配置，不得写入仓库或日志，不做任何默认密钥回退。
 *  2. configured()=true 只代表凭据配齐，不代表通道联调完成；enabled=false 时平台代收发起支付必须拒绝。
 *  3. enabled 只允许在完成真实通道联调后由环境变量打开；不得用它伪造“联调通过”。
 *  4. notifyPath 为平台代收订单专属回调入口，与商家直收 /api/v1/payment/callback/{channel} 隔离；
 *     完整回调地址 = notifyBaseUrl + notifyPath，notifyBaseUrl 未配置时禁止发起平台代收下单。
 *  5. spAppid 为服务商 AppID（电商收付通服务商模式下单必填）；apiBase 默认微信支付生产地址，
 *     仅在微信指定的联调/沙箱环境才允许覆盖。
 */
package com.shop.b2b2c.service.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "payment.platform")
@Data
public class PlatformCashierProperties {

    /** 平台收银台联调完成总开关：默认 false，未完成真实通道联调前不得打开。 */
    private boolean enabled = false;
    /** 平台服务商商户号（sp_mchid）。 */
    private String spMchid = "";
    /** 服务商 AppID（电商收付通服务商模式下单必填）。 */
    private String spAppid = "";
    /** 平台 API 证书序列号。 */
    private String serialNo = "";
    /** 平台 APIv3 密钥。 */
    private String apiV3Key = "";
    /** 平台 API 私钥（PEM）。 */
    private String privateKey = "";
    /** 微信支付平台公钥。 */
    private String platformPublicKey = "";
    /** 平台代收订单支付回调路径（独立于商家直收回调）。 */
    private String notifyPath = "/api/v1/payment/callback/platform";
    /** 平台代收回调公网基准地址；通常与 payment.channel.notify-base-url 同域，缺省为空=禁止下单。 */
    private String notifyBaseUrl = "";
    /** 微信支付 API 基准地址；默认生产，联调/沙箱环境由环境变量覆盖。 */
    private String apiBase = "https://api.mch.weixin.qq.com";

    /** 凭据是否配齐；配齐不代表已联调，发起支付还须 enabled=true。 */
    public boolean configured() {
        return notBlank(spMchid) && notBlank(spAppid) && notBlank(serialNo) && notBlank(apiV3Key)
                && notBlank(privateKey) && notBlank(platformPublicKey);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
