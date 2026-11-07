/*
 * 文件：BillingStatementJob.java
 * 用途：F09 商户账单定时生成：每日凌晨生成上一日 DAILY 账单、每月 1 日凌晨生成上月 MONTHLY 账单。
 * 维护注意：
 *  1. 单店失败只告警并计入失败数，不中断整轮；测试环境没有调度触发，账单正确性以服务层聚合为准。
 *  2. 业务日/月口径与 T+1 对账一致，固定 Asia/Shanghai；账单 upsert 由 uk_shop_period 兜底，重复执行幂等。
 *  3. 当日/当月未结束的账期在生成时标记 PENDING，次日/次月任务重算同键转为 SETTLED。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.service.BillingStatementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingStatementJob {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BillingStatementService billingService;
    private final MerchantStoreMapper stores;

    /** 每日 02:40 生成上一日账单（避开 02:00 结算批次与 T+1 对账窗口）。 */
    @Scheduled(cron = "0 40 2 * * ?")
    public void generateYesterdayDaily() {
        generateForAll(BillingStatementService.PERIOD_DAILY, LocalDate.now(ZONE).minusDays(1));
    }

    /** 每月 1 日 02:50 生成上月账单。 */
    @Scheduled(cron = "0 50 2 1 * ?")
    public void generateLastMonth() {
        generateForAll(BillingStatementService.PERIOD_MONTHLY, YearMonth.now(ZONE).minusMonths(1).atDay(1));
    }

    private void generateForAll(String periodType, LocalDate periodDate) {
        long success = 0, failed = 0;
        for (MerchantStore store : stores.selectList(null)) {
            try {
                billingService.generate(store.getId(), periodDate, periodType, null);
                success++;
            } catch (Exception ex) {
                failed++;
                log.error("商户账单生成失败 shopId={} periodType={} periodDate={}：{}",
                        store.getId(), periodType, periodDate, ex.getMessage());
            }
        }
        log.info("商户账单生成完成 periodType={} periodDate={} 成功={} 失败={}", periodType, periodDate, success, failed);
    }
}
