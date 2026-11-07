/*
 * 文件：SaasFeeBillJob.java
 * 用途：F10 SaaS 月费账单定时任务：每月为全部店铺生成上一自然月账单，每日扫描逾期账单。
 * 维护注意：
 *  1. 单店生成失败不阻断其他店铺，失败只记录日志；幂等由同店同月唯一键与 Service 保证。
 *  2. 逾期扫描只改账单状态并发站内信，不自动停店/停服、不改 merchant_store.fee_status。
 *  3. 调度开关沿用 ReservationExpiryJob 的 @EnableScheduling；测试环境不等待调度触发，手工入口走超管接口。
 *  4. 本任务与交易抽佣结算 SettlementBatchJob 分离，互不读取对方数据。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.service.SaasFeeBillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class SaasFeeBillJob {

    /** 定时任务制单人标识；0 仅代表系统任务，真实后台账号 ID 为正数。 */
    static final long SYSTEM_OPERATOR_ID = 0L;

    private final MerchantStoreMapper storeMapper;
    private final SaasFeeBillService saasFeeBillService;

    @Scheduled(cron = "${shop.saas-fee.monthly-cron:0 10 1 * * ?}", zone = "Asia/Shanghai")
    public void generatePreviousMonth() {
        YearMonth previous = YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1);
        for (MerchantStore store : storeMapper.selectList(null)) {
            try {
                saasFeeBillService.generateForMerchant(store.getId(), previous, SYSTEM_OPERATOR_ID);
            } catch (Exception e) {
                log.warn("SaaS 月费账单生成失败 merchantId={} month={}: {}", store.getId(), previous, e.getMessage());
            }
        }
    }

    @Scheduled(cron = "${shop.saas-fee.overdue-cron:0 10 2 * * ?}", zone = "Asia/Shanghai")
    public void scanOverdue() {
        try {
            int count = saasFeeBillService.markOverdue(null);
            if (count > 0) {
                log.info("SaaS 月费账单逾期置位 count={}", count);
            }
        } catch (Exception e) {
            log.warn("SaaS 月费账单逾期扫描失败: {}", e.getMessage());
        }
    }
}
