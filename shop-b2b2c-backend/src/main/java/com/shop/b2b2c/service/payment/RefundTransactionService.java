/*
 * 文件：RefundTransactionService.java
 * 用途：T33 退款数据库事务层：退款额度/服务费预占、退款记录落库、成功确认、失败释放与全额退款库存回退。
 * 维护注意：
 *  1. 本类只做本地事务，不调用外部渠道；渠道调用必须在事务外由 RefundService 执行，避免长事务占用订单行锁。
 *  2. 预占、成功、失败全部使用条件 UPDATE；先查后改的并发不安全写法禁止出现在本类。
 *  3. 商品退款额度 refunded_amount/fee_refunded_amount 含 PROCESSING 预占；只有成功记录累计达到实付才置订单 REFUNDED 并回退库存。
 *  4. source=DUPLICATE_PAYMENT 只落退款记录，不占用商品退款额度、不冲正服务费、不回退库存。
 *  5. 退款记录是资金证据，失败也保留，不提供删除。
 *  6. T34：退款成功迁移后接服务费账本红字/冲减欠费；本类不重建退款状态机与额度预占逻辑。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeOrderItem;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.mapper.ProductSkuMapper;
import com.shop.b2b2c.mapper.TradeOrderItemMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefundTransactionService {

    public static final String SOURCE_ORDER = "ORDER";
    public static final String SOURCE_DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private static final Set<String> REFUNDABLE_ORDER_STATUS = Set.of("PAID", "SHIPPED", "COMPLETED");
    private static final DateTimeFormatter REFUND_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final TradeOrderMapper tradeOrderMapper;
    private final TradeOrderItemMapper tradeOrderItemMapper;
    private final TradeRefundMapper tradeRefundMapper;
    private final ProductSkuMapper productSkuMapper;
    private final PaymentEventMapper paymentEventMapper;
    private final com.shop.b2b2c.service.MerchantAccountService merchantAccountService;

    /**
     * 订单商品退款：加订单行锁、校验额度、按快照比例预占服务费并落 PROCESSING 退款记录。
     * 同一订单内相同幂等键返回原记录；已 FAILED 的记录复用并重置为 PROCESSING 以便重试。
     */
    @Transactional
    public TradeRefund prepareOrderRefund(Long merchantId, String orderSn, BigDecimal amount, String reason,
                                          String idempotencyKey, Long operatorId) {
        if (orderSn == null || orderSn.isBlank()) {
            throw new BusinessException(400, "订单号不能为空");
        }
        TradeOrder order = tradeOrderMapper.findByOrderSn(orderSn)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));
        requireMerchantOwns(order, merchantId);
        order = tradeOrderMapper.lockById(order.getId());
        if (order == null) {
            throw new BusinessException(409, "订单状态已变化，请刷新后重试");
        }
        CollectionMode.requireStageOne(order.getCollectionMode());
        if (!REFUNDABLE_ORDER_STATUS.contains(order.getOrderStatus())) {
            throw new BusinessException(409, "订单当前状态不允许退款: " + order.getOrderStatus());
        }
        BigDecimal normalizedAmount = requirePositiveAmount(amount);
        BigDecimal payAmount = order.getPayAmount() == null ? ZERO : order.getPayAmount();
        BigDecimal alreadyRefunded = order.getRefundedAmount() == null ? ZERO : order.getRefundedAmount();
        BigDecimal remaining = payAmount.subtract(alreadyRefunded);
        if (normalizedAmount.compareTo(remaining) > 0) {
            throw new BusinessException(409, "退款金额超过订单可退余额: 剩余 " + remaining.toPlainString());
        }

        TradeRefund existing = tradeRefundMapper
                .findByOrderIdAndKey(order.getId(), idempotencyKey).orElse(null);
        if (existing != null) {
            if (!STATUS_FAILED.equals(existing.getStatus())) {
                return existing;
            }
            // 失败记录复用：先重新预占额度，再把记录重置为 PROCESSING；并发重试只允许一个生效。
            BigDecimal retryFee = calculateFeeRefund(order, normalizedAmount);
            if (tradeRefundMapper.reserveRefund(order.getId(), normalizedAmount, retryFee) != 1) {
                throw new BusinessException(409, "订单退款额度或服务费已变化，请刷新后重试");
            }
            if (tradeRefundMapper.markRetry(existing.getId()) != 1) {
                tradeRefundMapper.releaseRefundReservation(order.getId(), normalizedAmount, retryFee);
                return tradeRefundMapper.selectById(existing.getId());
            }
            existing.setRefundAmount(normalizedAmount);
            existing.setFeeRefundAmount(retryFee);
            existing.setStatus(STATUS_PROCESSING);
            existing.setFailureReason(null);
            return existing;
        }

        BigDecimal feeRefund = calculateFeeRefund(order, normalizedAmount);
        if (tradeRefundMapper.reserveRefund(order.getId(), normalizedAmount, feeRefund) != 1) {
            throw new BusinessException(409, "订单退款额度或服务费已变化，请刷新后重试");
        }

        TradeRefund refund = buildRefund(order, normalizedAmount, feeRefund, reason,
                idempotencyKey, operatorId, SOURCE_ORDER, null);
        try {
            tradeRefundMapper.insert(refund);
        } catch (DuplicateKeyException race) {
            // 并发同键：以先落库的记录为准，本次预占由数据库事务回滚释放。
            return tradeRefundMapper.findByOrderIdAndKey(order.getId(), idempotencyKey)
                    .orElseThrow(() -> new BusinessException(409, "退款请求并发冲突，请重试"));
        }
        return refund;
    }

    /**
     * 重复付款补偿：只针对渠道侧多收的第二笔资金，退款额等于重复付款事件实收，不占商品退款额度。
     */
    @Transactional
    public TradeRefund prepareDuplicateCompensation(Long merchantId, Long eventId, String idempotencyKey,
                                                    Long operatorId) {
        PaymentEvent event = paymentEventMapper.selectById(eventId);
        if (event == null || !"DUPLICATE_PAYMENT".equals(event.getEventStatus())) {
            throw new BusinessException(404, "重复付款事件不存在或状态不允许补偿");
        }
        TradeOrder order = tradeOrderMapper.findByOrderSn(event.getOrderSn())
                .orElseThrow(() -> new BusinessException(404, "重复付款关联订单不存在"));
        requireMerchantOwns(order, merchantId);

        TradeRefund existing = tradeRefundMapper.findBySourceEventId(eventId).orElse(null);
        if (existing != null) {
            if (STATUS_FAILED.equals(existing.getStatus())
                    && tradeRefundMapper.markRetry(existing.getId()) == 1) {
                existing.setStatus(STATUS_PROCESSING);
                existing.setFailureReason(null);
            }
            return existing;
        }
        BigDecimal amount = requirePositiveAmount(event.getPayAmount());
        String channel = PayChannel.normalize(event.getChannel());
        TradeRefund refund = buildRefund(order, amount, ZERO, "重复付款原路退回", idempotencyKey,
                operatorId, SOURCE_DUPLICATE_PAYMENT, eventId);
        refund.setChannel(channel);
        refund.setChannelTradeNo(event.getPayTradeNo());
        try {
            tradeRefundMapper.insert(refund);
        } catch (DuplicateKeyException race) {
            return tradeRefundMapper.findBySourceEventId(eventId)
                    .orElseThrow(() -> new BusinessException(409, "重复付款补偿并发冲突，请重试"));
        }
        return refund;
    }

    /**
     * 渠道确认退款成功：仅 PROCESSING 记录可迁移；商品退款累计成功额达到实付时置 REFUNDED 并回退库存。
     */
    @Transactional
    public void finalizeSuccess(Long refundId, String channelRefundNo) {
        TradeRefund refund = tradeRefundMapper.selectById(refundId);
        if (refund == null || tradeRefundMapper.markSuccess(refundId, channelRefundNo) != 1) {
            return;
        }
        if (!SOURCE_ORDER.equals(refund.getSource())) {
            return;
        }
        // T34：先按已成功退款额冲正服务费账本，再判断是否整单退款；部分退款也必须冲正。
        TradeOrder order = tradeOrderMapper.selectById(refund.getOrderId());
        if (order != null) {
            merchantAccountService.reverseOrderFee(order, refund);
        }
        if (tradeRefundMapper.markOrderRefundedIfFullyRefunded(refund.getOrderId()) != 1) {
            return;
        }
        // 只有成功迁移为 REFUNDED 的这一次调用回退库存，避免重复同步重复加库存。
        List<TradeOrderItem> items = tradeOrderItemMapper.findByOrderId(refund.getOrderId());
        for (TradeOrderItem item : items) {
            if (item.getQuantity() != null && item.getQuantity() > 0) {
                productSkuMapper.addStock(item.getSkuId(), refund.getMerchantId(), item.getQuantity());
            }
        }
    }

    /**
     * 渠道退款失败：迁移 FAILED 并释放商品退款/服务费预占；重复付款补偿没有预占可释放。
     */
    @Transactional
    public void finalizeFailure(Long refundId, String reason) {
        TradeRefund refund = tradeRefundMapper.selectById(refundId);
        if (refund == null || tradeRefundMapper.markFailed(refundId, truncate(reason)) != 1) {
            return;
        }
        if (SOURCE_ORDER.equals(refund.getSource())) {
            tradeRefundMapper.releaseRefundReservation(refund.getOrderId(),
                    refund.getRefundAmount(), refund.getFeeRefundAmount());
        }
    }

    /**
     * 按订单快照比例冲正服务费：feeRefund = commissionAmount * refundAmount / payAmount。
     * 本次退款使累计退款达到实付时取剩余全部服务费，避免逐次四舍五入留下无法冲正的分差。
     */
    private BigDecimal calculateFeeRefund(TradeOrder order, BigDecimal refundAmount) {
        BigDecimal payAmount = order.getPayAmount() == null ? ZERO : order.getPayAmount();
        BigDecimal commission = order.getCommissionAmount() == null ? ZERO : order.getCommissionAmount();
        BigDecimal alreadyFee = order.getFeeRefundedAmount() == null ? ZERO : order.getFeeRefundedAmount();
        BigDecimal alreadyRefunded = order.getRefundedAmount() == null ? ZERO : order.getRefundedAmount();
        if (payAmount.signum() <= 0 || commission.signum() <= 0) {
            return ZERO;
        }
        BigDecimal feeRefund;
        if (refundAmount.add(alreadyRefunded).compareTo(payAmount) >= 0) {
            feeRefund = commission.subtract(alreadyFee);
        } else {
            feeRefund = commission.multiply(refundAmount)
                    .divide(payAmount, 2, RoundingMode.HALF_UP);
        }
        BigDecimal feeRemaining = commission.subtract(alreadyFee);
        if (feeRefund.compareTo(feeRemaining) > 0) {
            feeRefund = feeRemaining;
        }
        return feeRefund.max(ZERO);
    }

    private TradeRefund buildRefund(TradeOrder order, BigDecimal amount, BigDecimal feeRefund, String reason,
                                    String idempotencyKey, Long operatorId, String source, Long sourceEventId) {
        TradeRefund refund = new TradeRefund();
        refund.setRefundNo("RF" + REFUND_NO_TIME.format(LocalDateTime.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        refund.setOrderId(order.getId());
        refund.setOrderSn(order.getOrderSn());
        refund.setMerchantId(order.getMerchantId());
        refund.setCollectionMode(order.getCollectionMode());
        refund.setChannel(PayChannel.normalize(order.getPayChannel()));
        refund.setSource(source);
        refund.setSourceEventId(sourceEventId);
        refund.setIdempotencyKey(idempotencyKey);
        refund.setRefundAmount(amount);
        refund.setFeeRefundAmount(feeRefund);
        refund.setStatus(STATUS_PROCESSING);
        refund.setChannelTradeNo(order.getPayTradeNo());
        refund.setReason(truncate(reason));
        refund.setRequestedBy(operatorId);
        refund.setCreatedAt(LocalDateTime.now());
        refund.setUpdatedAt(LocalDateTime.now());
        return refund;
    }

    private BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(400, "退款金额必须大于 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(400, "退款金额最多两位小数");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private void requireMerchantOwns(TradeOrder order, Long merchantId) {
        if (merchantId != null && !merchantId.equals(order.getMerchantId())) {
            throw new BusinessException(403, "无权操作非本店订单");
        }
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 256 ? value : value.substring(0, 256);
    }
}
