/*
 * 文件：CardVoidRecord.java
 * 用途：卡密安全作废的只追加记录，保存操作人、原因及可售数量变化。
 * 维护注意：cardId同时是自然幂等主键，不含卡密内容；记录不得通过业务接口覆盖或删除。仅说明本次作废，不代表全量资产审计或数据库管理员不可篡改。
 */
package com.shop.b2b2c.entity;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class CardVoidRecord {
    private Long cardId;
    private Long merchantId;
    private Long productId;
    private Long skuId;
    private Long actorId;
    private String reason;
    private Integer beforeStock;
    private Integer afterStock;
    private LocalDateTime createdAt;
}
