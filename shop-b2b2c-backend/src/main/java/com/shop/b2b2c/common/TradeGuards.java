package com.shop.b2b2c.common;

import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductSku;
import java.util.Objects;
import java.math.BigDecimal;
import com.shop.b2b2c.entity.MerchantStore;

public final class TradeGuards {
    private TradeGuards() {}

    public static void requireSkuOwnership(Product product, ProductSku sku) {
        if (product.getId() == null || product.getMerchantId() == null
                || !Objects.equals(product.getId(), sku.getProductId())
                || !Objects.equals(product.getMerchantId(), sku.getMerchantId())) {
            throw new BusinessException(400, "商品与SKU不匹配");
        }
    }
    public static void requirePurchasable(Product product, MerchantStore store) {
        if (store == null || !Integer.valueOf(1).equals(store.getStatus())
                || !Integer.valueOf(1).equals(product.getStatus())) {
            throw new BusinessException(400, "商品已下架或店铺不可交易");
        }
        requireGoodsType(product.getGoodsType());
    }

    public static void requireGoodsType(Integer type) {
        if (type == null || type < 1 || type > 3) throw new BusinessException(400, "不支持的商品类型");
    }

    public static void requireMoney(BigDecimal money) {
        if (money == null || money.signum() <= 0 || money.scale() > 2
                || money.compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new BusinessException(400, "金额须为正数、最多两位小数且不超过99999999.99");
        }
    }

    public static void requireQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0 || quantity > 10000) {
            throw new BusinessException(400, "数量须为1至10000");
        }
    }
}
