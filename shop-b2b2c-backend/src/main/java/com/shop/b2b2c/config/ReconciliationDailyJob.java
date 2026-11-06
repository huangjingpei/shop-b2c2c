/*
 * 文件：ReconciliationDailyJob.java
 * 用途：T+1 定时对账入口，默认每天 02:30（Asia/Shanghai）回看最近若干业务日并逐店跑批。
 * 维护注意：
 *  1. 只调用 ReconciliationBatchService；幂等、重试与转人工规则都在服务层，任务本身不重复实现。
 *  2. 回看窗口默认 3 天（shop.reconciliation.lookback-days）；已 SUCCESS/MANUAL 的业务日会被跳过，
 *     PARTIAL/FAILED 可续跑，因此重复触发不会重复入队。
 *  3. 时区固定按属性 shop.reconciliation.zone，默认 Asia/Shanghai；不要用 JVM 默认时区。
 *  4. 本任务不下载渠道账单；渠道流水仍由管理端导入，缺少流水的业务日标记 PARTIAL。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.service.ReconciliationBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationDailyJob {

    private final ReconciliationBatchService batchService;

    @Value("${shop.reconciliation.lookback-days:3}")
    private int lookbackDays;

    @Value("${shop.reconciliation.zone:Asia/Shanghai}")
    private String businessZone;

    @Scheduled(cron = "${shop.reconciliation.daily-cron:0 30 2 * * ?}",
            zone = "${shop.reconciliation.zone:Asia/Shanghai}")
    public void runT1() {
        LocalDate today = LocalDate.now(ZoneId.of(businessZone));
        // 服务层单次最多 31 天；配置超出时按 31 天运行，避免每日任务因参数直接抛 400。
        int days = Math.min(Math.max(lookbackDays, 1), 31);
        LocalDate start = today.minusDays(days);
        LocalDate end = today.minusDays(1);
        try {
            batchService.runRange(start, end, ReconciliationBatchService.SYSTEM_OPERATOR_ID, null);
        } catch (Exception e) {
            log.warn("T+1 对账跑批失败 businessDate={}~{}: {}", start, end, e.getMessage());
        }
    }
}
