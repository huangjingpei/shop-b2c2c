/*
 * 文件：MerchantCollectionSwitchAudit.java
 * 用途：商家收款模式切换审计（M1），只追加记录 from_mode/to_mode、原因、操作人与时间。
 * 维护注意：
 *  1. 只追加、不更新、不删除；operatorId 取会话用户，系统任务才允许为空。
 *  2. 与 merchant_collection_status_audit（停收/恢复）不是同一语义，禁止混用或互相替代。
 *  3. 切换只影响新订单；本表不记录订单快照，订单以 trade_order.collection_mode 为准。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_collection_switch_audit")
public class MerchantCollectionSwitchAudit {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;
    private String fromMode;
    private String toMode;
    private String reason;
    private Long operatorId;
    private LocalDateTime createdAt;
}
