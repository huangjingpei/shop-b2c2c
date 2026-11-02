package com.shop.b2b2c.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false)
    private String title;

    @Column(name = "sub_title")
    private String subTitle;

    @Column(name = "goods_type", nullable = false)
    private Integer goodsType = 1; // 1-实物物流发货, 2-线上服务/虚拟免物流

    @Column(name = "main_image", nullable = false)
    private String mainImage;

    @Column(name = "detail_images", columnDefinition = "json")
    private String detailImages;

    // ==================== 线上服务交付说明扩展 ====================
    @Column(name = "service_desc", columnDefinition = "text")
    private String serviceDesc; // 线上服务履约交付指引说明与不可逆防白嫖退款强警示

    @Column(name = "min_price", nullable = false)
    private BigDecimal minPrice = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer status = 1; // 0-下架, 1-在售, 2-违规下架

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
