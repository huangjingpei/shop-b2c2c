/*
 * 文件：PaymentChannelSecurityTest.java
 * 用途：T32 真实支付渠道安全契约用例：微信/支付宝回调验签与解密、金额/商户拒绝、模拟渠道拒绝、
 *      归集模式已知值约束（M4-B 起 requireStageOne 删除，只认 requireKnown）、平台收银台骨架拒绝细化（含 M4-B 退款入口）。
 * 维护注意：
 *  1. 用例在进程内生成 RSA 密钥并自签回调报文，不连接微信/支付宝，不读取生产凭据，不写数据库。
 *  2. 只验证适配器与网关入口的安全边界；真实渠道联调仍需用户在具备商户号/证书的环境执行。
 *  3. 修改回调字段或签名算法时必须同步更新本用例，不得删除“篡改报文必须失败”的断言。
 *  4. 财务加固E：回调时间戳使用当前时间动态生成；超窗用例使用 now-10分钟，避免固定时间失效。
 *  5. F01：网关回调审计用例用 Mockito 捕获 PaymentCallbackAudit，只验证结果分类与指纹，不连接数据库；
 *     真实写入由 Sprint1SecurityIntegrationTest 的 H2 用例覆盖。
 */
package com.shop.b2b2c;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
import com.shop.b2b2c.dto.PaymentEventDTOs;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import com.shop.b2b2c.entity.PaymentCallbackAudit;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.mapper.MerchantPaymentAccountMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.service.PaymentEventService;
import com.shop.b2b2c.service.payment.AlipayPaymentChannelAdapter;
import com.shop.b2b2c.service.payment.MockPlatformCashierChannelAdapter;
import com.shop.b2b2c.service.payment.PaymentChannelProperties;
import com.shop.b2b2c.service.payment.PaymentChannelRegistry;
import com.shop.b2b2c.service.payment.PaymentCallbackAuditService;
import com.shop.b2b2c.service.payment.PaymentCloseReceiptService;
import com.shop.b2b2c.service.payment.PaymentGatewayService;
import com.shop.b2b2c.service.payment.PlatformCashierChannelRegistry;
import com.shop.b2b2c.service.payment.PlatformCashierProperties;
import com.shop.b2b2c.service.payment.WeChatPaymentChannelAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentChannelSecurityTest {

    private static final String API_V3_KEY = "1234567890abcdef1234567890abcdef"; // 32字节，仅测试
    private static KeyPair keyPair;
    private static String privatePem;
    private static String publicPem;

    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Sprint 57：网关构造器新增的平台收银台骨架依赖；默认 mock，平台代收用例单独换真实 registry。 */
    private final PlatformCashierChannelRegistry platformCashiers = mock(PlatformCashierChannelRegistry.class);

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        privatePem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        publicPem = pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
    }

    @Test
    void wechatCallbackVerifiesSignatureAndDecryptsResource() throws Exception {
        String plaintext = "{\"out_trade_no\":\"SN123\",\"transaction_id\":\"WXTX1\","
                + "\"trade_state\":\"SUCCESS\",\"mchid\":\"1900000109\","
                + "\"amount\":{\"total\":12345,\"currency\":\"CNY\"},\"payer\":{\"openid\":\"openid-1\"}}";
        String body = wechatBody(plaintext);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-1";
        String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = sign(message);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Wechatpay-Timestamp", timestamp);
        headers.put("Wechatpay-Nonce", nonce);
        headers.put("Wechatpay-Signature", signature);
        headers.put("Wechatpay-Serial", "serial-1");

        PaymentChannelDTOs.ChannelCallback callback =
                weChatAdapter().parseCallback(headers, body, null);

        assertEquals("WECHAT", callback.getChannel());
        assertEquals("SN123", callback.getOrderSn());
        assertEquals("WXTX1", callback.getPayTradeNo());
        assertEquals("1900000109", callback.getChannelMerchantId());
        assertEquals(new BigDecimal("123.45"), callback.getPayAmount());
        assertEquals("CNY", callback.getCurrency());
        assertEquals("openid-1", callback.getPayerId());
    }

    @Test
    void wechatCallbackRejectsTamperedBody() throws Exception {
        String plaintext = "{\"out_trade_no\":\"SN123\",\"transaction_id\":\"WXTX1\","
                + "\"trade_state\":\"SUCCESS\",\"mchid\":\"1900000109\","
                + "\"amount\":{\"total\":100,\"currency\":\"CNY\"},\"payer\":{\"openid\":\"openid-1\"}}";
        String body = wechatBody(plaintext);
        Map<String, String> headers = new LinkedHashMap<>();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        headers.put("Wechatpay-Timestamp", timestamp);
        headers.put("Wechatpay-Nonce", "nonce-1");
        headers.put("Wechatpay-Signature", sign(timestamp + "\nnonce-1\n" + body + "\n"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> weChatAdapter().parseCallback(headers, body + " ", null));
        assertTrue(ex.getMessage().contains("验签失败"));
    }

    @Test
    void wechatCallbackRejectsStaleTimestampAsReplay() throws Exception {
        String plaintext = "{\"out_trade_no\":\"SN123\",\"transaction_id\":\"WXTX1\","
                + "\"trade_state\":\"SUCCESS\",\"mchid\":\"1900000109\","
                + "\"amount\":{\"total\":100,\"currency\":\"CNY\"}}";
        String body = wechatBody(plaintext);
        String staleTimestamp = String.valueOf(Instant.now().getEpochSecond() - 600);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Wechatpay-Timestamp", staleTimestamp);
        headers.put("Wechatpay-Nonce", "nonce-1");
        headers.put("Wechatpay-Signature", sign(staleTimestamp + "\nnonce-1\n" + body + "\n"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> weChatAdapter().parseCallback(headers, body, null));
        assertEquals(401, ex.getCode());
        assertTrue(ex.getMessage().contains("时间戳超出允许窗口"));
    }

    @Test
    void alipayCallbackRejectsStaleNotifyTimeAsReplay() {
        Map<String, String> params = alipayParams(new BigDecimal("88.50"), "TRADE_SUCCESS");
        params.put("notify_time", notifyTime(-600));
        params.put("sign", sign(signContent(params)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> alipayAdapter().parseCallback(null, null, params));
        assertEquals(401, ex.getCode());
        assertTrue(ex.getMessage().contains("时间超出允许窗口"));
    }

    @Test
    void alipayCallbackVerifiesSignatureAndAmount() {
        Map<String, String> params = alipayParams(new BigDecimal("88.50"), "TRADE_SUCCESS");
        String sign = sign(signContent(params));
        params.put("sign", sign);

        PaymentChannelDTOs.ChannelCallback callback =
                alipayAdapter().parseCallback(null, null, params);

        assertEquals("ALIPAY", callback.getChannel());
        assertEquals("SN-ALI-1", callback.getOrderSn());
        assertEquals("ALI-TX-1", callback.getPayTradeNo());
        assertEquals("2088000000000001", callback.getChannelMerchantId());
        assertEquals(new BigDecimal("88.50"), callback.getPayAmount());
        assertEquals("buyer-1", callback.getPayerId());
    }

    @Test
    void alipayCallbackRejectsTamperedAmount() {
        Map<String, String> params = alipayParams(new BigDecimal("88.50"), "TRADE_SUCCESS");
        params.put("sign", sign(signContent(params)));
        params.put("total_amount", "0.01");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> alipayAdapter().parseCallback(null, null, params));
        assertTrue(ex.getMessage().contains("验签失败"));
    }

    @Test
    void registryRejectsMockAndUnknownChannel() {
        PaymentChannelRegistry registry = new PaymentChannelRegistry(
                List.of(weChatAdapter(), alipayAdapter()));
        assertThrows(BusinessException.class, () -> registry.require("MOCK"));
        assertThrows(BusinessException.class, () -> registry.require("STRIPE"));
        assertNotNull(registry.require("wechat"));
    }

    @Test
    void collectionModeKnownCheckAcceptsBothModesAndRejectsUnknown() {
        // M4-B：requireStageOne 已删除，模式合法性只认 requireKnown；路由由支付/退款入口按值分流。
        assertEquals(CollectionMode.MERCHANT_DIRECT,
                CollectionMode.requireKnown(CollectionMode.MERCHANT_DIRECT));
        assertEquals(CollectionMode.PLATFORM_COLLECT,
                CollectionMode.requireKnown("platform_collect"));
        assertThrows(BusinessException.class, () -> CollectionMode.requireKnown("C"));
        assertThrows(BusinessException.class, () -> PayChannel.normalize("C"));
        assertThrows(BusinessException.class, () -> PayChannel.normalize("MOCK"));
    }

    @Test
    void gatewayRejectsCallbackFromDifferentChannelMerchant() {
        TradeOrder order = unpaidOrder();
        MerchantPaymentAccount account = passedAccount("WECHAT", "MCH-1");
        TradeOrderMapper orders = mock(TradeOrderMapper.class);
        MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
        com.shop.b2b2c.service.payment.PaymentChannelAdapter adapter =
                mock(com.shop.b2b2c.service.payment.PaymentChannelAdapter.class);
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        PaymentEventService events = mock(PaymentEventService.class);
        PaymentCallbackAuditService audits = mock(PaymentCallbackAuditService.class);

        when(orders.findByOrderSn("SN-GW")).thenReturn(Optional.of(order));
        when(accounts.findByMerchantIdAndChannel(1L, "WECHAT")).thenReturn(Optional.of(account));
        when(registry.require("WECHAT")).thenReturn(adapter);
        when(adapter.parseCallback(any(), any(), any())).thenReturn(
                PaymentChannelDTOs.ChannelCallback.builder()
                        .channel("WECHAT").orderSn("SN-GW").payTradeNo("TX-GW")
                        .channelMerchantId("OTHER-MCH").payAmount(new BigDecimal("10.00"))
                        .currency("CNY").build());

        PaymentGatewayService gateway = new PaymentGatewayService(orders, accounts, registry, events, audits,
                mock(PaymentCloseReceiptService.class), platformCashiers);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> gateway.handleCallback("WECHAT", Map.of(), "{}", null));
        assertTrue(ex.getMessage().contains("不匹配"));
        verify(events, never()).processPaymentEvent(any());
    }

    @Test
    void gatewayRejectsPlatformCollectOrderBeforeCreatingPayment() {
        TradeOrder order = unpaidOrder();
        order.setCollectionMode(CollectionMode.PLATFORM_COLLECT);
        TradeOrderMapper orders = mock(TradeOrderMapper.class);
        MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        PaymentEventService events = mock(PaymentEventService.class);
        PaymentCallbackAuditService audits = mock(PaymentCallbackAuditService.class);
        when(orders.findByOrderSn("SN-GW")).thenReturn(Optional.of(order));
        // Sprint 57：真实 registry + 空凭据配置 → 细化原因“平台收银台未配置”。
        PlatformCashierChannelRegistry cashierRegistry = new PlatformCashierChannelRegistry(
                List.of(new MockPlatformCashierChannelAdapter()), new PlatformCashierProperties());

        PaymentGatewayService gateway = new PaymentGatewayService(orders, accounts, registry, events, audits,
                mock(PaymentCloseReceiptService.class), cashierRegistry);
        PaymentChannelDTOs.CreatePaymentRequest request = new PaymentChannelDTOs.CreatePaymentRequest(
                "SN-GW", "PEER-TOKEN", "WECHAT");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> gateway.createPayment(request, "127.0.0.1"));
        assertTrue(ex.getMessage().contains("平台代收") || ex.getMessage().contains("平台收银台"));
        assertTrue(ex.getMessage().contains("未配置"));
        // 商家直收渠道适配器不得被触碰。
        verify(registry, never()).require(any());
    }

    @Test
    void platformCashierRegistryDistinguishesNotConfiguredAndNotIntegrated() {
        PlatformCashierProperties properties = new PlatformCashierProperties();
        PlatformCashierChannelRegistry registry = new PlatformCashierChannelRegistry(
                List.of(new MockPlatformCashierChannelAdapter()), properties);

        // 凭据缺失 → “未配置”
        BusinessException unconfigured = assertThrows(BusinessException.class, () -> registry.require("WECHAT"));
        assertEquals(409, unconfigured.getCode());
        assertTrue(unconfigured.getMessage().contains("平台收银台未配置"));
        // M4-B：退款入口同一检查链，文案细化到退款语义
        BusinessException refundUnconfigured = assertThrows(BusinessException.class,
                () -> registry.requireForRefund("WECHAT"));
        assertEquals(409, refundUnconfigured.getCode());
        assertTrue(refundUnconfigured.getMessage().contains("平台收银台未配置"));
        assertTrue(refundUnconfigured.getMessage().contains("退款"));

        // 凭据配齐但开关未开 → “已配置但未完成通道联调”
        properties.setSpMchid("SP-1");
        properties.setSerialNo("SER-1");
        properties.setApiV3Key("APIV3");
        properties.setPrivateKey("PRIVATE");
        properties.setPlatformPublicKey("PUBLIC");
        BusinessException notIntegrated = assertThrows(BusinessException.class, () -> registry.require("WECHAT"));
        assertEquals(409, notIntegrated.getCode());
        assertTrue(notIntegrated.getMessage().contains("已配置但未完成通道联调"));
        BusinessException refundNotIntegrated = assertThrows(BusinessException.class,
                () -> registry.requireForRefund("WECHAT"));
        assertEquals(409, refundNotIntegrated.getCode());
        assertTrue(refundNotIntegrated.getMessage().contains("已配置但未完成通道联调"));
        assertTrue(refundNotIntegrated.getMessage().contains("退款"));

        // 未接入渠道
        BusinessException unsupported = assertThrows(BusinessException.class, () -> registry.require("ALIPAY"));
        assertTrue(unsupported.getMessage().contains("未接入渠道"));
        assertThrows(BusinessException.class, () -> registry.requireForRefund("ALIPAY"));

        // 占位适配器即使被直接调用也必须拒绝，不得伪造成功。
        MockPlatformCashierChannelAdapter adapter = new MockPlatformCashierChannelAdapter();
        assertFalse(adapter.enabled());
        assertThrows(BusinessException.class,
                () -> adapter.createPayment(PaymentChannelDTOs.PaymentCreateCommand.builder().build()));
        // M4-B：退款/退款查单/退分/退分查单同为 501 占位拒绝
        assertThrows(BusinessException.class,
                () -> adapter.refundPayment(PaymentChannelDTOs.RefundCommand.builder().build()));
        assertThrows(BusinessException.class,
                () -> adapter.queryRefund(PaymentChannelDTOs.RefundCommand.builder().build()));
        assertThrows(BusinessException.class,
                () -> adapter.returnSplit(PaymentChannelDTOs.SplitReturnCommand.builder().build()));
        assertThrows(BusinessException.class,
                () -> adapter.querySplitReturn(PaymentChannelDTOs.SplitReturnCommand.builder().build()));
    }

    @Test
    void gatewayRecordsAcceptedCallbackAuditWithFingerprint() {
        TradeOrder order = unpaidOrder();
        MerchantPaymentAccount account = passedAccount("WECHAT", "MCH-1");
        TradeOrderMapper orders = mock(TradeOrderMapper.class);
        MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
        com.shop.b2b2c.service.payment.PaymentChannelAdapter adapter =
                mock(com.shop.b2b2c.service.payment.PaymentChannelAdapter.class);
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        PaymentEventService events = mock(PaymentEventService.class);
        PaymentCallbackAuditService audits = mock(PaymentCallbackAuditService.class);
        PaymentEventDTOs.PaymentProcessingResult processed = PaymentEventDTOs.PaymentProcessingResult.builder()
                .channel("WECHAT").payTradeNo("TX-GW").orderSn("SN-GW")
                .success(true).eventStatus("SUCCESS").orderStatus("COMPLETED").build();

        when(orders.findByOrderSn("SN-GW")).thenReturn(Optional.of(order));
        when(accounts.findByMerchantIdAndChannel(1L, "WECHAT")).thenReturn(Optional.of(account));
        when(registry.require("WECHAT")).thenReturn(adapter);
        when(adapter.parseCallback(any(), any(), any())).thenReturn(
                PaymentChannelDTOs.ChannelCallback.builder()
                        .channel("WECHAT").orderSn("SN-GW").payTradeNo("TX-GW")
                        .channelMerchantId("MCH-1").payAmount(new BigDecimal("10.00"))
                        .currency("CNY").payerId("openid-1").build());
        when(events.processPaymentEvent(any())).thenReturn(processed);

        PaymentGatewayService gateway = new PaymentGatewayService(orders, accounts, registry, events, audits,
                mock(PaymentCloseReceiptService.class), platformCashiers);
        PaymentEventDTOs.PaymentProcessingResult returned =
                gateway.handleCallback("WECHAT", Map.of(), "raw-body-1", null);

        assertSame(processed, returned);
        ArgumentCaptor<PaymentCallbackAudit> captor = ArgumentCaptor.forClass(PaymentCallbackAudit.class);
        verify(audits).record(captor.capture());
        PaymentCallbackAudit audit = captor.getValue();
        assertEquals(PaymentCallbackAuditService.OUTCOME_ACCEPTED, audit.getOutcome());
        assertEquals("SUCCESS", audit.getProcessingResult());
        assertEquals("SN-GW", audit.getOrderSn());
        assertEquals("TX-GW", audit.getPayTradeNo());
        assertEquals("MCH-1", audit.getChannelMerchantId());
        assertEquals(64, audit.getPayloadSha256().length());
        assertEquals(PaymentCallbackAuditService.fingerprint("raw-body-1", null), audit.getPayloadSha256());
        assertTrue(audit.getDurationMs() >= 0);
    }

    @Test
    void gatewayRecordsRejectedAuditWhenSignatureFails() {
        TradeOrderMapper orders = mock(TradeOrderMapper.class);
        MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
        com.shop.b2b2c.service.payment.PaymentChannelAdapter adapter =
                mock(com.shop.b2b2c.service.payment.PaymentChannelAdapter.class);
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        PaymentEventService events = mock(PaymentEventService.class);
        PaymentCallbackAuditService audits = mock(PaymentCallbackAuditService.class);

        when(registry.require("WECHAT")).thenReturn(adapter);
        when(adapter.parseCallback(any(), any(), any()))
                .thenThrow(new BusinessException(401, "微信支付回调验签失败"));

        PaymentGatewayService gateway = new PaymentGatewayService(orders, accounts, registry, events, audits,
                mock(PaymentCloseReceiptService.class), platformCashiers);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> gateway.handleCallback("WECHAT", Map.of(), "tampered", null));

        assertEquals(401, ex.getCode());
        verify(events, never()).processPaymentEvent(any());
        ArgumentCaptor<PaymentCallbackAudit> captor = ArgumentCaptor.forClass(PaymentCallbackAudit.class);
        verify(audits).record(captor.capture());
        PaymentCallbackAudit audit = captor.getValue();
        assertEquals(PaymentCallbackAuditService.OUTCOME_REJECTED, audit.getOutcome());
        assertTrue(audit.getReason().contains("验签失败"));
        assertNull(audit.getOrderSn());
        assertNull(audit.getPayTradeNo());
        assertEquals(PaymentCallbackAuditService.fingerprint("tampered", null), audit.getPayloadSha256());
    }

    @Test
    void gatewayAuditFailureDoesNotChangeCallbackResult() {
        TradeOrder order = unpaidOrder();
        MerchantPaymentAccount account = passedAccount("WECHAT", "MCH-1");
        TradeOrderMapper orders = mock(TradeOrderMapper.class);
        MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
        com.shop.b2b2c.service.payment.PaymentChannelAdapter adapter =
                mock(com.shop.b2b2c.service.payment.PaymentChannelAdapter.class);
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        PaymentEventService events = mock(PaymentEventService.class);
        PaymentCallbackAuditService audits = mock(PaymentCallbackAuditService.class);
        PaymentEventDTOs.PaymentProcessingResult processed = PaymentEventDTOs.PaymentProcessingResult.builder()
                .channel("WECHAT").payTradeNo("TX-GW").orderSn("SN-GW")
                .success(true).eventStatus("SUCCESS").build();

        when(orders.findByOrderSn("SN-GW")).thenReturn(Optional.of(order));
        when(accounts.findByMerchantIdAndChannel(1L, "WECHAT")).thenReturn(Optional.of(account));
        when(registry.require("WECHAT")).thenReturn(adapter);
        when(adapter.parseCallback(any(), any(), any())).thenReturn(
                PaymentChannelDTOs.ChannelCallback.builder()
                        .channel("WECHAT").orderSn("SN-GW").payTradeNo("TX-GW")
                        .channelMerchantId("MCH-1").payAmount(new BigDecimal("10.00"))
                        .currency("CNY").build());
        when(events.processPaymentEvent(any())).thenReturn(processed);
        doThrow(new RuntimeException("audit db down")).when(audits).record(any());

        PaymentGatewayService gateway = new PaymentGatewayService(orders, accounts, registry, events, audits,
                mock(PaymentCloseReceiptService.class), platformCashiers);
        PaymentEventDTOs.PaymentProcessingResult returned =
                gateway.handleCallback("WECHAT", Map.of(), "raw-body-1", null);

        assertSame(processed, returned);
        verify(events).processPaymentEvent(any());
    }

    @Test
    void closeBestEffortRecordsSkippedWhenAccountMissing() {
        TradeOrder order = unpaidOrder();
        TradeOrderMapper orders = mock(TradeOrderMapper.class);
        MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        PaymentEventService events = mock(PaymentEventService.class);
        PaymentCallbackAuditService audits = mock(PaymentCallbackAuditService.class);
        PaymentCloseReceiptService receipts = mock(PaymentCloseReceiptService.class);
        when(accounts.findByMerchantIdAndChannel(1L, "WECHAT")).thenReturn(Optional.empty());

        PaymentGatewayService gateway = new PaymentGatewayService(orders, accounts, registry, events, audits, receipts, platformCashiers);
        assertFalse(gateway.closePaymentBestEffort(order));
        verify(receipts).record(order, "WECHAT", PaymentCloseReceiptService.RESULT_SKIPPED,
                "渠道账户未登记或未审核通过");
        verifyNoInteractions(registry);
    }

    @Test
    void closeBestEffortRecordsSuccessAndCallsAdapter() {
        TradeOrder order = unpaidOrder();
        TradeOrderMapper orders = mock(TradeOrderMapper.class);
        MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
        com.shop.b2b2c.service.payment.PaymentChannelAdapter adapter =
                mock(com.shop.b2b2c.service.payment.PaymentChannelAdapter.class);
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        PaymentEventService events = mock(PaymentEventService.class);
        PaymentCallbackAuditService audits = mock(PaymentCallbackAuditService.class);
        PaymentCloseReceiptService receipts = mock(PaymentCloseReceiptService.class);
        when(accounts.findByMerchantIdAndChannel(1L, "WECHAT"))
                .thenReturn(Optional.of(passedAccount("WECHAT", "MCH-1")));
        when(registry.require("WECHAT")).thenReturn(adapter);
        when(adapter.enabled()).thenReturn(true);

        PaymentGatewayService gateway = new PaymentGatewayService(orders, accounts, registry, events, audits, receipts, platformCashiers);
        assertTrue(gateway.closePaymentBestEffort(order));
        verify(adapter).closePayment(any());
        verify(receipts).record(order, "WECHAT", PaymentCloseReceiptService.RESULT_SUCCESS, null);
    }

    @Test
    void closeBestEffortRecordsFailedWhenChannelThrows() {
        TradeOrder order = unpaidOrder();
        TradeOrderMapper orders = mock(TradeOrderMapper.class);
        MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
        com.shop.b2b2c.service.payment.PaymentChannelAdapter adapter =
                mock(com.shop.b2b2c.service.payment.PaymentChannelAdapter.class);
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        PaymentEventService events = mock(PaymentEventService.class);
        PaymentCallbackAuditService audits = mock(PaymentCallbackAuditService.class);
        PaymentCloseReceiptService receipts = mock(PaymentCloseReceiptService.class);
        when(accounts.findByMerchantIdAndChannel(1L, "WECHAT"))
                .thenReturn(Optional.of(passedAccount("WECHAT", "MCH-1")));
        when(registry.require("WECHAT")).thenReturn(adapter);
        when(adapter.enabled()).thenReturn(true);
        doThrow(new RuntimeException("channel timeout")).when(adapter).closePayment(any());

        PaymentGatewayService gateway = new PaymentGatewayService(orders, accounts, registry, events, audits, receipts, platformCashiers);
        assertFalse(gateway.closePaymentBestEffort(order));
        verify(receipts).record(eq(order), eq("WECHAT"), eq(PaymentCloseReceiptService.RESULT_FAILED),
                contains("channel timeout"));
    }

    @Test
    void closeBestEffortDoesNotFailWhenReceiptWriteFails() {
        TradeOrder order = unpaidOrder();
        TradeOrderMapper orders = mock(TradeOrderMapper.class);
        MerchantPaymentAccountMapper accounts = mock(MerchantPaymentAccountMapper.class);
        com.shop.b2b2c.service.payment.PaymentChannelAdapter adapter =
                mock(com.shop.b2b2c.service.payment.PaymentChannelAdapter.class);
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        PaymentEventService events = mock(PaymentEventService.class);
        PaymentCallbackAuditService audits = mock(PaymentCallbackAuditService.class);
        PaymentCloseReceiptService receipts = mock(PaymentCloseReceiptService.class);
        when(accounts.findByMerchantIdAndChannel(1L, "WECHAT"))
                .thenReturn(Optional.of(passedAccount("WECHAT", "MCH-1")));
        when(registry.require("WECHAT")).thenReturn(adapter);
        when(adapter.enabled()).thenReturn(true);
        doThrow(new RuntimeException("receipt db down"))
                .when(receipts).record(any(), any(), any(), any());

        PaymentGatewayService gateway = new PaymentGatewayService(orders, accounts, registry, events, audits, receipts, platformCashiers);
        assertTrue(gateway.closePaymentBestEffort(order));
    }

    private WeChatPaymentChannelAdapter weChatAdapter() {
        PaymentChannelProperties properties = new PaymentChannelProperties();
        properties.getWechat().setEnabled(true);
        properties.getWechat().setAppId("wx-test-app");
        properties.getWechat().setApiV3Key(API_V3_KEY);
        properties.getWechat().setPlatformPublicKey(publicPem);
        return new WeChatPaymentChannelAdapter(properties, objectMapper);
    }

    private AlipayPaymentChannelAdapter alipayAdapter() {
        PaymentChannelProperties properties = new PaymentChannelProperties();
        properties.getAlipay().setEnabled(true);
        properties.getAlipay().setAppId("ali-app-1");
        properties.getAlipay().setAlipayPublicKey(publicPem);
        return new AlipayPaymentChannelAdapter(properties, objectMapper);
    }

    private String wechatBody(String plaintext) throws Exception {
        byte[] nonceBytes = "nonce-123456".getBytes(StandardCharsets.UTF_8); // 12字节可打印随机串
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonceBytes));
        cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(
                cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        String nonce = "nonce-123456";
        return "{\"id\":\"EVT-1\",\"event_type\":\"TRANSACTION.SUCCESS\","
                + "\"resource\":{\"algorithm\":\"AEAD_AES_256_GCM\",\"ciphertext\":\"" + ciphertext
                + "\",\"nonce\":\"" + nonce + "\",\"associated_data\":\"transaction\"}}";
    }

    private Map<String, String> alipayParams(BigDecimal amount, String tradeStatus) {
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", "ali-app-1");
        params.put("seller_id", "2088000000000001");
        params.put("out_trade_no", "SN-ALI-1");
        params.put("trade_no", "ALI-TX-1");
        params.put("total_amount", amount.setScale(2).toPlainString());
        params.put("trade_status", tradeStatus);
        params.put("buyer_id", "buyer-1");
        params.put("sign_type", "RSA2");
        params.put("charset", "utf-8");
        params.put("timestamp", "2026-11-06 12:00:00");
        params.put("notify_time", notifyTime(0));
        params.put("version", "1.0");
        return params;
    }

    private String notifyTime(long offsetSeconds) {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(offsetSeconds)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String signContent(Map<String, String> params) {
        Map<String, String> sorted = new TreeMap<>();
        params.forEach((key, value) -> {
            if (!"sign".equals(key) && !"sign_type".equals(key) && value != null && !value.isEmpty()) {
                sorted.put(key, value);
            }
        });
        StringBuilder builder = new StringBuilder();
        sorted.forEach((key, value) -> {
            if (builder.length() > 0) builder.append('&');
            builder.append(key).append('=').append(value);
        });
        return builder.toString();
    }

    private String sign(String content) {
        try {
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getEncoder().encodeToString(encoded)
                + "\n-----END " + label + "-----";
    }

    private static TradeOrder unpaidOrder() {
        TradeOrder order = new TradeOrder();
        order.setId(1L);
        order.setOrderSn("SN-GW");
        order.setMerchantId(1L);
        order.setPayAmount(new BigDecimal("10.00"));
        order.setOrderStatus("UNPAID");
        order.setCollectionMode(CollectionMode.MERCHANT_DIRECT);
        order.setPayChannel("WECHAT");
        order.setPeerPayToken("PEER-TOKEN");
        order.setLockExpireTime(LocalDateTime.now().plusMinutes(10));
        return order;
    }

    private static MerchantPaymentAccount passedAccount(String channel, String subMchid) {
        MerchantPaymentAccount account = new MerchantPaymentAccount();
        account.setMerchantId(1L);
        account.setChannel(channel);
        account.setSubMchid(subMchid);
        account.setApplyStatus("PASSED");
        return account;
    }
}
