/*
 * 文件：ProductEditingDTOs.java
 * 用途：商品编辑、SKU改价/增删及上下架接口的字段白名单和编辑快照响应。
 * 维护注意：revision仅用于防止覆盖旧内容，不是授权凭证；商户从会话取得。更新不接收库存、类型、SKU编码或状态混写，规格编辑仍需独立业务设计。SkuDraft的stockQuantity为初始库存（自动卡密必须为0），后续库存走对应库存业务，删除SKU只允许零库存且无任何历史记录的SKU。
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
    public record SkuDraft(String revision, String skuCode, String specData, BigDecimal price, Integer stockQuantity) {}
    public record SkuRemoval(String revision) {}
    public record Editor(Product product, List<ProductSku> skus, String revision) {}
}
