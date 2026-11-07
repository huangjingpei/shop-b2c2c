/*
 * 文件：OrderCancellationService.java
 * 用途：F08 买家取消未支付订单：校验领取凭证、释放预占库存、置 CLOSED，并在事务提交后尽力关闭渠道交易。
 * 维护注意：
 *  1. 只有 UNPAID 可取消；已关闭/已取消/已超时重复提交按幂等成功返回，不重复释放库存或覆盖完成时间。
 *  2. 已支付、已发货、已完成、已退款订单一律拒绝 409，不得用取消接口绕过退款/售后流程。
 *  3. 订单状态变更与预占释放必须在同一事务、同一订单行锁内完成；库存回补失败整体回滚。
 *  4. 渠道关单是事务提交后的尽力而为动作；失败仅 WARN，不阻断本地关单，迟到支付由 PaymentEventService 拒绝并进入异常队列。
 *  5. queryToken 只从请求体读取，不记录、不回显；买家的 buyerContact 仍按原免密查单凭据匹配。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PeerStatus;
import com.shop.b2b2c.dto.BuyerLookupRequest;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.service.payment.PaymentGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCancellationService {

    /** 已是终态/关闭态的订单，重复取消按成功返回。 */
    private static final Set<String> ALREADY_CLOSED = Set.of("CLOSED", "REFUNDED", "CANCELLED");

    private final TradeOrderMapper orders;
    private final InventoryReservationService reservations;
    private final PaymentGatewayService paymentGateway;

    @Transactional(rollbackFor = Exception.class)
    public TradeOrder cancelByBuyer(BuyerLookupRequest request) {
        BuyerLookupRequest req = request == null ? null : request.normalized();
        if (req == null) {
            throw new BusinessException(400, BuyerLookupRequest.INVALID);
        }
        TradeOrder found = orders.findByOrderSnAndBuyerContact(req.orderSn(), req.contact())
                .orElseThrow(() -> new BusinessException(400, BuyerLookupRequest.INVALID));
        if (!BuyerLookupRequest.matches(found.getQueryToken(), req.queryToken())) {
            throw new BusinessException(400, BuyerLookupRequest.INVALID);
        }
        TradeOrder order = Optional.ofNullable(orders.lockById(found.getId()))
                .orElseThrow(() -> new BusinessException(400, BuyerLookupRequest.INVALID));

        if (ALREADY_CLOSED.contains(order.getOrderStatus())) {
            return order;
        }
        if (!"UNPAID".equals(order.getOrderStatus())) {
            throw new BusinessException(409, "订单已支付或已进入履约，不能直接取消");
        }

        // 预占不存在（普通直接下单）不视为失败；存在则条件释放并回补库存。
        reservations.release(order.getId());
        order.setOrderStatus("CLOSED");
        // 已锁定的代付/预占在订单取消后必须退出可付款状态，并清掉支付截止，避免前端继续展示待支付倒计时。
        if (PeerStatus.RESERVED_LOCK.equals(order.getPeerStatus())
                || (order.getPeerPayType() != null && order.getPeerPayType() != 0)) {
            order.setPeerStatus(PeerStatus.CANCELLED);
        }
        order.setLockExpireTime(null);
        orders.save(order);
        closeChannelAfterCommit(order);
        return order;
    }

    private void closeChannelAfterCommit(TradeOrder order) {
        Runnable close = () -> {
            try {
                if (!paymentGateway.closePaymentBestEffort(order)) {
                    log.info("渠道关单未执行（未配置/未启用/无需关单） orderSn={}", order.getOrderSn());
                }
            } catch (Exception e) {
                log.warn("渠道关单异常，本地订单已关闭 orderSn={}", order.getOrderSn(), e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    close.run();
                }
            });
        } else {
            close.run();
        }
    }
}
