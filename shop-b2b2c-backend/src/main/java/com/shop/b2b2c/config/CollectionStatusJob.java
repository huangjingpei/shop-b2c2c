/*
 * 文件：CollectionStatusJob.java
 * 用途：每日扫描欠费账户，把已过账期/超额度账户置为停收并写审计（T35）。
 * 维护注意：
 *  1. 只处理新单收款状态，不修改订单、退款、卡密与账本金额。
 *  2. 单批限 200 行；本轮处理不完的由次日任务继续，避免长事务锁表。
 *  3. 测试环境无调度触发；管理员可通过接口手动触发同一服务方法。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.service.MerchantAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectionStatusJob {

    private final MerchantAccountService accountService;

    @Scheduled(cron = "${shop.settlement.arrears-cron:0 5 3 * * ?}")
    public void stopExpiredArrears() {
        try {
            int changed = accountService.evaluateDueArrears(200);
            if (changed > 0) {
                log.info("欠费停收/恢复状态更新 {} 户", changed);
            }
        } catch (Exception e) {
            log.warn("欠费停收巡检失败: {}", e.getMessage());
        }
    }
}
