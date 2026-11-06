/*
 * 文件：MerchantSettlementServiceTest.java
 * 用途：T34/T35 服务费快照、账户扣费/欠费、退款冲正、线下充值确认、结算批次与收款状态控制的契约用例。
 * 维护注意：
 *  1. 全部使用 Mockito 隔离，不启动 Spring、不连接数据库、不调用真实渠道。
 *  2. 金额口径与状态迁移是账本核心，修改快照公式、幂等键、欠费/停收规则时必须同步更新本用例。
 *  3. T35 停收只影响新单收款；恢复必须由欠费清零触发，且每次状态变化写审计。
 *  4. 真实 MySQL 并发行锁、月度任务与人工核对仍由用户手动验证。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantAccount;
import com.shop.b2b2c.entity.MerchantAccountLedger;
import com.shop.b2b2c.entity.MerchantCollectionStatusAudit;
import com.shop.b2b2c.entity.MerchantRechargeOrder;
import com.shop.b2b2c.entity.MerchantSettlementBatch;
import com.shop.b2b2c.entity.MerchantSettlementItem;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.MerchantAccountLedgerMapper;
import com.shop.b2b2c.mapper.MerchantAccountMapper;
import com.shop.b2b2c.mapper.MerchantCollectionStatusAuditMapper;
import com.shop.b2b2c.mapper.MerchantRechargeOrderMapper;
import com.shop.b2b2c.mapper.MerchantSettlementBatchMapper;
import com.shop.b2b2c.mapper.MerchantSettlementItemMapper;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import com.shop.b2b2c.service.FinanceApprovalGuard;
import com.shop.b2b2c.service.MerchantAccountService;
import com.shop.b2b2c.service.ServiceFeeSnapshotService;
import com.shop.b2b2c.service.SettlementService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantSettlementServiceTest {

    private final MerchantAccountMapper accountMapper = mock(MerchantAccountMapper.class);
    private final MerchantAccountLedgerMapper ledgerMapper = mock(MerchantAccountLedgerMapper.class);
    private final MerchantRechargeOrderMapper rechargeMapper = mock(MerchantRechargeOrderMapper.class);
    private final TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
    private final MerchantSettlementBatchMapper batchMapper = mock(MerchantSettlementBatchMapper.class);
    private final MerchantSettlementItemMapper itemMapper = mock(MerchantSettlementItemMapper.class);
    private final TradeRefundMapper refundMapper = mock(TradeRefundMapper.class);
    private final PaymentEventMapper eventMapper = mock(PaymentEventMapper.class);
    private final MerchantCollectionStatusAuditMapper auditMapper =
            mock(MerchantCollectionStatusAuditMapper.class);
    private final ServiceFeeSnapshotService snapshotService = new ServiceFeeSnapshotService();

    private MerchantAccountService accountService() {
        return accountService(false);
    }

    private MerchantAccountService accountService(boolean dualApprovalRequired) {
        return new MerchantAccountService(accountMapper, ledgerMapper, rechargeMapper, orderMapper, auditMapper,
                snapshotService, new FinanceApprovalGuard(dualApprovalRequired));
    }

    private SettlementService settlementService() {
        return settlementService(false);
    }

    private SettlementService settlementService(boolean dualApprovalRequired) {
        return new SettlementService(batchMapper, itemMapper, ledgerMapper, orderMapper, refundMapper,
                eventMapper, snapshotService, new FinanceApprovalGuard(dualApprovalRequired));
    }

    @Test
    void snapshotUsesThreePercentAndSplitsTax() {
        TradeOrder order = new TradeOrder();
        order.setPayAmount(new BigDecimal("100.00"));

        snapshotService.apply(order, feePolicy(300), new BigDecimal("100.00"));

        assertEquals(0, new BigDecimal("3.00").compareTo(order.getFeeAmount()));
        assertEquals(300, order.getFeeRateBps());
        assertEquals("NORMAL", order.getFeeRateType());
        assertEquals(new BigDecimal("0.00"), order.getFeeWaivedAmount());
        assertEquals(new BigDecimal("2.83"), order.getFeeNetAmount());
        assertEquals(new BigDecimal("0.17"), order.getFeeTaxAmount());
        assertEquals(0, new BigDecimal("97.00").compareTo(order.getMerchantSettleAmount()));
    }

    @Test
    void promotionZeroRateKeepsWaivedRecord() {
        TradeOrder order = new TradeOrder();
        order.setPayAmount(new BigDecimal("100.00"));

        snapshotService.apply(order, feePolicy(0), new BigDecimal("100.00"));

        assertEquals("PROMOTION", order.getFeeRateType());
        assertEquals(new BigDecimal("0.00"), order.getFeeAmount());
        // 0费率下标准服务费本身为0，减免额与应收同为0；账本仍保留一条免收记录。
        assertEquals(new BigDecimal("0.00"), order.getFeeWaivedAmount());
        assertEquals(new BigDecimal("0.00"), order.getFeeNetAmount());
        assertEquals(new BigDecimal("0.00"), order.getFeeTaxAmount());
    }

    @Test
    void snapshotRejectsRateOutsideConfirmedRange() {
        TradeOrder order = new TradeOrder();
        order.setPayAmount(new BigDecimal("100.00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> snapshotService.apply(order, feePolicy(1100), new BigDecimal("100.00")));
        assertTrue(ex.getMessage().contains("1%~10%"));
    }

    private com.shop.b2b2c.entity.MerchantFeePolicy feePolicy(int rateBps) {
        com.shop.b2b2c.entity.MerchantFeePolicy policy = new com.shop.b2b2c.entity.MerchantFeePolicy();
        policy.setId(9L);
        policy.setRateBps(rateBps);
        policy.setRateType(rateBps == 0 ? "PROMOTION" : "NORMAL");
        return policy;
    }

    @Test
    void chargeDeductsBalanceWhenSufficient() {
        TradeOrder order = paidOrder("SN-C1", new BigDecimal("3.00"));
        MerchantAccount account = account("100.00", "0.00");
        when(accountMapper.lockByMerchantId(1L)).thenReturn(account);
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-C1")).thenReturn(Optional.empty());

        String type = accountService().chargeOrderFee(order, 9L);

        assertEquals(MerchantAccountService.ENTRY_CHARGE, type);
        assertEquals(0, new BigDecimal("97.00").compareTo(account.getBalanceAvailable()));
        assertEquals("ACCRUED", order.getFeeStatus());
        verify(accountMapper).updateById(account);
        verify(ledgerMapper).insert(any(MerchantAccountLedger.class));
    }

    @Test
    void chargeMovesWholeFeeToArrearsWhenBalanceInsufficient() {
        TradeOrder order = paidOrder("SN-C2", new BigDecimal("3.00"));
        MerchantAccount account = account("1.00", "0.00");
        when(accountMapper.lockByMerchantId(1L)).thenReturn(account);
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-C2")).thenReturn(Optional.empty());

        String type = accountService().chargeOrderFee(order, 10L);

        assertEquals(MerchantAccountService.ENTRY_ARREARS, type);
        assertEquals(0, new BigDecimal("3.00").compareTo(account.getArrears()));
        assertEquals(0, new BigDecimal("1.00").compareTo(account.getBalanceAvailable()));
        assertEquals("ARREARS", order.getFeeStatus());
        assertNotNull(account.getArrearsDueAt());
    }

    @Test
    void chargeIsIdempotentByOrderSn() {
        TradeOrder order = paidOrder("SN-C3", new BigDecimal("3.00"));
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-C3"))
                .thenReturn(Optional.of(new MerchantAccountLedger()));

        String type = accountService().chargeOrderFee(order, 11L);

        assertEquals("ALREADY_CHARGED", type);
        verify(accountMapper, never()).lockByMerchantId(anyLong());
        verify(ledgerMapper, never()).insert(any(MerchantAccountLedger.class));
    }

    @Test
    void zeroFeeWritesWaivedLedgerWithoutChangingBalance() {
        TradeOrder order = paidOrder("SN-C4", BigDecimal.ZERO);
        MerchantAccount account = account("10.00", "0.00");
        when(accountMapper.lockByMerchantId(1L)).thenReturn(account);
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-C4")).thenReturn(Optional.empty());

        String type = accountService().chargeOrderFee(order, 12L);

        assertEquals(MerchantAccountService.ENTRY_WAIVED, type);
        assertEquals(0, new BigDecimal("10.00").compareTo(account.getBalanceAvailable()));
        assertEquals("ACCRUED", order.getFeeStatus());
    }

    @Test
    void refundReversalCreditsBalanceForBalanceCharge() {
        TradeOrder order = paidOrder("SN-R1", new BigDecimal("3.00"));
        MerchantAccount account = account("50.00", "0.00");
        when(accountMapper.lockByMerchantId(1L)).thenReturn(account);
        when(ledgerMapper.findByIdempotencyKey("REFUND_FEE:RF-1")).thenReturn(Optional.empty());
        MerchantAccountLedger original = new MerchantAccountLedger();
        original.setEntryType(MerchantAccountService.ENTRY_CHARGE);
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-R1")).thenReturn(Optional.of(original));

        accountService().reverseOrderFee(order, refund("RF-1", 1L, order, new BigDecimal("1.50")));

        assertEquals(0, new BigDecimal("51.50").compareTo(account.getBalanceAvailable()));
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getArrears()));
    }

    @Test
    void refundReversalOffsetsArrearsFirstForArrearsCharge() {
        TradeOrder order = paidOrder("SN-R2", new BigDecimal("3.00"));
        MerchantAccount account = account("0.00", "3.00");
        account.setArrearsDueAt(LocalDateTime.now().plusDays(7));
        when(accountMapper.lockByMerchantId(1L)).thenReturn(account);
        when(ledgerMapper.findByIdempotencyKey("REFUND_FEE:RF-2")).thenReturn(Optional.empty());
        MerchantAccountLedger original = new MerchantAccountLedger();
        original.setEntryType(MerchantAccountService.ENTRY_ARREARS);
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-R2")).thenReturn(Optional.of(original));

        accountService().reverseOrderFee(order, refund("RF-2", 2L, order, new BigDecimal("3.00")));

        assertEquals(0, BigDecimal.ZERO.compareTo(account.getArrears()));
        assertNull(account.getArrearsDueAt());
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getBalanceAvailable()));
    }

    @Test
    void rechargeVoucherMustBeUnique() {
        when(rechargeMapper.findByVoucherNo("V-001")).thenReturn(Optional.of(new MerchantRechargeOrder()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService().createRecharge(1L, new BigDecimal("100.00"), "V-001", "张三", null, 5L));
        assertTrue(ex.getMessage().contains("已存在"));
        verify(rechargeMapper, never()).insert(any(MerchantRechargeOrder.class));
    }

    @Test
    void confirmRechargeCreditsConfirmedAmount() {
        MerchantRechargeOrder recharge = new MerchantRechargeOrder();
        recharge.setId(7L);
        recharge.setRechargeNo("RCH-7");
        recharge.setMerchantId(1L);
        recharge.setRequestedBy(5L);
        recharge.setAmount(new BigDecimal("100.00"));
        recharge.setVoucherNo("V-002");
        recharge.setStatus("PENDING");
        when(rechargeMapper.findByRechargeNo("RCH-7")).thenReturn(Optional.of(recharge));
        when(rechargeMapper.lockById(7L)).thenReturn(recharge);
        when(ledgerMapper.findByIdempotencyKey("RECHARGE:RCH-7")).thenReturn(Optional.empty());
        MerchantAccount account = account("0.00", "0.00");
        when(accountMapper.lockByMerchantId(1L)).thenReturn(account);

        MerchantRechargeOrder result = accountService()
                .confirmRecharge("RCH-7", new BigDecimal("99.50"), 3L);

        assertEquals("CONFIRMED", result.getStatus());
        assertEquals(new BigDecimal("99.50"), result.getConfirmedAmount());
        assertEquals(0, new BigDecimal("99.50").compareTo(account.getBalanceAvailable()));
    }

    @Test
    void generateBatchBuildsOrderAndRefundItemsWithTotals() {
        MerchantSettlementBatch batch = draftBatch(10L);
        when(batchMapper.findByPeriod(1L, YearMonth.of(2026, 10).atDay(1),
                YearMonth.of(2026, 11).atDay(1))).thenReturn(Optional.of(batch));
        TradeOrder order = paidOrder("SN-S1", new BigDecimal("3.00"));
        order.setId(20L);
        order.setCreatedAt(LocalDateTime.of(2026, 10, 5, 10, 0));
        when(orderMapper.findFeeBearingByMerchantAndPeriod(eq(1L), any(), any())).thenReturn(List.of(order));
        TradeRefund refund = refund("RF-S1", 5L, order, new BigDecimal("1.00"));
        refund.setUpdatedAt(LocalDateTime.of(2026, 10, 8, 9, 0));
        refund.setRefundAmount(new BigDecimal("33.33"));
        when(refundMapper.findSuccessfulOrderRefundsByPeriod(eq(1L), any(), any())).thenReturn(List.of(refund));
        when(orderMapper.findById(20L)).thenReturn(Optional.of(order));
        when(ledgerMapper.sumChargedByPeriod(eq(1L), any(), any())).thenReturn(new BigDecimal("2.00"));
        when(ledgerMapper.sumArrearsByPeriod(eq(1L), any(), any())).thenReturn(new BigDecimal("1.00"));

        MerchantSettlementBatch result = settlementService().generateMonthly(1L, YearMonth.of(2026, 10), 3L);

        assertEquals(new BigDecimal("100.00"), result.getGrossAmount());
        assertEquals(new BigDecimal("3.00"), result.getFeeAmount());
        assertEquals(new BigDecimal("-1.00"), result.getAdjustmentAmount());
        assertEquals(new BigDecimal("2.00"), result.getNetFeeAmount());
        assertEquals(new BigDecimal("2.00"), result.getPaidAmount());
        assertEquals(new BigDecimal("1.00"), result.getArrearsAmount());
        verify(itemMapper, org.mockito.Mockito.times(2)).insert(any(MerchantSettlementItem.class));
        verify(itemMapper).deleteByBatchId(10L);
    }

    @Test
    void confirmedBatchCannotBeRegenerated() {
        MerchantSettlementBatch batch = draftBatch(10L);
        batch.setStatus("CONFIRMED");
        when(batchMapper.findByPeriod(eq(1L), any(), any())).thenReturn(Optional.of(batch));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> settlementService().generateMonthly(1L, YearMonth.of(2026, 10), 3L));
        assertTrue(ex.getMessage().contains("已确认"));
        verify(itemMapper, never()).deleteByBatchId(anyLong());
    }

    @Test
    void confirmDraftBatchLocksAndMarksConfirmed() {
        MerchantSettlementBatch batch = draftBatch(10L);
        when(batchMapper.lockById(10L)).thenReturn(batch);

        MerchantSettlementBatch result = settlementService().confirm(10L, 3L);

        assertEquals("CONFIRMED", result.getStatus());
        assertEquals(3L, result.getConfirmedBy());
        assertEquals(FinanceApprovalGuard.MODE_DUAL_APPROVAL, result.getApprovalMode());
        assertNotNull(result.getConfirmedAt());
        verify(batchMapper).updateById(batch);
    }

    @Test
    void settlementPreparerCannotConfirmOwnBatchUnderStrictMode() {
        MerchantSettlementBatch batch = draftBatch(10L);
        batch.setPreparedBy(3L);
        when(batchMapper.lockById(10L)).thenReturn(batch);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> settlementService(true).confirm(10L, 3L));

        assertEquals(403, ex.getCode());
        assertEquals("DRAFT", batch.getStatus());
        verify(batchMapper, never()).updateById(any());
    }

    @Test
    void sameOperatorSettlementConfirmAllowedWhenDualApprovalDisabled() {
        MerchantSettlementBatch batch = draftBatch(10L);
        batch.setPreparedBy(3L);
        when(batchMapper.lockById(10L)).thenReturn(batch);

        MerchantSettlementBatch result = settlementService(false).confirm(10L, 3L);

        assertEquals("CONFIRMED", result.getStatus());
        assertEquals(FinanceApprovalGuard.MODE_SAME_OPERATOR, result.getApprovalMode());
        verify(batchMapper).updateById(batch);
    }

    @Test
    void rechargeRequesterCannotConfirmOwnRechargeUnderStrictMode() {
        MerchantRechargeOrder recharge = new MerchantRechargeOrder();
        recharge.setId(8L);
        recharge.setRechargeNo("RCH-8");
        recharge.setMerchantId(1L);
        recharge.setRequestedBy(3L);
        recharge.setAmount(new BigDecimal("100.00"));
        recharge.setStatus("PENDING");
        when(rechargeMapper.findByRechargeNo("RCH-8")).thenReturn(Optional.of(recharge));
        when(rechargeMapper.lockById(8L)).thenReturn(recharge);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService(true).confirmRecharge("RCH-8", new BigDecimal("100.00"), 3L));

        assertEquals(403, ex.getCode());
        verify(ledgerMapper, never()).insert(any(MerchantAccountLedger.class));
    }

    @Test
    void sameOperatorRechargeConfirmAllowedWhenDualApprovalDisabled() {
        MerchantRechargeOrder recharge = new MerchantRechargeOrder();
        recharge.setId(8L);
        recharge.setRechargeNo("RCH-8");
        recharge.setMerchantId(1L);
        recharge.setRequestedBy(3L);
        recharge.setAmount(new BigDecimal("100.00"));
        recharge.setStatus("PENDING");
        when(rechargeMapper.findByRechargeNo("RCH-8")).thenReturn(Optional.of(recharge));
        when(rechargeMapper.lockById(8L)).thenReturn(recharge);
        when(accountMapper.lockByMerchantId(1L)).thenReturn(account("0.00", "0.00"));

        MerchantRechargeOrder result = accountService(false)
                .confirmRecharge("RCH-8", new BigDecimal("100.00"), 3L);

        assertEquals("CONFIRMED", result.getStatus());
        assertEquals(FinanceApprovalGuard.MODE_SAME_OPERATOR, result.getApprovalMode());
        verify(ledgerMapper).insert(any(MerchantAccountLedger.class));
    }

    @Test
    void chargeReachingCreditLimitStopsCollectionAndWritesAudit() {
        TradeOrder order = paidOrder("SN-A1", new BigDecimal("3.00"));
        MerchantAccount account = account("0.00", "498.00");
        when(accountMapper.lockByMerchantId(1L)).thenReturn(account);
        when(ledgerMapper.findByIdempotencyKey("ORDER_FEE:SN-A1")).thenReturn(Optional.empty());

        accountService().chargeOrderFee(order, 21L);

        assertEquals(0, new BigDecimal("501.00").compareTo(account.getArrears()));
        assertTrue(account.getCollectionStopped());
        assertEquals("ARREARS_LIMIT", account.getStopReason());
        assertNotNull(account.getStoppedAt());
        verify(auditMapper).insert(any(MerchantCollectionStatusAudit.class));
    }

    @Test
    void requireCollectionAllowedRejectsStoppedMerchant() {
        MerchantAccount account = account("0.00", "501.00");
        account.setCollectionStopped(true);
        account.setStopReason("ARREARS_LIMIT");
        when(accountMapper.findByMerchantId(1L)).thenReturn(Optional.of(account));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService().requireCollectionAllowed(1L));

        assertTrue(ex.getMessage().contains("暂停"));
    }

    @Test
    void requireCollectionAllowedPermitsMerchantWithoutArrears() {
        MerchantAccount account = account("100.00", "0.00");
        when(accountMapper.findByMerchantId(1L)).thenReturn(Optional.of(account));

        accountService().requireCollectionAllowed(1L);

        verify(accountMapper, never()).updateById(any(MerchantAccount.class));
    }

    @Test
    void rechargeOffsetClearingArrearsRecoversCollection() {
        MerchantAccount account = account("0.00", "100.00");
        account.setCollectionStopped(true);
        account.setArrearsDueAt(LocalDateTime.now().plusDays(7));
        when(accountMapper.lockByMerchantId(1L)).thenReturn(account);

        MerchantRechargeOrder recharge = new MerchantRechargeOrder();
        recharge.setId(30L);
        recharge.setRechargeNo("RCH-30");
        recharge.setMerchantId(1L);
        recharge.setAmount(new BigDecimal("100.00"));
        recharge.setVoucherNo("V-030");
        recharge.setStatus("PENDING");
        when(rechargeMapper.findByRechargeNo("RCH-30")).thenReturn(Optional.of(recharge));
        when(rechargeMapper.lockById(30L)).thenReturn(recharge);
        when(ledgerMapper.findByIdempotencyKey("RECHARGE:RCH-30")).thenReturn(Optional.empty());

        accountService().confirmRecharge("RCH-30", null, 3L);

        assertEquals(0, BigDecimal.ZERO.compareTo(account.getArrears()));
        assertNull(account.getArrearsDueAt());
        assertEquals(false, account.getCollectionStopped());
        assertNotNull(account.getRecoveredAt());
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getBalanceAvailable()));
        verify(auditMapper).insert(any(MerchantCollectionStatusAudit.class));
    }

    @Test
    void policyRejectsGraceDaysOutsideConfirmedOptions() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService().configurePolicy(1L, new BigDecimal("500.00"), 14, 3L));

        assertTrue(ex.getMessage().contains("7/21/30"));
        verify(accountMapper, never()).lockByMerchantId(anyLong());
        verify(auditMapper, never()).insert(any(MerchantCollectionStatusAudit.class));
    }

    @Test
    void policyConfirmationPersistsGraceDaysAndAudits() {
        MerchantAccount account = account("0.00", "0.00");
        when(accountMapper.lockByMerchantId(1L)).thenReturn(account);

        MerchantAccount result = accountService()
                .configurePolicy(1L, new BigDecimal("800.00"), 21, 3L);

        assertEquals(new BigDecimal("800.00"), result.getCreditLimit());
        assertEquals(Integer.valueOf(21), result.getGraceDays());
        assertNull(result.getArrearsDueAt());
        verify(auditMapper).insert(any(MerchantCollectionStatusAudit.class));
    }

    @Test
    void graceDaysSuggestionDoesNotWriteAccount() {
        MerchantAccount account = account("0.00", "0.00");
        when(accountMapper.findByMerchantId(1L)).thenReturn(Optional.of(account));
        when(orderMapper.countPaidOrdersByPeriod(eq(1L), any(), any())).thenReturn(50);

        java.util.Map<String, Object> suggestion = accountService().suggestGraceDays(1L);

        assertEquals(30, suggestion.get("suggestedGraceDays"));
        assertNull(suggestion.get("confirmedGraceDays"));
        assertEquals("SUGGESTION_ONLY", suggestion.get("source"));
        verify(accountMapper, never()).updateById(any(MerchantAccount.class));
    }

    @Test
    void evaluateDueArrearsStopsOverdueAccount() {
        MerchantAccount candidate = account("0.00", "100.00");
        candidate.setArrearsDueAt(LocalDateTime.now().minusDays(1));
        candidate.setCollectionStopped(false);
        when(accountMapper.findWithArrears(200)).thenReturn(List.of(candidate));
        when(accountMapper.lockByMerchantId(1L)).thenReturn(candidate);

        int changed = accountService().evaluateDueArrears(200);

        assertEquals(1, changed);
        assertTrue(candidate.getCollectionStopped());
        assertEquals("GRACE_EXPIRED", candidate.getStopReason());
        verify(accountMapper).updateById(candidate);
        verify(auditMapper).insert(any(MerchantCollectionStatusAudit.class));
    }

    @Test
    void evaluateDueArrearsSkipsAccountWithinGrace() {
        MerchantAccount candidate = account("0.00", "100.00");
        candidate.setArrearsDueAt(LocalDateTime.now().plusDays(3));
        when(accountMapper.findWithArrears(200)).thenReturn(List.of(candidate));
        when(accountMapper.lockByMerchantId(1L)).thenReturn(candidate);

        int changed = accountService().evaluateDueArrears(200);

        assertEquals(0, changed);
        assertEquals(false, candidate.getCollectionStopped());
        verify(accountMapper, never()).updateById(any(MerchantAccount.class));
        verify(auditMapper, never()).insert(any(MerchantCollectionStatusAudit.class));
    }

    private TradeOrder paidOrder(String orderSn, BigDecimal fee) {
        TradeOrder order = new TradeOrder();
        order.setId(20L);
        order.setOrderSn(orderSn);
        order.setMerchantId(1L);
        order.setPayAmount(new BigDecimal("100.00"));
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setFeeBaseAmount(new BigDecimal("100.00"));
        order.setFeeAmount(fee);
        order.setFeeRateBps(300);
        order.setGoodsType(1);
        order.setOrderStatus("PAID");
        order.setFeeStatus("PENDING");
        return order;
    }

    private MerchantAccount account(String balance, String arrears) {
        MerchantAccount account = new MerchantAccount();
        account.setId(1L);
        account.setMerchantId(1L);
        account.setBalanceAvailable(new BigDecimal(balance));
        account.setArrears(new BigDecimal(arrears));
        account.setCreditLimit(new BigDecimal("500.00"));
        return account;
    }

    private MerchantSettlementBatch draftBatch(Long id) {
        MerchantSettlementBatch batch = new MerchantSettlementBatch();
        batch.setId(id);
        batch.setMerchantId(1L);
        batch.setPeriodStart(YearMonth.of(2026, 10).atDay(1));
        batch.setPeriodEnd(YearMonth.of(2026, 11).atDay(1));
        batch.setSettlementType("SERVICE_FEE");
        batch.setStatus("DRAFT");
        batch.setPreparedBy(5L);
        batch.setVersion(0);
        return batch;
    }

    private TradeRefund refund(String refundNo, Long id, TradeOrder order, BigDecimal fee) {
        TradeRefund refund = new TradeRefund();
        refund.setId(id);
        refund.setRefundNo(refundNo);
        refund.setOrderId(order.getId());
        refund.setOrderSn(order.getOrderSn());
        refund.setMerchantId(order.getMerchantId());
        refund.setFeeRefundAmount(fee);
        return refund;
    }
}
