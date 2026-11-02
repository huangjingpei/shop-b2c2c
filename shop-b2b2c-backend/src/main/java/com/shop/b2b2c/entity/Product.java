package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product")
public class Product {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;
    private Long categoryId;
    private String title;
    private String subTitle;
    private Integer goodsType = 1; // 1-实物物流发货, 2-自动卡密, 3-定制服务
    private String mainImage;
    private String detailImages;
    private String serviceDesc; // 线上服务履约交付指引说明与不可逆防白嫖退款强警示
    private BigDecimal minPrice = BigDecimal.ZERO;
    private Integer status = 1; // 0-下架, 1-在售, 2-违规下架
    private Integer sortOrder = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
