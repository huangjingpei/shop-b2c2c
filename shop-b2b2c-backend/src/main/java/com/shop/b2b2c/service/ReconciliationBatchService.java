/*
 * 文件：ReconciliationBatchService.java
 * 用途：T+1 对账跑批：按业务日逐店执行对账、自动重试旧差异、记录跑批状态与失败原因。
 * 维护注意：
 *  1. 幂等三层：reconciliation_batch_run 唯一键 (business_date, merchant_id)；markRunning 条件抢占；
 *     差异键唯一且已关闭记录不再改写。
 *  2. SUCCESS/MANUAL 不自动重跑；PARTIAL（渠道流水未导入）与 FAILED 在回看窗口内允许重跑。
 *  3. 单店失败不阻断其他店铺；失败原因写入 last_error，达到 MAX_RUN_ATTEMPTS 转 MANUAL，不得静默丢弃。
 *  4. 自动重试只处理业务日早于本次跑批日的未关闭差异；同一业务日每个差异只重试一次（由 last_retry_date 保证）。
 *  5. 业务日统一 Asia/Shanghai 墙上时间，窗口 [业务日 00:00, 业务日+1 00:00)。
 *  6. 跑批只写差异与跑批记录，不修改订单、退款、账本、结算单或平台代收台账。
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

    /** 同一店铺同一业务日的最大自动尝试次数；达到上限仍失败必须转 MANUAL。 */
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
        for (LocalDate date = rangeStart; !date.isAfter(rangeEnd); date = date.plusDays(1)) {
            for (Long storeId : merchants) {
                summary.runs++;
                try {
                    runOne(date, storeId, operator, summary);
                } catch (Exception e) {
                    // runOne 内部已尽力落库；这里兜底，保证单店异常不阻断其他店铺。
                    summary.failed++;
                    log.warn("T+1 对账跑批异常 businessDate={} merchantId={}: {}", date, storeId, e.getMessage());
                }
            }
        }
        return summary.toMap();
    }

    /** 跑批记录查询；业务日或店铺为空表示不限。 */
    public List<ReconciliationBatchRun> list(LocalDate businessDate, Long merchantId, int limit) {
        return runMapper.find(businessDate, merchantId, limit);
    }

    private void runOne(LocalDate date, Long merchantId, Long operatorId, Summary summary) {
        ReconciliationBatchRun run = runMapper.findByDateAndMerchant(date, merchantId).orElse(null);
        if (run == null) {
            run = createRun(date, merchantId);
            if (run == null) {
                summary.skipped++;
                return;
            }
        }
        if ("SUCCESS".equals(run.getStatus())) {
            summary.skipped++;
            return;
        }
        if ("MANUAL".equals(run.getStatus())) {
            summary.manual++;
            return;
        }
        int attempts = (run.getAttempts() == null ? 0 : run.getAttempts()) + 1;
        if (attempts > MAX_RUN_ATTEMPTS) {
            // 历史失败记录已用尽次数，补写 MANUAL，保证不会无限重跑。
            String error = truncate("已达到最大尝试次数" + MAX_RUN_ATTEMPTS + "，转人工处理");
            runMapper.markManual(run.getId(), LocalDateTime.now(), error, LocalDateTime.now().minus(STALE_RUN));
            summary.manual++;
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int claimed = runMapper.markRunning(run.getId(), attempts, now, now.minus(STALE_RUN));
        if (claimed == 0) {
            // 其他实例正在跑或状态已变化（例如刚被置为 SUCCESS），本轮跳过。
            summary.skipped++;
            return;
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
            RetryResult retry = autoRetry(merchantId, date, operatorId);
            run.setStatementCount(statements);
            run.setEventCount(events);
            run.setRefundCount(refunds);
            run.setDifferenceAdded(intOf(result, "added"));
            run.setDifferenceRefreshed(intOf(result, "refreshed"));
            run.setRetryProcessed(retry.processed);
            run.setManualEscalated(retry.escalated);
            run.setFinishedAt(LocalDateTime.now());
            if (statements == 0 && (events > 0 || refunds > 0)) {
                // 本地有单据但渠道流水未导入：标记 PARTIAL，避免把“未导入”误判为已对平。
                run.setStatus("PARTIAL");
                run.setLastError("渠道流水未导入：本地事件" + events + "笔、退款" + refunds
                        + "笔，渠道流水0笔，单边账暂不生成");
                summary.partial++;
            } else {
                run.setStatus("SUCCESS");
                run.setLastError(null);
                summary.success++;
            }
            runMapper.updateById(run);
            summary.added += run.getDifferenceAdded();
            summary.refreshed += run.getDifferenceRefreshed();
            summary.retryProcessed += retry.processed;
            summary.escalated += retry.escalated;
        } catch (Exception e) {
            run.setStatus(attempts >= MAX_RUN_ATTEMPTS ? "MANUAL" : "FAILED");
            run.setFinishedAt(LocalDateTime.now());
            run.setLastError(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            runMapper.updateById(run);
            if ("MANUAL".equals(run.getStatus())) {
                summary.manual++;
            } else {
                summary.failed++;
            }
            log.warn("T+1 对账失败 businessDate={} merchantId={} attempt={}: {}", date, merchantId, attempts,
                    e.getMessage());
        }
    }

    /** 只重试业务日早于本次跑批日的未关闭差异；同日重复跑批不会重复累加重试次数。 */
    private RetryResult autoRetry(Long merchantId, LocalDate businessDate, Long operatorId) {
        RetryResult result = new RetryResult();
        List<ReconciliationDifference> candidates = differenceMapper.findOpenByMerchant(merchantId,
                AUTO_RETRY_LIMIT);
        for (ReconciliationDifference difference : candidates) {
            if (difference.getBusinessDate() != null
                    && !difference.getBusinessDate().isBefore(businessDate)) {
                continue;
            }
            int before = difference.getRetryCount() == null ? 0 : difference.getRetryCount();
            try {
                ReconciliationDifference after = reconciliationService.retry(difference.getId(), operatorId,
                        businessDate);
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
            result.put("manualEscalated", escalated);
            return result;
        }
    }
}
