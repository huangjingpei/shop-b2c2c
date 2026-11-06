/*
 * 文件：SplitReverse.java
 * 用途：T40 阶段二退分账本模型（骨架），记录退款前对已分账金额的退分动作。
 * 维护注意：
 * 1. 同一 split_detail_id + refund_id 唯一，重试复用原记录，不得重复退分。
 * 2. reverseAmount 不得超过该分账明细未退分余额；渠道退分单号未联调前必须为空。
 * 3. 本表只记本地账本，reverse_status 只能由渠道真实结果推进，平台不得自行置 FINISHED。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("split_reverse")
public class SplitReverse {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long splitDetailId;
    private Long refundId;
    private String refundNo;
    private String orderSn;
    private Long merchantId;
    private BigDecimal reverseAmount;
    private String reverseStatus = "PENDING"; // PENDING/PROCESSING/FINISHED/FAILED
    private String channelReverseNo;
    private String failureReason;
    private Long operatorId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
