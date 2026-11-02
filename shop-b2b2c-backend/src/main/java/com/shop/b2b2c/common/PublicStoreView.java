package com.shop.b2b2c.common;

import com.shop.b2b2c.entity.MerchantStore;

/** Explicit public allowlist; adding entity fields never publishes them implicitly. */
public record PublicStoreView(Long id, String storeCode, String storeName,
                              String logoUrl, String notice, String contactPhone) {
    public static PublicStoreView from(MerchantStore store) {
        return store == null ? null : new PublicStoreView(store.getId(), store.getStoreCode(),
                store.getStoreName(), store.getLogoUrl(), store.getNotice(), store.getContactPhone());
    }
}
