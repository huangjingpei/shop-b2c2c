/*
 * 文件：SaasFeeBillService.java
 * 用途：F10 SaaS 月度技术服务费账单唯一写入口：按月幂等生成、试用/豁免判定、线下缴费人工确认、逾期扫描与查询。
 * 维护注意：
 *  1. 只处理固定月度技术服务费，不读取订单/抽佣/结算批次，也不扣减 merchant_account 余额或欠费。
 *  2. 同店同月由唯一键兜底；已存在账单（无论状态）一律原样返回，不重算覆盖，避免改写已缴/免收历史。
 *  3. 免收判定（代理细化，待业务确认）：月费为 0、fee_status=EXEMPTED、fee_status=TRIAL 或 fee_free_until 覆盖当月末日，记 WAIVED，amount=0。
 *  4. 到期日固定为次月 10 日（代理细化，待业务确认）；逾期扫描只改账单状态并发站内信，不自动停店/停服、不改 merchant_store.fee_status。
 *  5. 缴费仅支持 OFFLINE 人工确认，必须传凭证号；复核人由 FinanceApprovalGuard 判定，严格模式缺复核人 428、同人 403。
 *  6. 通知必须在业务事务内写入发件箱，失败回滚账单状态，避免状态与通知不一致。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.ManagementGuards;
import com.shop.b2b2c.common.ServiceFeeStatus;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.SaasFeeBill;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.SaasFeeBillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SaasFeeBillService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_WAIVED = "WAIVED";
    public static final String STATUS_OVERDUE = "OVERDUE";
    public static final Set<String> SUPPORTED_STATUS = Set.of(STATUS_PENDING, STATUS_PAID, STATUS_WAIVED, STATUS_OVERDUE);

    public static final String METHOD_OFFLINE = "OFFLINE";
    private static final Set<String> SUPPORTED_METHODS = Set.of(METHOD_OFFLINE);

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 到期日为次月 10 日；代理细化规则，须由业务确认后调整。 */
    private static final int DUE_DAY_OF_NEXT_MONTH = 10;

    private final SaasFeeBillMapper billMapper;
    private final MerchantStoreMapper storeMapper;
    private final MerchantNotificationService notificationService;
    private final FinanceApprovalGuard approvalGuard;

    /**
     * 为单店生成指定自然月账单；同店同月已存在时不覆盖并直接返回。
     * 只允许生成已结束月份，避免对未结束的账期提前出账。
     */
    @Transactional
    public SaasFeeBill generateForMerchant(Long merchantId, YearMonth month, Long operatorId) {
        if (merchantId == null || month == null) {
            throw new BusinessException(400, "店铺与账期月份不能为空");
        }
        YearMonth current = YearMonth.now(ZONE);
        if (month.isAfter(current.minusMonths(1))) {
            throw new BusinessException(400, "只能为已结束的自然月生成 SaaS 账单");
        }
        String billingMonth = month.format(MONTH_FORMAT);
        SaasFeeBill existing = billMapper.findByMerchantMonth(merchantId, billingMonth).orElse(null);
        if (existing != null) {
            return existing;
        }
        MerchantStore store = storeMapper.selectById(merchantId);
        if (store == null) {
            throw new BusinessException(404, "店铺不存在");
        }
        if (Integer.valueOf(4).equals(store.getStatus())) {
            throw new BusinessException(409, "店铺已注销，不再生成 SaaS 月费账单");
        }

        BigDecimal standard = scale(store.getMonthlyFee());
        String waiveReason = resolveWaiveReason(store, month, standard);
        SaasFeeBill bill = new SaasFeeBill();
        bill.setBillNo("SAAS-" + merchantId + "-" + month.format(DateTimeFormatter.ofPattern("yyyyMM")));
        bill.setMerchantId(merchantId);
        bill.setBillingMonth(billingMonth);
        bill.setStandardAmount(standard);
        bill.setAmount(waiveReason == null ? standard : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        bill.setStatus(waiveReason == null ? STATUS_PENDING : STATUS_WAIVED);
        bill.setWaiveReason(waiveReason);
        bill.setDueDate(month.plusMonths(1).atDay(DUE_DAY_OF_NEXT_MONTH));
        bill.setIssuedAt(LocalDateTime.now(ZONE));
        try {
            billMapper.insert(bill);
        } catch (DuplicateKeyException e) {
            // 并发生成由唯一键兜底，返回先写入的账单，不抛错打断跑批。
            return billMapper.findByMerchantMonth(merchantId, billingMonth)
                    .orElseThrow(() -> new BusinessException(409, "SaaS 账单生成冲突，请重试"));
        }
        if (STATUS_PENDING.equals(bill.getStatus())) {
            notifyMerchant(bill, MerchantNotificationService.BIZ_SAAS_FEE_ISSUED,
                    "SAAS_FEE_BILL_ISSUED", "SaaS 月费账单已生成");
        }
        log.info("SaaS 月费账单生成 merchantId={} month={} billNo={} amount={} status={} operatorId={}",
                merchantId, billingMonth, bill.getBillNo(), bill.getAmount(), bill.getStatus(), operatorId);
        return bill;
    }

    /**
     * 逾期扫描：把已过到期日仍待缴的账单置 OVERDUE，只改账单状态，不自动停店/停服。
     * 返回本次置位数量；重复执行不会重复通知（状态已非 PENDING）。
     */
    @Transactional
    public int markOverdue(LocalDate asOf) {
        LocalDate scanDate = asOf == null ? LocalDate.now(ZONE) : asOf;
        List<SaasFeeBill> candidates = billMapper.findPendingDueBefore(scanDate, 200);
        int count = 0;
        for (SaasFeeBill candidate : candidates) {
            SaasFeeBill bill = billMapper.lockById(candidate.getId());
            if (bill == null || !STATUS_PENDING.equals(bill.getStatus())) {
                continue;
            }
            if (bill.getDueDate() == null || !bill.getDueDate().isBefore(scanDate)) {
                continue;
            }
            bill.setStatus(STATUS_OVERDUE);
            billMapper.updateById(bill);
            notifyMerchant(bill, MerchantNotificationService.BIZ_SAAS_FEE_OVERDUE,
                    "SAAS_FEE_BILL_OVERDUE", "SaaS 月费账单已逾期");
            count++;
        }
        return count;
    }

    /**
     * 超管人工确认线下缴费：仅 PENDING/OVERDUE 可确认，WAIVED/PAID 明确拒绝。
     */
    @Transactional
    public SaasFeeBill confirmPayment(String billNo, Long operatorId, Long reviewerId,
                                      String paymentMethod, String voucherNo, String remark) {
        SaasFeeBill found = billMapper.findByBillNo(billNo)
                .orElseThrow(() -> new BusinessException(404, "SaaS 账单不存在"));
        SaasFeeBill bill = billMapper.lockById(found.getId());
        if (bill == null) {
            throw new BusinessException(404, "SaaS 账单不存在");
        }
        if (STATUS_WAIVED.equals(bill.getStatus())) {
            throw new BusinessException(409, "免收账单无需缴费");
        }
        if (STATUS_PAID.equals(bill.getStatus())) {
            throw new BusinessException(409, "账单已缴费，请勿重复确认");
        }
        if (bill.getAmount() == null || bill.getAmount().signum() <= 0) {
            throw new BusinessException(409, "账单应收金额为 0，不能登记缴费");
        }
        String method = paymentMethod == null || paymentMethod.isBlank() ? METHOD_OFFLINE : paymentMethod.trim();
        ManagementGuards.choice(method, SUPPORTED_METHODS);
        ManagementGuards.text(voucherNo, 64, true);
        ManagementGuards.text(remark, 255, false);

        String approvalMode = approvalGuard.verify("SaaS 月费缴费确认", operatorId, reviewerId);
        Long confirmedBy = approvalGuard.resolveReviewer(operatorId, reviewerId);
        bill.setStatus(STATUS_PAID);
        bill.setPaidAt(LocalDateTime.now(ZONE));
        bill.setPaidBy(operatorId);
        bill.setConfirmedBy(confirmedBy);
        bill.setApprovalMode(approvalMode);
        bill.setPaymentMethod(method);
        bill.setVoucherNo(voucherNo == null ? null : voucherNo.trim());
        bill.setRemark(remark == null ? null : remark.trim());
        billMapper.updateById(bill);
        notifyMerchant(bill, MerchantNotificationService.BIZ_SAAS_FEE_PAID,
                "SAAS_FEE_BILL_PAID", "SaaS 月费账单已缴费");
        return bill;
    }

    public List<SaasFeeBill> merchantBills(Long merchantId, int limit) {
        return billMapper.findByMerchant(merchantId, clampLimit(limit));
    }

    public SaasFeeBill requireOwnedBill(String billNo, Long merchantId) {
        SaasFeeBill bill = billMapper.findByBillNo(billNo)
                .orElseThrow(() -> new BusinessException(404, "SaaS 账单不存在"));
        if (!bill.getMerchantId().equals(merchantId)) {
            throw new BusinessException(404, "SaaS 账单不存在");
        }
        return bill;
    }

    public Map<String, Object> adminBills(Long merchantId, String status, String billingMonth,
                                          int limit, long offset) {
        String safeStatus = status == null || status.isBlank() ? null : status.trim();
        if (safeStatus != null && !SUPPORTED_STATUS.contains(safeStatus)) {
            throw new BusinessException(400, "账单状态只能是 PENDING/PAID/WAIVED/OVERDUE");
        }
        String safeMonth = billingMonth == null || billingMonth.isBlank() ? null : billingMonth.trim();
        if (safeMonth != null) {
            try {
                YearMonth.parse(safeMonth, MONTH_FORMAT);
            } catch (DateTimeParseException e) {
                throw new BusinessException(400, "账期月份格式应为 yyyy-MM");
            }
        }
        int safeLimit = clampLimit(limit);
        long safeOffset = Math.max(0, offset);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("limit", safeLimit);
        result.put("offset", safeOffset);
        result.put("items", billMapper.search(merchantId, safeStatus, safeMonth, safeLimit, safeOffset));
        result.put("note", "SaaS 月费账单与交易抽佣/结算批次分离；逾期只改账单状态，不自动停复服");
        return result;
    }

    /** 免收判定：返回原因；null 表示需要正常收费。 */
    private String resolveWaiveReason(MerchantStore store, YearMonth month, BigDecimal standard) {
        if (standard.signum() == 0) {
            return "月度技术服务费配置为 0";
        }
        if (ServiceFeeStatus.EXEMPTED.equals(store.getFeeStatus())) {
            return "商户服务费状态为 EXEMPTED";
        }
        if (ServiceFeeStatus.TRIAL.equals(store.getFeeStatus())) {
            return "商户服务费状态为 TRIAL";
        }
        LocalDateTime freeUntil = store.getFeeFreeUntil();
        if (freeUntil != null && !freeUntil.isBefore(month.atEndOfMonth().atStartOfDay())) {
            return "免费期覆盖当月";
        }
        return null;
    }

    private void notifyMerchant(SaasFeeBill bill, String bizType, String template, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("billNo", bill.getBillNo());
        payload.put("billingMonth", bill.getBillingMonth());
        payload.put("amount", bill.getAmount());
        payload.put("status", bill.getStatus());
        notificationService.notify(bizType, bill.getId(), MerchantNotificationService.CHANNEL_INAPP, template,
                MerchantNotificationService.merchantRecipient(bill.getMerchantId()), payload);
    }

    private int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }

    private BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
