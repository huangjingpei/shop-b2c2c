/*
 * 文件：CollectionMode.java
 * 用途：定义订单货款归集模式（阶段一商家直收 / 阶段二平台代收），替代旧 A/B/C 一列双语义 (T32)。
 * 维护注意：
 *  1. MERCHANT_DIRECT 为阶段一默认收款模式；PLATFORM_COLLECT 属阶段二，支付/退款入口按该快照分别路由到
 *     商家直收渠道或平台收银台（Sprint 57/M4-B），未配置/未联调时 409，不做隐式降级。
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
     * 支付/退款渠道入口在确认已知模式后按值路由：MERCHANT_DIRECT 走商家直收渠道，
     * PLATFORM_COLLECT 走平台收银台骨架（PlatformCashierChannelRegistry 细化拒绝）。
     */
    public static String requireKnown(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (MERCHANT_DIRECT.equals(normalized) || PLATFORM_COLLECT.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(400, "未知货款归集模式: " + value);
    }
}
