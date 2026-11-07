/*
 * 文件：OrderLifecycle.java
 * 用途：F07 实物订单完成与售后时效的统一口径（确认收货、自动完成、售后期）。
 * 维护注意：
 *  1. 自动完成与售后期天数当前为代理细化默认值（自动完成 10 天、售后 15 天），尚未经业务确认；
 *     修改这里会同时影响定时作业、退款/投诉受理与买家端展示，必须同步测试与文档。
 *  2. 自动完成只适用于 SHIPPED 实物订单且以 delivery_time 起算；缺少发货时间的历史单不自动完成。
 *  3. 售后期自 COMPLETED 的 finish_time 起算；finish_time 缺失的历史完成单不做时效拦截（无法判定起点）。
 *  4. 本类只提供纯计算，不写库、不加锁；状态迁移仍由调用方在订单行锁/事务内完成。
 */
package com.shop.b2b2c.common;

import com.shop.b2b2c.entity.TradeOrder;

import java.time.LocalDateTime;
import java.util.Optional;

public final class OrderLifecycle {

    /** 发货后自动确认收货天数（代理细化默认值，待业务确认）。 */
    public static final int AUTO_COMPLETE_DAYS = 10;

    /** 订单完成后售后受理天数（代理细化默认值，待业务确认）。 */
    public static final int AFTER_SALE_DAYS = 15;

    private OrderLifecycle() {
    }

    /** 自动完成扫描阈值：delivery_time 早于 now - AUTO_COMPLETE_DAYS。 */
    public static LocalDateTime autoCompleteCutoff(LocalDateTime now) {
        return now.minusDays(AUTO_COMPLETE_DAYS);
    }

    /** 售后截止时刻；未完成、缺少完成时间或非实物订单返回 empty。 */
    public static Optional<LocalDateTime> afterSaleDeadline(TradeOrder order) {
        if (order == null || !"COMPLETED".equals(order.getOrderStatus())) {
            return Optional.empty();
        }
        if (order.getFinishTime() == null) {
            return Optional.empty();
        }
        return Optional.of(order.getFinishTime().plusDays(AFTER_SALE_DAYS));
    }

    /**
     * 已完成订单是否仍在售后受理期内；未完成或缺少完成时间不拦截（返回 true）。
     * 截止时刻本身视为过期（开区间 [finishTime, deadline)）。
     */
    public static boolean withinAfterSale(TradeOrder order, LocalDateTime now) {
        return afterSaleDeadline(order)
                .map(deadline -> !now.isAfter(deadline) && !now.isEqual(deadline))
                .orElse(true);
    }
}
