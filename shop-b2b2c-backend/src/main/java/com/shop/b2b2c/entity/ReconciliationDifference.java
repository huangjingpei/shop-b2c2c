/*
 * 文件：ReconciliationDifference.java
 * 用途：对账差异队列模型（T35/T+1），记录金额不符、事件/流水缺失、退款/费用账本/平台代收不一致。
 * 维护注意：
 *  1. differenceKey 稳定唯一，防止同一差异重复入队；差异不得自动平衡或直接改订单金额。
 *  2. status=DISPUTED 表示待人工处理；RETRY 仅表示已发起重试，不代表已修复；
 *     MANUAL 表示自动重试达到上限已转人工，之后只能人工关闭。
 *  3. businessDate 是 T+1 归属业务日（Asia/Shanghai），渠道侧以 trade_time 切日。
 *  4. diffCategory 是运营口径分类（LONG/SHORT/ONE_SIDED_LOCAL/...），仅作提示，不改变金额。
 *  5. lastRetryDate 保证同一业务日重复跑批不重复累加重试次数。
 *  6. 处理动作、处理人、时间与说明必须留痕；完整历史见 reconciliation_difference_audit。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private String diffCategory;
    private String bizType;
    private Long bizId;
    private String orderSn;
    private String channelTradeNo;
    private LocalDate businessDate;
    private BigDecimal expectedAmount;
    private BigDecimal actualAmount;
    private String status = "DISPUTED"; // DISPUTED/RETRY/MANUAL/RESOLVED/IGNORED
    private String detail;
    private Integer retryCount = 0;
    private LocalDate lastRetryDate;
    private Long handledBy;
    private LocalDateTime handledAt;
    private String handleNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
