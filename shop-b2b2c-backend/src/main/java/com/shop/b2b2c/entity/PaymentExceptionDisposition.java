/*
 * 文件：PaymentExceptionDisposition.java
 * 用途：F08 支付异常人工处置记录实体，保存超管对异常事件的核对结论与操作人快照。
 * 维护注意：
 *  1. 只增不改：Mapper 不提供 update/delete；event_id 唯一，同一事件只允许一条处置记录。
 *  2. 本表不是资金账本，不改写 payment_event.event_status/funds_received，也不改订单或退款账本；
 *     退款与补偿仍须走渠道或既有退款流程。
 *  3. note 只写人工核对依据与结论（2-200 字），不得包含渠道凭据、报文正文或买家身份。
 *  4. 不设外键：事件归档后处置记录仍需保留；order_sn 仅为定位快照。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_exception_disposition")
public class PaymentExceptionDisposition {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** payment_event.id；唯一，同一事件只允许一条处置记录。 */
    private Long eventId;
    /** 订单号快照，事件归档后仍可定位。 */
    private String orderSn;
    /** REFUND_CONFIRMED / DUPLICATE_RESOLVED / CHANNEL_VERIFIED / NO_ACTION_REQUIRED。 */
    private String action;
    /** 人工核对依据与结论，单行化后 2-200 字。 */
    private String note;
    /** 处置操作的平台账号 ID 快照。 */
    private Long operatorId;
    private LocalDateTime createdAt;
}
