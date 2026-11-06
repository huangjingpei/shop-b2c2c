/*
 * 文件：PayChannel.java
 * 用途：定义真实支付渠道编码（微信/支付宝），与货款归集模式 CollectionMode 分离 (T32)。
 * 维护注意：
 *  1. 只允许 WECHAT / ALIPAY；MOCK 是历史沙箱概念，生产与阶段一真实渠道一律拒绝。
 *  2. 渠道编码必须来自真实回调/查单结果，不得把 merchant_store.payment_channel_type 的 A/B/C 写入本列。
 *  3. 未知或空值拒绝，不静默回退到微信。
 */
package com.shop.b2b2c.common;

public final class PayChannel {

    public static final String WECHAT = "WECHAT";
    public static final String ALIPAY = "ALIPAY";

    private PayChannel() {
    }

    public static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if ("MOCK".equals(normalized)) {
            throw new BusinessException(409, "生产环境禁用模拟支付渠道");
        }
        if (WECHAT.equals(normalized) || ALIPAY.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(400, "不支持的支付渠道: " + value);
    }

    public static boolean isSupported(String value) {
        if (value == null) return false;
        String normalized = value.trim().toUpperCase();
        return WECHAT.equals(normalized) || ALIPAY.equals(normalized);
    }
}
