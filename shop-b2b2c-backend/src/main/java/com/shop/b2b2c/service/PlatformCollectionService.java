/*
 * 文件：PlatformCollectionService.java
 * 用途：T40 阶段二平台代收与分账骨架：本地结算台账、分账明细与退分账本落库，不调用真实分账通道。
 * 维护注意：
 * 1. 默认关闭（shop.settlement.platform-collect-enabled=false）。未取得支付/分账资质并完成通道联调前，
 *    任何代收/分账/退分入口都必须拒绝，不得回退到阶段一商家直收，也不得伪造渠道成功。
 * 2. 只接受 collection_mode=PLATFORM_COLLECT 的订单；阶段一 MERCHANT_DIRECT 订单不得进入本服务。
 * 3. 分账接收方复用 T39 的 merchant_payment_account：必须 binding_status=BOUND、渠道商户号非空、
 *    生效时间已到，且 settlement_account_id 已关联阶段一收款账户；不另建接收方表。
 * 4. 金额守恒：gross=平台留成+商家应结；平台留成取订单服务费快照，split_amount 必须非负。
 *    全部使用 BigDecimal 两位小数，禁止浮点。
 * 5. 幂等：结算台账以 order_id 唯一、分账明细以 order_id+payment_account_id 唯一、
 *    退分以 split_detail_id+refund_id 唯一；先查同键记录再写，数据库唯一索引兜底。
 * 6. 退分先于退款：退分只写本地账本并以条件 UPDATE 累计，禁止超过未退分余额；
 *    split_reverse 保存来源退款单号、操作人与时间，作为不可变业务存根。
 * 7. 骨架阶段不推进真实渠道状态，split_status/reverse_status 保持 PENDING；
 *    渠道结果、四方对账与真实资金流转待资质后实现。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import com.shop.b2b2c.entity.PlatformCollectionSettlement;
import com.shop.b2b2c.entity.SplitDetail;
import com.shop.b2b2c.entity.SplitReverse;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.mapper.MerchantPaymentAccountMapper;
import com.shop.b2b2c.mapper.PlatformCollectionSettlementMapper;
import com.shop.b2b2c.mapper.SplitDetailMapper;
import com.shop.b2b2c.mapper.SplitReverseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PlatformCollectionService {

    public static final String STATUS_PENDING = "PENDING";

    /** T40 总开关；默认关闭，取得分账资质并完成通道联调后才允许开启。 */
    @Value("${shop.settlement.platform-collect-enabled:false}")
    private boolean platformCollectEnabled = false;

    private final PlatformCollectionSettlementMapper settlementMapper;
    private final SplitDetailMapper splitDetailMapper;
    private final SplitReverseMapper splitReverseMapper;
    private final MerchantPaymentAccountMapper paymentAccountMapper;

    public boolean enabled() {
        return platformCollectEnabled;
    }

    public void requireEnabled() {
        if (!platformCollectEnabled) {
            throw new BusinessException(409, "阶段二平台代收未启用：需取得支付分账资质并完成通道联调");
        }
    }

    /**
     * 平台代收到账后生成本地结算台账与分账明细；不调用渠道，不改变订单支付/履约状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public PlatformCollectionSettlement prepareSettlement(TradeOrder order, Long paymentEventId,
                                                          String platformTradeNo, BigDecimal grossAmount) {
        requireEnabled();
        validatePlatformCollectOrder(order);
        if (platformTradeNo == null || platformTradeNo.isBlank()) {
            throw new BusinessException(400, "平台代收流水号不能为空");
        }
        BigDecimal gross = requireAmount(grossAmount, "渠道实收金额");
        BigDecimal orderPay = requireAmount(order.getPayAmount(), "订单实付金额");
        if (gross.signum() <= 0 || orderPay.signum() <= 0) {
            throw new BusinessException(400, "平台代收金额必须大于 0");
        }
        if (gross.compareTo(orderPay) != 0) {
            throw new BusinessException(409, "渠道实收与订单实付不一致，禁止生成结算台账");
        }
        BigDecimal fee = order.getFeeAmount() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : order.getFeeAmount().setScale(2, RoundingMode.HALF_UP);
        if (fee.signum() < 0 || fee.compareTo(gross) > 0) {
            throw new BusinessException(409, "平台留成必须落在 0 到渠道实收之间");
        }
        BigDecimal payable = gross.subtract(fee);

        PlatformCollectionSettlement existing = settlementMapper.findByOrderId(order.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        MerchantPaymentAccount receiver = requireBoundReceiver(order.getMerchantId(), order.getPayChannel());

        PlatformCollectionSettlement settlement = buildSettlement(order, paymentEventId, platformTradeNo,
                gross, fee, payable);
        SplitDetail split = buildSplit(order, paymentEventId, platformTradeNo, receiver, gross, fee, payable);
        try {
            settlementMapper.insert(settlement);
            splitDetailMapper.insert(split);
        } catch (DuplicateKeyException race) {
            // 并发同单：以先落库的台账为准，本事务回滚，避免产生第二套分账。
            PlatformCollectionSettlement winner = settlementMapper.findByOrderId(order.getId()).orElse(null);
            if (winner == null) {
                throw new BusinessException(409, "平台代收结算并发冲突，请重试");
            }
            return winner;
        }
        return settlement;
    }

    /**
     * 记录退分：只写本地账本，必须在退款前完成；同一退款单重试返回原记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public SplitReverse recordSplitReverse(Long splitDetailId, Long refundId, String refundNo,
                                           BigDecimal reverseAmount, Long operatorId) {
        requireEnabled();
        if (splitDetailId == null || refundId == null) {
            throw new BusinessException(400, "分账明细与退款单不能为空");
        }
        BigDecimal amount = requireAmount(reverseAmount, "退分金额");
        if (amount.signum() <= 0) {
            throw new BusinessException(400, "退分金额必须大于 0");
        }
        SplitDetail split = splitDetailMapper.lockById(splitDetailId);
        if (split == null) {
            throw new BusinessException(404, "分账明细不存在");
        }
        SplitReverse existing = splitReverseMapper
                .findByDetailIdAndRefundId(splitDetailId, refundId).orElse(null);
        if (existing != null) {
            return existing;
        }
        BigDecimal reversed = split.getReversedAmount() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : split.getReversedAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal remaining = split.getSplitAmount().subtract(reversed);
        if (amount.compareTo(remaining) > 0) {
            throw new BusinessException(409, "退分金额超过未退分余额: 剩余 " + remaining.toPlainString());
        }

        SplitReverse reverse = new SplitReverse();
        reverse.setSplitDetailId(splitDetailId);
        reverse.setRefundId(refundId);
        reverse.setRefundNo(refundNo);
        reverse.setOrderSn(split.getOrderSn());
        reverse.setMerchantId(split.getMerchantId());
        reverse.setReverseAmount(amount);
        reverse.setReverseStatus(STATUS_PENDING);
        reverse.setOperatorId(operatorId);
        reverse.setCreatedAt(LocalDateTime.now());
        reverse.setUpdatedAt(LocalDateTime.now());
        try {
            splitReverseMapper.insert(reverse);
        } catch (DuplicateKeyException race) {
            return splitReverseMapper.findByDetailIdAndRefundId(splitDetailId, refundId)
                    .orElseThrow(() -> new BusinessException(409, "退分并发冲突，请重试"));
        }
        if (splitDetailMapper.addReversedAmount(splitDetailId, amount) != 1) {
            throw new BusinessException(409, "分账退分额度已变化，请刷新后重试");
        }
        if (settlementMapper.addRefundedAmount(split.getOrderId(), amount) != 1) {
            throw new BusinessException(409, "结算台账可退金额已变化，请刷新后重试");
        }
        return reverse;
    }

    public PlatformCollectionSettlement requireSettlement(Long orderId) {
        return settlementMapper.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(404, "平台代收结算台账不存在"));
    }

    private void validatePlatformCollectOrder(TradeOrder order) {
        if (order == null || order.getId() == null) {
            throw new BusinessException(400, "平台代收订单不能为空");
        }
        if (!CollectionMode.PLATFORM_COLLECT.equals(order.getCollectionMode())) {
            throw new BusinessException(409, "仅 PLATFORM_COLLECT 订单可生成平台代收结算台账");
        }
    }

    /**
     * 读取 T39 预留的分账接收方绑定列；未绑定、缺渠道商户号或未关联结算账户一律拒绝。
     */
    private MerchantPaymentAccount requireBoundReceiver(Long merchantId, String channel) {
        String normalized = PayChannel.normalize(channel);
        MerchantPaymentAccount receiver = paymentAccountMapper
                .findEffectiveBound(merchantId, normalized, LocalDateTime.now());
        if (receiver == null) {
            throw new BusinessException(409, "商家未绑定有效的" + normalized + "分账接收方");
        }
        if (receiver.getSettlementAccountId() == null) {
            throw new BusinessException(409, "分账接收方未关联收款结算账户");
        }
        return receiver;
    }

    private PlatformCollectionSettlement buildSettlement(TradeOrder order, Long paymentEventId,
                                                         String platformTradeNo, BigDecimal gross,
                                                         BigDecimal fee, BigDecimal payable) {
        PlatformCollectionSettlement settlement = new PlatformCollectionSettlement();
        settlement.setOrderId(order.getId());
        settlement.setOrderSn(order.getOrderSn());
        settlement.setMerchantId(order.getMerchantId());
        settlement.setPaymentEventId(paymentEventId);
        settlement.setPlatformTradeNo(platformTradeNo);
        settlement.setGrossAmount(gross);
        settlement.setPlatformRetainAmount(fee);
        settlement.setMerchantPayableAmount(payable);
        settlement.setSettledAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        settlement.setRefundedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        settlement.setSettlementStatus(STATUS_PENDING);
        settlement.setCreatedAt(LocalDateTime.now());
        settlement.setUpdatedAt(LocalDateTime.now());
        return settlement;
    }

    private SplitDetail buildSplit(TradeOrder order, Long paymentEventId, String platformTradeNo,
                                   MerchantPaymentAccount receiver, BigDecimal gross,
                                   BigDecimal fee, BigDecimal payable) {
        SplitDetail split = new SplitDetail();
        split.setOrderId(order.getId());
        split.setOrderSn(order.getOrderSn());
        split.setMerchantId(order.getMerchantId());
        split.setPaymentEventId(paymentEventId);
        split.setPaymentAccountId(receiver.getId());
        split.setChannel(PayChannel.normalize(order.getPayChannel()));
        split.setPlatformTradeNo(platformTradeNo);
        split.setGrossAmount(gross);
        split.setPlatformRetainAmount(fee);
        split.setFeeAmount(fee);
        split.setSplitAmount(payable);
        split.setSplitStatus(STATUS_PENDING);
        split.setReversedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        split.setCreatedAt(LocalDateTime.now());
        split.setUpdatedAt(LocalDateTime.now());
        return split;
    }

    private BigDecimal requireAmount(BigDecimal amount, String label) {
        if (amount == null || amount.signum() < 0) {
            throw new BusinessException(400, label + "不能为空或负数");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(400, label + "最多两位小数");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
