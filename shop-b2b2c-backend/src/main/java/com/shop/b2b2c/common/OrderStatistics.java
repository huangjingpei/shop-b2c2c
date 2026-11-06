/*
 * 文件：OrderStatistics.java
 * 用途：代付关闭编码复用PeerStatus；平台与本店共用的订单状态统计口径，不从意向单或分账标记推断实际资金收入。
 * 维护注意：PAID/SHIPPED/COMPLETED/REFUNDED计历史支付，REFUNDED订单金额不是实际退款额；预计佣金仅含前三种且支付金额有效的订单。BigDecimal累计并输出字符串；坏金额计异常，未知状态单列。退款/净额/结算依赖事件账本，当前返回null，不用0冒充已知。
 *          T25 新增 fromAggregates 供库内 GROUP BY 统计复用同一口径；SQL 汇总只累加合法金额，异常单数单独返回，聚合结果必须与 from(List) 一致。
 */
package com.shop.b2b2c.common;

import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.dto.OrderStatisticsAggregate;
import java.math.BigDecimal;
import java.util.*;

public record OrderStatistics(long totalOrders, long paidOrders, long pendingOrders, long closedOrders,
                              long refundedOrders, long unknownOrders, long amountAnomalyOrders,
                              String paidTurnover, String pendingIntentAmount, String refundedOrderAmount,
                              String estimatedCommission, String actualRefundAmount, String netTurnover,
                              String settledCommission, boolean financialLedgerAvailable) {
    private static final Set<String> ACTIVE_PAID = Set.of("PAID", "SHIPPED", "COMPLETED");
    private static final Set<String> CLOSED = Set.of("CLOSED", "CANCELLED", "EXPIRED");
    private static final Set<String> CLOSED_PEER = Set.of(PeerStatus.EXPIRED, PeerStatus.CANCELLED);

    public static OrderStatistics from(List<TradeOrder> orders) {
        Objects.requireNonNull(orders, "统计查询结果不能为空引用");
        long paid = 0, pending = 0, closed = 0, refunded = 0, unknown = 0, anomalies = 0;
        BigDecimal turnover = BigDecimal.ZERO, intents = BigDecimal.ZERO, refundOrders = BigDecimal.ZERO, commission = BigDecimal.ZERO;
        for (TradeOrder order : orders) {
            if (order == null || order.getOrderStatus() == null) { unknown++; continue; }
            String state = order.getOrderStatus();
            boolean activePaid = ACTIVE_PAID.contains(state), isRefunded = "REFUNDED".equals(state), anomaly = false;
            BigDecimal amount = order.getPayAmount();
            if (activePaid || isRefunded) {
                paid++;
                if (valid(amount)) turnover = turnover.add(amount); else anomaly = true;
                if (isRefunded) {
                    refunded++; if (valid(amount)) refundOrders = refundOrders.add(amount);
                } else {
                    if (!valid(order.getCommissionAmount())) anomaly = true;
                    else if (valid(amount)) commission = commission.add(order.getCommissionAmount());
                }
            } else if (CLOSED.contains(state) || ("UNPAID".equals(state) && CLOSED_PEER.contains(order.getPeerStatus() == null ? "" : order.getPeerStatus()))) {
                closed++;
            } else if ("UNPAID".equals(state)) {
                pending++; if (valid(amount)) intents = intents.add(amount); else anomaly = true;
            } else unknown++;
            if (anomaly) anomalies++;
        }
        return new OrderStatistics(orders.size(), paid, pending, closed, refunded, unknown, anomalies,
                money(turnover), money(intents), money(refundOrders), money(commission), null, null, null, false);
    }
    /**
     * T25：库内 GROUP BY 聚合行转同一输出口径。payAmountSum/commissionAmountSum 已由 SQL 过滤非法金额，
     * amountAnomalyCount 单独累加，因此与 from(List) 的分类、总额、异常计数保持一致。
     */
    public static OrderStatistics fromAggregates(List<OrderStatisticsAggregate> rows) {
        Objects.requireNonNull(rows, "统计聚合结果不能为空引用");
        long total = 0, paid = 0, pending = 0, closed = 0, refunded = 0, unknown = 0, anomalies = 0;
        BigDecimal turnover = BigDecimal.ZERO, intents = BigDecimal.ZERO, refundOrders = BigDecimal.ZERO, commission = BigDecimal.ZERO;
        for (OrderStatisticsAggregate row : rows) {
            if (row == null) continue;
            long count = row.getOrderCount();
            total += count;
            anomalies += row.getAmountAnomalyCount();
            BigDecimal amount = row.getPayAmountSum() == null ? BigDecimal.ZERO : row.getPayAmountSum();
            String state = row.getOrderStatus();
            if (state == null) {
                unknown += count;
            } else if (ACTIVE_PAID.contains(state)) {
                paid += count;
                turnover = turnover.add(amount);
                if (row.getCommissionAmountSum() != null) commission = commission.add(row.getCommissionAmountSum());
            } else if ("REFUNDED".equals(state)) {
                paid += count;
                refunded += count;
                turnover = turnover.add(amount);
                refundOrders = refundOrders.add(amount);
            } else if (CLOSED.contains(state)
                    || ("UNPAID".equals(state) && CLOSED_PEER.contains(row.getPeerStatus() == null ? "" : row.getPeerStatus()))) {
                closed += count;
            } else if ("UNPAID".equals(state)) {
                pending += count;
                intents = intents.add(amount);
            } else {
                unknown += count;
            }
        }
        return new OrderStatistics(total, paid, pending, closed, refunded, unknown, anomalies,
                money(turnover), money(intents), money(refundOrders), money(commission), null, null, null, false);
    }

    private static boolean valid(BigDecimal amount) { return amount != null && amount.signum() >= 0 && amount.scale() <= 2; }
    private static String money(BigDecimal amount) { return amount.setScale(2).toPlainString(); }
}
