/*
 * 文件：MerchantProductView.java
 * 用途：商家工作台本店商品列表的稳定响应契约，同时下发商品主记录与其SKU，供推单选品等页面直接使用。
 * 维护注意：字段名product/skus为前端已约定结构，变更会同时影响Orders.vue的选品与SKU联动；只读聚合，不承载库存扣减或权限判断。租户范围由控制器在查询前锁定，本类不得暴露其他店铺商品。
 */
package com.shop.b2b2c.dto;

import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductSku;

import java.util.List;

public record MerchantProductView(Product product, List<ProductSku> skus) {
}
