/*
 * 文件：PaymentEventServiceImpl.java
 * 用途：实现支付渠道到账事件持久化、资金要素自证、重复支付补偿与本地履约解耦 (R13/T05/T06/T07)。
 * 维护注意：
 *  1. processPaymentEvent 自身不是声明式事务入口，而由 TransactionTemplate 明确开启业务事务；
 *     事件初始写入、订单 CAS、库存/卡密交付、SUCCESS 标记在同一事务提交，外层回滚不会留下 SUCCESS。
 *  2. 失败审计只在事务结束并回滚后由 recordFailureAudit 独立写入，避免主连接池自锁；事件不得停在 PROCESSING。
 *  3. 重放短路必须回查订单真实状态与卡密交付结果；事件 SUCCESS 但订单 UNPAID 时以同一事件重新驱动。
 *  4. 实收金额/币种必须来自渠道事件，禁止从订单 payAmount 反填；缺失或不等即拒绝，不得交付资产。
 *  5. 已支付订单收到不同流水号时必须落 DUPLICATE_PAYMENT 事件，不得静默返回；退款动作由后续补偿/退款任务处理。
 *  6. 不新建第二套支付事件表，不恢复公开 Mock/未验签到账入口。
 *  7. T34：首次支付成功时在同一事务内扣减/挂账服务费；重放不重复扣费，扣费失败不得让事件停在 SUCCESS。
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventServiceImpl implements PaymentEventService {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FULFILLMENT_FAILED = "FULFILLMENT_FAILED";
    private static final String STATUS_DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT";

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
    private final com.shop.b2b2c.service.MerchantAccountService merchantAccountService;
    private final PlatformTransactionManager transactionManager;

    @Override
    public PaymentEventDTOs.PaymentProcessingResult processPaymentEvent(PaymentEventDTOs.IncomingPaymentEvent event) {
        validateIncomingEvent(event);
        String channel = event.getChannel().trim().toUpperCase();
        String payTradeNo = event.getPayTradeNo().trim();
        String orderSn = event.getOrderSn().trim();

        AtomicReference<PaymentEvent> auditSnapshot = new AtomicReference<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            return transaction.execute(status -> processInTransaction(event, channel, payTradeNo, orderSn, auditSnapshot));
        } catch (PaymentEventOwnershipConflict conflict) {
            // 流水号属于其他订单：不得改写对方事件，也不得把错误记到本单。
            throw conflict;
        } catch (PaymentEventRejection rejection) {
            auditFailure(auditSnapshot.get(), rejection.eventStatus, rejection.getMessage());
            throw rejection;
        } catch (BusinessException business) {
            auditFailure(auditSnapshot.get(), STATUS_FULFILLMENT_FAILED, business.getMessage());
            throw business;
        } catch (RuntimeException unexpected) {
            auditFailure(auditSnapshot.get(), STATUS_FULFILLMENT_FAILED, unexpected.getMessage());
            throw unexpected;
        }
    }

    private void validateIncomingEvent(PaymentEventDTOs.IncomingPaymentEvent event) {
        if (event == null || event.getChannel() == null || event.getPayTradeNo() == null || event.getOrderSn() == null) {
            throw new BusinessException(400, "支付事件核心要素不能为空");
        }
        if (event.getPayAmount() == null) {
            // T06：不得从订单应付金额反填，缺失实收金额直接拒绝。
            throw new BusinessException(400, "缺少渠道实收金额，拒绝落账");
        }
        if (event.getCurrency() == null || event.getCurrency().trim().isEmpty()) {
            throw new BusinessException(400, "缺少交易币种，拒绝落账");
        }
    }

    private PaymentEventDTOs.PaymentProcessingResult processInTransaction(
            PaymentEventDTOs.IncomingPaymentEvent event, String channel, String payTradeNo,
            String orderSn, AtomicReference<PaymentEvent> auditSnapshot) {

        // 1. 唯一性排重检查 (防止同一渠道交易号被多笔订单盗用或重复履约)
        PaymentEvent existing = paymentEventMapper.findByChannelAndTradeNo(channel, payTradeNo);
        if (existing != null && !orderSn.equals(existing.getOrderSn())) {
            throw new PaymentEventOwnershipConflict(400, "渠道流水号已被其他订单使用");
        }

        // 2. 查单并加排他行锁
        TradeOrder order = orderMapper.findByOrderSn(orderSn)
                .orElseThrow(() -> new BusinessException("关联订单不存在: " + orderSn));
        order = orderMapper.lockById(order.getId());
        if (order == null) {
            throw new BusinessException("无法锁定订单行: " + orderSn);
        }
        List<TradeOrderItem> items = itemMapper.findByOrderSn(orderSn);
        if (items.isEmpty()) {
            throw new BusinessException("订单未包含明细商品");
        }

        // 3. 支付到账事件与业务写入共用同一事务；外层回滚时不会残留 SUCCESS
        PaymentEvent pe = existing != null ? existing : new PaymentEvent();
        pe.setChannel(channel);
        pe.setPayTradeNo(payTradeNo);
        pe.setOrderSn(orderSn);
        pe.setMerchantId(order.getMerchantId());
        // T32：货款归集模式取订单快照；渠道商户号只作审计与核验，不能替换平台内部商户ID。
        pe.setCollectionMode(order.getCollectionMode());
        pe.setChannelMerchantId(event.getChannelMerchantId());
        pe.setPlatformTradeNo(null); // 阶段一平台不代收，平台流水号为空
        pe.setPayAmount(event.getPayAmount());
        pe.setCurrency(event.getCurrency().trim().toUpperCase());
        pe.setPayerId(event.getPayerId());
        pe.setRawPayload(event.getRawPayload());
        pe.setEventStatus(STATUS_PROCESSING);

        try {
            pe = eventRecorder.recordInitialEvent(pe);
        } catch (DuplicateKeyException dke) {
            // 并发通知下唯一键冲突可能来自同一订单：重新读取已存在事件，同一订单按幂等重放继续，
            // 只有流水号确实属于其他订单时才判定归属冲突，不能把同单重试误报为盗用。
            PaymentEvent concurrent = paymentEventMapper.findByChannelAndTradeNo(channel, payTradeNo);
            if (concurrent == null || !orderSn.equals(concurrent.getOrderSn())) {
                throw new PaymentEventOwnershipConflict(400, "渠道流水号已被其他订单使用");
            }
            existing = concurrent;
            pe = concurrent;
        }
        auditSnapshot.set(pe);

        // 4. 资金要素严格核验 (商户、实收金额、币种)，禁止从订单反填
        if (event.getMerchantId() != null && !event.getMerchantId().equals(order.getMerchantId())) {
            reject(pe.getId(), "MERCHANT_MISMATCH",
                    "支付事件商户与订单商户不一致: 事件=" + event.getMerchantId() + ", 订单=" + order.getMerchantId());
        }
        if (order.getPayAmount().compareTo(event.getPayAmount()) != 0) {
            reject(pe.getId(), "AMOUNT_MISMATCH",
                    "实付金额 (" + event.getPayAmount() + ") 与订单应付金额 (" + order.getPayAmount() + ") 不符");
        }
        if (!"CNY".equalsIgnoreCase(event.getCurrency().trim())) {
            reject(pe.getId(), "CURRENCY_MISMATCH", "交易币种不符: " + event.getCurrency());
        }

        // 5. 订单生命周期与迟到款处理 (超期已关单不得自动交付数字资产，必须转入补偿/退款流水)
        if (Set.of("CLOSED", "REFUNDED").contains(order.getOrderStatus())
                || PeerStatus.EXPIRED.equals(order.getPeerStatus())
                || PeerStatus.CANCELLED.equals(order.getPeerStatus())) {
            reject(pe.getId(), "LATE_PAYMENT", "订单已超时关闭，支付资金已进入自动退款与补偿流程");
        }

        // 6. 已支付单据：回查交付结果，不盲目短路
        boolean alreadyPaid = Set.of("PAID", "COMPLETED", "SHIPPED").contains(order.getOrderStatus());
        if (alreadyPaid) {
            if (existing == null) {
                // T07：订单已支付但出现不同渠道流水号，第二笔资金必须留痕并进入重复支付补偿队列，
                // 不能因为订单已履约就把它标成 SUCCESS 而静默吞掉资金。
                eventRecorder.recordEventStatus(pe.getId(), STATUS_DUPLICATE_PAYMENT,
                        "订单已支付，第二笔资金已记录并进入重复支付补偿队列");
                return result(pe, order, STATUS_DUPLICATE_PAYMENT,
                        "订单已支付，第二笔资金已记录并进入重复支付补偿队列");
            }
            if (isDelivered(order, items)) {
                // 同一渠道流水号重放：仅当订单已履约时才幂等短路。
                if (!STATUS_SUCCESS.equals(pe.getEventStatus())) {
                    eventRecorder.recordEventStatus(pe.getId(), STATUS_SUCCESS, null);
                }
                return result(pe, order, STATUS_SUCCESS, "支付事件已在库且已成功履约 (幂等返回)");
            }
            // 同一事件重放且交付不完整：保留已支付事实，仅补交付，不再重复扣库存/CAS。
        } else {
            // T09: 先校验商品可购与交付类型，再执行 CAS；已下架/关店/类型变化不得先落 PAID。
            Product payableProduct = productMapper.findById(items.get(0).getProductId())
                    .orElseThrow(() -> new BusinessException("商品不存在"));
            TradeGuards.requirePurchasable(payableProduct, storeMapper.findById(order.getMerchantId()).orElse(null));
            if (!payableProduct.getGoodsType().equals(order.getGoodsType())) {
                reject(pe.getId(), STATUS_FULFILLMENT_FAILED, "商品交付类型已变化，禁止继续交付");
            }

            // T08: 普通订单与代付订单统一以数据库 lock_expire_time 判定支付截止，NULL 不再放行。
            if (order.getLockExpireTime() == null || !order.getLockExpireTime().isAfter(LocalDateTime.now())) {
                reject(pe.getId(), "LATE_PAYMENT", "订单已超过 15 分钟支付截止时间，资金进入迟到支付处理");
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
                reject(pe.getId(), "ORDER_STATUS_INVALID", "订单状态或支付预占已失效");
            }
            order.setOrderStatus("PAID");
            order.setPeerStatus(PeerStatus.PAID);
            order.setPeerPayerId(event.getPayerId());
            order.setPayTradeNo(payTradeNo);
            order.setPaymentChannel(channel);
            order.setLockExpireTime(null);
        }

        // 8. 本地履约发货/发卡（商品可购性已在 CAS 前校验）
        // 9. 首次落账核销库存预占/扣减物理库存；补交付重放不得重复扣减
        if (!alreadyPaid) {
            InventoryReservation held = reservationMapper.find(order.getId());
            if (held != null) {
                TradeOrderItem item = items.get(0);
                if (!"HELD".equals(held.getStatus()) || !held.getSkuId().equals(item.getSkuId())
                        || !held.getProductId().equals(item.getProductId()) || !held.getMerchantId().equals(order.getMerchantId())
                        || !held.getQuantity().equals(item.getQuantity()) || !held.getExpiresAt().isAfter(LocalDateTime.now())
                        || reservationMapper.transition(order.getId(), "CONSUMED") != 1) {
                    reject(pe.getId(), STATUS_FULFILLMENT_FAILED, "库存预占核销失败");
                }
            } else {
                // 普通无预占自购单：此时原子扣减物理库存
                for (TradeOrderItem item : items) {
                    if (item.getQuantity() == null || item.getQuantity() <= 0
                            || skuMapper.deductStock(item.getSkuId(), item.getProductId(), order.getMerchantId(), item.getQuantity()) != 1) {
                        reject(pe.getId(), STATUS_FULFILLMENT_FAILED, "商品库存不足或SKU归属异常");
                    }
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
                        reject(pe.getId(), STATUS_FULFILLMENT_FAILED, "卡密已被分配，需人工审核");
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
                reject(pe.getId(), STATUS_FULFILLMENT_FAILED, "卡密库存不足，已转入交付异常待人工审核");
            }
        } else {
            // 实物或服务商品
            order.setOrderStatus("PAID");
            order.setSharingStatus("PENDING");
        }

        order = orderMapper.save(order);
        // T34：首次到账才扣费；账本幂等键兜底，重放不会重复扣费。
        if (!alreadyPaid) {
            merchantAccountService.chargeOrderFee(order, pe.getId());
        }
        eventRecorder.recordEventStatus(pe.getId(), STATUS_SUCCESS, null);

        log.info("【支付事件履约完成】渠道={}, 流水号={}, 订单号={}, 状态={}", channel, payTradeNo, orderSn, order.getOrderStatus());
        return result(pe, order, STATUS_SUCCESS, "支付到账核验通过并成功完成本地履约");
    }

    private void reject(Long eventId, String status, String message) {
        eventRecorder.recordEventStatus(eventId, status, message);
        throw new PaymentEventRejection(status, message);
    }

    private void auditFailure(PaymentEvent snapshot, String status, String message) {
        if (snapshot == null) return;
        try {
            eventRecorder.recordFailureAudit(snapshot, status, message);
        } catch (RuntimeException auditError) {
            log.error("支付事件失败审计写入异常，原业务异常仍向上抛出 tradeNo={}", snapshot.getPayTradeNo(), auditError);
        }
    }

    private boolean isDelivered(TradeOrder order, List<TradeOrderItem> items) {
        String status = order.getOrderStatus();
        if (!Set.of("PAID", "COMPLETED", "SHIPPED").contains(status)) {
            return false;
        }
        if (order.getGoodsType() != null && order.getGoodsType() == 2) {
            TradeOrderItem item = items.isEmpty() ? null : items.get(0);
            return item != null && item.getCardDetails() != null && !item.getCardDetails().isBlank();
        }
        return true;
    }

    private PaymentEventDTOs.PaymentProcessingResult result(PaymentEvent pe, TradeOrder order,
                                                             String eventStatus, String message) {
        return PaymentEventDTOs.PaymentProcessingResult.builder()
                .eventId(pe.getId())
                .channel(pe.getChannel())
                .payTradeNo(pe.getPayTradeNo())
                .orderSn(pe.getOrderSn())
                .success(true)
                .eventStatus(eventStatus)
                .orderStatus(order.getOrderStatus())
                .message(message)
                .build();
    }

    @Override
    public PaymentEvent findEvent(String channel, String payTradeNo) {
        if (channel == null || payTradeNo == null) return null;
        return paymentEventMapper.findByChannelAndTradeNo(channel.trim().toUpperCase(), payTradeNo.trim());
    }

    @Override
    public int reconcileSuccessfulWithoutFulfillment(int limit) {
        if (limit <= 0) return 0;
        List<PaymentEvent> candidates = paymentEventMapper.findSuccessfulWithoutPaidOrder(Math.min(limit, 200));
        int processed = 0;
        for (PaymentEvent candidate : candidates) {
            try {
                PaymentEventDTOs.IncomingPaymentEvent event = PaymentEventDTOs.IncomingPaymentEvent.builder()
                        .channel(candidate.getChannel())
                        .payTradeNo(candidate.getPayTradeNo())
                        .orderSn(candidate.getOrderSn())
                        .merchantId(candidate.getMerchantId())
                        .payAmount(candidate.getPayAmount())
                        .currency(candidate.getCurrency())
                        .payerId(candidate.getPayerId())
                        .rawPayload(candidate.getRawPayload())
                        .build();
                processPaymentEvent(event);
                processed++;
            } catch (RuntimeException e) {
                log.warn("支付事件补偿重驱动失败，已转失败态或保持原状 tradeNo={}", candidate.getPayTradeNo(), e);
            }
        }
        return processed;
    }

    /** 明确的业务拒绝：携带应写入 payment_event 的终态。 */
    private static class PaymentEventRejection extends BusinessException {
        private final String eventStatus;

        PaymentEventRejection(String eventStatus, String message) {
            super(message);
            this.eventStatus = eventStatus;
        }
    }

    /** 渠道流水号归属冲突：不得把失败审计写到其他订单的事件上。 */
    private static class PaymentEventOwnershipConflict extends BusinessException {
        PaymentEventOwnershipConflict(int code, String message) {
            super(code, message);
        }
    }
}
