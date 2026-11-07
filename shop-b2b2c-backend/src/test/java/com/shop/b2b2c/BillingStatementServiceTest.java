/*
 * 文件：BillingStatementServiceTest.java
 * 用途：F09 商户账单生成的聚合口径、状态判定、upsert 幂等降级、查询/导出校验与 CSV 转义的契约用例。
 * 维护注意：
 *  1. 全部 Mockito 隔离，不启动 Spring、不连接数据库；金额口径断言 orders/refunds/fee 的净扣收语义。
 *  2. 非法金额（null/负/超2位小数）必须跳过且不影响其余订单；非 ORDER 来源退款不计入账单口径。
 *  3. upsert 并发撞 uk_shop_period 时降级为更新，金额以本事务聚合结果为准（不回读旧值）。
 *  4. 真实 MySQL 唯一键并发、调度触发与跨店批量仍由用户手动验证。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.BillingStatement;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.BillingStatementMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.ReconciliationDifferenceMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import com.shop.b2b2c.service.BillingStatementService;
import com.shop.b2b2c.service.ServiceFeeSnapshotService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingStatementServiceTest {

    private static final long SHOP = 7L;
    private static final long OPERATOR = 3L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate PAST = LocalDate.of(2026, 1, 15);

    private final BillingStatementMapper statements = mock(BillingStatementMapper.class);
    private final TradeOrderMapper ordersMapper = mock(TradeOrderMapper.class);
    private final TradeRefundMapper refundsMapper = mock(TradeRefundMapper.class);
    private final ReconciliationDifferenceMapper differences = mock(ReconciliationDifferenceMapper.class);
    private final MerchantStoreMapper stores = mock(MerchantStoreMapper.class);
    private final ServiceFeeSnapshotService snapshotService = mock(ServiceFeeSnapshotService.class);

    private BillingStatementService service() {
        return new BillingStatementService(statements, ordersMapper, refundsMapper, differences, stores, snapshotService);
    }

    @Test
    void generateRejectsBadShopIdAndMissingStore() {
        assertThrows(BusinessException.class, () -> service().generate(null, PAST, "DAILY", OPERATOR));
        assertThrows(BusinessException.class, () -> service().generate(0L, PAST, "DAILY", OPERATOR));
        stubStore(9L);
        BusinessException notFound = assertThrows(BusinessException.class,
                () -> service().generate(99L, PAST, "DAILY", OPERATOR));
        assertEquals(404, notFound.getCode());
    }

    @Test
    void generateRejectsBadPeriodTypeAndNullDate() {
        stubStore(SHOP);
        assertThrows(BusinessException.class, () -> service().generate(SHOP, PAST, "WEEKLY", OPERATOR));
        assertThrows(BusinessException.class, () -> service().generate(SHOP, null, "DAILY", OPERATOR));
    }

    @Test
    void dailyAggregationSplitsPeerAndDirectAndNetsFeeRefund() {
        stubStore(SHOP);
        TradeOrder direct = order(1L, "100.00", 0);
        TradeOrder peer = order(2L, "50.00", 1);
        when(ordersMapper.findFeeBearingByMerchantAndPeriod(eq(SHOP), any(), any()))
                .thenReturn(List.of(direct, peer));
        when(snapshotService.resolveFeeAmount(direct)).thenReturn(new BigDecimal("3.00"));
        when(snapshotService.resolveFeeAmount(peer)).thenReturn(new BigDecimal("1.50"));
        when(refundsMapper.findSuccessfulRefundsByPeriod(eq(SHOP), any(), any()))
                .thenReturn(List.of(refund("ORDER", "10.00", "0.30")));
        when(differences.countOpenByMerchantAndBusinessDateBetween(eq(SHOP), any(), any())).thenReturn(0L);
        when(statements.findByShopIdAndPeriodDateAndPeriodType(eq(SHOP), any(), eq("DAILY")))
                .thenReturn(Optional.empty());

        BillingStatement row = service().generate(SHOP, PAST, "DAILY", OPERATOR);

        assertEquals("150.00", row.getGmv().toPlainString());
        assertEquals("50.00", row.getPeerPayGmv().toPlainString());
        assertEquals("100.00", row.getDirectPayGmv().toPlainString());
        assertEquals(2, row.getOrdersCnt());
        assertEquals("10.00", row.getRefundAmt().toPlainString());
        // 净扣收 = 订单应扣 4.50 - 退款冲正 0.30 = 4.20
        assertEquals("4.20", row.getFeeAmt().toPlainString());
        assertEquals(BillingStatementService.STATUS_SETTLED, row.getStatus());
        verify(statements).save(row);
    }

    @Test
    void invalidOrderAmountsAreSkippedWithoutFakingData() {
        stubStore(SHOP);
        TradeOrder valid = order(1L, "80.00", 0);
        TradeOrder nullAmount = order(2L, null, 0);
        TradeOrder negative = order(3L, "-5.00", 1);
        TradeOrder overScale = new TradeOrder();
        overScale.setId(4L);
        overScale.setPayAmount(new BigDecimal("9.999"));
        overScale.setPeerPayType(0);
        when(ordersMapper.findFeeBearingByMerchantAndPeriod(eq(SHOP), any(), any()))
                .thenReturn(List.of(valid, nullAmount, negative, overScale));
        when(snapshotService.resolveFeeAmount(valid)).thenReturn(new BigDecimal("2.40"));
        when(refundsMapper.findSuccessfulRefundsByPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(differences.countOpenByMerchantAndBusinessDateBetween(eq(SHOP), any(), any())).thenReturn(0L);
        when(statements.findByShopIdAndPeriodDateAndPeriodType(eq(SHOP), any(), eq("DAILY")))
                .thenReturn(Optional.empty());

        BillingStatement row = service().generate(SHOP, PAST, "DAILY", OPERATOR);

        assertEquals(1, row.getOrdersCnt());
        assertEquals("80.00", row.getGmv().toPlainString());
        assertEquals("80.00", row.getDirectPayGmv().toPlainString());
        assertEquals("0.00", row.getPeerPayGmv().toPlainString());
        assertEquals("2.40", row.getFeeAmt().toPlainString());
    }

    @Test
    void nonOrderSourceRefundsAreExcludedAndInvalidRefundAmountsSkipped() {
        stubStore(SHOP);
        when(ordersMapper.findFeeBearingByMerchantAndPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(refundsMapper.findSuccessfulRefundsByPeriod(eq(SHOP), any(), any())).thenReturn(List.of(
                refund("ORDER", "20.00", "0.60"),
                refund("DUPLICATE_PAYMENT", "99.00", "9.90"),
                refund("ORDER", "-1.00", null)));
        when(differences.countOpenByMerchantAndBusinessDateBetween(eq(SHOP), any(), any())).thenReturn(0L);
        when(statements.findByShopIdAndPeriodDateAndPeriodType(eq(SHOP), any(), eq("DAILY")))
                .thenReturn(Optional.empty());

        BillingStatement row = service().generate(SHOP, PAST, "DAILY", OPERATOR);

        assertEquals("20.00", row.getRefundAmt().toPlainString());
        // 净扣收 = 0 - 0.60 = -0.60，跨期冲正可为负，如实输出
        assertEquals("-0.60", row.getFeeAmt().toPlainString());
    }

    @Test
    void openDifferencesMarkStatementDisputed() {
        stubStore(SHOP);
        when(ordersMapper.findFeeBearingByMerchantAndPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(refundsMapper.findSuccessfulRefundsByPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(differences.countOpenByMerchantAndBusinessDateBetween(eq(SHOP), any(), any())).thenReturn(2L);
        when(statements.findByShopIdAndPeriodDateAndPeriodType(eq(SHOP), any(), eq("DAILY")))
                .thenReturn(Optional.empty());

        BillingStatement row = service().generate(SHOP, PAST, "DAILY", OPERATOR);

        assertEquals(BillingStatementService.STATUS_DISPUTED, row.getStatus());
    }

    @Test
    void unfinishedWindowStaysPending() {
        stubStore(SHOP);
        LocalDate today = LocalDate.now(ZONE);
        when(ordersMapper.findFeeBearingByMerchantAndPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(refundsMapper.findSuccessfulRefundsByPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(differences.countOpenByMerchantAndBusinessDateBetween(eq(SHOP), any(), any())).thenReturn(0L);
        when(statements.findByShopIdAndPeriodDateAndPeriodType(eq(SHOP), any(), eq("DAILY")))
                .thenReturn(Optional.empty());

        BillingStatement row = service().generate(SHOP, today, "DAILY", OPERATOR);

        assertEquals(BillingStatementService.STATUS_PENDING, row.getStatus());
    }

    @Test
    void monthlyPeriodIsNormalizedToFirstDayOfMonth() {
        stubStore(SHOP);
        when(ordersMapper.findFeeBearingByMerchantAndPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(refundsMapper.findSuccessfulRefundsByPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(differences.countOpenByMerchantAndBusinessDateBetween(eq(SHOP), any(), any())).thenReturn(0L);
        when(statements.findByShopIdAndPeriodDateAndPeriodType(eq(SHOP), any(), eq("MONTHLY")))
                .thenReturn(Optional.empty());

        BillingStatement row = service().generate(SHOP, LocalDate.of(2026, 1, 20), "MONTHLY", OPERATOR);

        assertEquals(LocalDate.of(2026, 1, 1), row.getPeriodDate());
        // 聚合窗口 [2026-01-01, 2026-02-01)
        verify(ordersMapper).findFeeBearingByMerchantAndPeriod(eq(SHOP),
                eq(LocalDateTime.of(2026, 1, 1, 0, 0)), eq(LocalDateTime.of(2026, 2, 1, 0, 0)));
    }

    @Test
    void existingRowIsUpdatedNotDuplicated() {
        stubStore(SHOP);
        when(ordersMapper.findFeeBearingByMerchantAndPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(refundsMapper.findSuccessfulRefundsByPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(differences.countOpenByMerchantAndBusinessDateBetween(eq(SHOP), any(), any())).thenReturn(0L);
        BillingStatement existing = new BillingStatement();
        existing.setId(11L);
        existing.setShopId(SHOP);
        existing.setPeriodDate(PAST);
        existing.setPeriodType("DAILY");
        existing.setGmv(new BigDecimal("999.00"));
        when(statements.findByShopIdAndPeriodDateAndPeriodType(eq(SHOP), eq(PAST), eq("DAILY")))
                .thenReturn(Optional.of(existing));

        BillingStatement row = service().generate(SHOP, PAST, "DAILY", OPERATOR);

        assertEquals(11L, row.getId());
        assertEquals("0.00", row.getGmv().toPlainString());
        verify(statements).save(existing);
    }

    @Test
    void duplicateKeyOnInsertDegradesToUpdate() {
        stubStore(SHOP);
        when(ordersMapper.findFeeBearingByMerchantAndPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(refundsMapper.findSuccessfulRefundsByPeriod(eq(SHOP), any(), any())).thenReturn(List.of());
        when(differences.countOpenByMerchantAndBusinessDateBetween(eq(SHOP), any(), any())).thenReturn(0L);
        BillingStatement concurrent = new BillingStatement();
        concurrent.setId(12L);
        concurrent.setShopId(SHOP);
        concurrent.setPeriodDate(PAST);
        concurrent.setPeriodType("DAILY");
        // 第一次查无（本事务视角），插入撞唯一键；回查命中并发已落库行，降级更新。
        when(statements.findByShopIdAndPeriodDateAndPeriodType(eq(SHOP), eq(PAST), eq("DAILY")))
                .thenReturn(Optional.empty(), Optional.of(concurrent));
        when(statements.save(any(BillingStatement.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_shop_period"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BillingStatement row = service().generate(SHOP, PAST, "DAILY", OPERATOR);

        assertEquals(12L, row.getId());
        verify(statements, times(2)).save(any(BillingStatement.class));
    }

    @Test
    void listRejectsBadInput() {
        assertThrows(BusinessException.class, () -> service().list(null, "WEEKLY", PAST, PAST, 1, 20));
        assertThrows(BusinessException.class, () -> service().list(null, "DAILY", null, PAST, 1, 20));
        assertThrows(BusinessException.class,
                () -> service().list(null, "DAILY", PAST, PAST.minusDays(1), 1, 20));
        assertThrows(BusinessException.class,
                () -> service().list(null, "DAILY", PAST, PAST.plusDays(92), 1, 20));
        assertThrows(BusinessException.class,
                () -> service().list(null, "MONTHLY", PAST, PAST.plusMonths(24), 1, 20));
        assertThrows(BusinessException.class, () -> service().list(null, "DAILY", PAST, PAST, 0, 20));
        assertThrows(BusinessException.class, () -> service().list(null, "DAILY", PAST, PAST, 1, 101));
    }

    @Test
    void csvEscapesFormulaInjectionAndQuotes() {
        BillingStatement row = new BillingStatement();
        row.setShopId(SHOP);
        row.setPeriodDate(PAST);
        row.setPeriodType("DAILY");
        row.setStoreName("=HYPERLINK(\"evil\"),店");
        row.setGmv(new BigDecimal("100.00"));
        row.setPeerPayGmv(BigDecimal.ZERO);
        row.setDirectPayGmv(new BigDecimal("100.00"));
        row.setOrdersCnt(1);
        row.setRefundAmt(BigDecimal.ZERO);
        row.setFeeAmt(new BigDecimal("3.00"));
        row.setStatus("SETTLED");
        when(statements.listByPeriodRange(null, "DAILY", PAST, PAST)).thenReturn(List.of(row));

        String csv = service().csv(null, "DAILY", PAST, PAST);

        String[] lines = csv.split("\n");
        assertTrue(lines[0].startsWith("period_date,period_type,shop_id"));
        // 店名以 = 开头须被单引号转义防公式注入，含逗号/引号整体包裹并转义。
        assertTrue(lines[1].contains("\"'=HYPERLINK(\"\"evil\"\"),店\""));
        assertTrue(lines[1].contains("100.00"));
    }

    private void stubStore(long shopId) {
        MerchantStore store = new MerchantStore();
        store.setId(shopId);
        store.setStoreName("测试店铺");
        when(stores.findById(shopId)).thenReturn(Optional.of(store));
    }

    private TradeOrder order(long id, String amount, int peerPayType) {
        TradeOrder order = new TradeOrder();
        order.setId(id);
        order.setPayAmount(amount == null ? null : new BigDecimal(amount));
        order.setPeerPayType(peerPayType);
        return order;
    }

    private TradeRefund refund(String source, String refundAmount, String feeRefundAmount) {
        TradeRefund refund = new TradeRefund();
        refund.setSource(source);
        refund.setRefundAmount(refundAmount == null ? null : new BigDecimal(refundAmount));
        refund.setFeeRefundAmount(feeRefundAmount == null ? null : new BigDecimal(feeRefundAmount));
        return refund;
    }
}
