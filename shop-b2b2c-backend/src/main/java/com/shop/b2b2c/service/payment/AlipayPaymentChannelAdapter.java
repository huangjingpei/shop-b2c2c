/*
 * 文件：AlipayPaymentChannelAdapter.java
 * 用途：支付宝商家直收适配器：统一预下单、异步通知验签、查单、关单 (T32)。
 * 维护注意：
 *  1. 异步通知必须按支付宝规则剔除 sign/sign_type 后排序验签，并校验 app_id 与 seller_id；未验签不得落账。
 *  2. 实收金额取通知/查单的 total_amount，禁止用订单应付金额反填；只接受 TRADE_SUCCESS / TRADE_FINISHED。
 *  3. 商户渠道号使用订单所属商家的 seller_id，不使用平台内部 merchantId；私钥只来自配置，不写日志。
 *  4. 请求签名统一 RSA2，参数按字典序拼接；不要对 biz_content 二次转义。
 */
package com.shop.b2b2c.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AlipayPaymentChannelAdapter implements PaymentChannelAdapter {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String TRADE_SUCCESS = "TRADE_SUCCESS";
    private static final String RESPONSE_QUERY = "alipay_trade_query_response";
    private static final String RESPONSE_PRECREATE = "alipay_trade_precreate_response";
    private static final String RESPONSE_CLOSE = "alipay_trade_close_response";

    private final PaymentChannelProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = ChannelHttpClient.create();

    @Override
    public String channel() {
        return PayChannel.ALIPAY;
    }

    @Override
    public boolean enabled() {
        return properties.getAlipay().isEnabled();
    }

    @Override
    public PaymentChannelDTOs.PaymentAttemptView createPayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        requireTradingConfig();
        requireMerchantChannelId(command);
        if (command.getAmount() == null || command.getAmount().signum() <= 0) {
            throw new BusinessException(400, "支付金额必须大于0");
        }
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", command.getOrderSn());
        biz.put("total_amount", command.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        biz.put("subject", truncate(command.getSubject(), 256));
        String bizContent;
        try {
            bizContent = objectMapper.writeValueAsString(biz);
        } catch (Exception e) {
            throw new BusinessException(500, "支付宝下单报文生成失败");
        }
        Map<String, String> params = commonParams("alipay.trade.precreate", properties.notifyUrl(PayChannel.ALIPAY));
        params.put("biz_content", bizContent);
        String response = postSigned(params);
        JsonNode node = responseNode(response, RESPONSE_PRECREATE);
        String qrCode = text(node, "qr_code");
        if (qrCode == null || qrCode.isBlank()) {
            throw new BusinessException(502, "支付宝未返回有效二维码链接");
        }
        return PaymentChannelDTOs.PaymentAttemptView.builder()
                .orderSn(command.getOrderSn())
                .payChannel(PayChannel.ALIPAY)
                .collectionMode(CollectionMode.MERCHANT_DIRECT)
                .amount(command.getAmount())
                .currency("CNY")
                .tradeType("PRECREATE")
                .codeUrl(qrCode)
                .build();
    }

    @Override
    public PaymentChannelDTOs.ChannelCallback parseCallback(Map<String, String> headers, String rawBody,
                                                            Map<String, String> formParams) {
        String alipayPublicKey = properties.getAlipay().getAlipayPublicKey();
        ChannelHttpClient.ensureConfigured(alipayPublicKey != null && !alipayPublicKey.isBlank(),
                "支付宝公钥未配置");
        if (formParams == null || formParams.isEmpty()) {
            throw new BusinessException(400, "支付宝异步通知缺少表单参数");
        }
        String sign = formParams.get("sign");
        if (sign == null || sign.isBlank()) {
            throw new BusinessException(401, "支付宝异步通知缺少签名");
        }
        String content = signContent(formParams);
        PublicKey publicKey = PemKeySupport.publicKey(alipayPublicKey);
        if (!PemKeySupport.verify(content, sign, publicKey)) {
            throw new BusinessException(401, "支付宝异步通知验签失败");
        }
        String expectedAppId = properties.getAlipay().getAppId();
        String appId = formParams.get("app_id");
        if (expectedAppId != null && !expectedAppId.isBlank() && !expectedAppId.equals(appId)) {
            throw new BusinessException(401, "支付宝异步通知 app_id 不匹配");
        }
        String tradeStatus = formParams.get("trade_status");
        if (!TRADE_SUCCESS.equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            throw new BusinessException(409, "支付宝交易状态不是成功: " + tradeStatus);
        }
        String orderSn = formParams.get("out_trade_no");
        String payTradeNo = formParams.get("trade_no");
        String sellerId = formParams.get("seller_id");
        String totalAmount = formParams.get("total_amount");
        if (orderSn == null || payTradeNo == null || sellerId == null || totalAmount == null) {
            throw new BusinessException(400, "支付宝异步通知缺少订单号/渠道流水/商户号/金额");
        }
        BigDecimal payAmount;
        try {
            payAmount = new BigDecimal(totalAmount).setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new BusinessException(400, "支付宝异步通知金额格式错误");
        }
        return PaymentChannelDTOs.ChannelCallback.builder()
                .channel(PayChannel.ALIPAY)
                .payTradeNo(payTradeNo)
                .orderSn(orderSn)
                .channelMerchantId(sellerId)
                .payAmount(payAmount)
                .currency("CNY")
                .payerId(formParams.get("buyer_id"))
                .rawPayload(compact(formParams))
                .build();
    }

    @Override
    public PaymentChannelDTOs.ChannelQueryResult queryPayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        requireTradingConfig();
        requireMerchantChannelId(command);
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", command.getOrderSn());
        Map<String, String> params = commonParams("alipay.trade.query", null);
        try {
            params.put("biz_content", objectMapper.writeValueAsString(biz));
        } catch (Exception e) {
            throw new BusinessException(500, "支付宝查单报文生成失败");
        }
        JsonNode node = responseNode(postSigned(params), RESPONSE_QUERY);
        String tradeState = mapTradeState(text(node, "trade_status"));
        BigDecimal amount = null;
        if (node.hasNonNull("total_amount")) {
            amount = new BigDecimal(node.get("total_amount").asText()).setScale(2, RoundingMode.HALF_UP);
        }
        return PaymentChannelDTOs.ChannelQueryResult.builder()
                .channel(PayChannel.ALIPAY)
                .orderSn(command.getOrderSn())
                .payTradeNo(text(node, "trade_no"))
                .tradeState(tradeState)
                .channelMerchantId(text(node, "seller_id"))
                .payAmount(amount)
                .currency("CNY")
                .payerId(text(node, "buyer_user_id"))
                .rawPayload(node.toString())
                .build();
    }

    @Override
    public void closePayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        requireTradingConfig();
        requireMerchantChannelId(command);
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", command.getOrderSn());
        Map<String, String> params = commonParams("alipay.trade.close", null);
        try {
            params.put("biz_content", objectMapper.writeValueAsString(biz));
        } catch (Exception e) {
            throw new BusinessException(500, "支付宝关单报文生成失败");
        }
        JsonNode node = responseNode(postSigned(params), RESPONSE_CLOSE);
        String code = text(node, "code");
        // 交易不存在视为已关闭；其余非成功码仍失败。
        if (!"10000".equals(code) && !"ACQ.TRADE_NOT_EXIST".equals(code)) {
            throw new BusinessException(502, "支付宝关单失败: " + code);
        }
    }

    @Override
    public PaymentChannelDTOs.RefundResult refundPayment(PaymentChannelDTOs.RefundCommand command) {
        requireTradingConfig();
        requireRefundMerchantChannelId(command);
        Map<String, Object> biz = new LinkedHashMap<>();
        if (command.getChannelTradeNo() != null && !command.getChannelTradeNo().isBlank()) {
            biz.put("trade_no", command.getChannelTradeNo());
        } else {
            biz.put("out_trade_no", command.getOrderSn());
        }
        biz.put("refund_amount", command.getRefundAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        biz.put("out_request_no", command.getRefundNo());
        if (command.getReason() != null && !command.getReason().isBlank()) {
            biz.put("refund_reason", truncate(command.getReason(), 256));
        }
        Map<String, String> params = commonParams("alipay.trade.refund", null);
        try {
            params.put("biz_content", objectMapper.writeValueAsString(biz));
        } catch (Exception e) {
            throw new BusinessException(500, "支付宝退款报文生成失败");
        }
        JsonNode node = responseNode(postSigned(params), "alipay_trade_refund_response");
        BigDecimal refundFee = node.hasNonNull("refund_fee")
                ? new BigDecimal(node.get("refund_fee").asText()).setScale(2, RoundingMode.HALF_UP) : null;
        return PaymentChannelDTOs.RefundResult.builder()
                .channel(PayChannel.ALIPAY)
                .refundNo(command.getRefundNo())
                .channelRefundNo(text(node, "trade_no"))
                .status("SUCCESS") // 支付宝退款接口同步返回成功即已受理并完成资金变更
                .refundAmount(refundFee)
                .rawPayload(node.toString())
                .build();
    }

    @Override
    public PaymentChannelDTOs.RefundResult queryRefund(PaymentChannelDTOs.RefundCommand command) {
        requireTradingConfig();
        requireRefundMerchantChannelId(command);
        Map<String, Object> biz = new LinkedHashMap<>();
        if (command.getChannelTradeNo() != null && !command.getChannelTradeNo().isBlank()) {
            biz.put("trade_no", command.getChannelTradeNo());
        } else {
            biz.put("out_trade_no", command.getOrderSn());
        }
        biz.put("out_request_no", command.getRefundNo());
        Map<String, String> params = commonParams("alipay.trade.fastpay.refund.query", null);
        try {
            params.put("biz_content", objectMapper.writeValueAsString(biz));
        } catch (Exception e) {
            throw new BusinessException(500, "支付宝退款查单报文生成失败");
        }
        JsonNode node = responseNode(postSigned(params), "alipay_trade_fastpay_refund_query_response");
        String refundStatus = text(node, "refund_status");
        String status = "REFUND_SUCCESS".equals(refundStatus) ? "SUCCESS" : "PROCESSING";
        return PaymentChannelDTOs.RefundResult.builder()
                .channel(PayChannel.ALIPAY)
                .refundNo(command.getRefundNo())
                .channelRefundNo(text(node, "trade_no"))
                .status(status)
                .refundAmount(node.hasNonNull("refund_amount")
                        ? new BigDecimal(node.get("refund_amount").asText()).setScale(2, RoundingMode.HALF_UP) : null)
                .rawPayload(node.toString())
                .build();
    }

    private void requireRefundMerchantChannelId(PaymentChannelDTOs.RefundCommand command) {
        String sellerId = command.getMerchantChannelId();
        if (sellerId == null || sellerId.isBlank()) {
            throw new BusinessException(409, "商家未登记支付宝收款账号，无法退款");
        }
    }

    private Map<String, String> commonParams(String method, String notifyUrl) {
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", properties.getAlipay().getAppId());
        params.put("method", method);
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", ZonedDateTime.now(ZoneId.systemDefault()).format(TIMESTAMP));
        params.put("version", "1.0");
        if (notifyUrl != null) {
            params.put("notify_url", notifyUrl);
        }
        return params;
    }

    private String postSigned(Map<String, String> params) {
        PrivateKey privateKey = PemKeySupport.privateKey(properties.getAlipay().getPrivateKey());
        Map<String, String> signed = new TreeMap<>(params);
        String content = joinParams(signed);
        String sign = PemKeySupport.sign(content, privateKey);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        signed.forEach(form::add);
        form.add("sign", sign);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        try {
            String response = restTemplate.postForObject(properties.getAlipay().getGatewayUrl(),
                    new HttpEntity<>(form, headers), String.class);
            return response == null ? "{}" : response;
        } catch (RestClientException e) {
            ChannelHttpClient.handleRestFailure("支付宝", e);
            return null;
        }
    }

    private JsonNode responseNode(String response, String rootName) {
        if (response == null || response.isBlank()) {
            throw new BusinessException(502, "支付宝渠道返回空响应");
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode node = root.get(rootName);
            if (node == null) {
                throw new BusinessException(502, "支付宝响应缺少 " + rootName);
            }
            String code = text(node, "code");
            if (!"10000".equals(code)) {
                throw new BusinessException(502, "支付宝渠道返回失败: " + code + " " + text(node, "msg"));
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "支付宝响应解析失败");
        }
    }

    private void requireTradingConfig() {
        ChannelHttpClient.ensureConfigured(enabled(), "支付宝渠道未启用");
        PaymentChannelProperties.Alipay cfg = properties.getAlipay();
        ChannelHttpClient.ensureConfigured(cfg.getAppId() != null && !cfg.getAppId().isBlank(),
                "支付宝 AppId 未配置");
        ChannelHttpClient.ensureConfigured(cfg.getPrivateKey() != null && !cfg.getPrivateKey().isBlank(),
                "支付宝应用私钥未配置");
    }

    private void requireMerchantChannelId(PaymentChannelDTOs.PaymentCreateCommand command) {
        String sellerId = command.getMerchantChannelId();
        if (sellerId == null || sellerId.isBlank()) {
            throw new BusinessException(409, "商家未登记支付宝收款账号，无法发起支付");
        }
    }

    private static String signContent(Map<String, String> params) {
        Map<String, String> filtered = new TreeMap<>();
        params.forEach((key, value) -> {
            if ("sign".equals(key) || "sign_type".equals(key) || value == null || value.isEmpty()) {
                return;
            }
            filtered.put(key, value);
        });
        return joinParams(filtered);
    }

    private static String joinParams(Map<String, String> sorted) {
        List<String> pairs = new ArrayList<>();
        sorted.forEach((key, value) -> pairs.add(key + "=" + value));
        return String.join("&", pairs);
    }

    private static String compact(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        new TreeMap<>(params).forEach((key, value) -> {
            if ("sign".equals(key)) return;
            if (builder.length() > 0) builder.append('&');
            builder.append(key).append('=').append(value);
        });
        return builder.toString();
    }

    private String mapTradeState(String state) {
        if (state == null) return "UNKNOWN";
        return switch (state) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> "SUCCESS";
            case "TRADE_CLOSED" -> "CLOSED";
            case "WAIT_BUYER_PAY" -> "PENDING";
            default -> "FAILED";
        };
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) return "订单支付";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
