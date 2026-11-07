/*
 * 文件：PaymentCloseRetryService.java
 * 用途：F08 关单回执重试：按回执扫描失败的关单尝试，二次校验订单已关闭后重新调用渠道关单。
 * 维护注意：
 *  1. 只处理本地已 CLOSED/CANCELLED 的订单；UNPAID 的关单由商家接口或订单关闭路径自行发起，不在此抢跑。
 *  2. 每轮只取固定批量；单条异常只记录 WARN 并继续，不阻断后续候选；重试次数/退避/窗口由调用方传入。
 *  3. 重试仍调用 PaymentGatewayService.closePaymentBestEffort，新的回执决定是否继续重试；本类不改订单状态、不做退款。
 *  4. 未取得分布式锁，按单实例部署假设设计；多实例部署前必须补跨实例抢占，否则会重复调用渠道关单（渠道侧应幂等）。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCloseRetryService {

    private static final Set<String> CLOSED_STATUSES = Set.of("CLOSED", "CANCELLED");

    private final PaymentCloseReceiptService receipts;
    private final TradeOrderMapper orders;
    private final PaymentGatewayService gateway;

    /**
     * 执行一轮重试。返回实际发起渠道关单调用的条数。
     */
    public int retryPending(int maxAttempts, Duration minInterval, Duration window, int limit) {
        if (maxAttempts <= 0 || limit <= 0 || minInterval == null || window == null) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        List<String> orderSns = receipts.findRetryableOrderSn(now.minus(window), now.minus(minInterval),
                maxAttempts, limit);
        int attempted = 0;
        for (String orderSn : orderSns) {
            try {
                TradeOrder order = orders.findByOrderSn(orderSn).orElse(null);
                if (order == null || !CLOSED_STATUSES.contains(order.getOrderStatus())) {
                    continue;
                }
                gateway.closePaymentBestEffort(order);
                attempted++;
            } catch (Exception e) {
                log.warn("渠道关单重试异常，跳过当前订单等待下一轮 orderSn={}", orderSn, e);
            }
        }
        if (!orderSns.isEmpty()) {
            log.info("渠道关单重试本轮候选 {} 条，实际调用 {} 条", orderSns.size(), attempted);
        }
        if (orderSns.size() >= limit) {
            log.warn("渠道关单重试达到批量上限，可能存在积压 limit={}", limit);
        }
        return attempted;
    }
}
