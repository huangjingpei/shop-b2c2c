/*
 * 文件：MerchantNotificationService.java
 * 用途：O8 通知发件箱写入服务：开店节点/店铺状态变更/审核SLA/证件到期提醒的统一入口。
 * 维护注意：
 * 1. “每节点只发一次”由 uk_notification_event(biz_type,biz_id,channel,template_code) 兜底；
 *    只对 DuplicateKeyException 返回 false（视为已通知），其余异常必须抛出，不得吞掉真实故障。
 * 2. INAPP 落库即送达（SENT）；SMS 通道默认禁用（shop.notification.sms-enabled=false）置 SKIPPED，
 *    即使调用方请求短信也绝不在未接服务商时发送；启用后仅落 PENDING，真实发送器属后续渠道接入。
 * 3. 密码红线：payload 任意键含 password（忽略大小写）直接 fail-fast，防止初始密码/新密码进通知。
 * 4. 调用方必须在业务事务内调用：通知与业务状态同事务提交，避免“状态变了通知丢了”或反之。
 */
package com.shop.b2b2c.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantNotificationOutbox;
import com.shop.b2b2c.mapper.MerchantNotificationOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class MerchantNotificationService {

    // 业务类型（与开发文档 12.1 节点一致；LICENSE_EXPIRING 由 O9 挂载）
    public static final String BIZ_ONBOARDING_SUBMITTED = "ONBOARDING_SUBMITTED";
    public static final String BIZ_ONBOARDING_SUPPLEMENT = "ONBOARDING_SUPPLEMENT";
    public static final String BIZ_ONBOARDING_REJECTED = "ONBOARDING_REJECTED";
    public static final String BIZ_ONBOARDING_APPROVED = "ONBOARDING_APPROVED";
    public static final String BIZ_ONBOARDING_OPENED = "ONBOARDING_OPENED";
    public static final String BIZ_LICENSE_EXPIRING = "LICENSE_EXPIRING";
    public static final String BIZ_REVIEW_SLA_BREACH = "REVIEW_SLA_BREACH";
    public static final String BIZ_STORE_STATUS_CHANGED = "STORE_STATUS_CHANGED";
    // F06 投诉流转：商家收到买家提交/裁决结果，超管收到买家申请介入。
    public static final String BIZ_COMPLAINT_SUBMITTED = "COMPLAINT_SUBMITTED";
    public static final String BIZ_COMPLAINT_ESCALATED = "COMPLAINT_ESCALATED";
    public static final String BIZ_COMPLAINT_DECIDED = "COMPLAINT_DECIDED";

    public static final String CHANNEL_INAPP = "INAPP";
    public static final String CHANNEL_SMS = "SMS";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MerchantNotificationOutboxMapper outboxMapper;
    private final boolean smsEnabled;

    public MerchantNotificationService(MerchantNotificationOutboxMapper outboxMapper,
                                       @Value("${shop.notification.sms-enabled:false}") boolean smsEnabled) {
        this.outboxMapper = outboxMapper;
        this.smsEnabled = smsEnabled;
    }

    /** 商家站内收件人标识：MERCHANT:{店铺ID}。 */
    public static String merchantRecipient(Long merchantId) {
        return "MERCHANT:" + merchantId;
    }

    /** 运营/角色站内收件人标识：ROLE:{角色码}。 */
    public static String roleRecipient(String roleCode) {
        return "ROLE:" + roleCode;
    }

    /**
     * 落一条通知。同一四元组重复调用返回 false（已通知过，幂等跳过）。
     * @return true=本次新写入；false=四元组冲突（重复事件）
     */
    public boolean notify(String bizType, Long bizId, String channel, String templateCode,
                          String recipient, Map<String, Object> payload) {
        if (bizType == null || bizType.isBlank() || bizId == null
                || templateCode == null || templateCode.isBlank()
                || recipient == null || recipient.isBlank()) {
            throw new BusinessException(400, "通知业务类型/事件ID/模板/接收人不能为空");
        }
        String payloadJson = serialize(payload);
        MerchantNotificationOutbox row = new MerchantNotificationOutbox();
        row.setBizType(bizType.trim());
        row.setBizId(bizId);
        row.setTemplateCode(templateCode.trim());
        row.setRecipient(recipient.trim());
        row.setPayloadJson(payloadJson);
        row.setAttempts(0);
        if (CHANNEL_INAPP.equals(channel)) {
            row.setChannel(CHANNEL_INAPP);
            row.setStatus(STATUS_SENT);
            row.setSentAt(LocalDateTime.now());
        } else if (CHANNEL_SMS.equals(channel)) {
            row.setChannel(CHANNEL_SMS);
            if (smsEnabled) {
                // 通道启用后落 PENDING 等真实发送器取走；本期不接服务商，不会有发送器消费。
                row.setStatus(STATUS_PENDING);
            } else {
                row.setStatus(STATUS_SKIPPED);
                row.setLastError("SMS 通道默认禁用（shop.notification.sms-enabled=false）");
            }
        } else {
            throw new BusinessException(400, "不支持的通知渠道: " + channel);
        }
        try {
            outboxMapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            log.info("通知事件已发送过，幂等跳过 bizType={} bizId={} channel={} template={}",
                    row.getBizType(), row.getBizId(), row.getChannel(), row.getTemplateCode());
            return false;
        }
    }

    /** 同一事件多通道投递（如 INAPP+SMS）；返回实际新写入条数。 */
    public int notifyAll(String bizType, Long bizId, String templateCode, String recipient,
                         Map<String, Object> payload, String... channels) {
        int written = 0;
        for (String channel : channels) {
            if (notify(bizType, bizId, channel, templateCode, recipient, payload)) {
                written++;
            }
        }
        return written;
    }

    /** 密码红线 fail-fast + JSON 序列化；payload 只允许字符串/数字/布尔/嵌套同类结构。 */
    private String serialize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        assertNoPasswordKeys(payload);
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BusinessException(500, "通知内容序列化失败");
        }
    }

    private void assertNoPasswordKeys(Map<String, Object> payload) {
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (key.contains("password")) {
                throw new BusinessException(500, "通知内容不得包含密码字段（密码红线）");
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) nested;
                assertNoPasswordKeys(nestedMap);
            }
        }
    }
}
