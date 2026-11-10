/*
 * 文件：WeChatPlatformCashierChannelAdapter.java
 * 用途：Sprint 59 微信电商收付通平台收银台真实适配器（服务商模式）：平台代收 Native 下单、
 *      支付回调验签解密、查单、关单、平台退款/退款查单、分账回退（退分）/退分查单。
 *      凭据全部来自 PlatformCashierProperties（payment.platform.*，环境变量注入）。
 * 维护注意：
 *  1. 签名/验签/解密与商家直收同口径：RSA-SHA256 请求签名（商户号=sp_mchid）、微信支付平台公钥验签回调、
 *     AES-256-GCM 解密 resource、Wechatpay-Timestamp ±300 秒防重放；密钥凭据不写日志。
 *  2. 适配器只返回渠道事实，不写本地账本、不改订单/退款状态；本地状态由支付事件/退款事务服务推进。
 *  3. 下单/查单/关单走 /v3/pay/transactions/*（服务商：sp_mchid+sub_mchid）；退款走 /v3/ecommerce/refunds/*；
 *     退分走 /v3/ecommerce/profitsharing/returnorders*。金额“分”与库内“元”只在本类换算。
 *  4. 分账回退约定：未来分账发起必须以 orderSn 为商户分账单号 out_order_no，退分凭此关联；
 *     out_return_no 固定取 refundNo（渠道幂等键），重试不会产生二次退分；
 *     报文 sub_mchid 即分账接收方（商家二级商户号），与 return_mchid 同源。
 *  5. 回调解密报文必须校验 sp_mchid 与配置一致；channelMerchantId 返回二级商户号 sub_mchid，
 *     由网关与商家当前有效分账接收方比对，不与商家直收账户混用。
 *  6. 退款命令的 merchantChannelId 承载二级商户号 sub_mchid（由退款编排按分账接收方解析）；
 *     发起商户号固定取 payment.platform.sp-mchid，不得改用任何商家直收账户。
 *  7. 未完成真实通道联调前 properties.enabled 保持 false，入口由 PlatformCashierChannelRegistry 409 拦截；
 *     本类方法被直接调用时也必须 503 拒绝，不得伪造成功。联调问题以微信支付返回的业务错误码为准，禁止吞错。
 */
package com.shop.b2b2c.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
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
@Slf4j
public class WeChatPlatformCashierChannelAdapter implements PlatformCashierChannelAdapter {

    private static final String NATIVE_PATH = "/v3/pay/transactions/native";
    private static final String OUT_TRADE_PATH = "/v3/pay/transactions/out-trade-no/";
    private static final String ECOMMERCE_REFUND_PATH = "/v3/ecommerce/refunds/apply";
    private static final String ECOMMERCE_REFUND_QUERY_PATH = "/v3/ecommerce/refunds/out-refund-no/";
    private static final String PROFITSHARING_RETURN_PATH = "/v3/ecommerce/profitsharing/returnorders";
    private static final String SUCCESS = "SUCCESS";

    private final PlatformCashierProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    /** 生产构造：显式 @Autowired 指定（类内另有测试专用三参构造，避免 Spring 构造器抉择失败）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public WeChatPlatformCashierChannelAdapter(PlatformCashierProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, ChannelHttpClient.create());
    }

    /** 测试专用：注入可拦截的 RestTemplate，验证报文拼装与错误翻译；生产走默认构造（@Autowired 二参）。 */
    WeChatPlatformCashierChannelAdapter(PlatformCashierProperties properties, ObjectMapper objectMapper,
                                        RestTemplate restTemplate) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    @Override
    public String channel() {
        return PayChannel.WECHAT;
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    @Override
    public PaymentChannelDTOs.PaymentAttemptView createPayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        requireReady();
        String subMchid = requireSubMchid(command.getMerchantChannelId(), "发起平台代收支付");
        long totalFen = toFen(command.getAmount());
        if (totalFen <= 0) {
            throw new BusinessException(400, "支付金额必须大于0");
        }
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("total", totalFen);
        amount.put("currency", "CNY");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sp_appid", properties.getSpAppid().trim());
        body.put("sp_mchid", properties.getSpMchid().trim());
        body.put("sub_mchid", subMchid);
        body.put("description", truncate(command.getSubject(), 127));
        body.put("out_trade_no", command.getOrderSn());
        body.put("notify_url", notifyUrl());
        body.put("amount", amount);
        String payload = writeJson(body, "微信支付平台代收下单报文生成失败");
        try {
            String response = restTemplate.postForObject(properties.getApiBase() + NATIVE_PATH,
                    new HttpEntity<>(payload, signedHeaders(HttpMethod.POST, NATIVE_PATH, payload)), String.class);
            JsonNode node = readJson(response, "微信支付平台代收下单响应解析失败");
            String codeUrl = text(node, "code_url");
            if (codeUrl == null || codeUrl.isBlank()) {
                throw new BusinessException(502, "微信支付平台代收未返回有效二维码链接");
            }
            return PaymentChannelDTOs.PaymentAttemptView.builder()
                    .orderSn(command.getOrderSn())
                    .payChannel(PayChannel.WECHAT)
                    .collectionMode(CollectionMode.PLATFORM_COLLECT)
                    .amount(command.getAmount())
                    .currency("CNY")
                    .tradeType("NATIVE")
                    .codeUrl(codeUrl)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            ChannelHttpClient.handleRestFailure("微信支付平台代收", e);
            return null;
        }
    }

    @Override
    public PaymentChannelDTOs.ChannelCallback parseCallback(Map<String, String> headers, String rawBody,
                                                            Map<String, String> formParams) {
        String platformPublicKey = properties.getPlatformPublicKey();
        ChannelHttpClient.ensureConfigured(platformPublicKey != null && !platformPublicKey.isBlank(),
                "微信支付平台公钥未配置");
        if (headers == null || rawBody == null || rawBody.isBlank()) {
            throw new BusinessException(400, "微信支付平台代收回调缺少报文或请求头");
        }
        String timestamp = header(headers, "Wechatpay-Timestamp");
        String nonce = header(headers, "Wechatpay-Nonce");
        String signature = header(headers, "Wechatpay-Signature");
        if (timestamp == null || nonce == null || signature == null) {
            throw new BusinessException(401, "微信支付平台代收回调缺少验签头");
        }
        CallbackReplayGuard.requireWechatTimestamp(timestamp);
        String message = timestamp + "\n" + nonce + "\n" + rawBody + "\n";
        PublicKey publicKey = PemKeySupport.publicKey(platformPublicKey);
        if (!PemKeySupport.verify(message, signature, publicKey)) {
            throw new BusinessException(401, "微信支付平台代收回调验签失败");
        }
        JsonNode root = readJson(rawBody, "微信支付平台代收回调报文格式错误");
        String eventType = text(root, "event_type");
        if (!"TRANSACTION.SUCCESS".equals(eventType)) {
            throw new BusinessException(409, "微信支付平台代收回调不是支付成功通知: " + eventType);
        }
        JsonNode resource = root.get("resource");
        if (resource == null) {
            throw new BusinessException(400, "微信支付平台代收回调缺少加密资源");
        }
        JsonNode transaction = readJson(decryptResource(resource), "微信支付平台代收回调解密内容格式错误");
        if (!SUCCESS.equals(text(transaction, "trade_state"))) {
            throw new BusinessException(409, "微信支付平台代收交易状态不是 SUCCESS");
        }
        String spMchid = text(transaction, "sp_mchid");
        if (spMchid == null || !spMchid.equals(properties.getSpMchid().trim())) {
            throw new BusinessException(401, "微信支付平台代收回调服务商商户号与配置不匹配");
        }
        String subMchid = text(transaction, "sub_mchid");
        String orderSn = text(transaction, "out_trade_no");
        String payTradeNo = text(transaction, "transaction_id");
        if (subMchid == null || orderSn == null || payTradeNo == null) {
            throw new BusinessException(400, "微信支付平台代收回调缺少二级商户号/订单号/渠道流水");
        }
        JsonNode amount = transaction.get("amount");
        if (amount == null || amount.get("total") == null) {
            throw new BusinessException(400, "微信支付平台代收回调缺少实收金额");
        }
        BigDecimal payAmount = new BigDecimal(amount.get("total").asLong())
                .divide(new BigDecimal("100"), 2, RoundingMode.UNNECESSARY);
        String currency = amount.hasNonNull("currency") ? amount.get("currency").asText() : "CNY";
        JsonNode payer = transaction.get("payer");
        String payerId = payer != null && payer.hasNonNull("sp_openid") ? payer.get("sp_openid").asText() : null;
        return PaymentChannelDTOs.ChannelCallback.builder()
                .channel(PayChannel.WECHAT)
                .payTradeNo(payTradeNo)
                .orderSn(orderSn)
                .channelMerchantId(subMchid)
                .payAmount(payAmount)
                .currency(currency)
                .payerId(payerId)
                .rawPayload(rawBody)
                .build();
    }

    @Override
    public PaymentChannelDTOs.ChannelQueryResult queryPayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        requireReady();
        String subMchid = requireSubMchid(command.getMerchantChannelId(), "查询平台代收支付");
        String path = OUT_TRADE_PATH + UriUtils.encodePathSegment(command.getOrderSn(), StandardCharsets.UTF_8)
                + "?sp_mchid=" + UriUtils.encodeQueryParam(properties.getSpMchid().trim(), StandardCharsets.UTF_8)
                + "&sub_mchid=" + UriUtils.encodeQueryParam(subMchid, StandardCharsets.UTF_8);
        try {
            String response = restTemplate.exchange(properties.getApiBase() + path, HttpMethod.GET,
                    new HttpEntity<>(signedHeaders(HttpMethod.GET, path, "")), String.class).getBody();
            JsonNode node = readJson(response, "微信支付平台代收查单响应解析失败");
            BigDecimal amount = null;
            JsonNode amountNode = node.get("amount");
            if (amountNode != null && amountNode.get("total") != null) {
                amount = new BigDecimal(amountNode.get("total").asLong())
                        .divide(new BigDecimal("100"), 2, RoundingMode.UNNECESSARY);
            }
            JsonNode payer = node.get("payer");
            String payerId = payer != null && payer.hasNonNull("sp_openid") ? payer.get("sp_openid").asText() : null;
            return PaymentChannelDTOs.ChannelQueryResult.builder()
                    .channel(PayChannel.WECHAT)
                    .orderSn(command.getOrderSn())
                    .payTradeNo(text(node, "transaction_id"))
                    .tradeState(mapTradeState(text(node, "trade_state")))
                    .channelMerchantId(text(node, "sub_mchid"))
                    .payAmount(amount)
                    .currency("CNY")
                    .payerId(payerId)
                    .rawPayload(response)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            // 与直收同口径：404/ORDER_NOT_EXIST 是渠道确定的“查无此单”，其余 HTTP 错误保留可重试。
            if (e.getStatusCode().value() != 404) {
                ChannelHttpClient.handleRestFailure("微信支付平台代收", e);
                return null;
            }
            return PaymentChannelDTOs.ChannelQueryResult.builder()
                    .channel(PayChannel.WECHAT)
                    .orderSn(command.getOrderSn())
                    .tradeState("NOT_EXIST")
                    .currency("CNY")
                    .build();
        } catch (RestClientException e) {
            ChannelHttpClient.handleRestFailure("微信支付平台代收", e);
            return null;
        }
    }

    @Override
    public void closePayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        requireReady();
        String subMchid = requireSubMchid(command.getMerchantChannelId(), "关闭平台代收支付");
        String path = OUT_TRADE_PATH + UriUtils.encodePathSegment(command.getOrderSn(), StandardCharsets.UTF_8)
                + "/close";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sp_mchid", properties.getSpMchid().trim());
        body.put("sub_mchid", subMchid);
        String payload = writeJson(body, "微信支付平台代收关单报文生成失败");
        try {
            restTemplate.postForEntity(properties.getApiBase() + path,
                    new HttpEntity<>(payload, signedHeaders(HttpMethod.POST, path, payload)), String.class);
        } catch (RestClientResponseException e) {
            // 404 表示渠道侧不存在该单，视为已关闭；其余错误仍失败。
            if (e.getStatusCode().value() != 404) {
                throw ChannelHttpClient.failure("微信支付平台代收");
            }
        } catch (RestClientException e) {
            ChannelHttpClient.handleRestFailure("微信支付平台代收", e);
        }
    }

    @Override
    public PaymentChannelDTOs.RefundResult refundPayment(PaymentChannelDTOs.RefundCommand command) {
        requireReady();
        String subMchid = requireSubMchid(command.getMerchantChannelId(), "办理平台代收退款");
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("refund", toFen(command.getRefundAmount()));
        amount.put("total", toFen(command.getPayAmount()));
        amount.put("currency", "CNY");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sp_mchid", properties.getSpMchid().trim());
        body.put("sub_mchid", subMchid);
        if (command.getChannelTradeNo() != null && !command.getChannelTradeNo().isBlank()) {
            body.put("transaction_id", command.getChannelTradeNo());
        } else {
            body.put("out_trade_no", command.getOrderSn());
        }
        body.put("out_refund_no", command.getRefundNo());
        if (command.getReason() != null && !command.getReason().isBlank()) {
            body.put("reason", truncate(command.getReason(), 80));
        }
        body.put("amount", amount);
        String payload = writeJson(body, "微信电商平台代收退款报文生成失败");
        try {
            String response = restTemplate.postForObject(properties.getApiBase() + ECOMMERCE_REFUND_PATH,
                    new HttpEntity<>(payload, signedHeaders(HttpMethod.POST, ECOMMERCE_REFUND_PATH, payload)),
                    String.class);
            return parseRefundResponse(response, command.getRefundNo());
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            ChannelHttpClient.handleRestFailure("微信支付平台代收", e);
            return null;
        }
    }

    @Override
    public PaymentChannelDTOs.RefundResult queryRefund(PaymentChannelDTOs.RefundCommand command) {
        requireReady();
        String subMchid = requireSubMchid(command.getMerchantChannelId(), "查询平台代收退款");
        String path = ECOMMERCE_REFUND_QUERY_PATH
                + UriUtils.encodePathSegment(command.getRefundNo(), StandardCharsets.UTF_8)
                + "?sub_mchid=" + UriUtils.encodeQueryParam(subMchid, StandardCharsets.UTF_8);
        try {
            String response = restTemplate.exchange(properties.getApiBase() + path, HttpMethod.GET,
                    new HttpEntity<>(signedHeaders(HttpMethod.GET, path, "")), String.class).getBody();
            return parseRefundResponse(response, command.getRefundNo());
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            ChannelHttpClient.handleRestFailure("微信支付平台代收", e);
            return null;
        }
    }

    @Override
    public PaymentChannelDTOs.SplitReturnResult returnSplit(PaymentChannelDTOs.SplitReturnCommand command) {
        requireReady();
        Map<String, Object> body = new LinkedHashMap<>();
        // 约定：分账发起以 orderSn 为商户分账单号 out_order_no；报文 sub_mchid 即分账接收方（商家二级商户号）。
        body.put("sub_mchid", requireSubMchid(command.getReceiverChannelId(), "办理平台代收退分"));
        body.put("out_order_no", command.getOrderSn());
        body.put("out_return_no", command.getRefundNo());
        body.put("return_mchid", command.getReceiverChannelId().trim());
        body.put("amount", toFen(command.getAmount()));
        body.put("description", truncate(command.getReason(), 80));
        String payload = writeJson(body, "微信电商平台代收退分报文生成失败");
        try {
            String response = restTemplate.postForObject(properties.getApiBase() + PROFITSHARING_RETURN_PATH,
                    new HttpEntity<>(payload, signedHeaders(HttpMethod.POST, PROFITSHARING_RETURN_PATH, payload)),
                    String.class);
            return parseSplitReturnResponse(response, command.getRefundNo());
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            ChannelHttpClient.handleRestFailure("微信支付平台代收", e);
            return null;
        }
    }

    @Override
    public PaymentChannelDTOs.SplitReturnResult querySplitReturn(PaymentChannelDTOs.SplitReturnCommand command) {
        requireReady();
        String subMchid = requireSubMchid(command.getReceiverChannelId(), "查询平台代收退分");
        String path = PROFITSHARING_RETURN_PATH + "/"
                + UriUtils.encodePathSegment(command.getRefundNo(), StandardCharsets.UTF_8)
                + "?sub_mchid=" + UriUtils.encodeQueryParam(subMchid, StandardCharsets.UTF_8);
        try {
            String response = restTemplate.exchange(properties.getApiBase() + path, HttpMethod.GET,
                    new HttpEntity<>(signedHeaders(HttpMethod.GET, path, "")), String.class).getBody();
            return parseSplitReturnResponse(response, command.getRefundNo());
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            ChannelHttpClient.handleRestFailure("微信支付平台代收", e);
            return null;
        }
    }

    private PaymentChannelDTOs.RefundResult parseRefundResponse(String response, String refundNo) {
        JsonNode node = readJson(response, "微信电商平台代收退款响应解析失败");
        String channelStatus = text(node, "status");
        // 电商收付通退款状态：SUCCESS / PROCESSING / CLOSED / ABNORMAL；后两者按失败收敛。
        String status = SUCCESS.equals(channelStatus) ? SUCCESS
                : "PROCESSING".equals(channelStatus) ? "PROCESSING" : "FAILED";
        BigDecimal refundAmount = null;
        JsonNode amount = node.get("amount");
        if (amount != null && amount.get("refund") != null) {
            refundAmount = new BigDecimal(amount.get("refund").asLong())
                    .divide(new BigDecimal("100"), 2, RoundingMode.UNNECESSARY);
        }
        return PaymentChannelDTOs.RefundResult.builder()
                .channel(PayChannel.WECHAT)
                .refundNo(text(node, "out_refund_no") == null ? refundNo : text(node, "out_refund_no"))
                .channelRefundNo(text(node, "refund_id"))
                .status(status)
                .refundAmount(refundAmount)
                .rawPayload(response)
                .build();
    }

    private PaymentChannelDTOs.SplitReturnResult parseSplitReturnResponse(String response, String refundNo) {
        JsonNode node = readJson(response, "微信电商平台代收退分响应解析失败");
        String result = text(node, "result");
        String status = SUCCESS.equals(result) ? SUCCESS
                : "PROCESSING".equals(result) ? "PROCESSING" : "FAILED";
        BigDecimal amount = null;
        if (node.get("amount") != null) {
            amount = new BigDecimal(node.get("amount").asLong())
                    .divide(new BigDecimal("100"), 2, RoundingMode.UNNECESSARY);
        }
        return PaymentChannelDTOs.SplitReturnResult.builder()
                .channel(PayChannel.WECHAT)
                .refundNo(text(node, "out_return_no") == null ? refundNo : text(node, "out_return_no"))
                .channelReturnNo(text(node, "return_id"))
                .status(status)
                .amount(amount)
                .rawPayload(response)
                .build();
    }

    /** 直接调用守卫：未联调/凭据缺失一律 503；正常链路经 registry 的 409 细化入口拦截。 */
    private void requireReady() {
        ChannelHttpClient.ensureConfigured(enabled(), "平台收银台未启用（payment.platform.enabled=false）");
        ChannelHttpClient.ensureConfigured(properties.configured(), "平台收银台凭据未配置（payment.platform.*）");
    }

    private String requireSubMchid(String candidate, String action) {
        if (candidate == null || candidate.isBlank()) {
            throw new BusinessException(409, "缺少商家二级商户号 sub_mchid，无法" + action);
        }
        return candidate.trim();
    }

    private String notifyUrl() {
        String base = properties.getNotifyBaseUrl() == null ? "" : properties.getNotifyBaseUrl().trim();
        if (base.isEmpty()) {
            throw new BusinessException(503, "未配置 payment.platform.notify-base-url，禁止发起平台代收下单");
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + properties.getNotifyPath();
    }

    private HttpHeaders signedHeaders(HttpMethod method, String pathWithQuery, String payload) {
        long timestamp = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String message = method.name() + "\n" + pathWithQuery + "\n" + timestamp + "\n" + nonce + "\n" + payload + "\n";
        PrivateKey privateKey = PemKeySupport.privateKey(properties.getPrivateKey());
        String signature = PemKeySupport.sign(message, privateKey);
        String authorization = "WECHATPAY2-SHA256-RSA2048 mchid=\"" + properties.getSpMchid().trim()
                + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + signature
                + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + properties.getSerialNo().trim() + "\"";
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
            throw new BusinessException(400, "微信支付平台代收回调不支持的加密算法: " + algorithm);
        }
        String apiV3Key = properties.getApiV3Key();
        if (apiV3Key == null || apiV3Key.getBytes(StandardCharsets.UTF_8).length != 32) {
            throw new BusinessException(503, "微信支付平台 APIv3 密钥未配置或长度不是32字节");
        }
        String ciphertext = text(resource, "ciphertext");
        String nonce = text(resource, "nonce");
        if (ciphertext == null || nonce == null) {
            throw new BusinessException(400, "微信支付平台代收回调缺少密文或随机串");
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
            throw new BusinessException(400, "微信支付平台代收回调解密失败");
        }
    }

    private String mapTradeState(String state) {
        if (state == null) return "UNKNOWN";
        return switch (state) {
            case "SUCCESS" -> "SUCCESS";
            case "CLOSED", "REVOKED" -> "CLOSED";
            case "NOTPAY", "USERPAYING" -> "PENDING";
            case "REFUND" -> "REFUNDED";
            case "PAYERROR" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    private String writeJson(Map<String, Object> body, String failureMessage) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new BusinessException(500, failureMessage);
        }
    }

    private JsonNode readJson(String raw, String failureMessage) {
        try {
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (Exception e) {
            throw new BusinessException(502, failureMessage);
        }
    }

    private static long toFen(BigDecimal amount) {
        if (amount == null) {
            throw new BusinessException(400, "金额不能为空");
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
