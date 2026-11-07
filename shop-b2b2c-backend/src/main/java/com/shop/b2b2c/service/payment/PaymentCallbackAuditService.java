/*
 * 文件：PaymentCallbackAuditService.java
 * 用途：F01 渠道回调持久化审计：计算回调指纹、截断原因摘要并写入 payment_callback_audit。
 * 维护注意：
 *  1. 本类只做留痕，不参与验签、金额核验或履约；写库失败不得改变支付结果，异常由调用方（PaymentGatewayService）兜住。
 *  2. 指纹优先取原始报文；仅有表单参数（支付宝）时按 key 升序拼接并排除 sign/sign_type，与适配器验签口径一致，便于关联同源重试。
 *  3. 不保存回调正文、签名、证书、买家身份或渠道凭据；reason 只写内部异常摘要并在换行/超长时截断。
 *  4. 写入前按列宽截断，避免超长值导致整条审计失败；超宽字段截断不影响支付主流程。
 *  5. 审计表只增不改，本类不提供更新/删除方法；查询与留存清理由运维另定。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.entity.PaymentCallbackAudit;
import com.shop.b2b2c.mapper.PaymentCallbackAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCallbackAuditService {

    public static final String OUTCOME_ACCEPTED = "ACCEPTED";
    public static final String OUTCOME_REJECTED = "REJECTED";
    public static final String OUTCOME_ERROR = "ERROR";

    private static final int MAX_CHANNEL = 32;
    private static final int MAX_ORDER_SN = 64;
    private static final int MAX_TRADE_NO = 128;
    private static final int MAX_CHANNEL_MERCHANT = 64;
    private static final int MAX_OUTCOME = 16;
    private static final int MAX_PROCESSING_RESULT = 32;
    private static final int MAX_REASON = 200;

    private final PaymentCallbackAuditMapper auditMapper;

    /**
     * 计算回调指纹：原始报文优先；否则用排除 sign/sign_type 的升序表单参数。
     * 任何输入（包括 null/空）都返回 64 位十六进制指纹，不抛异常。
     */
    public static String fingerprint(String rawBody, Map<String, String> formParams) {
        String canonical;
        if (rawBody != null && !rawBody.isBlank()) {
            canonical = rawBody;
        } else {
            Map<String, String> sorted = new TreeMap<>();
            if (formParams != null) {
                formParams.forEach((key, value) -> {
                    if (key == null || value == null || value.isEmpty()
                            || "sign".equals(key) || "sign_type".equals(key)) {
                        return;
                    }
                    sorted.put(key, value);
                });
            }
            StringBuilder builder = new StringBuilder();
            sorted.forEach((key, value) -> {
                if (builder.length() > 0) builder.append('&');
                builder.append(key).append('=').append(value);
            });
            canonical = builder.toString();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JRE 必带 SHA-256；此处仅为编译期检查，不把异常抛给支付主流程。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 写入一条回调审计。只插入不更新；调用方须捕获异常，审计失败不得改变支付结果。
     */
    public void record(PaymentCallbackAudit audit) {
        if (audit == null) {
            return;
        }
        audit.setChannel(truncate(audit.getChannel() == null || audit.getChannel().isBlank()
                ? "UNKNOWN" : audit.getChannel().trim().toUpperCase(), MAX_CHANNEL));
        audit.setOrderSn(truncate(audit.getOrderSn(), MAX_ORDER_SN));
        audit.setPayTradeNo(truncate(audit.getPayTradeNo(), MAX_TRADE_NO));
        audit.setChannelMerchantId(truncate(audit.getChannelMerchantId(), MAX_CHANNEL_MERCHANT));
        audit.setOutcome(truncate(audit.getOutcome() == null || audit.getOutcome().isBlank()
                ? OUTCOME_ERROR : audit.getOutcome(), MAX_OUTCOME));
        audit.setProcessingResult(truncate(audit.getProcessingResult(), MAX_PROCESSING_RESULT));
        audit.setReason(truncate(singleLine(audit.getReason()), MAX_REASON));
        audit.setPayloadSha256(audit.getPayloadSha256() == null || audit.getPayloadSha256().isBlank()
                ? fingerprint(null, null) : audit.getPayloadSha256());
        if (audit.getDurationMs() == null || audit.getDurationMs() < 0) {
            audit.setDurationMs(0);
        }
        auditMapper.insert(audit);
    }

    private static String singleLine(String value) {
        if (value == null) {
            return null;
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
