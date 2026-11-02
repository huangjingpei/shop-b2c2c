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
