package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("product_virtual_card")
public class ProductVirtualCard {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;
    private Long skuId;
    private Long merchantId;
    private String cardContent;
    private Integer status = 0; // 0-未售出, 1-已售出核销, 2-作废
    private String orderSn;
    private LocalDateTime createdAt;
}
