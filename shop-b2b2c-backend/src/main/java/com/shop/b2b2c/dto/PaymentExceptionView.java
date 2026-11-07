/*
 * 文件：PaymentExceptionView.java
 * 用途：F08 平台支付异常队列的只读投影，供超管核对迟到支付/重复付款/金额与商户不符等需人工补偿的事件。
 * 维护注意：
 *  1. 只输出定位与金额要素，不包含 raw_payload、payer_id、渠道商户号等敏感或超长字段；不得替换为 PaymentEvent 实体序列化。
 *  2. event_status 为异常态（LATE_PAYMENT/DUPLICATE_PAYMENT/金额/币种/商户不符/人工复核/履约失败），不代表已退款或已补偿。
 *  3. funds_received 语义见 PaymentEvent：1=渠道存在资金往来、0=明确无、NULL=未判定；NULL 必须人工核对，不得当 0。
 */
package com.shop.b2b2c.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PaymentExceptionView {
    private Long id;
    private Long merchantId;
    private String channel;
    private String orderSn;
    private BigDecimal payAmount;
    private String currency;
    private String eventStatus;
    private String errorMessage;
    private Integer fundsReceived;
    private LocalDateTime createdAt;
}
