/*
 * 文件：ProductEditingDTOs.java
 * 用途：商品编辑、SKU改价及上下架接口的字段白名单和编辑快照响应。
 * 维护注意：revision仅用于防止覆盖旧内容，不是授权凭证；商户从会话取得。更新不接收库存、类型、SKU编码或状态混写，SKU新增/删除与库存流水仍需独立业务设计。
 */
package com.shop.b2b2c.dto;

import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductSku;
import java.math.BigDecimal;
import java.util.List;

public final class ProductEditingDTOs {
    private ProductEditingDTOs() {}
    public record Price(Long id, BigDecimal price) {}
    public record Update(String revision, Long categoryId, String title, String subTitle, String mainImage,
                         String serviceDesc, List<Price> skus) {}
    public record Status(String revision, Integer status) {}
    public record Editor(Product product, List<ProductSku> skus, String revision) {}
}
