/*
 * 文件：BillingStatementJobTest.java
 * 用途：F09 账单定时任务单店失败隔离与全店遍历的契约用例。
 * 维护注意：全部 Mockito 隔离，不启动 Spring；调度 cron 由 Spring 框架保证，单测只验证业务逻辑（每店 generate、单店失败不中断）。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.config.BillingStatementJob;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.service.BillingStatementService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingStatementJobTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BillingStatementService billingService = mock(BillingStatementService.class);
    private final MerchantStoreMapper stores = mock(MerchantStoreMapper.class);

    @Test
    void oneStoreFailureDoesNotBlockOthers() {
        when(stores.selectList(null)).thenReturn(List.of(store(1L), store(2L), store(3L)));
        // 第一店抛异常，其余正常。
        doThrow(new RuntimeException("store 1 down"))
                .when(billingService).generate(eq(1L), any(), any(), any());

        BillingStatementJob job = new BillingStatementJob(billingService, stores);
        job.generateYesterdayDaily();

        // 三店都尝试生成，失败不中断后续店铺。
        verify(billingService, times(3)).generate(any(), any(), any(), any());
        verify(billingService).generate(eq(1L), eq(LocalDate.now(ZONE).minusDays(1)),
                eq(BillingStatementService.PERIOD_DAILY), eq(null));
        verify(billingService).generate(eq(2L), eq(LocalDate.now(ZONE).minusDays(1)),
                eq(BillingStatementService.PERIOD_DAILY), eq(null));
        verify(billingService).generate(eq(3L), eq(LocalDate.now(ZONE).minusDays(1)),
                eq(BillingStatementService.PERIOD_DAILY), eq(null));
    }

    @Test
    void monthlyJobGeneratesLastMonthFirstDay() {
        when(stores.selectList(null)).thenReturn(List.of(store(1L)));

        BillingStatementJob job = new BillingStatementJob(billingService, stores);
        job.generateLastMonth();

        verify(billingService).generate(eq(1L),
                eq(YearMonth.now(ZONE).minusMonths(1).atDay(1)),
                eq(BillingStatementService.PERIOD_MONTHLY), eq(null));
    }

    private MerchantStore store(long id) {
        MerchantStore store = new MerchantStore();
        store.setId(id);
        return store;
    }
}
