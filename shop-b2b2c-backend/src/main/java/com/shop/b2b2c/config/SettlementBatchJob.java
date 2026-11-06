/*
 * 文件：SettlementBatchJob.java
 * 用途：每月自动为全部店铺生成上一自然月的服务费结算草稿（T34）。
 * 维护注意：
 *  1. 仅生成 DRAFT 草稿，确认必须由管理员人工执行；已确认批次会被 SettlementService 拒绝重算。
 *  2. 单店失败不阻断其他店铺；失败只记录日志，不吞掉异常类型以外的信息。
 *  3. 测试环境无调度触发；手工补算走管理员接口。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementBatchJob {

    private final MerchantStoreMapper storeMapper;
    private final SettlementService settlementService;

    @Scheduled(cron = "${shop.settlement.monthly-cron:0 20 2 1 * ?}")
    public void generatePreviousMonth() {
        YearMonth previous = YearMonth.now().minusMonths(1);
        List<MerchantStore> stores = storeMapper.selectList(null);
        for (MerchantStore store : stores) {
            try {
                settlementService.generateMonthly(store.getId(), previous);
            } catch (Exception e) {
                log.warn("结算草稿生成失败 merchantId={} period={}: {}", store.getId(), previous, e.getMessage());
            }
        }
    }
}
