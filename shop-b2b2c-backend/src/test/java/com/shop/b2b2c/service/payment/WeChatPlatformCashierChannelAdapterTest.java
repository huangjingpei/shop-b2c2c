/*
 * 文件：WeChatPlatformCashierChannelAdapterTest.java
 * 用途：Sprint 59 微信电商收付通平台收银台真实适配器单测：
 *      回调验签/解密/sp_mchid 匹配、下单/退款/退分报文拼装（MockRestServiceServer 拦截 HTTP）、
 *      错误翻译（404=查无此单、网络错误=502）、未启用/缺凭据/缺 sub_mchid 的明确拒绝。
 * 维护注意：
 *  1. 使用测试期临时生成的 RSA 密钥对与固定 32 字节 APIv3 密钥，不包含任何真实渠道凭据；
 *     通过 MockRestServiceServer 拦截，不发起真实网络请求。
 *  2. 与商家直收 WeChatPaymentChannelAdapter 的验签/解密口径保持一致（RSA-SHA256、平台公钥、
 *     AES-256-GCM、±300 秒防重放）；渠道报文字段以微信电商收付通文档为准，字段变化须同步适配器。
 *  3. 报文断言基于 ObjectMapper 紧凑 JSON（LinkedHashMap 字段序稳定）；若序列化策略调整需同步断言。
 */
package com.shop.b2b2c.service.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeChatPlatformCashierChannelAdapterTest {

    private static final String APIV3 = "0123456789abcdef0123456789abcdef";
    private static KeyPair keyPair;
    private static String privatePem;
    private static String publicPem;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        privatePem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    // ---------- 回调验签/解密（真实 RSA + AES-GCM 链路） ----------

    @Test
    void parseCallbackVerifiesSignatureDecryptsAndMatchesSpMchid() throws Exception {
        WeChatPlatformCashierChannelAdapter adapter = adapter(new RestTemplate());
        String transaction = "{\"sp_mchid\":\"SP-MCH-1\",\"sub_mchid\":\"SUB-MCH-1\","
                + "\"out_trade_no\":\"SN-9\",\"transaction_id\":\"TX-9\",\"trade_state\":\"SUCCESS\","
                + "\"trade_type\":\"NATIVE\",\"amount\":{\"total\":8800,\"payer_total\":8800,\"currency\":\"CNY\"},"
                + "\"payer\":{\"sp_openid\":\"SP-OPEN-1\"}}";
        String body = callbackBody(transaction);
        Map<String, String> headers = signedHeaders(body);

        PaymentChannelDTOs.ChannelCallback callback = adapter.parseCallback(headers, body, null);

        assertEquals(PayChannel.WECHAT, callback.getChannel());
        assertEquals("TX-9", callback.getPayTradeNo());
        assertEquals("SN-9", callback.getOrderSn());
        assertEquals("SUB-MCH-1", callback.getChannelMerchantId());
        assertEquals(new BigDecimal("88.00"), callback.getPayAmount());
        assertEquals("CNY", callback.getCurrency());
        assertEquals("SP-OPEN-1", callback.getPayerId());
    }

    @Test
    void parseCallbackRejectsTamperedBody() throws Exception {
        WeChatPlatformCashierChannelAdapter adapter = adapter(new RestTemplate());
        String body = callbackBody("{\"sp_mchid\":\"SP-MCH-1\"}");
        Map<String, String> headers = signedHeaders(body);
        String tampered = body.replace("TRANSACTION.SUCCESS", "TRANSACTION.SUCCESSX");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.parseCallback(headers, tampered, null));
        assertEquals(401, ex.getCode());
        assertTrue(ex.getMessage().contains("验签失败"));
    }

    @Test
    void parseCallbackRejectsStaleTimestamp() throws Exception {
        WeChatPlatformCashierChannelAdapter adapter = adapter(new RestTemplate());
        String body = callbackBody("{\"sp_mchid\":\"SP-MCH-1\"}");
        String timestamp = String.valueOf(Instant.now().getEpochSecond() - 400);
        Map<String, String> headers = Map.of(
                "Wechatpay-Timestamp", timestamp,
                "Wechatpay-Nonce", "nonce-1",
                "Wechatpay-Signature", sign(timestamp + "\nnonce-1\n" + body + "\n"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.parseCallback(headers, body, null));
        assertEquals(401, ex.getCode());
        assertTrue(ex.getMessage().contains("超出允许窗口"));
    }

    @Test
    void parseCallbackRejectsForeignSpMchid() throws Exception {
        WeChatPlatformCashierChannelAdapter adapter = adapter(new RestTemplate());
        String transaction = "{\"sp_mchid\":\"SP-OTHER\",\"sub_mchid\":\"SUB-MCH-1\","
                + "\"out_trade_no\":\"SN-9\",\"transaction_id\":\"TX-9\",\"trade_state\":\"SUCCESS\","
                + "\"amount\":{\"total\":8800,\"currency\":\"CNY\"}}";
        String body = callbackBody(transaction);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.parseCallback(signedHeaders(body), body, null));
        assertEquals(401, ex.getCode());
        assertTrue(ex.getMessage().contains("服务商商户号与配置不匹配"));
    }

    @Test
    void parseCallbackRejectsNonSuccessEvent() throws Exception {
        WeChatPlatformCashierChannelAdapter adapter = adapter(new RestTemplate());
        String body = "{\"id\":\"EVT-1\",\"event_type\":\"TRANSACTION.CLOSED\",\"resource\":{}}";
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.parseCallback(signedHeaders(body), body, null));
        assertEquals(409, ex.getCode());
    }

    // ---------- 下单/查单/关单报文（MockRestServiceServer，不发起真实请求） ----------

    @Test
    void createPaymentAssemblesEcommerceNativeOrder() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        WeChatPlatformCashierChannelAdapter adapter = adapter(restTemplate);
        server.expect(requestTo("https://api.mch.weixin.qq.com/v3/pay/transactions/native"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", startsWith("WECHATPAY2-SHA256-RSA2048 mchid=\"SP-MCH-1\"")))
                .andExpect(content().string(containsString("\"sp_appid\":\"SP-APP-1\"")))
                .andExpect(content().string(containsString("\"sp_mchid\":\"SP-MCH-1\"")))
                .andExpect(content().string(containsString("\"sub_mchid\":\"SUB-MCH-1\"")))
                .andExpect(content().string(containsString("\"out_trade_no\":\"SN-9\"")))
                .andExpect(content().string(containsString("\"total\":12345")))
                .andExpect(content().string(containsString(
                        "\"notify_url\":\"https://pay.example.com/api/v1/payment/callback/platform\"")))
                .andRespond(withSuccess("{\"code_url\":\"weixin://wxpay/bizpayurl?pr=abc\"}",
                        MediaType.APPLICATION_JSON));

        PaymentChannelDTOs.PaymentAttemptView view = adapter.createPayment(
                PaymentChannelDTOs.PaymentCreateCommand.builder()
                        .orderSn("SN-9").amount(new BigDecimal("123.45")).subject("订单 SN-9")
                        .merchantChannelId("SUB-MCH-1").build());

        assertEquals(PayChannel.WECHAT, view.getPayChannel());
        assertEquals(CollectionMode.PLATFORM_COLLECT, view.getCollectionMode());
        assertEquals("NATIVE", view.getTradeType());
        assertEquals("weixin://wxpay/bizpayurl?pr=abc", view.getCodeUrl());
        server.verify();
    }

    @Test
    void createPaymentRejectsWhenSubMchidMissing() {
        WeChatPlatformCashierChannelAdapter adapter = adapter(new RestTemplate());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.createPayment(PaymentChannelDTOs.PaymentCreateCommand.builder()
                        .orderSn("SN-9").amount(new BigDecimal("1.00")).subject("s").build()));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("sub_mchid"));
    }

    @Test
    void createPaymentRejectsWhenNotifyBaseMissing() {
        PlatformCashierProperties properties = properties();
        properties.setNotifyBaseUrl("");
        WeChatPlatformCashierChannelAdapter adapter =
                new WeChatPlatformCashierChannelAdapter(properties, objectMapper, new RestTemplate());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.createPayment(PaymentChannelDTOs.PaymentCreateCommand.builder()
                        .orderSn("SN-9").amount(new BigDecimal("1.00")).subject("s")
                        .merchantChannelId("SUB-MCH-1").build()));
        assertEquals(503, ex.getCode());
        assertTrue(ex.getMessage().contains("notify-base-url"));
    }

    @Test
    void createPaymentFailsWhenCodeUrlMissing() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        WeChatPlatformCashierChannelAdapter adapter = adapter(restTemplate);
        server.expect(requestTo(containsString("/v3/pay/transactions/native")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.createPayment(PaymentChannelDTOs.PaymentCreateCommand.builder()
                        .orderSn("SN-9").amount(new BigDecimal("1.00")).subject("s")
                        .merchantChannelId("SUB-MCH-1").build()));
        assertEquals(502, ex.getCode());
    }

    @Test
    void queryPaymentMapsNotExistToTradeState() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        WeChatPlatformCashierChannelAdapter adapter = adapter(restTemplate);
        server.expect(requestTo(containsString("/v3/pay/transactions/out-trade-no/SN-404")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        PaymentChannelDTOs.ChannelQueryResult result = adapter.queryPayment(
                PaymentChannelDTOs.PaymentCreateCommand.builder()
                        .orderSn("SN-404").merchantChannelId("SUB-MCH-1").build());

        assertEquals("NOT_EXIST", result.getTradeState());
        server.verify();
    }

    @Test
    void queryPaymentMapsSuccessFields() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        WeChatPlatformCashierChannelAdapter adapter = adapter(restTemplate);
        server.expect(requestTo(containsString("sub_mchid=SUB-MCH-1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"sp_mchid\":\"SP-MCH-1\",\"sub_mchid\":\"SUB-MCH-1\","
                        + "\"out_trade_no\":\"SN-9\",\"transaction_id\":\"TX-9\",\"trade_state\":\"SUCCESS\","
                        + "\"amount\":{\"total\":8800},\"payer\":{\"sp_openid\":\"SP-OPEN-1\"}}",
                        MediaType.APPLICATION_JSON));

        PaymentChannelDTOs.ChannelQueryResult result = adapter.queryPayment(
                PaymentChannelDTOs.PaymentCreateCommand.builder()
                        .orderSn("SN-9").merchantChannelId("SUB-MCH-1").build());

        assertEquals("SUCCESS", result.getTradeState());
        assertEquals("TX-9", result.getPayTradeNo());
        assertEquals("SUB-MCH-1", result.getChannelMerchantId());
        assertEquals(new BigDecimal("88.00"), result.getPayAmount());
        assertEquals("SP-OPEN-1", result.getPayerId());
    }

    // ---------- 退款/退分报文 ----------

    @Test
    void refundPaymentAssemblesEcommerceRefundBody() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        WeChatPlatformCashierChannelAdapter adapter = adapter(restTemplate);
        server.expect(requestTo("https://api.mch.weixin.qq.com/v3/ecommerce/refunds/apply"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"sp_mchid\":\"SP-MCH-1\"")))
                .andExpect(content().string(containsString("\"sub_mchid\":\"SUB-MCH-1\"")))
                .andExpect(content().string(containsString("\"transaction_id\":\"TX-9\"")))
                .andExpect(content().string(containsString("\"out_refund_no\":\"RF-1\"")))
                .andExpect(content().string(containsString("\"refund\":9700")))
                .andExpect(content().string(containsString("\"total\":10000")))
                .andRespond(withSuccess("{\"refund_id\":\"5030\",\"out_refund_no\":\"RF-1\","
                        + "\"status\":\"PROCESSING\",\"amount\":{\"refund\":9700,\"total\":10000}}",
                        MediaType.APPLICATION_JSON));

        PaymentChannelDTOs.RefundResult result = adapter.refundPayment(
                PaymentChannelDTOs.RefundCommand.builder()
                        .orderSn("SN-9").refundNo("RF-1").channelTradeNo("TX-9")
                        .merchantChannelId("SUB-MCH-1")
                        .payAmount(new BigDecimal("100.00")).refundAmount(new BigDecimal("97.00"))
                        .reason("买家申请").build());

        assertEquals("PROCESSING", result.getStatus());
        assertFalse(result.isSuccess());
        assertEquals("5030", result.getChannelRefundNo());
        server.verify();
    }

    @Test
    void refundPaymentNetworkErrorBecomes502() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        WeChatPlatformCashierChannelAdapter adapter = adapter(restTemplate);
        server.expect(requestTo(containsString("/v3/ecommerce/refunds/apply")))
                .andRespond(request -> {
                    throw new java.io.IOException("connection reset");
                });
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.refundPayment(PaymentChannelDTOs.RefundCommand.builder()
                        .orderSn("SN-9").refundNo("RF-1")
                        .merchantChannelId("SUB-MCH-1")
                        .payAmount(new BigDecimal("100.00")).refundAmount(new BigDecimal("97.00")).build()));
        assertEquals(502, ex.getCode());
    }

    @Test
    void returnSplitAssemblesProfitsharingReturnBody() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        WeChatPlatformCashierChannelAdapter adapter = adapter(restTemplate);
        // 约定：out_order_no=orderSn（分账发起时的商户分账单号），out_return_no=refundNo（渠道幂等键）。
        server.expect(requestTo("https://api.mch.weixin.qq.com/v3/ecommerce/profitsharing/returnorders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"sub_mchid\":\"SUB-MCH-1\"")))
                .andExpect(content().string(containsString("\"out_order_no\":\"SN-9\"")))
                .andExpect(content().string(containsString("\"out_return_no\":\"RF-1\"")))
                .andExpect(content().string(containsString("\"return_mchid\":\"SUB-MCH-1\"")))
                .andExpect(content().string(containsString("\"amount\":9700")))
                .andRespond(withSuccess("{\"sub_mchid\":\"SUB-MCH-1\",\"out_order_no\":\"SN-9\","
                        + "\"out_return_no\":\"RF-1\",\"return_id\":\"3008450911\",\"amount\":9700,"
                        + "\"result\":\"SUCCESS\"}", MediaType.APPLICATION_JSON));

        PaymentChannelDTOs.SplitReturnResult result = adapter.returnSplit(
                PaymentChannelDTOs.SplitReturnCommand.builder()
                        .orderSn("SN-9").refundNo("RF-1").platformTradeNo("TX-9")
                        .receiverChannelId("SUB-MCH-1").amount(new BigDecimal("97.00"))
                        .reason("先退分后退款").build());

        assertEquals("SUCCESS", result.getStatus());
        assertTrue(result.isSuccess());
        assertEquals("3008450911", result.getChannelReturnNo());
        assertEquals(new BigDecimal("97.00"), result.getAmount());
        server.verify();
    }

    @Test
    void querySplitReturnMapsResult() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        WeChatPlatformCashierChannelAdapter adapter = adapter(restTemplate);
        server.expect(requestTo(containsString("/v3/ecommerce/profitsharing/returnorders/RF-1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"out_return_no\":\"RF-1\",\"amount\":9700,"
                        + "\"result\":\"PROCESSING\"}", MediaType.APPLICATION_JSON));

        PaymentChannelDTOs.SplitReturnResult result = adapter.querySplitReturn(
                PaymentChannelDTOs.SplitReturnCommand.builder()
                        .orderSn("SN-9").refundNo("RF-1").receiverChannelId("SUB-MCH-1")
                        .amount(new BigDecimal("97.00")).reason("r").build());

        assertEquals("PROCESSING", result.getStatus());
        assertFalse(result.isSuccess());
    }

    @Test
    void disabledAdapterRejectsDirectCall() {
        PlatformCashierProperties properties = properties();
        properties.setEnabled(false);
        WeChatPlatformCashierChannelAdapter adapter =
                new WeChatPlatformCashierChannelAdapter(properties, objectMapper, new RestTemplate());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.createPayment(PaymentChannelDTOs.PaymentCreateCommand.builder()
                        .orderSn("SN-9").amount(new BigDecimal("1.00")).subject("s")
                        .merchantChannelId("SUB-MCH-1").build()));
        assertEquals(503, ex.getCode());
        assertTrue(ex.getMessage().contains("平台收银台未启用"));
    }

    // ---------- 测试夹具 ----------

    private WeChatPlatformCashierChannelAdapter adapter(RestTemplate restTemplate) {
        return new WeChatPlatformCashierChannelAdapter(properties(), objectMapper, restTemplate);
    }

    private static PlatformCashierProperties properties() {
        PlatformCashierProperties properties = new PlatformCashierProperties();
        properties.setEnabled(true);
        properties.setSpMchid("SP-MCH-1");
        properties.setSpAppid("SP-APP-1");
        properties.setSerialNo("SER-1");
        properties.setApiV3Key(APIV3);
        properties.setPrivateKey(privatePem);
        properties.setPlatformPublicKey(publicPem);
        properties.setNotifyBaseUrl("https://pay.example.com");
        return properties;
    }

    private static String callbackBody(String transactionJson) throws Exception {
        byte[] nonce = "nonce-123456".getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(APIV3.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder()
                .encodeToString(cipher.doFinal(transactionJson.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("algorithm", "AEAD_AES_256_GCM");
        resource.put("ciphertext", ciphertext);
        resource.put("nonce", "nonce-123456");
        resource.put("associated_data", "transaction");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "EVT-1");
        root.put("event_type", "TRANSACTION.SUCCESS");
        root.put("resource", resource);
        return new ObjectMapper().writeValueAsString(root);
    }

    private static Map<String, String> signedHeaders(String body) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-headers";
        return Map.of(
                "Wechatpay-Timestamp", timestamp,
                "Wechatpay-Nonce", nonce,
                "Wechatpay-Signature", sign(timestamp + "\n" + nonce + "\n" + body + "\n"));
    }

    private static String sign(String message) throws Exception {
        PrivateKey privateKey = keyPair.getPrivate();
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }
}
