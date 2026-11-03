/*
 * 文件：InventoryAdjustment.java
 * 用途：人工库存调整的持久化记录，保存操作人、原因及可售数量变化。
 * 维护注意：仅追加成功调整，禁止通过业务Mapper修改/删除；requestKey是带租户/操作人作用域的摘要，不向客户端暴露。该表不覆盖发卡、预占等全部库存事件，也不是数据库管理员不可篡改的审计设施。
 */
package com.shop.b2b2c.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class InventoryAdjustment {
    private Long id;
    private Long merchantId;
    private Long productId;
    private Long skuId;
    private Long actorId;
    private String requestKey;
    private Integer delta;
    private Integer beforeStock;
    private Integer afterStock;
    private String reason;
    private LocalDateTime createdAt;
}
