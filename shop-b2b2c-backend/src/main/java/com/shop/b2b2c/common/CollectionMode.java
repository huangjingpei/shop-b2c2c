/*
 * 文件：CollectionMode.java
 * 用途：定义订单货款归集模式（阶段一商家直收 / 阶段二平台代收），替代旧 A/B/C 一列双语义 (T32)。
 * 维护注意：
 *  1. MERCHANT_DIRECT 为阶段一唯一允许的收款模式；PLATFORM_COLLECT 属阶段二，未取得分账资质前必须拒绝。
 *  2. 旧 merchant_store.payment_channel_type（A/B/C）只保留为迁移来源，新逻辑不得再把它写入支付渠道。
 *  3. 本类只做编码与校验，不持有任何资金状态；未知值一律拒绝，不自动降级。
 */
package com.shop.b2b2c.common;

public final class CollectionMode {

    public static final String MERCHANT_DIRECT = "MERCHANT_DIRECT";
    public static final String PLATFORM_COLLECT = "PLATFORM_COLLECT";

    private CollectionMode() {
    }

    /**
     * 已知模式规范化：只接受两种已定义值，未知值一律 400，不自动降级。
     * 用于运营切换、配置读取等需要同时识别两种模式的场景；支付渠道入口仍须调用 requireStageOne。
     */
    public static String requireKnown(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (MERCHANT_DIRECT.equals(normalized) || PLATFORM_COLLECT.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(400, "未知货款归集模式: " + value);
    }

    /**
     * 阶段一渠道入口只接受商家直收；平台代收需阶段二资质与分账模型，直接拒绝而不是回退。
     */
    public static String requireStageOne(String value) {
        String normalized = requireKnown(value);
        if (PLATFORM_COLLECT.equals(normalized)) {
            throw new BusinessException(409, "阶段一未启用平台代收货款，请使用商家直收渠道");
        }
        return MERCHANT_DIRECT;
    }
}
