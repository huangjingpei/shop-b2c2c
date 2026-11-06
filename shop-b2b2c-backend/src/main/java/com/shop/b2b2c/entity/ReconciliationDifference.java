/*
 * 文件：ReconciliationDifference.java
 * 用途：对账差异队列模型（T35），记录金额不符、事件/流水缺失、费用账本缺失与结算明细不一致。
 * 维护注意：
 *  1. differenceKey 稳定唯一，防止同一差异重复入队；差异不得自动平衡或直接改订单金额。
 *  2. status=DISPUTED 表示待人工处理；RETRY 仅表示已发起重试，不代表已修复。
 *  3. 处理动作、处理人、时间与说明必须留痕。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("reconciliation_difference")
public class ReconciliationDifference {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String differenceKey;
    private Long merchantId;
    private String channel;
    private String diffType;
    private String bizType;
    private Long bizId;
    private String orderSn;
    private String channelTradeNo;
    private BigDecimal expectedAmount;
    private BigDecimal actualAmount;
    private String status = "DISPUTED"; // DISPUTED/RETRY/RESOLVED/IGNORED
    private String detail;
    private Integer retryCount = 0;
    private Long handledBy;
    private LocalDateTime handledAt;
    private String handleNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
