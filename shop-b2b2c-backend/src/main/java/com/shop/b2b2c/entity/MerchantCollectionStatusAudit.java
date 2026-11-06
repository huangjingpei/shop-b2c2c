/*
 * 文件：MerchantCollectionStatusAudit.java
 * 用途：收款状态审计模型（T35），记录停收、恢复与账期/额度策略确认。
 * 维护注意：只追加；operatorId 为空表示系统自动触发，不得用前端传入的操作人替代会话用户。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("merchant_collection_status_audit")
public class MerchantCollectionStatusAudit {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;
    private String action; // STOP/RECOVER/POLICY
    private String reason;
    private BigDecimal arrearsAtAction = BigDecimal.ZERO;
    private BigDecimal creditLimit;
    private Integer graceDays;
    private LocalDateTime dueAt;
    private Long operatorId;
    private LocalDateTime createdAt;
}
