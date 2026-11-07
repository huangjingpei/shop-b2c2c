/*
 * 文件：ProductViolationAudit.java
 * 用途：F11 商品违规处置审计实体：平台违规下架(TAKEDOWN)与审核恢复(RESTORE)全量留痕，append-only。
 * 维护注意：
 *  1. 只追加不修改；fromStatus/toStatus 语义同 product.status：0-已下架 1-在售 2-违规下架。
 *  2. RESTORE 的 toStatus 取最近一次 TAKEDOWN 的 fromStatus（撤销处置回到原状态），无历史 TAKEDOWN 兜底 0。
 *  3. operatorId 为操作的平台超管 sys_user.id，无外键；merchantId 冗余便于按店筛查。
 *  4. 审计必须与 product.status 的 CAS 更新在同一事务写入，禁止只有状态没有审计。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product_violation_audit")
public class ProductViolationAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    /** 冗余商品所属店铺ID，便于按店筛查。 */
    private Long merchantId;

    /** TAKEDOWN-违规下架 / RESTORE-审核恢复。 */
    private String action;

    /** 处置前商品状态：0-已下架 1-在售 2-违规下架。 */
    private Integer fromStatus;

    private Integer toStatus;

    /** 处置原因/审核备注，必填，1..255。 */
    private String reason;

    /** 操作的平台超管 sys_user.id。 */
    private Long operatorId;

    private LocalDateTime createdAt;
}
