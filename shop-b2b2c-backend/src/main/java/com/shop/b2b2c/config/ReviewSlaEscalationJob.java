/*
 * 文件：ReviewSlaEscalationJob.java
 * 用途：O8 审核SLA超时升级定时任务：提交待审24h提醒、48h升级，初审滞留24h提醒复核人。
 * 维护注意：
 * 1. 幂等靠 merchant_notification_outbox 四元组唯一键（biz_id=申请ID + 各档模板码区分档位），
 *    每档每单只发一次；任务可安全重扫，不会重复通知。
 * 2. 档位语义：REVIEW_SLA_REMINDER=提交超 slaHours 未开始初审；REVIEW_SLA_ESCALATION=超 2*slaHours；
 *    REVIEW_SLA_REVIEWER=初审开始超 slaHours 未出结论（以 START_REVIEW 审计计时）。
 * 3. 收件人为 ROLE:SUPERADMIN（运营/审核站内）；升级不自动改单状态，只通知，处置仍由人工完成。
 * 4. 单批上限 scanBatchSize 防表膨胀时长时间占用；异常不向上抛，避免影响调度线程后续触发。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.entity.MerchantOnboardingApplication;
import com.shop.b2b2c.mapper.MerchantOnboardingApplicationMapper;
import com.shop.b2b2c.service.MerchantNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class ReviewSlaEscalationJob {

    /** SLA 提醒模板：提交超 slaHours 未开始初审。 */
    public static final String TEMPLATE_SLA_REMINDER = "REVIEW_SLA_REMINDER";
    /** SLA 升级模板：提交超 2*slaHours 未开始初审。 */
    public static final String TEMPLATE_SLA_ESCALATION = "REVIEW_SLA_ESCALATION";
    /** 初审滞留提醒模板：初审开始超 slaHours 未出结论。 */
    public static final String TEMPLATE_SLA_REVIEWER = "REVIEW_SLA_REVIEWER";

    private final MerchantOnboardingApplicationMapper applicationMapper;
    private final MerchantNotificationService notificationService;
    private final int reviewSlaHours;
    private final int scanBatchSize;

    public ReviewSlaEscalationJob(MerchantOnboardingApplicationMapper applicationMapper,
                                  MerchantNotificationService notificationService,
                                  @Value("${shop.onboarding.review-sla-hours:24}") int reviewSlaHours,
                                  @Value("${shop.onboarding.review-sla-scan-size:200}") int scanBatchSize) {
        this.applicationMapper = applicationMapper;
        this.notificationService = notificationService;
        this.reviewSlaHours = reviewSlaHours;
        this.scanBatchSize = scanBatchSize;
    }

    @Scheduled(cron = "${shop.onboarding.review-sla-cron:0 0/30 * * * *}")
    public void run() {
        try {
            ScanResult result = scan(LocalDateTime.now());
            if (result.total() > 0) {
                log.info("开店审核SLA扫描完成 result={}", result);
            }
        } catch (Exception e) {
            // 通知落库失败等异常只记录日志：不阻塞调度线程，下一轮按唯一键幂等补发。
            log.warn("开店审核SLA扫描失败: {}", e.getMessage());
        }
    }

    /** 可注入时间的扫描实现，便于单元测试；返回各档新写入的通知条数。 */
    public ScanResult scan(LocalDateTime now) {
        int reminded = escalateSubmitted(now, TEMPLATE_SLA_REMINDER, reviewSlaHours);
        int escalated = escalateSubmitted(now, TEMPLATE_SLA_ESCALATION, reviewSlaHours * 2);
        int reviewerReminded = remindStalePrimaryReview(now);
        return new ScanResult(reminded, escalated, reviewerReminded);
    }

    /** 提交待审超时：SUBMITTED 且 submitted_at 超 hours；biz_id=申请ID，档位模板区分提醒/升级。 */
    private int escalateSubmitted(LocalDateTime now, String templateCode, int hours) {
        List<MerchantOnboardingApplication> overdue =
                applicationMapper.findSubmittedBefore(now.minusHours(hours), scanBatchSize);
        int written = 0;
        for (MerchantOnboardingApplication app : overdue) {
            long waitingHours = app.getSubmittedAt() == null ? hours
                    : Duration.between(app.getSubmittedAt(), now).toHours();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("applicationId", app.getId());
            payload.put("storeName", app.getStoreName());
            payload.put("level", templateCode);
            payload.put("waitingHours", waitingHours);
            payload.put("tip", TEMPLATE_SLA_ESCALATION.equals(templateCode)
                    ? "入驻申请提交超 " + (reviewSlaHours * 2) + " 小时未开始初审，请升级处理"
                    : "入驻申请提交超 " + reviewSlaHours + " 小时未开始初审，请尽快处理");
            if (notificationService.notify(MerchantNotificationService.BIZ_REVIEW_SLA_BREACH, app.getId(),
                    MerchantNotificationService.CHANNEL_INAPP, templateCode,
                    MerchantNotificationService.roleRecipient("SUPERADMIN"), payload)) {
                written++;
            }
        }
        return written;
    }

    /** 初审滞留提醒：REVIEWING+PRIMARY 且 START_REVIEW 审计超 slaHours；提醒复核人（运营角色收件）。 */
    private int remindStalePrimaryReview(LocalDateTime now) {
        List<MerchantOnboardingApplication> stale =
                applicationMapper.findPrimaryReviewStale(now.minusHours(reviewSlaHours), scanBatchSize);
        int written = 0;
        for (MerchantOnboardingApplication app : stale) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("applicationId", app.getId());
            payload.put("storeName", app.getStoreName());
            payload.put("level", TEMPLATE_SLA_REVIEWER);
            payload.put("firstReviewerId", app.getFirstReviewerId() == null ? "" : String.valueOf(app.getFirstReviewerId()));
            payload.put("tip", "初审开始超 " + reviewSlaHours + " 小时未出结论，请复核人尽快跟进");
            if (notificationService.notify(MerchantNotificationService.BIZ_REVIEW_SLA_BREACH, app.getId(),
                    MerchantNotificationService.CHANNEL_INAPP, TEMPLATE_SLA_REVIEWER,
                    MerchantNotificationService.roleRecipient("SUPERADMIN"), payload)) {
                written++;
            }
        }
        return written;
    }

    /** 各档新写入通知条数（幂等跳过不计入）。 */
    public record ScanResult(int reminded, int escalated, int reviewerReminded) {
        public int total() {
            return reminded + escalated + reviewerReminded;
        }
    }
}
