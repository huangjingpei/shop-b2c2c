/*
 * 文件：BillingStatementService.java
 * 用途：F09 商户账单生成：按账期聚合已支付订单与成功退款为 billing_statement 行，提供商户/管理端查询、CSV导出与重算。
 * 维护注意：
 *  1. 聚合口径与 Sprint 13 订单统计一致：订单按 created_at 取已支付集合（复用 findFeeBearingByMerchantAndPeriod），
 *     退款按 success_at 取 SUCCESS 且 source=ORDER；非法金额（null/负/超2位小数）跳过并告警，不伪造数据。
 *  2. feeAmt 为账期净扣收服务费（订单应扣合计 - 成功退款冲正合计），退款冲正跨期可为负，如实输出。
 *  3. 状态语义：窗口内存在未关闭对账差异(DISPUTED/RETRY/MANUAL)→DISPUTED；窗口尚未结束(当日/当月)→PENDING；
 *     其余→SETTLED。账单是展示口径快照，不含 SettlementService 批次的确认/锁定语义，重算直接覆盖金额。
 *  4. upsert 依据唯一键 uk_shop_period：先查后改，并发插入撞唯一键时降级为更新；generate 必须在事务内执行。
 *  5. 时区固定 Asia/Shanghai，与 T+1 对账业务日口径一致；禁止改用系统默认时区。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CsvEscaper;
import com.shop.b2b2c.entity.BillingStatement;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.BillingStatementMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.ReconciliationDifferenceMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingStatementService {

    public static final String PERIOD_DAILY = "DAILY";
    public static final String PERIOD_MONTHLY = "MONTHLY";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SETTLED = "SETTLED";
    public static final String STATUS_DISPUTED = "DISPUTED";
    /** 与 T+1 对账业务日同一时区口径。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 导出/查询日期跨度上限：日账单 92 天，月账单 24 个月。 */
    private static final long MAX_DAILY_SPAN_DAYS = 92;
    private static final long MAX_MONTHLY_SPAN_MONTHS = 24;

    private final BillingStatementMapper statements;
    private final TradeOrderMapper ordersMapper;
    private final TradeRefundMapper refundsMapper;
    private final ReconciliationDifferenceMapper differences;
    private final MerchantStoreMapper stores;
    private final ServiceFeeSnapshotService snapshotService;

    /** 生成/重算单店单期账单：金额重算、状态重判，upsert 落库。 */
    @Transactional(rollbackFor = Exception.class)
    public BillingStatement generate(Long shopId, LocalDate periodDate, String periodType, Long operatorId) {
        MerchantStore store = requireStore(shopId);
        String type = requirePeriodType(periodType);
        if (periodDate == null) {
            throw new BusinessException(400, "账期日期必填");
        }
        LocalDate normalized = PERIOD_MONTHLY.equals(type)
                ? YearMonth.from(periodDate).atDay(1) : periodDate;
        LocalDateTime from = normalized.atStartOfDay();
        LocalDateTime to = PERIOD_MONTHLY.equals(type)
                ? YearMonth.from(normalized).plusMonths(1).atDay(1).atStartOfDay()
                : normalized.plusDays(1).atStartOfDay();

        BigDecimal gmv = BigDecimal.ZERO, peer = BigDecimal.ZERO, direct = BigDecimal.ZERO, fee = BigDecimal.ZERO;
        int ordersCnt = 0;
        for (TradeOrder order : ordersMapper.findFeeBearingByMerchantAndPeriod(shopId, from, to)) {
            BigDecimal amount = order.getPayAmount();
            if (!validAmount(amount)) {
                log.warn("账单生成跳过异常金额订单 shopId={} orderId={} amount={}", shopId, order.getId(), amount);
                continue;
            }
            ordersCnt++;
            gmv = gmv.add(amount);
            if (order.getPeerPayType() != null && order.getPeerPayType() > 0) {
                peer = peer.add(amount);
            } else {
                direct = direct.add(amount);
            }
            fee = fee.add(snapshotService.resolveFeeAmount(order));
        }
        BigDecimal refundAmt = BigDecimal.ZERO, feeRefund = BigDecimal.ZERO;
        for (TradeRefund refund : refundsMapper.findSuccessfulRefundsByPeriod(shopId, from, to)) {
            if (!"ORDER".equals(refund.getSource())) {
                continue; // 重复付款回收不属于订单退款口径
            }
            if (validAmount(refund.getRefundAmount())) {
                refundAmt = refundAmt.add(refund.getRefundAmount());
            }
            if (validAmount(refund.getFeeRefundAmount())) {
                feeRefund = feeRefund.add(refund.getFeeRefundAmount());
            }
        }
        LocalDate windowEnd = to.toLocalDate();
        long openDiffs = differences.countOpenByMerchantAndBusinessDateBetween(shopId, normalized, windowEnd);
        String status = openDiffs > 0 ? STATUS_DISPUTED
                : to.isAfter(LocalDateTime.now(ZONE)) ? STATUS_PENDING : STATUS_SETTLED;

        BillingStatement row = statements.findByShopIdAndPeriodDateAndPeriodType(shopId, normalized, type)
                .orElseGet(() -> {
                    BillingStatement created = new BillingStatement();
                    created.setShopId(shopId);
                    created.setPeriodDate(normalized);
                    created.setPeriodType(type);
                    return created;
                });
        row.setStoreName(store.getStoreName());
        row.setGmv(money(gmv));
        row.setPeerPayGmv(money(peer));
        row.setDirectPayGmv(money(direct));
        row.setOrdersCnt(ordersCnt);
        row.setRefundAmt(money(refundAmt));
        row.setFeeAmt(money(fee.subtract(feeRefund)));
        row.setStatus(status);
        log.info("商户账单生成 shopId={} periodType={} periodDate={} status={} operatorId={}",
                shopId, type, normalized, status, operatorId);
        try {
            statements.save(row);
        } catch (DataIntegrityViolationException duplicate) {
            // 并发生成撞 uk_shop_period：降级为更新，金额以本事务聚合结果为准
            BillingStatement existing = statements
                    .findByShopIdAndPeriodDateAndPeriodType(shopId, normalized, type)
                    .orElseThrow(() -> duplicate);
            existing.setStoreName(row.getStoreName());
            existing.setGmv(row.getGmv());
            existing.setPeerPayGmv(row.getPeerPayGmv());
            existing.setDirectPayGmv(row.getDirectPayGmv());
            existing.setOrdersCnt(row.getOrdersCnt());
            existing.setRefundAmt(row.getRefundAmt());
            existing.setFeeAmt(row.getFeeAmt());
            existing.setStatus(row.getStatus());
            statements.save(existing);
            return existing;
        }
        return row;
    }

    /** 账单分页查询（shopId 为空=全平台，管理端用）。 */
    public Map<String, Object> list(Long shopId, String periodType, LocalDate from, LocalDate to, int page, int size) {
        String type = requirePeriodType(periodType);
        validateRange(type, from, to);
        if (page < 1) {
            throw new BusinessException(400, "page 必须大于等于 1");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException(400, "size 必须在 1 到 100 之间");
        }
        var result = statements.pageByPeriodRange(shopId, type, from, to,
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", result.getRecords());
        body.put("total", result.getTotal());
        body.put("page", page);
        body.put("size", size);
        return body;
    }

    /** 账单 CSV 导出（同 list 口径，行数受跨度上限保护）。 */
    public String csv(Long shopId, String periodType, LocalDate from, LocalDate to) {
        String type = requirePeriodType(periodType);
        validateRange(type, from, to);
        StringBuilder csv = new StringBuilder();
        csv.append("period_date,period_type,shop_id,store_name,gmv,peer_pay_gmv,direct_pay_gmv,orders_cnt,refund_amt,fee_amt,status\n");
        for (BillingStatement row : statements.listByPeriodRange(shopId, type, from, to)) {
            csv.append(CsvEscaper.escape(String.valueOf(row.getPeriodDate()))).append(',')
                    .append(CsvEscaper.escape(row.getPeriodType())).append(',')
                    .append(CsvEscaper.escape(String.valueOf(row.getShopId()))).append(',')
                    .append(CsvEscaper.escape(row.getStoreName())).append(',')
                    .append(CsvEscaper.escape(amount(row.getGmv()))).append(',')
                    .append(CsvEscaper.escape(amount(row.getPeerPayGmv()))).append(',')
                    .append(CsvEscaper.escape(amount(row.getDirectPayGmv()))).append(',')
                    .append(CsvEscaper.escape(String.valueOf(row.getOrdersCnt()))).append(',')
                    .append(CsvEscaper.escape(amount(row.getRefundAmt()))).append(',')
                    .append(CsvEscaper.escape(amount(row.getFeeAmt()))).append(',')
                    .append(CsvEscaper.escape(row.getStatus())).append('\n');
        }
        return csv.toString();
    }

    private String requirePeriodType(String periodType) {
        if (!PERIOD_DAILY.equals(periodType) && !PERIOD_MONTHLY.equals(periodType)) {
            throw new BusinessException(400, "账期类型只能是 DAILY 或 MONTHLY");
        }
        return periodType;
    }

    private void validateRange(String type, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException(400, "起止日期必填");
        }
        if (from.isAfter(to)) {
            throw new BusinessException(400, "起始日期不能晚于截止日期");
        }
        if (PERIOD_DAILY.equals(type)) {
            if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAILY_SPAN_DAYS) {
                throw new BusinessException(400, "日账单跨度最长 " + MAX_DAILY_SPAN_DAYS + " 天");
            }
        } else if (java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1
                > MAX_MONTHLY_SPAN_MONTHS) {
            throw new BusinessException(400, "月账单跨度最长 " + MAX_MONTHLY_SPAN_MONTHS + " 个月");
        }
    }

    private MerchantStore requireStore(Long shopId) {
        if (shopId == null || shopId <= 0) {
            throw new BusinessException(400, "店铺编号不正确");
        }
        MerchantStore store = stores.findById(shopId).orElse(null);
        if (store == null) {
            throw new BusinessException(404, "店铺不存在");
        }
        return store;
    }

    private static boolean validAmount(BigDecimal amount) {
        return amount != null && amount.signum() >= 0 && amount.scale() <= 2;
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String amount(BigDecimal amount) {
        return amount == null ? "" : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
