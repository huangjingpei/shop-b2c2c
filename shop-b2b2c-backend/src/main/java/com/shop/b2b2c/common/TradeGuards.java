package com.shop.b2b2c.common;

import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductSku;
import java.util.Objects;

public final class TradeGuards {
    private TradeGuards() {}

    public static void requireSkuOwnership(Product product, ProductSku sku) {
        if (product.getId() == null || product.getMerchantId() == null
                || !Objects.equals(product.getId(), sku.getProductId())
                || !Objects.equals(product.getMerchantId(), sku.getMerchantId())) {
            throw new BusinessException(400, "商品与SKU不匹配");
        }
    }
}
