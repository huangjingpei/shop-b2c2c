/*
 * 文件：ProductSku.java
 * 用途：商品规格、价格及可售库存的持久化模型。
 * 维护注意：库存已扣除有效预占，更新必须走原子增减及事务；price为服务端计价依据，须校验商品与商户归属，不能信任前端组合ID。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product_sku")
public class ProductSku {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;
    private Long merchantId;
    private String skuCode;
    private String specData;
    private BigDecimal price;
    private Integer stockQuantity = 0;
    private LocalDateTime createdAt;
}
