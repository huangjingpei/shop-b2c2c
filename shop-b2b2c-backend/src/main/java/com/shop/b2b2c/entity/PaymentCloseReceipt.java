/*
 * 文件：PaymentCloseReceipt.java
 * 用途：F08 渠道关单回执实体：记录每次关单尝试（首次/重试）的渠道、次数、结果与原因。
 * 维护注意：
 *  1. 本表是重试与排障的依据，不是资金账本；SUCCESS 只表示渠道关单调用成功，不代表退款或订单状态变化。
 *  2. 一次关单调用插一行，只增不改；同一订单+渠道的 attempt_no 递增，不设唯一键（多实例并发允许重复号，以最新 attempted_at 为准）。
 *  3. reason 只写内部异常摘要，写入前单行化并截断，不得包含渠道凭据、回调正文或买家身份。
 *  4. 不设外键：订单在测试或归档场景可能不存在，回执仍需保留；重试只针对 FAILED，SKIPPED 表示渠道/账户尚不可关单，不自动重试。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_close_receipt")
public class PaymentCloseReceipt {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderSn;
    private Long merchantId;
    private String channel;
    /** 同一订单+渠道的关单尝试序号，从 1 开始递增。 */
    private Integer attemptNo;
    /** SUCCESS 渠道关单调用成功 / FAILED 调用失败可重试 / SKIPPED 未配置、未审核或适配器未启用。 */
    private String result;
    private String reason;
    private LocalDateTime attemptedAt;
    private LocalDateTime createdAt;
}
