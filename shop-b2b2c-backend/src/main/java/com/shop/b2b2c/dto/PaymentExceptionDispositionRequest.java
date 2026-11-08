/*
 * 文件：PaymentExceptionDispositionRequest.java
 * 用途：F08 支付异常人工处置入参，承载处置结论与核对说明。
 * 维护注意：
 *  1. action 必须为白名单值，note 为必填 2-200 字；后端在 PaymentExceptionDispositionService 统一校验。
 *  2. 本请求只记录人工结论，不包含退款金额、渠道流水等可执行资金动作的字段，避免被误用为退款指令。
 */
package com.shop.b2b2c.dto;

import lombok.Data;

@Data
public class PaymentExceptionDispositionRequest {
    private String action;
    private String note;
}
