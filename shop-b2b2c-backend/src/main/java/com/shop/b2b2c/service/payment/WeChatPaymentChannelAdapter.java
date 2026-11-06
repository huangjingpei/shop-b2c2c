/*
 * 文件：WeChatPaymentChannelAdapter.java
 * 用途：微信支付 APIv3 商家直收适配器：Native 下单、回调验签与 AES-GCM 解密、查单、关单 (T32)。
 * 维护注意：
 *  1. 回调必须先用微信支付平台公钥验证 Wechatpay-Signature，再用 APIv3 密钥解密 resource，验签失败不得落账。
 *  2. 金额从渠道报文 amount.total（分）换算为元；禁止用订单应付金额反填。
 *  3. 商户号使用订单所属商家在渠道侧的 mchid，不使用平台内部 merchantId；凭据仅来自配置，不写日志。
 *  4. 微信请求签名串固定为“方法\\nURL\\n时间戳\\n随机串\\n报文\\n”，GET 的 URL 必须包含查询参数。
 */
package com.shop.b2b2c.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WeChatPaymentChannelAdapter implements PaymentChannelAdapter {

    private static final String NATIVE_PATH = "/v3/pay/transactions/native";
    private static final String OUT_TRADE_PATH = "/v3/pay/transactions/out-trade-no/";
    private static final String SUCCESS = "SUCCESS";

    private final PaymentChannelProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = ChannelHttpClient.create();

    @Override
    public String channel() {
        return PayChannel.WECHAT;
    }

    @Override
    public boolean enabled() {
        return properties.getWechat().isEnabled();
    }

    @Override
    public PaymentChannelDTOs.PaymentAttemptView createPayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        requireTradingConfig();
        String mchid = requireMerchantChannelId(command);
        long totalFen = toFen(command.getAmount());
        if (totalFen <= 0) {
            throw new BusinessException(400, "支付金额必须大于0");
        }
        String notifyUrl = properties.notifyUrl(PayChannel.WECHAT);

        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("total", totalFen);
        amount.put("currency", "CNY");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", properties.getWechat().getAppId());
        body.put("mchid", mchid);
        body.put("description", truncate(command.getSubject(), 127));
        body.put("out_trade_no", command.getOrderSn());
        body.put("notify_url", notifyUrl);
        body.put("amount", amount);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new BusinessException(500, "微信支付下单报文生成失败");
        }

        HttpHeaders headers = signedHeaders(HttpMethod.POST, NATIVE_PATH, payload, mchid);
        try {
            String response = restTemplate.postForObject(
                    properties.getWechat().getApiBase() + NATIVE_PATH,
                    new HttpEntity<>(payload, headers), String.class);
            JsonNode node = objectMapper.readTree(response == null ? "{}" : response);
            String codeUrl = text(node, "code_url");
            if (codeUrl == null || codeUrl.isBlank()) {
                throw new BusinessException(502, "微信支付未返回有效二维码链接");
            }
            return PaymentChannelDTOs.PaymentAttemptView.builder()
                    .orderSn(command.getOrderSn())
                    .payChannel(PayChannel.WECHAT)
                    .collectionMode(com.shop.b2b2c.common.CollectionMode.MERCHANT_DIRECT)
                    .amount(command.getAmount())
                    .currency("CNY")
                    .tradeType("NATIVE")
                    .codeUrl(codeUrl)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            ChannelHttpClient.handleRestFailure("微信支付", e);
            return null;
        } catch (Exception e) {
            throw new BusinessException(502, "微信支付下单响应解析失败");
        }
    }

    @Override
    public PaymentChannelDTOs.ChannelCallback parseCallback(Map<String, String> headers, String rawBody,
                                                            Map<String, String> formParams) {
        String platformPublicKey = properties.getWechat().getPlatformPublicKey();
        ChannelHttpClient.ensureConfigured(platformPublicKey != null && !platformPublicKey.isBlank(),
                "微信支付平台公钥未配置");
        if (headers == null || rawBody == null || rawBody.isBlank()) {
            throw new BusinessException(400, "微信支付回调缺少报文或请求头");
        }
        String timestamp = header(headers, "Wechatpay-Timestamp");
        String nonce = header(headers, "Wechatpay-Nonce");
        String signature = header(headers, "Wechatpay-Signature");
        if (timestamp == null || nonce == null || signature == null) {
            throw new BusinessException(401, "微信支付回调缺少验签头");
        }
        String message = timestamp + "\n" + nonce + "\n" + rawBody + "\n";
        PublicKey publicKey = PemKeySupport.publicKey(platformPublicKey);
        if (!PemKeySupport.verify(message, signature, publicKey)) {
            throw new BusinessException(401, "微信支付回调验签失败");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new BusinessException(400, "微信支付回调报文格式错误");
        }
        String eventType = text(root, "event_type");
        if (!"TRANSACTION.SUCCESS".equals(eventType)) {
            throw new BusinessException(409, "微信支付回调不是支付成功通知: " + eventType);
        }
        JsonNode resource = root.get("resource");
        if (resource == null) {
            throw new BusinessException(400, "微信支付回调缺少加密资源");
        }
        String plaintext = decryptResource(resource);
        JsonNode transaction;
        try {
            transaction = objectMapper.readTree(plaintext);
        } catch (Exception e) {
            throw new BusinessException(400, "微信支付回调解密内容格式错误");
        }
        if (!SUCCESS.equals(text(transaction, "trade_state"))) {
            throw new BusinessException(409, "微信支付交易状态不是 SUCCESS");
        }
        String orderSn = text(transaction, "out_trade_no");
        String payTradeNo = text(transaction, "transaction_id");
        String mchid = text(transaction, "mchid");
        if (orderSn == null || payTradeNo == null || mchid == null) {
            throw new BusinessException(400, "微信支付回调缺少订单号/渠道流水/商户号");
        }
        JsonNode amount = transaction.get("amount");
        if (amount == null || amount.get("total") == null) {
            throw new BusinessException(400, "微信支付回调缺少实收金额");
        }
        BigDecimal payAmount = new BigDecimal(amount.get("total").asLong())
                .divide(new BigDecimal("100"), 2, RoundingMode.UNNECESSARY);
        String currency = amount.hasNonNull("currency") ? amount.get("currency").asText() : "CNY";
        JsonNode payer = transaction.get("payer");
        String payerId = payer != null && payer.hasNonNull("openid") ? payer.get("openid").asText() : null;
        return PaymentChannelDTOs.ChannelCallback.builder()
                .channel(PayChannel.WECHAT)
                .payTradeNo(payTradeNo)
                .orderSn(orderSn)
                .channelMerchantId(mchid)
                .payAmount(payAmount)
                .currency(currency)
                .payerId(payerId)
                .rawPayload(rawBody)
                .build();
    }

    @Override
    public PaymentChannelDTOs.ChannelQueryResult queryPayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        requireTradingConfig();
        String mchid = requireMerchantChannelId(command);
        String path = OUT_TRADE_PATH + UriUtils.encodePathSegment(command.getOrderSn(), StandardCharsets.UTF_8)
                + "?mchid=" + UriUtils.encodeQueryParam(mchid, StandardCharsets.UTF_8);
        HttpHeaders headers = signedHeaders(HttpMethod.GET, path, "", mchid);
        try {
            String response = restTemplate.exchange(
                    properties.getWechat().getApiBase() + path, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class).getBody();
            JsonNode node = objectMapper.readTree(response == null ? "{}" : response);
            String tradeState = mapTradeState(text(node, "trade_state"));
            BigDecimal amount = null;
            JsonNode amountNode = node.get("amount");
            if (amountNode != null && amountNode.get("total") != null) {
                amount = new BigDecimal(amountNode.get("total").asLong())
                        .divide(new BigDecimal("100"), 2, RoundingMode.UNNECESSARY);
            }
            JsonNode payer = node.get("payer");
            String payerId = payer != null && payer.hasNonNull("openid") ? payer.get("openid").asText() : null;
            return PaymentChannelDTOs.ChannelQueryResult.builder()
                    .channel(PayChannel.WECHAT)
                    .orderSn(command.getOrderSn())
                    .payTradeNo(text(node, "transaction_id"))
                    .tradeState(tradeState)
                    .channelMerchantId(text(node, "mchid"))
                    .payAmount(amount)
                    .currency("CNY")
                    .payerId(payerId)
                    .rawPayload(response)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            ChannelHttpClient.handleRestFailure("微信支付", e);
            return null;
        } catch (Exception e) {
            throw new BusinessException(502, "微信支付查单响应解析失败");
        }
    }

    @Override
    public void closePayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        requireTradingConfig();
        String mchid = requireMerchantChannelId(command);
        String path = OUT_TRADE_PATH + UriUtils.encodePathSegment(command.getOrderSn(), StandardCharsets.UTF_8) + "/close";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mchid", mchid);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new BusinessException(500, "微信支付关单报文生成失败");
        }
        HttpHeaders headers = signedHeaders(HttpMethod.POST, path, payload, mchid);
        try {
            restTemplate.postForEntity(properties.getWechat().getApiBase() + path,
                    new HttpEntity<>(payload, headers), String.class);
        } catch (RestClientResponseException e) {
            // 404/ORDER_NOT_EXIST 表示渠道侧不存在该单，视为已关闭；其余错误仍失败。
            if (e.getStatusCode().value() != 404) {
                throw ChannelHttpClient.failure("微信支付");
            }
        } catch (RestClientException e) {
            ChannelHttpClient.handleRestFailure("微信支付", e);
        }
    }

    private void requireTradingConfig() {
        ChannelHttpClient.ensureConfigured(enabled(), "微信支付渠道未启用");
        PaymentChannelProperties.WeChat cfg = properties.getWechat();
        ChannelHttpClient.ensureConfigured(cfg.getAppId() != null && !cfg.getAppId().isBlank(),
                "微信支付 AppId 未配置");
        ChannelHttpClient.ensureConfigured(cfg.getSerialNo() != null && !cfg.getSerialNo().isBlank(),
                "微信支付证书序列号未配置");
        ChannelHttpClient.ensureConfigured(cfg.getPrivateKey() != null && !cfg.getPrivateKey().isBlank(),
                "微信支付商户私钥未配置");
    }

    private String requireMerchantChannelId(PaymentChannelDTOs.PaymentCreateCommand command) {
        String mchid = command.getMerchantChannelId();
        if (mchid == null || mchid.isBlank()) {
            throw new BusinessException(409, "商家未登记微信支付商户号，无法发起支付");
        }
        return mchid.trim();
    }

    private HttpHeaders signedHeaders(HttpMethod method, String pathWithQuery, String payload, String mchid) {
        PaymentChannelProperties.WeChat cfg = properties.getWechat();
        long timestamp = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String message = method.name() + "\n" + pathWithQuery + "\n" + timestamp + "\n" + nonce + "\n" + payload + "\n";
        PrivateKey privateKey = PemKeySupport.privateKey(cfg.getPrivateKey());
        String signature = PemKeySupport.sign(message, privateKey);
        String authorization = "WECHATPAY2-SHA256-RSA2048 mchid=\"" + mchid
                + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + signature
                + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + cfg.getSerialNo() + "\"";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.set("Authorization", authorization);
        headers.set("User-Agent", "shop-b2b2c/1.0");
        return headers;
    }

    private String decryptResource(JsonNode resource) {
        String algorithm = text(resource, "algorithm");
        if (!"AEAD_AES_256_GCM".equals(algorithm)) {
            throw new BusinessException(400, "微信支付回调不支持的加密算法: " + algorithm);
        }
        String apiV3Key = properties.getWechat().getApiV3Key();
        if (apiV3Key == null || apiV3Key.getBytes(StandardCharsets.UTF_8).length != 32) {
            throw new BusinessException(503, "微信支付 APIv3 密钥未配置或长度不是32字节");
        }
        String ciphertext = text(resource, "ciphertext");
        String nonce = text(resource, "nonce");
        if (ciphertext == null || nonce == null) {
            throw new BusinessException(400, "微信支付回调缺少密文或随机串");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                    new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            String associatedData = text(resource, "associated_data");
            if (associatedData != null && !associatedData.isBlank()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(400, "微信支付回调解密失败");
        }
    }

    private String mapTradeState(String state) {
        if (state == null) return "UNKNOWN";
        return switch (state) {
            case "SUCCESS" -> "SUCCESS";
            case "CLOSED", "REVOKED" -> "CLOSED";
            case "NOTPAY", "USERPAYING" -> "PENDING";
            case "REFUND" -> "REFUNDED";
            default -> "FAILED";
        };
    }

    private static long toFen(BigDecimal amount) {
        if (amount == null) {
            throw new BusinessException(400, "支付金额不能为空");
        }
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) return "订单支付";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
