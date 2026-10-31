package com.shop.b2b2c.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Entity
@Table(name = "trade_order_item")
public class TradeOrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_sn", nullable = false)
    private String orderSn;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "sku_code", nullable = false)
    private String skuCode;

    @Column(name = "product_title", nullable = false)
    private String productTitle;

    @Column(name = "spec_info")
    private String specInfo;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private BigDecimal subtotal;

    @Column(name = "card_details", columnDefinition = "text")
    private String cardDetails; // 虚拟卡密售出内容快照
}
