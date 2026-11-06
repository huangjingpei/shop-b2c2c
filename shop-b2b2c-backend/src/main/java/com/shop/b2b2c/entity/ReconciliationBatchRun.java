/*
 * 文件：ReconciliationBatchRun.java
 * 用途：T+1 对账跑批记录，每店每业务日一条，支撑幂等、断点续跑、失败重试与跑批可见。
 * 维护注意：
 *  1. (businessDate, merchantId) 由唯一键 uk_reconciliation_run 约束，同一天重复启动不会产生第二条记录。
 *  2. status=PENDING/RUNNING/SUCCESS/PARTIAL/FAILED/MANUAL；PARTIAL 表示渠道流水未导入、可续跑；
 *     MANUAL 表示达到最大尝试次数，需人工处理，不再自动重跑。
 *  3. 计数列只用于展示本次跑批结果，不作为对账通过依据；具体单据以 reconciliation_difference 为准。
 *  4. lastError 必须保留失败/部分完成原因，尤其“渠道流水未导入”，不得静默跳过。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("reconciliation_batch_run")
public class ReconciliationBatchRun {
    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate businessDate;
    private Long merchantId;
    private String status = "PENDING"; // PENDING/RUNNING/SUCCESS/PARTIAL/FAILED/MANUAL
    private Integer attempts = 0;
    private Integer statementCount = 0;
    private Integer eventCount = 0;
    private Integer refundCount = 0;
    private Integer differenceAdded = 0;
    private Integer differenceRefreshed = 0;
    private Integer retryProcessed = 0;
    /** 自动重试异常数；异常摘要必须写入 lastError，不得只留日志。 */
    private Integer retryFailed = 0;
    private Integer manualEscalated = 0;
    private String lastError;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
