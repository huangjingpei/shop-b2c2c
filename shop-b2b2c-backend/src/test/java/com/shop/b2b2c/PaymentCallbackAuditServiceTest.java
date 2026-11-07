/*
 * 文件：PaymentCallbackAuditServiceTest.java
 * 用途：F01 回调审计的隔离规则用例：指纹规范化、敏感摘要截断、字段归一与只插入不抛业务。
 * 维护注意：
 *  1. 全部 Mockito 隔离，不启动 Spring、不连数据库；真实插入与列宽由 Sprint1SecurityIntegrationTest 的 H2 用例覆盖。
 *  2. 指纹用例锁定“原始报文优先、表单排除 sign/sign_type 并按键升序”，改动必须同步网关关联口径。
 *  3. 不得为了通过测试放宽截断长度；审计列宽由 schema.sql 与迁移脚本共同约束。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.entity.PaymentCallbackAudit;
import com.shop.b2b2c.mapper.PaymentCallbackAuditMapper;
import com.shop.b2b2c.service.payment.PaymentCallbackAuditService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentCallbackAuditServiceTest {

    private final PaymentCallbackAuditMapper mapper = mock(PaymentCallbackAuditMapper.class);
    private final PaymentCallbackAuditService service = new PaymentCallbackAuditService(mapper);

    @Test
    void fingerprintIsStableSha256OfRawBody() {
        String first = PaymentCallbackAuditService.fingerprint("{\"id\":\"EVT-1\"}", null);
        String second = PaymentCallbackAuditService.fingerprint("{\"id\":\"EVT-1\"}", null);

        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertEquals(first, second);
        assertNotEquals(first, PaymentCallbackAuditService.fingerprint("{\"id\":\"EVT-2\"}", null));
    }

    @Test
    void fingerprintOfFormParametersIgnoresSignatureAndOrder() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("out_trade_no", "SN-1");
        first.put("total_amount", "10.00");
        first.put("sign", "signature-a");
        first.put("sign_type", "RSA2");

        Map<String, String> second = new TreeMap<>();
        second.put("total_amount", "10.00");
        second.put("out_trade_no", "SN-1");
        second.put("sign", "signature-b");
        second.put("sign_type", "RSA2");

        assertEquals(PaymentCallbackAuditService.fingerprint(null, first),
                PaymentCallbackAuditService.fingerprint(null, second),
                "签名每次不同但业务内容一致，指纹必须一致");
        assertNotEquals(
                PaymentCallbackAuditService.fingerprint(null, first),
                PaymentCallbackAuditService.fingerprint(null, Map.of("out_trade_no", "SN-1", "total_amount", "10.01")));
    }

    @Test
    void fingerprintPrefersRawBodyAndNeverReturnsNull() {
        assertEquals(PaymentCallbackAuditService.fingerprint("raw", Map.of("out_trade_no", "SN-1")),
                PaymentCallbackAuditService.fingerprint("raw", null));
        String empty = PaymentCallbackAuditService.fingerprint(null, null);
        assertEquals(64, empty.length());
        assertEquals(empty, PaymentCallbackAuditService.fingerprint(null, null));
    }

    @Test
    void recordNormalizesFieldsAndTruncatesReason() {
        PaymentCallbackAudit audit = new PaymentCallbackAudit();
        audit.setChannel(" wechat ");
        audit.setOrderSn("SN-1");
        audit.setPayTradeNo("TX-1");
        audit.setChannelMerchantId("MCH-1");
        audit.setOutcome(PaymentCallbackAuditService.OUTCOME_REJECTED);
        audit.setReason("line-1\r\nline-2 " + "x".repeat(300));
        audit.setPayloadSha256("a".repeat(64));
        audit.setDurationMs(-3);

        service.record(audit);

        ArgumentCaptor<PaymentCallbackAudit> captor = ArgumentCaptor.forClass(PaymentCallbackAudit.class);
        verify(mapper).insert(captor.capture());
        PaymentCallbackAudit saved = captor.getValue();
        assertEquals("WECHAT", saved.getChannel());
        assertEquals(PaymentCallbackAuditService.OUTCOME_REJECTED, saved.getOutcome());
        assertEquals(200, saved.getReason().length());
        assertFalse(saved.getReason().contains("\n"));
        assertFalse(saved.getReason().contains("\r"));
        assertEquals(0, saved.getDurationMs().intValue());
    }

    @Test
    void recordDefaultsMissingOutcomeAndFingerprint() {
        PaymentCallbackAudit audit = new PaymentCallbackAudit();
        audit.setReason("channel error");

        service.record(audit);

        ArgumentCaptor<PaymentCallbackAudit> captor = ArgumentCaptor.forClass(PaymentCallbackAudit.class);
        verify(mapper).insert(captor.capture());
        PaymentCallbackAudit saved = captor.getValue();
        assertEquals("UNKNOWN", saved.getChannel());
        assertEquals(PaymentCallbackAuditService.OUTCOME_ERROR, saved.getOutcome());
        assertEquals(64, saved.getPayloadSha256().length());
        assertEquals(PaymentCallbackAuditService.fingerprint(null, null), saved.getPayloadSha256());
    }

    @Test
    void recordIgnoresNullAuditWithoutInserting() {
        service.record(null);
        verifyNoInteractions(mapper);
    }
}
