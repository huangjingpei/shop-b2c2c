package com.shop.b2b2c.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class InventoryReservation {
    private Long orderId;
    private Long skuId;
    private Long productId;
    private Long merchantId;
    private Integer quantity;
    private String ownerToken;
    private String status;
    private LocalDateTime expiresAt;
}
