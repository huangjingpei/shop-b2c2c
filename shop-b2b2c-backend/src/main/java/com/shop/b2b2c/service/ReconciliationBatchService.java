/*
 * 文件：ReconciliationBatchService.java
 * 用途：T+1 对账跑批：按业务日逐店执行对账、每店每次跑批自动重试一轮旧差异、记录跑批状态与失败原因。
 * 维护注意：
 *  1. 幂等三层：reconciliation_batch_run 唯一键 (business_date, merchant_id)；markRunning 条件抢占；
 *     差异键唯一且已关闭记录不再改写。
 *  2. SUCCESS/MANUAL 不自动重跑；PARTIAL（渠道流水未导入）与 FAILED 在回看窗口内允许重跑。
 *  3. 单店失败不阻断其他店铺；失败原因写入 last_error，达到 MAX_RUN_ATTEMPTS 转 MANUAL，不得静默丢弃。
 *     PARTIAL 也计入尝试次数，连续 PARTIAL 达上限必须真正落 MANUAL。
 *  4. 自动重试每店每次跑批只做一轮，last_retry_date 记实际执行日；不能按回看业务日逐日重复重试。
 *  5. 完成回写必须带 status=RUNNING + attempts + started_at 条件，防止超时被接管后原节点覆盖接管方结果。
 *  6. 自动重试异常数与异常摘要必须落库（retry_failed / last_error），不能只写日志。
 *  7. 业务日与记录时间统一 Asia/Shanghai，窗口 [业务日 00:00, 业务日+1 00:00)。
 *  8. 跑批只写差异与跑批记录，不修改订单、退款、账本、结算单或平台代收台账。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.ReconciliationBatchRun;
import com.shop.b2b2c.entity.ReconciliationDifference;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.ReconciliationBatchRunMapper;
import com.shop.b2b2c.mapper.ReconciliationDifferenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationBatchService {

    /** 同一店铺同一业务日的最大自动尝试次数；达到上限仍失败（含连续 PARTIAL）必须转 MANUAL。 */
    public static final int MAX_RUN_ATTEMPTS = 3;
    public static final long SYSTEM_OPERATOR_ID = 0L;

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration STALE_RUN = Duration.ofHours(2);
    private static final int AUTO_RETRY_LIMIT = 200;
    private static final int MAX_RANGE_DAYS = 31;

    private final MerchantStoreMapper storeMapper;
    private final ReconciliationBatchRunMapper runMapper;
    private final ReconciliationService reconciliationService;
    private final ReconciliationDifferenceMapper differenceMapper;

    /**
     * 按业务日区间逐店跑批。
     *
     * @param start      开始业务日；为空取昨天
     * @param end        结束业务日；为空与 start 相同
     * @param operatorId 人工触发时为管理员 ID，定时任务传 0
     * @param merchantId 指定店铺；为空表示全库逐店
     */
    public Map<String, Object> runRange(LocalDate start, LocalDate end, Long operatorId, Long merchantId) {
        LocalDate rangeStart = start == null ? LocalDate.now(BUSINESS_ZONE).minusDays(1) : start;
        LocalDate rangeEnd = end == null ? rangeStart : end;
        if (rangeEnd.isBefore(rangeStart)) {
            throw new BusinessException(400, "结束业务日不能早于开始业务日");
        }
        if (ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException(400, "单次跑批业务日跨度不得超过" + MAX_RANGE_DAYS + "天");
        }
        Long operator = operatorId == null ? SYSTEM_OPERATOR_ID : operatorId;
        List<Long> merchants = resolveMerchants(merchantId);
        Summary summary = new Summary();
        // 库表 started_at 为 DATETIME（秒级），CAS 回写要求同一精度，否则纳秒会被截断导致条件永不命中。
        LocalDateTime runStartedAt = LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS);
        Map<Long, ReconciliationBatchRun> latestRuns = new LinkedHashMap<>();
        for (LocalDate date = rangeStart; !date.isAfter(rangeEnd); date = date.plusDays(1)) {
            for (Long storeId : merchants) {
                summary.runs++;
                try {
                    ReconciliationBatchRun run = runOne(date, storeId, operator, summary);
                    if (run != null) {
                        // 同一店铺多业务日时，自动重试计数落到区间内最后一天，避免逐日重复重试。
                        latestRuns.put(storeId, run);
                    }
                } catch (Exception e) {
                    // runOne 内部已尽力落库；这里兜底，保证单店异常不阻断其他店铺。
                    summary.failed++;
                    log.warn("T+1 对账跑批异常 businessDate={} merchantId={}: {}", date, storeId, e.getMessage());
                }
            }
        }
        LocalDate executionDate = LocalDate.now(BUSINESS_ZONE);
        for (Long storeId : merchants) {
            RetryResult retry = autoRetry(storeId, executionDate, operator, runStartedAt);
            summary.retryProcessed += retry.processed;
            summary.retryFailed += retry.failed;
            summary.escalated += retry.escalated;
            ReconciliationBatchRun tallyRun = latestRuns.get(storeId);
            if (tallyRun != null) {
                runMapper.addRetryTally(tallyRun.getId(), retry.processed, retry.failed, retry.escalated,
                        truncate(retry.errorSummary()));
            }
        }
        return summary.toMap();
    }

    /** 跑批记录查询；业务日或店铺为空表示不限。 */
    public List<ReconciliationBatchRun> list(LocalDate businessDate, Long merchantId, int limit) {
        return runMapper.find(businessDate, merchantId, limit);
    }

    private ReconciliationBatchRun runOne(LocalDate date, Long merchantId, Long operatorId, Summary summary) {
        ReconciliationBatchRun run = runMapper.findByDateAndMerchant(date, merchantId).orElse(null);
        if (run == null) {
            run = createRun(date, merchantId);
            if (run == null) {
                summary.skipped++;
                return null;
            }
        }
        if ("SUCCESS".equals(run.getStatus())) {
            summary.skipped++;
            return run;
        }
        if ("MANUAL".equals(run.getStatus())) {
            summary.manual++;
            return run;
        }
        int attempts = (run.getAttempts() == null ? 0 : run.getAttempts()) + 1;
        if (attempts > MAX_RUN_ATTEMPTS) {
            // 连续 PARTIAL/FAILED 已用尽次数：必须真正落 MANUAL，且只有更新成功才计入 manual。
            String error = truncate("已达到最大尝试次数" + MAX_RUN_ATTEMPTS + "，转人工处理");
            LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS);
            int updated = runMapper.markManual(run.getId(), now, error, now.minus(STALE_RUN));
            if (updated > 0) {
                run.setStatus("MANUAL");
                run.setLastError(error);
                summary.manual++;
            } else {
                summary.skipped++;
            }
            return run;
        }
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS);
        int claimed = runMapper.markRunning(run.getId(), attempts, now, now.minus(STALE_RUN));
        if (claimed == 0) {
            // 其他实例正在跑或状态已变化（例如刚被置为 SUCCESS），本轮跳过。
            summary.skipped++;
            return run;
        }
        run.setAttempts(attempts);
        run.setStatus("RUNNING");
        run.setStartedAt(now);
        try {
            Map<String, Object> result = reconciliationService.reconcile(merchantId, date.atStartOfDay(),
                    date.plusDays(1).atStartOfDay(), operatorId);
            int statements = intOf(result, "statements");
            int events = intOf(result, "events");
            int refunds = intOf(result, "refunds");
            int added = intOf(result, "added");
            int refreshed = intOf(result, "refreshed");
            String incompleteChannels = String.valueOf(
                    result.getOrDefault("incompleteChannels", ""));
            int successAtMissing = intOf(result, "successAtMissing");
            if (successAtMissing > 0) {
                // 巡检告警：这些成功退款不会进入 success_at 窗口，不能当作“对账无差异”。
                log.warn("success_at 缺失的 SUCCESS 退款 {} 笔，T+1 不会扫描，需人工补数", successAtMissing);
            }
            String status;
            String lastError = null;
            if (!incompleteChannels.isBlank()) {
                // 多渠道只导入一部分：按渠道维度标记 PARTIAL，避免总流水数不为 0 时静默通过。
                status = "PARTIAL";
                lastError = "渠道流水未完整导入：渠道" + incompleteChannels
                        + "有本地单据但当期流水0笔，相关单边账暂不生成";
            } else if (statements == 0 && (events > 0 || refunds > 0)) {
                // 本地有单据但渠道流水完全未导入：标记 PARTIAL；支付事件与退款单边账均不批量生成。
                status = "PARTIAL";
                lastError = "渠道流水未导入：本地事件" + events + "笔、退款" + refunds
                        + "笔，渠道流水0笔，单边账暂不生成";
            } else {
                status = "SUCCESS";
            }
            int updated = runMapper.finishRun(run.getId(), attempts, now, status, statements, events, refunds,
                    added, refreshed, lastError,
                    LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS));
            if (updated == 0) {
                // 超时被接管：不覆盖接管方结果，本次只记录差异计数。
                summary.skipped++;
                log.warn("T+1 跑批已被接管，跳过回写 businessDate={} merchantId={}", date, merchantId);
            } else {
                run.setStatus(status);
                run.setLastError(lastError);
                run.setStatementCount(statements);
                run.setEventCount(events);
                run.setRefundCount(refunds);
                run.setDifferenceAdded(added);
                run.setDifferenceRefreshed(refreshed);
                if ("PARTIAL".equals(status)) {
                    summary.partial++;
                } else {
                    summary.success++;
                }
            }
            summary.added += added;
            summary.refreshed += refreshed;
            return run;
        } catch (Exception e) {
            String status = attempts >= MAX_RUN_ATTEMPTS ? "MANUAL" : "FAILED";
            int updated = runMapper.finishRun(run.getId(), attempts, now, status, 0, 0, 0, 0, 0,
                    truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS));
            if (updated == 0) {
                summary.skipped++;
            } else {
                run.setStatus(status);
                run.setLastError(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                if ("MANUAL".equals(status)) {
                    summary.manual++;
                } else {
                    summary.failed++;
                }
            }
            log.warn("T+1 对账失败 businessDate={} merchantId={} attempt={}: {}", date, merchantId, attempts,
                    e.getMessage());
            return run;
        }
    }

    /**
     * 每店每次跑批只重试一轮：只取未关闭差异，跳过本次跑批期间新建记录和今天已重试过的记录。
     * last_retry_date 使用实际执行日，跨业务日回看不会把一条差异拆成多次重试。
     */
    private RetryResult autoRetry(Long merchantId, LocalDate executionDate, Long operatorId,
                                  LocalDateTime runStartedAt) {
        RetryResult result = new RetryResult();
        List<ReconciliationDifference> candidates = differenceMapper.findOpenByMerchant(merchantId,
                AUTO_RETRY_LIMIT);
        for (ReconciliationDifference difference : candidates) {
            if (difference.getLastRetryDate() != null
                    && difference.getLastRetryDate().equals(executionDate)) {
                continue;
            }
            if (difference.getCreatedAt() != null && !difference.getCreatedAt().isBefore(runStartedAt)) {
                // 本次跑批刚生成的差异不立即重试，避免“刚发现即计一次失败”。
                continue;
            }
            if (difference.getBusinessDate() != null
                    && difference.getBusinessDate().isAfter(executionDate)) {
                continue;
            }
            int before = difference.getRetryCount() == null ? 0 : difference.getRetryCount();
            try {
                ReconciliationDifference after = reconciliationService.retry(difference.getId(), operatorId,
                        executionDate);
                if (after == null) {
                    continue;
                }
                int afterCount = after.getRetryCount() == null ? 0 : after.getRetryCount();
                if (afterCount > before) {
                    result.processed++;
                }
                if ("MANUAL".equals(after.getStatus())) {
                    result.escalated++;
                }
            } catch (BusinessException e) {
                // 状态已被并发人工处理（已关闭或已转人工）时跳过，不影响本轮跑批。
                log.info("差异自动重试跳过 id={}: {}", difference.getId(), e.getMessage());
            } catch (RuntimeException e) {
                result.failed++;
                result.addError("差异" + difference.getId() + "重试失败：" + e.getMessage());
                log.warn("差异自动重试失败 id={}: {}", difference.getId(), e.getMessage());
            }
        }
        return result;
    }

    private ReconciliationBatchRun createRun(LocalDate date, Long merchantId) {
        ReconciliationBatchRun run = new ReconciliationBatchRun();
        run.setBusinessDate(date);
        run.setMerchantId(merchantId);
        run.setStatus("PENDING");
        run.setAttempts(0);
        try {
            runMapper.insert(run);
            return run;
        } catch (DuplicateKeyException e) {
            // 并发启动时另一实例已建记录，读回即可，不产生第二条。
            return runMapper.findByDateAndMerchant(date, merchantId).orElse(null);
        }
    }

    private List<Long> resolveMerchants(Long merchantId) {
        if (merchantId != null) {
            MerchantStore store = storeMapper.selectById(merchantId);
            if (store == null) {
                throw new BusinessException(404, "店铺不存在");
            }
            return List.of(merchantId);
        }
        List<MerchantStore> stores = storeMapper.selectList(null);
        List<Long> ids = new ArrayList<>();
        for (MerchantStore store : stores) {
            ids.add(store.getId());
        }
        return ids;
    }

    private int intOf(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static final class RetryResult {
        private int processed;
        private int escalated;
        private int failed;
        private final List<String> errors = new ArrayList<>();

        private void addError(String error) {
            if (errors.size() < 20) {
                errors.add(error);
            }
        }

        private String errorSummary() {
            if (errors.isEmpty()) {
                return null;
            }
            return "自动重试异常" + failed + "笔：" + String.join("; ", errors);
        }
    }

    private static final class Summary {
        private int runs;
        private int success;
        private int partial;
        private int failed;
        private int manual;
        private int skipped;
        private int added;
        private int refreshed;
        private int retryProcessed;
        private int retryFailed;
        private int escalated;

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runs", runs);
            result.put("success", success);
            result.put("partial", partial);
            result.put("failed", failed);
            result.put("manual", manual);
            result.put("skipped", skipped);
            result.put("differencesAdded", added);
            result.put("differencesRefreshed", refreshed);
            result.put("retryProcessed", retryProcessed);
            result.put("retryFailed", retryFailed);
            result.put("manualEscalated", escalated);
            return result;
        }
    }
}
