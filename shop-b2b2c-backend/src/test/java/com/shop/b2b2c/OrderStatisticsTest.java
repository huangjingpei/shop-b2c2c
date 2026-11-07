/*
 * 文件：OrderStatisticsTest.java
 * 用途：统一状态统计的分类、金额精度、异常输入及未知财务指标单元测试。
 * 维护注意：使用内存订单，不模拟到账证明；REFUNDED订单金额不是实际退款额，FINISHED不是结算凭据。D25：withRefundLedger 按成功退款账本合入实际退款/净额/扣冲正佣金，跨期净额可为负、账本异常保持未知；聚合 SQL 正确性仍需用户在真实 MySQL 验证。
 */
package com.shop.b2b2c;
import com.shop.b2b2c.common.OrderStatistics;
import com.shop.b2b2c.dto.OrderStatisticsAggregate;
import com.shop.b2b2c.entity.TradeOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class OrderStatisticsTest {
    TradeOrder order(String state, String amount, String commission) {
        var order = new TradeOrder(); order.setOrderStatus(state); order.setPeerStatus("INIT");
        order.setPayAmount(amount == null ? null : new BigDecimal(amount));
        order.setCommissionAmount(commission == null ? null : new BigDecimal(commission)); return order;
    }
    @Test void mixedStatusesDoNotCountIntentAsPaidOrEstimateAsSettled() {
        var expired = order("UNPAID", "500", "15"); expired.setPeerStatus("EXPIRED");
        var summary = OrderStatistics.from(List.of(order("UNPAID", "1000", "30"), order("PAID", "10", "0.30"),
                order("SHIPPED", "20", "0.60"), order("COMPLETED", "30", "0.90"), order("REFUNDED", "5", "0.15"),
                order("CLOSED", "100", "3"), expired, order("UNKNOWN", "900", "27")));
        assertEquals(8, summary.totalOrders()); assertEquals(4, summary.paidOrders()); assertEquals(1, summary.pendingOrders());
        assertEquals(2, summary.closedOrders()); assertEquals(1, summary.unknownOrders()); assertEquals(1, summary.refundedOrders());
        assertEquals("65.00", summary.paidTurnover()); assertEquals("1000.00", summary.pendingIntentAmount());
        assertEquals("5.00", summary.refundedOrderAmount()); assertEquals("1.80", summary.estimatedCommission());
        assertEquals(summary.totalOrders(), summary.paidOrders() + summary.pendingOrders() + summary.closedOrders() + summary.unknownOrders());
        assertNull(summary.actualRefundAmount()); assertNull(summary.netTurnover()); assertNull(summary.settledCommission()); assertFalse(summary.financialLedgerAvailable());
    }
    @ParameterizedTest @ValueSource(strings = {"PAID", "SHIPPED", "COMPLETED", "REFUNDED"})
    void paidStatesKeepHistoryAndDoNotDependOnPeerLockState(String status) {
        var order = order(status, "1.23", "0.01"); order.setPeerStatus("EXPIRED"); order.setSharingStatus("FINISHED");
        var summary = OrderStatistics.from(List.of(order));
        assertEquals(1, summary.paidOrders()); assertEquals("1.23", summary.paidTurnover());
        assertEquals(status.equals("REFUNDED") ? "0.00" : "0.01", summary.estimatedCommission());
        assertNull(summary.settledCommission());
    }
    @ParameterizedTest @ValueSource(strings = {"EXPIRED", "CANCELLED"})
    void expiredUnpaidIntentIsClosedNotPending(String peer) {
        var order = order("UNPAID", "50", "1.50"); order.setPeerStatus(peer);
        var summary = OrderStatistics.from(List.of(order)); assertEquals(1, summary.closedOrders()); assertEquals(0, summary.pendingOrders());
        assertEquals("0.00", summary.paidTurnover()); assertEquals("0.00", summary.pendingIntentAmount());
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"-1", "1.001"})
    void badPayAmountsAreVisibleAsAnomalyAndNeverRoundedOrAdded(String value) {
        var summary = OrderStatistics.from(List.of(order("PAID", value, "0.30")));
        assertEquals(1, summary.paidOrders()); assertEquals(1, summary.amountAnomalyOrders());
        assertEquals("0.00", summary.paidTurnover()); assertEquals("0.00", summary.estimatedCommission());
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"-1", "0.001"})
    void badCommissionDoesNotMakeValidPaidAmountDisappear(String commission) {
        var summary = OrderStatistics.from(List.of(order("COMPLETED", "10", commission)));
        assertEquals("10.00", summary.paidTurnover()); assertEquals("0.00", summary.estimatedCommission()); assertEquals(1, summary.amountAnomalyOrders());
    }
    @Test void exactDecimalAndLargeAmountsUseStringsWithoutFloatingPoint() {
        var summary = OrderStatistics.from(List.of(order("PAID", "9007199254740993.01", "0.10"), order("PAID", "0.02", "0.20")));
        assertEquals("9007199254740993.03", summary.paidTurnover()); assertEquals("0.30", summary.estimatedCommission());
    }
    @Test void emptyUnknownAndNullRowsAreNotSilentlyTreatedAsPaid() {
        assertEquals("0.00", OrderStatistics.from(List.of()).paidTurnover());
        var summary = OrderStatistics.from(Arrays.asList(null, order(null, "100", "3"), order("REFUNDING", "100", "3")));
        assertEquals(3, summary.unknownOrders()); assertEquals(0, summary.paidOrders());
        assertThrows(NullPointerException.class, () -> OrderStatistics.from(null));
    }
    OrderStatisticsAggregate aggregate(String state, String peer, long count, String paySum, String commissionSum, long anomalies) {
        var row = new OrderStatisticsAggregate();
        row.setOrderStatus(state); row.setPeerStatus(peer); row.setOrderCount(count);
        row.setPayAmountSum(paySum == null ? null : new BigDecimal(paySum));
        row.setCommissionAmountSum(commissionSum == null ? null : new BigDecimal(commissionSum));
        row.setAmountAnomalyCount(anomalies);
        return row;
    }
    @Test void databaseAggregatesProduceSameClassificationAndMoneyAsRowLevel() {
        var expired = order("UNPAID", "500", "15"); expired.setPeerStatus("EXPIRED");
        var rowSummary = OrderStatistics.from(List.of(order("UNPAID", "1000", "30"), order("PAID", "10", "0.30"),
                order("SHIPPED", "20", "0.60"), order("COMPLETED", "30", "0.90"), order("REFUNDED", "5", "0.15"),
                order("CLOSED", "100", "3"), expired, order("UNKNOWN", "900", "27")));
        var aggregateRows = List.of(
                aggregate("UNPAID", "INIT", 1, "1000.00", "0.00", 0),
                aggregate("PAID", "INIT", 1, "10.00", "0.30", 0),
                aggregate("SHIPPED", "INIT", 1, "20.00", "0.60", 0),
                aggregate("COMPLETED", "INIT", 1, "30.00", "0.90", 0),
                aggregate("REFUNDED", "INIT", 1, "5.00", "0.00", 0),
                aggregate("CLOSED", "INIT", 1, "0.00", "0.00", 0),
                aggregate("UNPAID", "EXPIRED", 1, "500.00", "0.00", 0),
                aggregate("UNKNOWN", "INIT", 1, "900.00", "0.00", 0));
        assertEquals(rowSummary, OrderStatistics.fromAggregates(aggregateRows));
    }
    @Test void aggregateAnomalyCountIsAdditiveNotAveraged() {
        var summary = OrderStatistics.fromAggregates(List.of(
                aggregate("PAID", "INIT", 2, "10.00", "0.30", 1),
                aggregate("COMPLETED", "INIT", 3, "0.00", "0.00", 3),
                aggregate(null, null, 1, "0.00", "0.00", 0)));
        assertEquals(6, summary.totalOrders()); assertEquals(5, summary.paidOrders()); assertEquals(1, summary.unknownOrders());
        assertEquals(4, summary.amountAnomalyOrders()); assertEquals("10.00", summary.paidTurnover()); assertEquals("0.30", summary.estimatedCommission());
    }
    @Test void zeroValuesAreValidAndFinishedUnpaidIsNotIncome() {
        var free = order("PAID", "0.00", "0"); var intent = order("UNPAID", "100", "3"); intent.setSharingStatus("FINISHED");
        var summary = OrderStatistics.from(List.of(free, intent)); assertEquals(0, summary.amountAnomalyOrders());
        assertEquals(1, summary.paidOrders()); assertEquals("0.00", summary.estimatedCommission()); assertNull(summary.settledCommission());
    }
    // ---------- D25 退款账本合入 ----------
    @Test void refundLedgerFillsActualRefundNetAndSettledCommission() {
        var summary = OrderStatistics.from(List.of(order("PAID", "100", "3"), order("REFUNDED", "40", "0")))
                .withRefundLedger(new BigDecimal("40"), new BigDecimal("1.20"));
        assertEquals("140.00", summary.paidTurnover());
        assertEquals("40.00", summary.actualRefundAmount());
        assertEquals("100.00", summary.netTurnover());
        assertEquals("1.80", summary.settledCommission());
        assertTrue(summary.financialLedgerAvailable());
    }
    @Test void crossPeriodRefundMayProduceNegativeNetAndIsReportedAsIs() {
        // 历史支付在上期、退款在本期：净额为负是真实跨期口径，不得按0截断。
        var summary = OrderStatistics.from(List.of(order("PAID", "10", "0.30")))
                .withRefundLedger(new BigDecimal("25"), new BigDecimal("0.45"));
        assertEquals("-15.00", summary.netTurnover());
        assertEquals("-0.15", summary.settledCommission());
        assertTrue(summary.financialLedgerAvailable());
    }
    @Test void emptyRefundLedgerYieldsZeroRefundWithoutPretendingMissing() {
        var summary = OrderStatistics.from(List.of(order("PAID", "10", "0.30")))
                .withRefundLedger(BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals("0.00", summary.actualRefundAmount());
        assertEquals("10.00", summary.netTurnover());
        assertEquals("0.30", summary.settledCommission());
        assertTrue(summary.financialLedgerAvailable());
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"-1", "1.001"})
    void invalidLedgerInputsKeepLedgerUnknownInsteadOfZero(String value) {
        var base = OrderStatistics.from(List.of(order("PAID", "10", "0.30")));
        var summary = base.withRefundLedger(value == null ? null : new BigDecimal(value), new BigDecimal("0.10"));
        assertNull(summary.actualRefundAmount()); assertNull(summary.netTurnover()); assertNull(summary.settledCommission());
        assertFalse(summary.financialLedgerAvailable());
        var byFee = base.withRefundLedger(new BigDecimal("1"), value == null ? null : new BigDecimal(value));
        assertNull(byFee.actualRefundAmount()); assertFalse(byFee.financialLedgerAvailable());
    }
}
