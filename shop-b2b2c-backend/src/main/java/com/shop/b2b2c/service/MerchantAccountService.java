/*
 * 文件：MerchantAccountService.java
 * 用途：商家服务费账户与账本的唯一写入口（T34）：支付成功扣费、退款冲正、线下充值人工确认。
 * 维护注意：
 *  1. 账户余额/欠费只允许在本服务事务内通过账本方法变更；任何新增扣费场景必须生成稳定幂等键。
 *  2. 支付成功扣费与支付事件 SUCCESS 在同一事务内完成；余额不足时整笔转欠费，不部分扣减。
 *  3. 退款冲正只接入 T33 已确认的退款成功流程，不重建退款状态机；冲正优先冲减欠费，剩余才回补余额。
 *  4. 推广期 0 费率仍写一条金额为 0 的免收账本，保留业务记录但不改余额。
 *  5. T35：欠费达到信用额度或账期到期即停新单收款；补齐欠费（含退款冲减/充值抵扣）后自动恢复并写审计。
 *     停收只影响新单，不影响已支付订单履约与退款。
 *  6. 账期只允许 7/21/30，且必须管理员显式确认；系统建议值不得自动写入。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantAccount;
import com.shop.b2b2c.entity.MerchantAccountLedger;
import com.shop.b2b2c.entity.MerchantRechargeOrder;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.MerchantAccountLedgerMapper;
import com.shop.b2b2c.mapper.MerchantAccountMapper;
import com.shop.b2b2c.mapper.MerchantRechargeOrderMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MerchantAccountService {

    private final MerchantAccountMapper accountMapper;
    private final MerchantAccountLedgerMapper ledgerMapper;
    private final MerchantRechargeOrderMapper rechargeMapper;
    private final TradeOrderMapper orderMapper;
    private final com.shop.b2b2c.mapper.MerchantCollectionStatusAuditMapper auditMapper;
    private final ServiceFeeSnapshotService snapshotService;

    public static final String ENTRY_CHARGE = "SERVICE_FEE_CHARGE";
    public static final String ENTRY_ARREARS = "SERVICE_FEE_ARREARS";
    public static final String ENTRY_WAIVED = "SERVICE_FEE_WAIVED";
    public static final String ENTRY_REFUND = "REFUND_REVERSAL";
    public static final String ENTRY_RECHARGE = "RECHARGE";

    private static final BigDecimal DEFAULT_CREDIT_LIMIT = new BigDecimal("500.00");

    /**
     * 支付成功扣费：余额足够则扣余额，不足则整笔计入欠费；返回实际入账类型。
     * 幂等键固定为 ORDER_FEE:{orderSn}，事件重放或并发回调不会重复扣费。
     */
    @Transactional
    public String chargeOrderFee(TradeOrder order, Long paymentEventId) {
        if (order == null || order.getOrderSn() == null) {
            throw new IllegalArgumentException("order required");
        }
        String key = "ORDER_FEE:" + order.getOrderSn();
        if (ledgerMapper.findByIdempotencyKey(key).isPresent()) {
            return "ALREADY_CHARGED";
        }
        BigDecimal fee = snapshotService.resolveFeeAmount(order);
        MerchantAccount account = lockOrCreate(order.getMerchantId());

        if (fee.signum() == 0) {
            insertLedger(account, ENTRY_WAIVED, "DEBIT", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    "ORDER", order.getId(), order.getOrderSn(), null, key, "推广期0费率，免收服务费");
            markOrderFeeStatus(order, "ACCRUED");
            return ENTRY_WAIVED;
        }

        BigDecimal balance = account.getBalanceAvailable() == null ? BigDecimal.ZERO : account.getBalanceAvailable();
        BigDecimal arrears = account.getArrears() == null ? BigDecimal.ZERO : account.getArrears();
        if (balance.compareTo(fee) >= 0) {
            account.setBalanceAvailable(balance.subtract(fee).setScale(2, RoundingMode.HALF_UP));
            applyArrearsControl(account, null);
            touch(account);
            insertLedger(account, ENTRY_CHARGE, "DEBIT", fee,
                    "ORDER", order.getId(), order.getOrderSn(), null, key, "支付成功扣减服务费");
            markOrderFeeStatus(order, "ACCRUED");
            return ENTRY_CHARGE;
        }

        account.setArrears(arrears.add(fee).setScale(2, RoundingMode.HALF_UP));
        if (account.getArrearsDueAt() == null) {
            int grace = account.getGraceDays() == null ? 0 : account.getGraceDays();
            account.setArrearsDueAt(LocalDateTime.now().plusDays(grace));
        }
        applyArrearsControl(account, null);
        touch(account);
        insertLedger(account, ENTRY_ARREARS, "DEBIT", fee,
                "ORDER", order.getId(), order.getOrderSn(), null, key, "余额不足，转入欠费");
        markOrderFeeStatus(order, "ARREARS");
        return ENTRY_ARREARS;
    }

    /**
     * 退款成功冲正：按订单原扣费方式回补。原扣余额则回补余额；原计欠费则先冲减欠费，剩余回补余额。
     * 幂等键固定为 REFUND_FEE:{refundNo}。
     */
    @Transactional
    public void reverseOrderFee(TradeOrder order, TradeRefund refund) {
        if (order == null || refund == null || refund.getFeeRefundAmount() == null
                || refund.getFeeRefundAmount().signum() <= 0) {
            return;
        }
        String key = "REFUND_FEE:" + refund.getRefundNo();
        if (ledgerMapper.findByIdempotencyKey(key).isPresent()) {
            return;
        }
        BigDecimal amount = refund.getFeeRefundAmount().setScale(2, RoundingMode.HALF_UP);
        MerchantAccount account = lockOrCreate(order.getMerchantId());

        MerchantAccountLedger original = ledgerMapper
                .findByIdempotencyKey("ORDER_FEE:" + order.getOrderSn()).orElse(null);
        boolean originalArrears = ENTRY_ARREARS.equals(original == null ? null : original.getEntryType())
                || original == null && "ARREARS".equals(order.getFeeStatus());

        BigDecimal arrears = account.getArrears() == null ? BigDecimal.ZERO : account.getArrears();
        BigDecimal balance = account.getBalanceAvailable() == null ? BigDecimal.ZERO : account.getBalanceAvailable();
        BigDecimal remaining = amount;
        if (originalArrears && arrears.signum() > 0) {
            BigDecimal offset = arrears.min(amount);
            account.setArrears(arrears.subtract(offset).setScale(2, RoundingMode.HALF_UP));
            remaining = amount.subtract(offset);
        }
        if (remaining.signum() > 0) {
            account.setBalanceAvailable(balance.add(remaining).setScale(2, RoundingMode.HALF_UP));
        }
        if (account.getArrears() == null || account.getArrears().signum() == 0) {
            account.setArrearsDueAt(null);
        }
        applyArrearsControl(account, null);
        touch(account);
        insertLedger(account, ENTRY_REFUND, "CREDIT", amount,
                "REFUND", refund.getId(), order.getOrderSn(), refund.getRefundNo(), key,
                "退款成功冲正服务费");
    }

    /**
     * 商家发起线下转账充值申请；凭证号唯一，重复提交直接拒绝。
     */
    @Transactional
    public MerchantRechargeOrder createRecharge(Long merchantId, BigDecimal amount, String voucherNo,
                                                String payerName, String proofUrl) {
        BigDecimal value = normalizeAmount(amount, "充值金额");
        String voucher = voucherNo == null ? "" : voucherNo.trim();
        if (voucher.isEmpty()) {
            throw new BusinessException(400, "转账凭证号不能为空");
        }
        if (rechargeMapper.findByVoucherNo(voucher).isPresent()) {
            throw new BusinessException(409, "转账凭证号已存在，请勿重复提交");
        }
        MerchantRechargeOrder recharge = new MerchantRechargeOrder();
        recharge.setRechargeNo("RCH" + UUID.randomUUID().toString().replace("-", ""));
        recharge.setMerchantId(merchantId);
        recharge.setAmount(value);
        recharge.setVoucherNo(voucher);
        recharge.setPayerName(payerName == null ? null : payerName.trim());
        recharge.setVoucherProofUrl(proofUrl == null ? null : proofUrl.trim());
        recharge.setStatus("PENDING");
        try {
            rechargeMapper.insert(recharge);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "转账凭证号已存在，请勿重复提交");
        }
        return recharge;
    }

    /**
     * 管理员人工确认到账：以确认金额入账，凭证号唯一保证不可重复。
     */
    @Transactional
    public MerchantRechargeOrder confirmRecharge(String rechargeNo, BigDecimal confirmedAmount, Long operatorId) {
        MerchantRechargeOrder recharge = rechargeMapper.findByRechargeNo(rechargeNo)
                .orElseThrow(() -> new BusinessException(404, "充值单不存在"));
        recharge = rechargeMapper.lockById(recharge.getId());
        if (recharge == null || "CONFIRMED".equals(recharge.getStatus())) {
            throw new BusinessException(409, "充值单已确认");
        }
        if ("REJECTED".equals(recharge.getStatus())) {
            throw new BusinessException(409, "充值单已驳回");
        }
        BigDecimal amount = confirmedAmount == null ? recharge.getAmount() : normalizeAmount(confirmedAmount, "确认金额");
        String key = "RECHARGE:" + recharge.getRechargeNo();
        if (ledgerMapper.findByIdempotencyKey(key).isPresent()) {
            return recharge;
        }
        MerchantAccount account = lockOrCreate(recharge.getMerchantId());
        BigDecimal arrears = account.getArrears() == null ? BigDecimal.ZERO : account.getArrears();
        BigDecimal offset = arrears.min(amount);
        BigDecimal remaining = amount.subtract(offset);
        if (offset.signum() > 0) {
            account.setArrears(arrears.subtract(offset).setScale(2, RoundingMode.HALF_UP));
            if (account.getArrears().signum() == 0) {
                account.setArrearsDueAt(null);
            }
        }
        BigDecimal balance = account.getBalanceAvailable() == null ? BigDecimal.ZERO : account.getBalanceAvailable();
        account.setBalanceAvailable(balance.add(remaining).setScale(2, RoundingMode.HALF_UP));
        applyArrearsControl(account, operatorId);
        touch(account);
        MerchantAccountLedger entry = insertLedger(account, ENTRY_RECHARGE, "CREDIT", amount,
                "RECHARGE", recharge.getId(), null, null, key, "线下转账人工确认入账");

        recharge.setStatus("CONFIRMED");
        recharge.setConfirmedAmount(amount);
        recharge.setLedgerEntryId(entry.getId());
        recharge.setConfirmedBy(operatorId);
        recharge.setConfirmedAt(LocalDateTime.now());
        recharge.setVersion((recharge.getVersion() == null ? 0 : recharge.getVersion()) + 1);
        rechargeMapper.updateById(recharge);
        return recharge;
    }

    @Transactional
    public MerchantRechargeOrder rejectRecharge(String rechargeNo, String reason, Long operatorId) {
        MerchantRechargeOrder recharge = rechargeMapper.findByRechargeNo(rechargeNo)
                .orElseThrow(() -> new BusinessException(404, "充值单不存在"));
        recharge = rechargeMapper.lockById(recharge.getId());
        if (recharge == null || !"PENDING".equals(recharge.getStatus())) {
            throw new BusinessException(409, "充值单不是待确认状态");
        }
        recharge.setStatus("REJECTED");
        recharge.setRejectReason(reason);
        recharge.setConfirmedBy(operatorId);
        recharge.setConfirmedAt(LocalDateTime.now());
        rechargeMapper.updateById(recharge);
        return recharge;
    }

    /**
     * T35：新单收款前置检查。只读判断，不在此处持久化停收状态；停收状态由扣费/策略确认/到期任务落库留痕。
     * 已支付订单履约与退款不调用本方法。
     */
    public void requireCollectionAllowed(Long merchantId) {
        if (merchantId == null) {
            return;
        }
        MerchantAccount account = accountMapper.findByMerchantId(merchantId).orElse(null);
        if (account == null) {
            return;
        }
        if (Boolean.TRUE.equals(account.getCollectionStopped())) {
            throw new BusinessException(409, "欠费已暂停新单收款，请先补齐欠费");
        }
        BigDecimal arrears = account.getArrears() == null ? BigDecimal.ZERO : account.getArrears();
        if (arrears.signum() == 0) {
            return;
        }
        BigDecimal credit = account.getCreditLimit() == null ? DEFAULT_CREDIT_LIMIT : account.getCreditLimit();
        if (arrears.compareTo(credit) >= 0) {
            throw new BusinessException(409, "欠费已达信用额度，新单收款已暂停");
        }
        if (account.getArrearsDueAt() != null && !account.getArrearsDueAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(409, "欠费账期已到期，新单收款已暂停");
        }
    }

    /**
     * T35：管理员确认店铺账期与信用额度。账期必须显式传 7/21/30，系统建议值不会自动落库。
     */
    @Transactional
    public MerchantAccount configurePolicy(Long merchantId, BigDecimal creditLimit, Integer graceDays,
                                           Long operatorId) {
        if (creditLimit == null) {
            throw new BusinessException(400, "信用额度不能为空");
        }
        BigDecimal limit = creditLimit.setScale(2, RoundingMode.HALF_UP);
        if (limit.signum() < 0 || limit.compareTo(new BigDecimal("100000.00")) > 0) {
            throw new BusinessException(400, "信用额度必须在 0~100000 元之间");
        }
        if (graceDays == null || (graceDays != 7 && graceDays != 21 && graceDays != 30)) {
            throw new BusinessException(400, "账期只允许 7/21/30 天，且必须由管理员确认");
        }
        MerchantAccount account = lockOrCreate(merchantId);
        account.setCreditLimit(limit);
        account.setGraceDays(graceDays);
        BigDecimal arrears = account.getArrears() == null ? BigDecimal.ZERO : account.getArrears();
        account.setArrearsDueAt(arrears.signum() > 0 ? LocalDateTime.now().plusDays(graceDays) : null);
        applyArrearsControl(account, operatorId);
        insertAudit(account, "POLICY", "POLICY_CONFIRMED", operatorId);
        account.setVersion((account.getVersion() == null ? 0 : account.getVersion()) + 1);
        accountMapper.updateById(account);
        return account;
    }

    /**
     * T35：账期建议，仅返回建议值，不写入账户。近30天已支付订单越多建议账期越短。
     */
    public java.util.Map<String, Object> suggestGraceDays(Long merchantId) {
        LocalDateTime to = LocalDateTime.now();
        int paidOrders = orderMapper.countPaidOrdersByPeriod(merchantId, to.minusDays(30), to);
        int suggested = paidOrders >= 1000 ? 7 : (paidOrders >= 200 ? 21 : 30);
        MerchantAccount account = accountMapper.findByMerchantId(merchantId).orElse(null);
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("paidOrdersLast30Days", paidOrders);
        result.put("suggestedGraceDays", suggested);
        result.put("confirmedGraceDays", account == null ? null : account.getGraceDays());
        result.put("creditLimit", account == null ? DEFAULT_CREDIT_LIMIT : account.getCreditLimit());
        result.put("source", "SUGGESTION_ONLY");
        return result;
    }

    public List<com.shop.b2b2c.entity.MerchantCollectionStatusAudit> auditOf(Long merchantId, int limit) {
        return auditMapper.findByMerchant(merchantId, limit);
    }

    /**
     * T35：到期任务扫描欠费账户，将已过账期账户置为停收并写审计；由定时任务或管理员手动触发。
     */
    @Transactional
    public int evaluateDueArrears(int limit) {
        List<MerchantAccount> candidates = accountMapper.findWithArrears(Math.min(Math.max(limit, 1), 500));
        int changed = 0;
        for (MerchantAccount candidate : candidates) {
            MerchantAccount account = accountMapper.lockByMerchantId(candidate.getMerchantId());
            if (account != null && applyArrearsControl(account, null)) {
                account.setVersion((account.getVersion() == null ? 0 : account.getVersion()) + 1);
                accountMapper.updateById(account);
                changed++;
            }
        }
        return changed;
    }

    public MerchantAccount view(Long merchantId) {
        return accountMapper.findByMerchantId(merchantId).orElseGet(() -> {
            MerchantAccount empty = new MerchantAccount();
            empty.setMerchantId(merchantId);
            empty.setBalanceAvailable(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            empty.setBalanceFrozen(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            empty.setArrears(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            empty.setCreditLimit(DEFAULT_CREDIT_LIMIT);
            return empty;
        });
    }

    public List<MerchantAccountLedger> ledgerOf(Long merchantId, LocalDateTime from, LocalDateTime to) {
        return ledgerMapper.findByMerchantAndPeriod(merchantId, from, to);
    }

    public List<MerchantRechargeOrder> rechargesOf(Long merchantId) {
        return rechargeMapper.findByMerchant(merchantId);
    }

    public List<MerchantRechargeOrder> pendingRecharges(Long merchantId) {
        return rechargeMapper.findPending(merchantId);
    }

    /**
     * 获取账户行锁；不存在时补建空账户，再重新加锁。唯一键冲突视为并发补建，重新读取即可。
     */
    private MerchantAccount lockOrCreate(Long merchantId) {
        if (merchantId == null) {
            throw new BusinessException(400, "商户不存在");
        }
        MerchantAccount account = accountMapper.lockByMerchantId(merchantId);
        if (account != null) {
            return account;
        }
        MerchantAccount created = new MerchantAccount();
        created.setMerchantId(merchantId);
        created.setBalanceAvailable(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        created.setBalanceFrozen(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        created.setArrears(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        created.setCreditLimit(DEFAULT_CREDIT_LIMIT);
        try {
            accountMapper.insert(created);
        } catch (DuplicateKeyException ignore) {
            // 并发创建，继续加锁读取已有行
        }
        account = accountMapper.lockByMerchantId(merchantId);
        if (account == null) {
            throw new BusinessException(500, "服务费账户初始化失败");
        }
        return account;
    }

    /**
     * 评估停收/恢复：欠费达到信用额度或欠费账期到期 -> 停收；欠费清零 -> 自动恢复。
     * 只修改内存字段并写审计，由调用方统一 touch 保存，避免同一事务内重复更新。
     */
    private boolean applyArrearsControl(MerchantAccount account, Long operatorId) {
        BigDecimal arrears = account.getArrears() == null ? BigDecimal.ZERO : account.getArrears();
        BigDecimal credit = account.getCreditLimit() == null ? DEFAULT_CREDIT_LIMIT : account.getCreditLimit();
        boolean stopped = Boolean.TRUE.equals(account.getCollectionStopped());

        if (!stopped) {
            String reason = null;
            if (arrears.compareTo(credit) >= 0) {
                reason = "ARREARS_LIMIT";
            } else if (arrears.signum() > 0 && account.getArrearsDueAt() != null
                    && !account.getArrearsDueAt().isAfter(LocalDateTime.now())) {
                reason = "GRACE_EXPIRED";
            }
            if (reason != null) {
                account.setCollectionStopped(true);
                account.setStopReason(reason);
                account.setStoppedAt(LocalDateTime.now());
                insertAudit(account, "STOP", reason, operatorId);
                return true;
            }
            return false;
        }

        if (arrears.signum() == 0) {
            account.setCollectionStopped(false);
            account.setStopReason(null);
            account.setRecoveredAt(LocalDateTime.now());
            insertAudit(account, "RECOVER", "ARREARS_CLEARED", operatorId);
            return true;
        }
        return false;
    }

    private void insertAudit(MerchantAccount account, String action, String reason, Long operatorId) {
        com.shop.b2b2c.entity.MerchantCollectionStatusAudit audit =
                new com.shop.b2b2c.entity.MerchantCollectionStatusAudit();
        audit.setMerchantId(account.getMerchantId());
        audit.setAction(action);
        audit.setReason(reason);
        audit.setArrearsAtAction(account.getArrears() == null ? BigDecimal.ZERO : account.getArrears());
        audit.setCreditLimit(account.getCreditLimit());
        audit.setGraceDays(account.getGraceDays());
        audit.setDueAt(account.getArrearsDueAt());
        audit.setOperatorId(operatorId);
        auditMapper.insert(audit);
    }

    private void markOrderFeeStatus(TradeOrder order, String status) {
        order.setFeeStatus(status);
        orderMapper.updateById(order);
    }

    private void touch(MerchantAccount account) {
        account.setVersion((account.getVersion() == null ? 0 : account.getVersion()) + 1);
        accountMapper.updateById(account);
    }

    private MerchantAccountLedger insertLedger(MerchantAccount account, String entryType, String direction,
                                               BigDecimal amount, String bizType, Long bizId, String orderSn,
                                               String refundNo, String key, String remark) {
        MerchantAccountLedger entry = new MerchantAccountLedger();
        entry.setMerchantId(account.getMerchantId());
        entry.setEntryType(entryType);
        entry.setDirection(direction);
        entry.setAmount(amount);
        entry.setBalanceAfter(account.getBalanceAvailable() == null ? BigDecimal.ZERO : account.getBalanceAvailable());
        entry.setArrearsAfter(account.getArrears() == null ? BigDecimal.ZERO : account.getArrears());
        entry.setBizType(bizType);
        entry.setBizId(bizId);
        entry.setOrderSn(orderSn);
        entry.setRefundNo(refundNo);
        entry.setIdempotencyKey(key);
        entry.setRemark(remark);
        try {
            ledgerMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            return ledgerMapper.findByIdempotencyKey(key).orElseThrow(() -> e);
        }
        return entry;
    }

    private BigDecimal normalizeAmount(BigDecimal amount, String field) {
        if (amount == null) {
            throw new BusinessException(400, field + "不能为空");
        }
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) {
            throw new BusinessException(400, field + "必须大于0");
        }
        return value;
    }
}
