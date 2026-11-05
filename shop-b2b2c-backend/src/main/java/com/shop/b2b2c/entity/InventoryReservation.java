/*
 * 文件：InventoryReservation.java
 * 用途：inventory_reservation 表的预占流水模型。
 * 维护注意：
 *  1. 每订单唯一；HELD 预占已扣可售库存，CONSUMED 付款核销，RELEASED 已返还。
 *  2. ownerToken 为服务端签发的高熵所有权凭证，不可公开序列化到详情或记录日志 (T10)。
 *  3. attemptKey 是客户端幂等尝试键，与 ownerToken 分离，不得用于恢复/取消授权；reissueCount 记录凭证重发次数，上限 3 次。
 *  4. 改表须同步 schema.sql、版本化迁移和测试结构。
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
    private String attemptKey;
    private Integer reissueCount;
    private String status;
    private LocalDateTime expiresAt;
}
