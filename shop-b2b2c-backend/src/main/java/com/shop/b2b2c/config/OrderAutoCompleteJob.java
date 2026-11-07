/*
 * 文件：OrderAutoCompleteJob.java
 * 用途：F07 发货超期自动确认收货：将超过 OrderLifecycle.AUTO_COMPLETE_DAYS 仍未确认的实物订单置为 COMPLETED。
 * 维护注意：
 *  1. 只处理 goods_type=1、order_status=SHIPPED 且有 delivery_time 的订单；数字商品不参与。
 *  2. 候选查询排除待审批退款与未结投诉，避免争议中自动完成；CAS 更新仍要求 SHIPPED，防并发覆盖。
 *  3. 采用 id 游标 + 固定批大小 + 最大轮次，避免单轮长事务和积压拖死调度；单条失败只向前移游标。
 *  4. 开关 shop.order.auto-complete-enabled 默认 true，扫描间隔 shop.order.auto-complete-delay-ms 默认 1 小时。
 */
package com.shop.b2b2c.config;

import com.shop.b2b2c.common.OrderLifecycle;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderAutoCompleteJob {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_ROUNDS = 20;

    private final TradeOrderMapper orders;

    @Value("${shop.order.auto-complete-enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${shop.order.auto-complete-delay-ms:3600000}")
    public void scheduled() {
        try {
            complete(LocalDateTime.now());
        } catch (Exception e) {
            log.error("自动确认收货扫描异常，等待下一轮重试", e);
        }
    }

    /** 可测试入口：返回本轮实际完成订单数。 */
    public int complete(LocalDateTime now) {
        if (!enabled) {
            return 0;
        }
        LocalDateTime cutoff = OrderLifecycle.autoCompleteCutoff(now);
        long cursor = 0L;
        int completed = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<Long> ids = orders.findAutoCompleteCandidateIds(cutoff, cursor, BATCH_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                cursor = Math.max(cursor, id);
                try {
                    if (orders.completeIfShipped(id, now) > 0) {
                        completed++;
                    }
                } catch (Exception e) {
                    log.error("自动确认收货失败，已前移游标避免饿死后续订单 orderId={}", id, e);
                }
            }
            if (ids.size() < BATCH_SIZE) {
                break;
            }
            if (round == MAX_ROUNDS - 1) {
                log.warn("自动确认收货达到最大轮次，可能存在积压 cursor={} batch={}", cursor, BATCH_SIZE);
            }
        }
        if (completed > 0) {
            log.info("自动确认收货完成 {} 笔，阈值 cutoff={}", completed, cutoff);
        }
        return completed;
    }
}
