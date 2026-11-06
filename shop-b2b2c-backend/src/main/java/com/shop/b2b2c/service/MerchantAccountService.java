/*
 * 文件：MerchantAccountService.java
 * 用途：商家服务费账户与账本的唯一写入口（T34）：支付成功扣费、退款冲正、线下充值人工确认。
 * 维护注意：
 *  1. 账户余额/欠费只允许在本服务事务内通过账本方法变更；任何新增扣费场景必须生成稳定幂等键。
 *  2. 支付成功扣费与支付事件 SUCCESS 在同一事务内完成；余额不足时整笔转欠费，不部分扣减。
 *  3. 退款冲正只接入 T33 已确认的退款成功流程，不重建退款状态机；冲正优先冲减欠费，剩余才回补余额。
 *  4. 推广期 0 费率仍写一条金额为 0 的免收账本，保留业务记录但不改余额。
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
        BigDecimal balance = account.getBalanceAvailable() == null ? BigDecimal.ZERO : account.getBalanceAvailable();
        account.setBalanceAvailable(balance.add(amount).setScale(2, RoundingMode.HALF_UP));
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
