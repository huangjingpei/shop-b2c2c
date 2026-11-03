/*
 * 文件：OrderStatistics.java
 * 用途：平台与本店共用的订单状态统计口径，不从意向单或分账标记推断实际资金收入。
 * 维护注意：PAID/SHIPPED/COMPLETED/REFUNDED计历史支付，REFUNDED订单金额不是实际退款额；预计佣金仅含前三种且支付金额有效的订单。BigDecimal累计并输出字符串；坏金额计异常，未知状态单列。退款/净额/结算依赖事件账本，当前返回null，不用0冒充已知。
 */
package com.shop.b2b2c.common;

import com.shop.b2b2c.entity.TradeOrder;
import java.math.BigDecimal;
import java.util.*;

public record OrderStatistics(long totalOrders, long paidOrders, long pendingOrders, long closedOrders,
                              long refundedOrders, long unknownOrders, long amountAnomalyOrders,
                              String paidTurnover, String pendingIntentAmount, String refundedOrderAmount,
                              String estimatedCommission, String actualRefundAmount, String netTurnover,
                              String settledCommission, boolean financialLedgerAvailable) {
    private static final Set<String> ACTIVE_PAID = Set.of("PAID", "SHIPPED", "COMPLETED");
    private static final Set<String> CLOSED = Set.of("CLOSED", "CANCELLED", "EXPIRED");
    private static final Set<String> CLOSED_PEER = Set.of("EXPIRED", "CANCELLED");

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
    private static boolean valid(BigDecimal amount) { return amount != null && amount.signum() >= 0 && amount.scale() <= 2; }
    private static String money(BigDecimal amount) { return amount.setScale(2).toPlainString(); }
}
