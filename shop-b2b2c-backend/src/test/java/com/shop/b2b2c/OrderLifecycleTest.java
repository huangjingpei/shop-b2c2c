/*
 * 文件：OrderLifecycleTest.java
 * 用途：校验 F07 自动完成阈值与售后时效的纯计算口径（完成时间边界、缺失时间与未完成状态）。
 * 维护注意：本用例不连数据库、不依赖时钟；天数常量变化须同步调整期望值，避免测试掩盖业务口径变化。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.OrderLifecycle;
import com.shop.b2b2c.entity.TradeOrder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class OrderLifecycleTest {

    @Test
    void autoCompleteCutoffIsThresholdDaysBeforeNow() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 7, 10, 0);
        assertEquals(LocalDateTime.of(2026, 10, 28, 10, 0), OrderLifecycle.autoCompleteCutoff(now));
    }

    @Test
    void afterSaleDeadlineOnlyForCompletedOrdersWithFinishTime() {
        TradeOrder order = new TradeOrder();
        assertTrue(OrderLifecycle.afterSaleDeadline(order).isEmpty());

        order.setOrderStatus("SHIPPED");
        order.setFinishTime(LocalDateTime.of(2026, 11, 1, 10, 0));
        assertTrue(OrderLifecycle.afterSaleDeadline(order).isEmpty());

        order.setOrderStatus("COMPLETED");
        order.setFinishTime(null);
        assertTrue(OrderLifecycle.afterSaleDeadline(order).isEmpty());

        order.setFinishTime(LocalDateTime.of(2026, 11, 1, 10, 0));
        assertEquals(LocalDateTime.of(2026, 11, 16, 10, 0), OrderLifecycle.afterSaleDeadline(order).orElseThrow());
    }

    @Test
    void withinAfterSaleIsHalfOpenAndDoesNotBlockUndeterminedOrders() {
        LocalDateTime finish = LocalDateTime.of(2026, 11, 1, 10, 0);
        TradeOrder order = new TradeOrder();
        order.setOrderStatus("COMPLETED");
        order.setFinishTime(finish);
        LocalDateTime deadline = finish.plusDays(OrderLifecycle.AFTER_SALE_DAYS);

        assertTrue(OrderLifecycle.withinAfterSale(order, finish)); // 完成当天
        assertTrue(OrderLifecycle.withinAfterSale(order, deadline.minusSeconds(1)));
        assertFalse(OrderLifecycle.withinAfterSale(order, deadline)); // 截止时刻视为过期
        assertFalse(OrderLifecycle.withinAfterSale(order, deadline.plusSeconds(1)));

        order.setFinishTime(null);
        assertTrue(OrderLifecycle.withinAfterSale(order, LocalDateTime.of(2030, 1, 1, 0, 0)));

        order.setOrderStatus("SHIPPED");
        order.setFinishTime(finish);
        assertTrue(OrderLifecycle.withinAfterSale(order, LocalDateTime.of(2030, 1, 1, 0, 0)));
    }
}
