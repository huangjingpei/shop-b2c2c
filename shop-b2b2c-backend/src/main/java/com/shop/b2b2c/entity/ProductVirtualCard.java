package com.shop.b2b2c.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "product_virtual_card")
public class ProductVirtualCard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "card_content", columnDefinition = "text", nullable = false)
    private String cardContent;

    @Column(nullable = false)
    private Integer status = 0; // 0-未售出, 1-已售出核销, 2-作废

    @Column(name = "order_sn")
    private String orderSn;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
