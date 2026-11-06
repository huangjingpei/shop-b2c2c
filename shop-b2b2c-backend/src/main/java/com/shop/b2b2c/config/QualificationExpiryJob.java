/*
 * 文件：QualificationExpiryJob.java
 * 用途：O9 资质有效期定时任务：到期前 60/30/7 天站内+可选短信提醒；过期超 30 天未更新的在营店铺自动冻结。
 * 维护注意：
 * 1. 数据源是 merchant_store.id_card_expiry/license_expiry（审核通过时的快照，商家更新有效期也写这两列）；
 *    申请单上的填报值不参与本任务。有效期为空表示未采集/长期，不提醒、不冻结。
 * 2. 提醒幂等：merchant_notification_outbox 四元组唯一键（biz_type=LICENSE_EXPIRING, biz_id=店铺ID, channel,
 *    template_code=LICENSE_EXPIRING_ID_CARD_D{N}/LICENSE_EXPIRING_LICENSE_D{N}），同一资质同一节点只发一次；
 *    任务可安全重扫，DuplicateKeyException 由 MerchantNotificationService 归一为 false 跳过。
 * 3. 冻结走 MerchantStoreStatusService.applyAction(FREEZE, EXPIRED_LICENSE)，operatorId=null 表示系统任务；
 *    每次调用独立事务，单店失败（如状态已变化 409）只记日志继续，不回滚整批。
 *    冻结后退款/售后不受影响（O6 状态机口径），解冻需商家更新资质后人工审核。
 * 4. “到期当天限制上新”不在本任务落标记：由商品创建路径按 expiry <= 当天 实时判定，商家更新有效期后即时恢复。
 * 5. 每天 09:00（Asia/Shanghai）触发；cron 可配 shop.qualification.expiry-cron，zone 固定 Asia/Shanghai。
 * 6. run 不向上抛异常，避免影响调度线程；scan 可注入日期便于单元测试。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.service.MerchantNotificationService;
import com.shop.b2b2c.service.MerchantStoreStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class QualificationExpiryJob {

    /** 提醒节点：到期前 60/30/7 天。 */
    public static final List<Integer> REMINDER_DAYS = List.of(60, 30, 7);
    /** 逾期宽限天数：到期超过 30 天未更新自动冻结。 */
    public static final int FREEZE_GRACE_DAYS = 30;

    /** 资质类型：身份证。 */
    public static final String QUALIFICATION_ID_CARD = "ID_CARD";
    /** 资质类型：营业执照。 */
    public static final String QUALIFICATION_LICENSE = "LICENSE";

    private final MerchantStoreMapper storeMapper;
    private final MerchantNotificationService notificationService;
    private final MerchantStoreStatusService storeStatusService;
    private final int scanBatchSize;

    public QualificationExpiryJob(MerchantStoreMapper storeMapper,
                                  MerchantNotificationService notificationService,
                                  MerchantStoreStatusService storeStatusService,
                                  @Value("${shop.qualification.expiry-scan-size:200}") int scanBatchSize) {
        this.storeMapper = storeMapper;
        this.notificationService = notificationService;
        this.storeStatusService = storeStatusService;
        this.scanBatchSize = scanBatchSize;
    }

    @Scheduled(cron = "${shop.qualification.expiry-cron:0 0 9 * * *}", zone = "Asia/Shanghai")
    public void run() {
        try {
            ScanResult result = scan(LocalDate.now());
            if (result.total() > 0) {
                log.info("资质有效期扫描完成 result={}", result);
            }
        } catch (Exception e) {
            // 扫描异常只记录日志：不阻塞调度线程，下一轮按唯一键幂等补发。
            log.warn("资质有效期扫描失败: {}", e.getMessage());
        }
    }

    /** 可注入日期的扫描实现，便于单元测试；返回新写入提醒条数与成功冻结店铺数。 */
    public ScanResult scan(LocalDate today) {
        int reminded = 0;
        for (int days : REMINDER_DAYS) {
            reminded += remindExpiringOn(today, days);
        }
        int frozen = freezeExpiredBeyondGrace(today);
        return new ScanResult(reminded, frozen);
    }

    /** 到期前 N 天提醒：找 expiry=今天+N 的店铺，逐资质发 LICENSE_EXPIRING（站内+可选短信）。 */
    private int remindExpiringOn(LocalDate today, int days) {
        LocalDate expiryDate = today.plusDays(days);
        List<MerchantStore> stores = storeMapper.findExpiringOn(expiryDate, scanBatchSize);
        int written = 0;
        for (MerchantStore store : stores) {
            if (expiryDate.equals(store.getIdCardExpiry())) {
                written += notifyExpiring(store, QUALIFICATION_ID_CARD, expiryDate, days);
            }
            if (expiryDate.equals(store.getLicenseExpiry())) {
                written += notifyExpiring(store, QUALIFICATION_LICENSE, expiryDate, days);
            }
        }
        return written;
    }

    /** 单店单资质到期提醒；template_code 含资质与档位，uk 兜底“同一资质同一节点只发一次”。 */
    private int notifyExpiring(MerchantStore store, String qualification, LocalDate expiryDate, int days) {
        String templateCode = "LICENSE_EXPIRING_" + qualification + "_D" + days;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", store.getId());
        payload.put("storeName", store.getStoreName());
        payload.put("qualification", qualification);
        payload.put("qualificationName", QUALIFICATION_ID_CARD.equals(qualification) ? "身份证" : "营业执照");
        payload.put("expiryDate", expiryDate.toString());
        payload.put("daysLeft", days);
        payload.put("tip", (QUALIFICATION_ID_CARD.equals(qualification) ? "身份证" : "营业执照")
                + "将于 " + days + " 天后（" + expiryDate + "）到期，请尽快在商家资料页更新有效期");
        return notificationService.notifyAll(MerchantNotificationService.BIZ_LICENSE_EXPIRING, store.getId(),
                templateCode, MerchantNotificationService.merchantRecipient(store.getId()), payload,
                MerchantNotificationService.CHANNEL_INAPP, MerchantNotificationService.CHANNEL_SMS);
    }

    /** 逾期超 30 天未更新：自动冻结营业(1)/打烊(2)店铺；单店失败只记日志，不影响其余店铺。 */
    private int freezeExpiredBeyondGrace(LocalDate today) {
        LocalDate threshold = today.minusDays(FREEZE_GRACE_DAYS);
        List<MerchantStore> stores = storeMapper.findExpiredBeyond(threshold, scanBatchSize);
        int frozen = 0;
        for (MerchantStore store : stores) {
            try {
                storeStatusService.applyAction(store.getId(), MerchantStoreStatusService.ACTION_FREEZE,
                        MerchantStoreStatusService.REASON_EXPIRED_LICENSE,
                        "身份证/营业执照过期超过 " + FREEZE_GRACE_DAYS + " 天未更新，系统自动冻结；更新资质后请联系平台解冻",
                        null, null);
                frozen++;
                log.info("资质过期自动冻结 merchantId={}", store.getId());
            } catch (Exception e) {
                // 409（状态已变化/并发）或其它异常：跳过该店，下一轮按状态条件自然跳过或重试。
                log.warn("资质过期自动冻结跳过 merchantId={}: {}", store.getId(), e.getMessage());
            }
        }
        return frozen;
    }

    /** 扫描结果：新写入提醒条数（幂等跳过不计）与本轮成功冻结店铺数。 */
    public record ScanResult(int reminded, int frozen) {
        public int total() {
            return reminded + frozen;
        }
    }
}
