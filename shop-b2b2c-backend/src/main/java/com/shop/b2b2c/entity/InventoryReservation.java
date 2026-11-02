/*
 * 文件：InventoryReservation.java
 * 用途：inventory_reservation表的预占流水模型。
 * 维护注意：每订单唯一；HELD预占已扣可售库存，CONSUMED付款核销，RELEASED已返还。ownerToken为取消凭证，不可公开序列化到详情或记录日志；改表须同步增量迁移和测试结构。
 */
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
