/*
 * 文件：CardImportBatch.java
 * 用途：成功卡密导入批次的元数据，保存操作人、商品/SKU、数量和可售库存前后快照。
 * 维护注意：不保存卡密内容或重试凭证；与卡片、批次关联和库存同事务提交，只追加。历史导入不伪造批次，before/after不是实时数量。
 */
package com.shop.b2b2c.entity;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class CardImportBatch {
    private Long id;
    private Long merchantId;
    private Long productId;
    private Long skuId;
    private Long actorId;
    private Integer importedCount;
    private Integer beforeStock;
    private Integer afterStock;
    private LocalDateTime createdAt;
}
