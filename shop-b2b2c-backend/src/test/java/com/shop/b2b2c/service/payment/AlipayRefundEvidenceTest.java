/*
 * 文件：AlipayRefundEvidenceTest.java
 * 用途：验证支付宝 trade.query 响应在 TRADE_CLOSED 时能否依据退款金额字段判定为已退款（G34/T+1）。
 * 维护注意：只解析本地构造的报文，不发起网络请求、不连接真实支付宝；trade.query 是否稳定返回
 * refund_fee / refund_amount 需真实联调确认，本用例通过不代表线上字段一定存在。
 */
package com.shop.b2b2c.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlipayRefundEvidenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void refundFeeAboveZeroIsRefundEvidence() throws Exception {
        assertTrue(adapter().queryShowsRefund(objectMapper.readTree("{\"refund_fee\":\"0.01\"}")));
    }

    @Test
    void numericRefundAmountAboveZeroIsRefundEvidence() throws Exception {
        assertTrue(adapter().queryShowsRefund(objectMapper.readTree("{\"refund_amount\":5}")));
    }

    @Test
    void zeroRefundAmountIsNotRefundEvidence() throws Exception {
        assertFalse(adapter().queryShowsRefund(
                objectMapper.readTree("{\"refund_fee\":\"0.00\",\"refund_amount\":0}")));
    }

    @Test
    void missingRefundFieldsKeepClosedState() throws Exception {
        assertFalse(adapter().queryShowsRefund(objectMapper.readTree("{\"trade_status\":\"TRADE_CLOSED\"}")));
    }

    @Test
    void nonNumericRefundFieldIsNotRefundEvidence() throws Exception {
        assertFalse(adapter().queryShowsRefund(objectMapper.readTree("{\"refund_fee\":\"unknown\"}")));
    }

    @Test
    void nullNodeIsNotRefundEvidence() {
        assertFalse(adapter().queryShowsRefund(null));
    }

    private AlipayPaymentChannelAdapter adapter() {
        PaymentChannelProperties properties = new PaymentChannelProperties();
        properties.getAlipay().setEnabled(true);
        properties.getAlipay().setAppId("ali-test-app");
        return new AlipayPaymentChannelAdapter(properties, objectMapper);
    }
}
