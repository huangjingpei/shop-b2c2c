/*
 * 文件：PaymentExceptionDispositionView.java
 * 用途：F08 支付异常人工处置的响应投影，仅回传处置结论与操作人快照。
 * 维护注意：
 *  1. 只输出处置记录字段，不包含 payment_event 原始报文、付款人标识或渠道商户号。
 *  2. operatorId 为平台账号 ID 快照，用于审计展示；不得替换为用户名或明文个人信息。
 */
package com.shop.b2b2c.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PaymentExceptionDispositionView {
    private Long id;
    private Long eventId;
    private String orderSn;
    private String action;
    private String note;
    private Long operatorId;
    private LocalDateTime createdAt;
}
