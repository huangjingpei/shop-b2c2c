/*
 * 文件：PublicStoreView.java
 * 用途：公开店铺资料、商品详情和查单接口的店铺信息白名单。
 * 维护注意：只公开七个展示字段（F07 增加实物订单固定运费 shippingFee，用于下单前明示）；禁止直接复用MerchantStore以免新增实体字段被自动公开。修改record字段属于API契约变更，须测试序列化及所有公开入口。
 */
package com.shop.b2b2c.common;

import com.shop.b2b2c.entity.MerchantStore;

/** Explicit public allowlist; adding entity fields never publishes them implicitly. */
public record PublicStoreView(Long id, String storeCode, String storeName,
                              String logoUrl, String notice, String contactPhone,
                              java.math.BigDecimal shippingFee) {
    public static PublicStoreView from(MerchantStore store) {
        return store == null ? null : new PublicStoreView(store.getId(), store.getStoreCode(),
                store.getStoreName(), store.getLogoUrl(), store.getNotice(), store.getContactPhone(),
                store.getShippingFee() == null ? java.math.BigDecimal.ZERO : store.getShippingFee());
    }
}
