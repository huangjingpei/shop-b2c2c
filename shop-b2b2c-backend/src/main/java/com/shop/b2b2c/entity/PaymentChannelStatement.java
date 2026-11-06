/*
 * 文件：PaymentChannelStatement.java
 * 用途：渠道对账流水模型（T35），保存人工导入的微信/支付宝流水，供订单、事件、账本与结算单比对。
 * 维护注意：
 *  1. 渠道流水只读比对，不直接修改订单/账本/结算单；差异写入 reconciliation_difference。
 *  2. (channel, channelTradeNo) 唯一，重复导入同一流水不重复入队。
 *  3. currency 与 payment_event.currency 比对；不一致只记 CURRENCY 差异，不做汇率折算。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_channel_statement")
public class PaymentChannelStatement {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String channel;
    private String channelTradeNo;
    private String outTradeNo;
    private String orderSn;
    private Long merchantId;
    private BigDecimal amount;
    private String currency;
    private String tradeStatus = "SUCCESS";
    private LocalDateTime tradeTime;
    private Long importedBy;
    private LocalDateTime createdAt;
}
