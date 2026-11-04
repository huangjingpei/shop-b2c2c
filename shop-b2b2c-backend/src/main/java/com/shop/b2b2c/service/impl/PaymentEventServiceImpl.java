/*
 * 文件：PaymentEventServiceImpl.java
 * 用途：实现支付渠道到账事件持久化、资金要素核对与本地履约解耦 (R13)。
 */
package com.shop.b2b2c.service.impl;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PeerStatus;
import com.shop.b2b2c.common.TradeGuards;
import com.shop.b2b2c.dto.PaymentEventDTOs;
import com.shop.b2b2c.entity.*;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.PaymentEventRecorder;
import com.shop.b2b2c.service.PaymentEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventServiceImpl implements PaymentEventService {

    private final PaymentEventMapper paymentEventMapper;
    private final PaymentEventRecorder eventRecorder;
    private final TradeOrderMapper orderMapper;
    private final TradeOrderItemMapper itemMapper;
    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final MerchantStoreMapper storeMapper;
    private final InventoryReservationMapper reservationMapper;
    private final ProductVirtualCardMapper cardMapper;
    private final com.shop.b2b2c.service.CardCipherService cardCipher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentEventDTOs.PaymentProcessingResult processPaymentEvent(PaymentEventDTOs.IncomingPaymentEvent event) {
        if (event == null || event.getChannel() == null || event.getPayTradeNo() == null || event.getOrderSn() == null) {
            throw new BusinessException(400, "支付事件核心要素不能为空");
        }
        String channel = event.getChannel().trim().toUpperCase();
        String payTradeNo = event.getPayTradeNo().trim();
        String orderSn = event.getOrderSn().trim();

        // 1. 唯一性排重检查 (防止同一渠道交易号被多笔订单盗用或重复履约)
        PaymentEvent existing = paymentEventMapper.findByChannelAndTradeNo(channel, payTradeNo);
        if (existing != null) {
            if (!orderSn.equals(existing.getOrderSn())) {
                throw new BusinessException(400, "渠道流水号已被其他订单使用");
            }
            if ("SUCCESS".equals(existing.getEventStatus())) {
                TradeOrder currentOrder = orderMapper.findByOrderSn(orderSn).orElse(null);
                return PaymentEventDTOs.PaymentProcessingResult.builder()
                        .eventId(existing.getId())
                        .channel(channel)
                        .payTradeNo(payTradeNo)
                        .orderSn(orderSn)
                        .success(true)
                        .eventStatus(existing.getEventStatus())
                        .orderStatus(currentOrder != null ? currentOrder.getOrderStatus() : "PAID")
                        .message("支付事件已在库且已成功履约 (幂等返回)")
                        .build();
            }
        }

        // 2. 查单并加排他行锁
        TradeOrder order = orderMapper.findByOrderSn(orderSn)
                .orElseThrow(() -> new BusinessException("关联订单不存在: " + orderSn));
        order = orderMapper.lockById(order.getId());
        if (order == null) {
            throw new BusinessException("无法锁定订单行: " + orderSn);
        }

        // 3. 构建并独立事务持久化支付到账事件流水 (确保后续发生业务错误回滚时流水依然在库可审计)
        PaymentEvent pe = existing != null ? existing : new PaymentEvent();
        pe.setChannel(channel);
        pe.setPayTradeNo(payTradeNo);
        pe.setOrderSn(orderSn);
        pe.setMerchantId(order.getMerchantId());
        pe.setPayAmount(event.getPayAmount() != null ? event.getPayAmount() : order.getPayAmount());
        pe.setCurrency(event.getCurrency() != null ? event.getCurrency().trim().toUpperCase() : "CNY");
        pe.setPayerId(event.getPayerId());
        pe.setRawPayload(event.getRawPayload());
        pe.setEventStatus("PROCESSING");

        try {
            pe = eventRecorder.recordInitialEvent(pe);
        } catch (DuplicateKeyException dke) {
            throw new BusinessException(400, "渠道流水号已被其他订单使用");
        }

        // 4. 资金要素严格核验 (商户、实付金额、币种)
        if (event.getMerchantId() != null && !event.getMerchantId().equals(order.getMerchantId())) {
            eventRecorder.recordEventStatus(pe.getId(), "MERCHANT_MISMATCH",
                    "支付事件商户ID (" + event.getMerchantId() + ") 与订单商户ID (" + order.getMerchantId() + ") 不一致");
            throw new BusinessException("支付事件商户与订单商户不一致");
        }
        if (event.getPayAmount() != null && order.getPayAmount().compareTo(event.getPayAmount()) != 0) {
            eventRecorder.recordEventStatus(pe.getId(), "AMOUNT_MISMATCH",
                    "实付金额 (" + event.getPayAmount() + ") 与订单应付金额 (" + order.getPayAmount() + ") 不符");
            throw new BusinessException("实付金额与订单应付金额不符");
        }
        if (event.getCurrency() != null && !"CNY".equalsIgnoreCase(event.getCurrency().trim())) {
            eventRecorder.recordEventStatus(pe.getId(), "CURRENCY_MISMATCH", "交易币种不符: " + event.getCurrency());
            throw new BusinessException("交易币种不符");
        }

        // 5. 订单生命周期与迟到款处理 (超期已关单不得自动交付数字资产，必须转入补偿/退款流水)
        if (Set.of("CLOSED", "REFUNDED").contains(order.getOrderStatus())
                || PeerStatus.EXPIRED.equals(order.getPeerStatus())
                || PeerStatus.CANCELLED.equals(order.getPeerStatus())) {
            eventRecorder.recordEventStatus(pe.getId(), "LATE_PAYMENT", "订单已超时关闭，支付资金已进入自动退款与补偿流程");
            throw new BusinessException("订单已超时关闭，支付资金已进入自动退款与补偿流程");
        }

        // 6. 已支付单据幂等返回
        if (Set.of("PAID", "COMPLETED", "SHIPPED").contains(order.getOrderStatus())) {
            eventRecorder.recordEventStatus(pe.getId(), "SUCCESS", null);
            return PaymentEventDTOs.PaymentProcessingResult.builder()
                    .eventId(pe.getId())
                    .channel(channel)
                    .payTradeNo(payTradeNo)
                    .orderSn(orderSn)
                    .success(true)
                    .eventStatus("SUCCESS")
                    .orderStatus(order.getOrderStatus())
                    .message("订单先前已落账成功")
                    .build();
        }

        // 7. CAS原子更新订单为PAID
        int updated;
        if (order.getPeerPayToken() != null && !order.getPeerPayToken().isBlank()) {
            // 代付两段式订单：严格要求持有有效 RESERVED_LOCK 且预占未过期
            updated = orderMapper.markPaidIfUnpaid(order.getId(), event.getPayerId(), payTradeNo, channel);
        } else {
            // 普通直购订单：要求处于 UNPAID 状态且未超期
            updated = orderMapper.markOrderPaidIfUnpaid(order.getId(), event.getPayerId(), payTradeNo, channel);
        }
        if (updated != 1) {
            eventRecorder.recordEventStatus(pe.getId(), "ORDER_STATUS_INVALID", "订单状态或支付预占已失效");
            throw new BusinessException("订单状态或支付预占已失效");
        }
        order.setOrderStatus("PAID");
        order.setPeerStatus(PeerStatus.PAID);
        order.setPeerPayerId(event.getPayerId());
        order.setPayTradeNo(payTradeNo);
        order.setPaymentChannel(channel);
        order.setLockExpireTime(null);

        // 8. 校验商品与本地履约发货/发卡
        List<TradeOrderItem> items = itemMapper.findByOrderSn(order.getOrderSn());
        if (items.isEmpty()) {
            eventRecorder.recordEventStatus(pe.getId(), "FULFILLMENT_FAILED", "订单未包含明细商品");
            throw new BusinessException("订单未包含明细商品");
        }

        Product payableProduct = productMapper.findById(items.get(0).getProductId())
                .orElseThrow(() -> new BusinessException("商品不存在"));
        TradeGuards.requirePurchasable(payableProduct, storeMapper.findById(order.getMerchantId()).orElse(null));
        if (!payableProduct.getGoodsType().equals(order.getGoodsType())) {
            eventRecorder.recordEventStatus(pe.getId(), "FULFILLMENT_FAILED", "商品交付类型已变化，禁止继续交付");
            throw new BusinessException("商品交付类型已变化，禁止继续交付");
        }

        // 9. 核销库存预占状态
        InventoryReservation held = reservationMapper.find(order.getId());
        if (held != null) {
            TradeOrderItem item = items.get(0);
            if (!"HELD".equals(held.getStatus()) || !held.getSkuId().equals(item.getSkuId())
                    || !held.getProductId().equals(item.getProductId()) || !held.getMerchantId().equals(order.getMerchantId())
                    || !held.getQuantity().equals(item.getQuantity()) || !held.getExpiresAt().isAfter(LocalDateTime.now())
                    || reservationMapper.transition(order.getId(), "CONSUMED") != 1) {
                eventRecorder.recordEventStatus(pe.getId(), "FULFILLMENT_FAILED", "库存预占核销失败");
                throw new BusinessException("库存预占已失效");
            }
        } else {
            // 普通无预占自购单：此时原子扣减物理库存
            for (TradeOrderItem item : items) {
                if (item.getQuantity() == null || item.getQuantity() <= 0
                        || skuMapper.deductStock(item.getSkuId(), item.getProductId(), order.getMerchantId(), item.getQuantity()) != 1) {
                    eventRecorder.recordEventStatus(pe.getId(), "FULFILLMENT_FAILED", "商品库存不足或SKU归属异常");
                    throw new BusinessException("商品库存不足或SKU归属异常");
                }
            }
        }

        // 10. 针对商品交付类型执行具体交付
        if (order.getGoodsType() == 2) {
            // 虚拟卡密自动分配
            TradeOrderItem firstItem = items.get(0);
            List<ProductVirtualCard> availableCards = cardMapper.lockAvailableCards(
                    firstItem.getSkuId(), firstItem.getProductId(), order.getMerchantId(), firstItem.getQuantity());
            if (availableCards.size() >= firstItem.getQuantity()) {
                List<String> deliveredCards = new ArrayList<>();
                for (int i = 0; i < firstItem.getQuantity(); i++) {
                    ProductVirtualCard card = availableCards.get(i);
                    if (cardMapper.claimCard(card.getId(), order.getMerchantId(), firstItem.getSkuId(), order.getOrderSn()) != 1) {
                        eventRecorder.recordEventStatus(pe.getId(), "FULFILLMENT_FAILED", "卡密已被分配，需人工审核");
                        throw new BusinessException("卡密已被分配，请重试");
                    }
                    String plain = cardCipher.decrypt(card.getCardContent());
                    deliveredCards.add(plain);
                }
                // R05: 订单明细中卡密快照使用分级加密存储，避免正文多处直接泄露
                firstItem.setCardDetails(cardCipher.encrypt(String.join("\n", deliveredCards)));
                itemMapper.save(firstItem);

                order.setOrderStatus("COMPLETED");
                order.setSharingStatus("PENDING");
                // R05: 服务凭证仅保存脱敏核销审计摘要，严禁在服务凭证中冗余明文卡密
                order.setServiceVoucher("系统卡密已自动交付 (共" + deliveredCards.size() + "张)");
                order.setFinishTime(LocalDateTime.now());
            } else {
                eventRecorder.recordEventStatus(pe.getId(), "FULFILLMENT_FAILED", "卡密库存不足，已转入交付异常待人工审核");
                throw new BusinessException("卡密库存不足，不能转为人工服务");
            }
        } else {
            // 实物或服务商品
            order.setOrderStatus("PAID");
            order.setSharingStatus("PENDING");
        }

        order = orderMapper.save(order);
        eventRecorder.recordEventStatus(pe.getId(), "SUCCESS", null);

        log.info("【支付事件履约完成】渠道={}, 流水号={}, 订单号={}, 状态={}", channel, payTradeNo, orderSn, order.getOrderStatus());
        return PaymentEventDTOs.PaymentProcessingResult.builder()
                .eventId(pe.getId())
                .channel(channel)
                .payTradeNo(payTradeNo)
                .orderSn(orderSn)
                .success(true)
                .eventStatus("SUCCESS")
                .orderStatus(order.getOrderStatus())
                .message("支付到账核验通过并成功完成本地履约")
                .build();
    }

    @Override
    public PaymentEvent findEvent(String channel, String payTradeNo) {
        if (channel == null || payTradeNo == null) return null;
        return paymentEventMapper.findByChannelAndTradeNo(channel.trim().toUpperCase(), payTradeNo.trim());
    }
}
