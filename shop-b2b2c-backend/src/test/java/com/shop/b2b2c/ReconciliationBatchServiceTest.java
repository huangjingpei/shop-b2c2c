/*
 * 文件：ReconciliationBatchServiceTest.java
 * 用途：T+1 跑批幂等、失败续跑、转人工、部分完成与自动重试的契约用例。
 * 维护注意：
 *  1. 全部 Mockito 隔离，不启动 Spring、不连接数据库、不调用真实渠道。
 *  2. 断言重点是状态与计数，不通过“总数为 0”推断对账通过；差异明细由 ReconciliationService 用例覆盖。
 *  3. 真实 MySQL 唯一键冲突、并发拦截与调度触发仍由用户手动验证。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.ReconciliationBatchRun;
import com.shop.b2b2c.entity.ReconciliationDifference;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.ReconciliationBatchRunMapper;
import com.shop.b2b2c.mapper.ReconciliationDifferenceMapper;
import com.shop.b2b2c.service.ReconciliationBatchService;
import com.shop.b2b2c.service.ReconciliationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationBatchServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
    private static final long MERCHANT = 1L;
    private static final long OPERATOR = 3L;

    private final MerchantStoreMapper storeMapper = mock(MerchantStoreMapper.class);
    private final ReconciliationBatchRunMapper runMapper = mock(ReconciliationBatchRunMapper.class);
    private final ReconciliationService reconciliationService = mock(ReconciliationService.class);
    private final ReconciliationDifferenceMapper differenceMapper = mock(ReconciliationDifferenceMapper.class);

    private ReconciliationBatchService service() {
        return new ReconciliationBatchService(storeMapper, runMapper, reconciliationService, differenceMapper);
    }

    @Test
    void sameDaySuccessRunIsSkippedAndDoesNotReconcileAgain() {
        singleStore();
        ReconciliationBatchRun run = run("SUCCESS", 1, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));

        Map<String, Object> result = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, result.get("skipped"));
        verify(reconciliationService, never()).reconcile(any(), any(), any(), any());
        verify(runMapper, never()).insert(any(ReconciliationBatchRun.class));
        verify(runMapper, never()).markRunning(any(), anyInt(), any(), any());
    }

    @Test
    void failedRunIsReusedAndNoSecondRecordIsInserted() {
        singleStore();
        ReconciliationBatchRun run = run("FAILED", 1, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenReturn(result(2, 1, 0, 0, 0));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("success"));
        assertEquals("SUCCESS", run.getStatus());
        assertEquals(2, run.getAttempts());
        verify(runMapper, never()).insert(any(ReconciliationBatchRun.class));
        verify(runMapper).updateById(run);
    }

    @Test
    void missingChannelStatementsMarksRunPartialAndVisible() {
        singleStore();
        ReconciliationBatchRun run = run("PENDING", 0, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenReturn(result(0, 3, 0, 0, 0));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("partial"));
        assertEquals("PARTIAL", run.getStatus());
        assertTrue(run.getLastError().contains("渠道流水未导入"));
    }

    @Test
    void reconcileFailureBelowLimitStaysFailedForRetry() {
        singleStore();
        ReconciliationBatchRun run = run("PENDING", 0, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenThrow(new RuntimeException("db down"));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("failed"));
        assertEquals("FAILED", run.getStatus());
        assertEquals(1, run.getAttempts());
        assertTrue(run.getLastError().contains("db down"));
        verify(runMapper).updateById(run);
    }

    @Test
    void reconcileFailureAtMaxAttemptsEscalatesToManual() {
        singleStore();
        ReconciliationBatchRun run = run("FAILED", ReconciliationBatchService.MAX_RUN_ATTEMPTS - 1, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenThrow(new RuntimeException("still down"));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("manual"));
        assertEquals("MANUAL", run.getStatus());
        assertEquals(ReconciliationBatchService.MAX_RUN_ATTEMPTS, run.getAttempts());
        verify(runMapper).updateById(run);
    }

    @Test
    void exhaustedFailedRunIsMarkedManualWithoutRunningAgain() {
        singleStore();
        ReconciliationBatchRun run = run("FAILED", ReconciliationBatchService.MAX_RUN_ATTEMPTS, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("manual"));
        verify(runMapper).markManual(eq(run.getId()), any(), any(), any());
        verify(reconciliationService, never()).reconcile(any(), any(), any(), any());
        verify(runMapper, never()).markRunning(any(), anyInt(), any(), any());
    }

    @Test
    void manualRunIsNotRetriedAutomatically() {
        singleStore();
        ReconciliationBatchRun run = run("MANUAL", ReconciliationBatchService.MAX_RUN_ATTEMPTS, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("manual"));
        verify(reconciliationService, never()).reconcile(any(), any(), any(), any());
        verify(runMapper, never()).markRunning(any(), anyInt(), any(), any());
    }

    @Test
    void oneStoreFailureDoesNotBlockOtherStores() {
        when(storeMapper.selectList(isNull())).thenReturn(List.of(store(1L), store(2L)));
        ReconciliationBatchRun first = run("PENDING", 0, 1L);
        ReconciliationBatchRun second = run("PENDING", 0, 2L);
        when(runMapper.findByDateAndMerchant(DATE, 1L)).thenReturn(Optional.of(first));
        when(runMapper.findByDateAndMerchant(DATE, 2L)).thenReturn(Optional.of(second));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        when(reconciliationService.reconcile(eq(1L), any(), any(), eq(OPERATOR)))
                .thenThrow(new RuntimeException("store one failed"));
        when(reconciliationService.reconcile(eq(2L), any(), any(), eq(OPERATOR)))
                .thenReturn(result(1, 1, 0, 0, 0));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("failed"));
        assertEquals(1, outcome.get("success"));
        assertEquals("FAILED", first.getStatus());
        assertEquals("SUCCESS", second.getStatus());
    }

    @Test
    void autoRetryProcessesEarlierDifferencesOnceAndSkipsSameDay() {
        singleStore();
        ReconciliationBatchRun run = run("PENDING", 0, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenReturn(result(1, 1, 0, 0, 0));
        ReconciliationDifference older = difference(10L, DATE.minusDays(1), 0, "DISPUTED");
        ReconciliationDifference sameDay = difference(11L, DATE, 0, "DISPUTED");
        when(differenceMapper.findOpenByMerchant(MERCHANT, 200)).thenReturn(List.of(older, sameDay));
        ReconciliationDifference retried = difference(10L, DATE.minusDays(1), 1, "RETRY");
        when(reconciliationService.retry(10L, OPERATOR, DATE)).thenReturn(retried);

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("retryProcessed"));
        assertEquals(1, run.getRetryProcessed());
        verify(reconciliationService, never()).retry(11L, OPERATOR, DATE);
    }

    private void singleStore() {
        when(storeMapper.selectList(isNull())).thenReturn(List.of(store(MERCHANT)));
    }

    private MerchantStore store(long id) {
        MerchantStore store = new MerchantStore();
        store.setId(id);
        return store;
    }

    private ReconciliationBatchRun run(String status, int attempts, long merchantId) {
        ReconciliationBatchRun run = new ReconciliationBatchRun();
        run.setId(20L + merchantId);
        run.setBusinessDate(DATE);
        run.setMerchantId(merchantId);
        run.setStatus(status);
        run.setAttempts(attempts);
        return run;
    }

    private ReconciliationDifference difference(long id, LocalDate businessDate, int retryCount, String status) {
        ReconciliationDifference difference = new ReconciliationDifference();
        difference.setId(id);
        difference.setMerchantId(MERCHANT);
        difference.setBusinessDate(businessDate);
        difference.setRetryCount(retryCount);
        difference.setStatus(status);
        return difference;
    }

    private Map<String, Object> result(int statements, int events, int refunds, int added, int refreshed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", added);
        result.put("refreshed", refreshed);
        result.put("skippedClosed", 0);
        result.put("statements", statements);
        result.put("events", events);
        result.put("refunds", refunds);
        result.put("batches", 0);
        result.put("platformSettlements", 0);
        result.put("businessDate", DATE.toString());
        return result;
    }
}
