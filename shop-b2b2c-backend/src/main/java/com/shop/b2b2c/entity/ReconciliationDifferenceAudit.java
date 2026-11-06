/*
 * 文件：ReconciliationDifferenceAudit.java
 * 用途：对账差异处理审计（append-only），记录谁在何时把哪条差异从什么状态改成什么状态。
 * 维护注意：
 *  1. 只追加，不更新、不删除；差异本身只保留最后一次处理结果，历史以本表为准。
 *  2. operatorId 为 0 或 null 表示系统跑批/自动重试；人工动作必须写登录管理员 ID。
 *  3. action 仅允许 CREATE/RETRY/RESOLVE/IGNORE/ESCALATE，避免出现无法归类的操作。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("reconciliation_difference_audit")
public class ReconciliationDifferenceAudit {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long differenceId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private Long operatorId;
    private String note;
    private LocalDateTime createdAt;
}
