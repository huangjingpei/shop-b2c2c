/*
 * 文件：ReconciliationBatchServiceTest.java
 * 用途：T+1 跑批幂等、失败续跑、PARTIAL/FAILED 转人工、接管防覆盖与单轮自动重试的契约用例。
 * 维护注意：
 *  1. 全部 Mockito 隔离，不启动 Spring、不连接数据库、不调用真实渠道。
 *  2. 完成回写断言 finishRun 的条件参数，不用 updateById；转人工计数只在 markManual 实际命中时增加。
 *  3. 自动重试用例覆盖“多业务日回看只重试一轮”“今天已重试跳过”“本次新建跳过”“异常落库可见”。
 *  4. 真实 MySQL 唯一键冲突、并发接管与调度触发仍由用户手动验证。
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
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationBatchServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
    private static final long MERCHANT = 1L;
    private static final long OPERATOR = 3L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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
        ReconciliationBatchRun run = run("SUCCESS", 1, DATE, MERCHANT);
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
        ReconciliationBatchRun run = run("FAILED", 1, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        stubFinish(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenReturn(result(2, 1, 0, 0, 0));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("success"));
        assertEquals("SUCCESS", run.getStatus());
        assertEquals(2, run.getAttempts());
        verify(runMapper, never()).insert(any(ReconciliationBatchRun.class));
        verify(runMapper).finishRun(eq(run.getId()), eq(2), any(), eq("SUCCESS"), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void missingChannelStatementsMarksRunPartialAndVisible() {
        singleStore();
        ReconciliationBatchRun run = run("PENDING", 0, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        stubFinish(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenReturn(result(0, 3, 0, 0, 0));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("partial"));
        assertEquals("PARTIAL", run.getStatus());
        assertTrue(run.getLastError().contains("渠道流水未导入"));
    }

    @Test
    void partiallyImportedMultiChannelMarksRunPartialInsteadOfSuccess() {
        singleStore();
        ReconciliationBatchRun run = run("PENDING", 0, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        stubFinish(1);
        Map<String, Object> partial = result(1, 3, 1, 0, 0);
        // 总流水数不为 0，但 ALIPAY 渠道有本地单据且当日未导入流水。
        partial.put("incompleteChannels", "ALIPAY");
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenReturn(partial);

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("partial"));
        assertEquals("PARTIAL", run.getStatus());
        assertTrue(run.getLastError().contains("ALIPAY"));
        assertTrue(run.getLastError().contains("未完整导入"));
    }

    @Test
    void noMissingChannelsResultStaysSuccessEvenWithLocalDocs() {
        singleStore();
        ReconciliationBatchRun run = run("PENDING", 0, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        stubFinish(1);
        // 本地事件/退款存在且当期流水已导入，差集为空（空串渠道不计入），不得误标 PARTIAL。
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenReturn(result(1, 2, 1, 0, 0));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("success"));
        assertEquals(0, outcome.get("partial"));
        assertEquals("SUCCESS", run.getStatus());
    }

    @Test
    void reconcileFailureBelowLimitStaysFailedForRetry() {
        singleStore();
        ReconciliationBatchRun run = run("PENDING", 0, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        stubFinish(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenThrow(new RuntimeException("db down"));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("failed"));
        assertEquals("FAILED", run.getStatus());
        assertEquals(1, run.getAttempts());
        assertTrue(run.getLastError().contains("db down"));
        verify(runMapper).finishRun(eq(run.getId()), eq(1), any(), eq("FAILED"), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void reconcileFailureAtMaxAttemptsEscalatesToManual() {
        singleStore();
        ReconciliationBatchRun run = run("FAILED", ReconciliationBatchService.MAX_RUN_ATTEMPTS - 1, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        stubFinish(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenThrow(new RuntimeException("still down"));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("manual"));
        assertEquals("MANUAL", run.getStatus());
        assertEquals(ReconciliationBatchService.MAX_RUN_ATTEMPTS, run.getAttempts());
        verify(runMapper).finishRun(eq(run.getId()), eq(3), any(), eq("MANUAL"), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void exhaustedFailedRunIsMarkedManualWithoutRunningAgain() {
        singleStore();
        ReconciliationBatchRun run = run("FAILED", ReconciliationBatchService.MAX_RUN_ATTEMPTS, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markManual(any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("manual"));
        verify(runMapper).markManual(eq(run.getId()), any(), any(), any());
        verify(reconciliationService, never()).reconcile(any(), any(), any(), any());
        verify(runMapper, never()).markRunning(any(), anyInt(), any(), any());
    }

    @Test
    void continuousPartialAtAttemptLimitReallyEscalatesToManual() {
        singleStore();
        ReconciliationBatchRun run = run("PARTIAL", ReconciliationBatchService.MAX_RUN_ATTEMPTS, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markManual(any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("manual"));
        assertEquals("MANUAL", run.getStatus());
        verify(runMapper).markManual(eq(run.getId()), any(), any(), any());
        verify(reconciliationService, never()).reconcile(any(), any(), any(), any());
    }

    @Test
    void manualCountOnlyIncreasesWhenMarkManualActuallyUpdates() {
        singleStore();
        ReconciliationBatchRun run = run("PARTIAL", ReconciliationBatchService.MAX_RUN_ATTEMPTS, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        // 并发下已被其他节点改成 SUCCESS/MANUAL，条件更新命中 0 行，不得虚报 manual。
        when(runMapper.markManual(any(), any(), any(), any())).thenReturn(0);

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(0, outcome.get("manual"));
        assertEquals(1, outcome.get("skipped"));
    }

    @Test
    void manualRunIsNotRetriedAutomatically() {
        singleStore();
        ReconciliationBatchRun run = run("MANUAL", ReconciliationBatchService.MAX_RUN_ATTEMPTS, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("manual"));
        verify(reconciliationService, never()).reconcile(any(), any(), any(), any());
        verify(runMapper, never()).markRunning(any(), anyInt(), any(), any());
    }

    @Test
    void oneStoreFailureDoesNotBlockOtherStores() {
        when(storeMapper.selectList(isNull())).thenReturn(List.of(store(1L), store(2L)));
        ReconciliationBatchRun first = run("PENDING", 0, DATE, 1L);
        ReconciliationBatchRun second = run("PENDING", 0, DATE, 2L);
        when(runMapper.findByDateAndMerchant(DATE, 1L)).thenReturn(Optional.of(first));
        when(runMapper.findByDateAndMerchant(DATE, 2L)).thenReturn(Optional.of(second));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        stubFinish(1);
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
    void takeoverWithoutOwnershipDoesNotCountAsSuccess() {
        singleStore();
        ReconciliationBatchRun run = run("PENDING", 0, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        // 回写条件未命中：started_at/attempts 已被接管方改写。
        stubFinish(0);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenReturn(result(2, 1, 0, 1, 0));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(0, outcome.get("success"));
        assertEquals(1, outcome.get("skipped"));
        assertEquals(1, outcome.get("differencesAdded"));
        assertEquals("RUNNING", run.getStatus());
    }

    @Test
    void autoRetryRunsOnlyOnceAcrossMultiDateLookback() {
        singleStore();
        ReconciliationBatchRun day1 = run("PENDING", 0, DATE.minusDays(2), MERCHANT);
        ReconciliationBatchRun day2 = run("PENDING", 0, DATE.minusDays(1), MERCHANT);
        ReconciliationBatchRun day3 = run("PENDING", 0, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE.minusDays(2), MERCHANT)).thenReturn(Optional.of(day1));
        when(runMapper.findByDateAndMerchant(DATE.minusDays(1), MERCHANT)).thenReturn(Optional.of(day2));
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(day3));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        stubFinish(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenReturn(result(1, 1, 0, 0, 0));
        ReconciliationDifference old = difference(10L, DATE.minusDays(3), 0, "DISPUTED");
        when(differenceMapper.findOpenByMerchant(MERCHANT, 200)).thenReturn(List.of(old));
        ReconciliationDifference retried = difference(10L, DATE.minusDays(3), 1, "RETRY");
        when(reconciliationService.retry(eq(10L), eq(OPERATOR), any())).thenReturn(retried);

        Map<String, Object> outcome = service().runRange(DATE.minusDays(2), DATE, OPERATOR, null);

        assertEquals(3, outcome.get("runs"));
        assertEquals(1, outcome.get("retryProcessed"));
        verify(reconciliationService, times(1)).retry(eq(10L), eq(OPERATOR), any());
        // success_at 巡检按商户每轮只统计一次，不随 3 个业务日重复统计。
        verify(reconciliationService, times(1)).countSuccessWithoutSuccessAt(MERCHANT);
        verify(runMapper).addRetryTally(eq(day3.getId()), eq(1), eq(0), eq(0), any());
    }

    @Test
    void autoRetrySkipsAlreadyRetriedTodayAndDifferencesCreatedDuringRun() {
        singleStore();
        ReconciliationBatchRun run = run("PENDING", 0, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        stubFinish(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenReturn(result(1, 1, 0, 0, 0));
        LocalDate today = LocalDate.now(ZONE);
        ReconciliationDifference already = difference(10L, DATE, 1, "RETRY");
        already.setLastRetryDate(today);
        ReconciliationDifference createdNow = difference(11L, DATE, 0, "DISPUTED");
        createdNow.setCreatedAt(LocalDateTime.now(ZONE).plusMinutes(1));
        when(differenceMapper.findOpenByMerchant(MERCHANT, 200)).thenReturn(List.of(already, createdNow));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(0, outcome.get("retryProcessed"));
        verify(reconciliationService, never()).retry(any(), any(), any());
    }

    @Test
    void autoRetryFailureIsWrittenToRunAndSummary() {
        singleStore();
        ReconciliationBatchRun run = run("PENDING", 0, DATE, MERCHANT);
        when(runMapper.findByDateAndMerchant(DATE, MERCHANT)).thenReturn(Optional.of(run));
        when(runMapper.markRunning(any(), anyInt(), any(), any())).thenReturn(1);
        stubFinish(1);
        when(reconciliationService.reconcile(eq(MERCHANT), any(), any(), eq(OPERATOR)))
                .thenReturn(result(1, 1, 0, 0, 0));
        ReconciliationDifference old = difference(10L, DATE.minusDays(1), 0, "DISPUTED");
        when(differenceMapper.findOpenByMerchant(MERCHANT, 200)).thenReturn(List.of(old));
        when(reconciliationService.retry(any(), any(), any()))
                .thenThrow(new RuntimeException("retry db error"));

        Map<String, Object> outcome = service().runRange(DATE, DATE, OPERATOR, null);

        assertEquals(1, outcome.get("retryFailed"));
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(runMapper).addRetryTally(eq(run.getId()), eq(0), eq(1), eq(0), error.capture());
        assertNotNull(error.getValue());
        assertTrue(error.getValue().contains("retry db error"));
    }

    private void singleStore() {
        when(storeMapper.selectList(isNull())).thenReturn(List.of(store(MERCHANT)));
    }

    private void stubFinish(int result) {
        when(runMapper.finishRun(any(), anyInt(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt(), any(), any())).thenReturn(result);
    }

    private MerchantStore store(long id) {
        MerchantStore store = new MerchantStore();
        store.setId(id);
        return store;
    }

    private ReconciliationBatchRun run(String status, int attempts, LocalDate date, long merchantId) {
        ReconciliationBatchRun run = new ReconciliationBatchRun();
        // 同一店铺不同业务日必须是不同记录；用 epochDay 生成稳定且唯一的测试 ID。
        run.setId(20L + merchantId + date.toEpochDay() * 10);
        run.setBusinessDate(date);
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
        result.put("incompleteChannels", "");
        result.put("businessDate", DATE.toString());
        return result;
    }
}
