/*
 * 文件：RefundService.java
 * 用途：T33 阶段一商家直退编排：幂等入口、渠道退款调用、退款查单同步与重复付款补偿；
 *      M4-B 起按 collectionMode 路由：PLATFORM_COLLECT 走平台收银台“先退分后退款”编排。
 * 维护注意：
 *  1. 本地预占/落库在 RefundTransactionService 的短事务内完成，渠道 HTTP 调用严禁放在该事务内。
 *  2. 商家直收：由商家自己的渠道账户原路退款，平台不垫资退货款；平台仅按订单快照冲正服务费。
 *     平台代收（M4-B/D4）：先退分后退款——退分额=退款额−服务费冲正（金额守恒），渠道退分 SUCCESS 后才
 *     登记本地 split_reverse 账本并发起平台收银台退款；退分以 refundNo 为渠道幂等键，重试不会产生二次退分；
 *     平台收银台未配置/未联调 409，绝不回退商家直收渠道、不读取商家直收账户。
 *  3. 渠道 PROCESSING 表示未确认到账，不能置订单 REFUNDED 或回退库存；必须由 syncRefund/对账任务再确认。
 *  4. 渠道异常必须先落 FAILED 并释放额度再向上抛出，不能留下永久 PROCESSING 预占。
 *  5. 重复付款补偿只退多收的第二笔资金，不改订单商品退款额度、不冲正服务费；资金未分账，平台代收场景跳过退分。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
import com.shop.b2b2c.dto.RefundDTOs;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import com.shop.b2b2c.entity.PaymentEvent;
import com.shop.b2b2c.entity.SplitDetail;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeRefund;
import com.shop.b2b2c.mapper.MerchantPaymentAccountMapper;
import com.shop.b2b2c.mapper.PaymentEventMapper;
import com.shop.b2b2c.mapper.SplitDetailMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import com.shop.b2b2c.service.PlatformCollectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RefundService {

    private final TradeRefundMapper tradeRefundMapper;
    private final TradeOrderMapper tradeOrderMapper;
    private final PaymentEventMapper paymentEventMapper;
    private final MerchantPaymentAccountMapper merchantPaymentAccountMapper;
    private final PaymentChannelRegistry paymentChannelRegistry;
    private final RefundTransactionService refundTransactionService;
    /** M4-B：平台代收退款编排依赖，仅 collectionMode=PLATFORM_COLLECT 的退款使用；字段序即构造器序。 */
    private final PlatformCashierChannelRegistry platformCashierChannelRegistry;
    private final PlatformCollectionService platformCollectionService;
    private final SplitDetailMapper splitDetailMapper;

    /**
     * 申请订单退款：金额按订单快照校验，服务费按比例冲正；同键重试复用原退款记录。
     */
    public RefundDTOs.RefundView applyOrderRefund(Long merchantId, String orderSn,
                                                  RefundDTOs.RefundRequest request, String idempotencyKey,
                                                  Long operatorId) {
        if (request == null) {
            throw new BusinessException(400, "退款申请不能为空");
        }
        String key = requireIdempotencyKey(idempotencyKey);
        TradeRefund refund = refundTransactionService.prepareOrderRefund(merchantId, orderSn,
                request.getAmount(), request.getReason(), key, operatorId);
        if (!RefundTransactionService.STATUS_PROCESSING.equals(refund.getStatus())) {
            return RefundDTOs.RefundView.from(refund);
        }
        return RefundDTOs.RefundView.from(executeChannelRefund(refund));
    }

    /**
     * 重复付款补偿：按渠道重复付款流水号原路退回，不占订单商品退款额度。
     */
    public RefundDTOs.RefundView compensateDuplicatePayment(Long merchantId, Long eventId,
                                                            String idempotencyKey, Long operatorId) {
        String key = requireIdempotencyKey(idempotencyKey);
        TradeRefund refund = refundTransactionService.prepareDuplicateCompensation(merchantId, eventId, key, operatorId);
        if (!RefundTransactionService.STATUS_PROCESSING.equals(refund.getStatus())) {
            return RefundDTOs.RefundView.from(refund);
        }
        return RefundDTOs.RefundView.from(executeChannelRefund(refund));
    }

    /**
     * 退款查单同步：只对 PROCESSING 记录调用渠道查询，SUCCESS/FAILED 迁移后释放或确认预占。
     * M4-B：平台代收退款查单走平台收银台，不读取商家直收渠道账户。
     */
    public RefundDTOs.RefundView syncRefund(Long merchantId, String refundNo) {
        TradeRefund refund = requireRefund(merchantId, refundNo);
        if (!RefundTransactionService.STATUS_PROCESSING.equals(refund.getStatus())) {
            return RefundDTOs.RefundView.from(refund);
        }
        BigDecimal total = resolveOriginalPayAmount(refund);
        PaymentChannelDTOs.RefundResult result;
        if (CollectionMode.PLATFORM_COLLECT.equals(refund.getCollectionMode())) {
            result = platformCashierChannelRegistry.requireForRefund(refund.getChannel())
                    .queryRefund(platformRefundCommandOf(refund, total));
        } else {
            result = paymentChannelRegistry.require(refund.getChannel())
                    .queryRefund(commandOf(refund, total));
        }
        if (result == null) {
            throw new BusinessException(502, "渠道未返回退款查单结果");
        }
        if (result.isSuccess()) {
            refundTransactionService.finalizeSuccess(refund.getId(), result.getChannelRefundNo());
        } else if (RefundTransactionService.STATUS_FAILED.equals(result.getStatus())) {
            refundTransactionService.finalizeFailure(refund.getId(), "渠道退款查单失败");
        }
        return RefundDTOs.RefundView.from(requireRefund(merchantId, refundNo));
    }

    public List<RefundDTOs.RefundView> listOrderRefunds(Long merchantId, String orderSn) {
        TradeOrder order = tradeOrderMapper.findByOrderSn(orderSn)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));
        if (merchantId != null && !merchantId.equals(order.getMerchantId())) {
            throw new BusinessException(403, "无权查看非本店订单退款");
        }
        return RefundDTOs.RefundView.from(tradeRefundMapper.findByOrderSn(orderSn));
    }

    public List<RefundDTOs.DuplicatePaymentView> listDuplicatePayments(Long merchantId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<PaymentEvent> events = paymentEventMapper.findDuplicatePayments(merchantId, safeLimit);
        List<RefundDTOs.DuplicatePaymentView> views = new ArrayList<>(events.size());
        for (PaymentEvent event : events) {
            TradeRefund refund = tradeRefundMapper.findBySourceEventId(event.getId()).orElse(null);
            views.add(RefundDTOs.DuplicatePaymentView.from(event, refund));
        }
        return views;
    }

    private TradeRefund executeChannelRefund(TradeRefund refund) {
        // M4-B：平台代收退款走平台收银台“先退分后退款”编排，绝不进入商家直收渠道与账户。
        if (CollectionMode.PLATFORM_COLLECT.equals(refund.getCollectionMode())) {
            return executePlatformCollectRefund(refund);
        }
        PaymentChannelDTOs.RefundResult result;
        try {
            MerchantPaymentAccount account = requireAccount(refund.getMerchantId(), refund.getChannel());
            BigDecimal total = resolveOriginalPayAmount(refund);
            result = paymentChannelRegistry.require(refund.getChannel())
                    .refundPayment(commandOf(refund, total, account.getSubMchid()));
        } catch (BusinessException business) {
            refundTransactionService.finalizeFailure(refund.getId(), business.getMessage());
            throw business;
        } catch (RuntimeException unexpected) {
            refundTransactionService.finalizeFailure(refund.getId(), unexpected.getMessage());
            throw new BusinessException(502, "渠道退款调用失败，请稍后重试");
        }
        return applyChannelResult(refund, result);
    }

    /**
     * M4-B 平台代收退款编排（设计 D4 先退分后退款，金额守恒）：
     * 1) 入口由 PlatformCashierChannelRegistry 给出 409 细化原因（未配置/已配置未联调），不回退商家直收；
     * 2) source=ORDER 且退分额（退款额−服务费冲正）> 0 时先调用渠道退分，非 SUCCESS 一律中止退款发起；
     * 3) 渠道退分 SUCCESS 后登记本地 split_reverse 账本（幂等：split_detail_id+refund_id 唯一）；
     * 4) 最后由平台收银台发起原路退款；重复付款补偿资金未分账，跳过退分直接退款。
     * 任何异常都先 finalizeFailure 释放预占再向上抛出；渠道退分以 refundNo 幂等，重试不会二次退分。
     */
    private TradeRefund executePlatformCollectRefund(TradeRefund refund) {
        PaymentChannelDTOs.RefundResult result;
        try {
            PlatformCashierChannelAdapter adapter =
                    platformCashierChannelRegistry.requireForRefund(refund.getChannel());
            if (RefundTransactionService.SOURCE_ORDER.equals(refund.getSource())) {
                returnSplitBeforeRefund(adapter, refund);
            }
            result = adapter.refundPayment(platformRefundCommandOf(refund, resolveOriginalPayAmount(refund)));
        } catch (BusinessException business) {
            refundTransactionService.finalizeFailure(refund.getId(), business.getMessage());
            throw business;
        } catch (RuntimeException unexpected) {
            refundTransactionService.finalizeFailure(refund.getId(), unexpected.getMessage());
            throw new BusinessException(502, "平台收银台退款调用失败，请稍后重试");
        }
        return applyChannelResult(refund, result);
    }

    /** 先退分：退分额 = 退款额 − 服务费冲正（平台留成退回部分）；≤0 时无商家分账可退，直接跳过。 */
    private void returnSplitBeforeRefund(PlatformCashierChannelAdapter adapter, TradeRefund refund) {
        BigDecimal feeRefund = refund.getFeeRefundAmount() == null
                ? BigDecimal.ZERO : refund.getFeeRefundAmount();
        BigDecimal reverseAmount = refund.getRefundAmount().subtract(feeRefund);
        if (reverseAmount.signum() <= 0) {
            return;
        }
        List<SplitDetail> splits = splitDetailMapper.findByOrderId(refund.getOrderId());
        if (splits == null || splits.isEmpty()) {
            throw new BusinessException(409, "平台代收订单缺少分账明细，无法执行先退分后退款");
        }
        SplitDetail split = splits.get(0);
        MerchantPaymentAccount receiver = split.getPaymentAccountId() == null
                ? null : merchantPaymentAccountMapper.selectById(split.getPaymentAccountId());
        if (receiver == null || receiver.getSubMchid() == null || receiver.getSubMchid().isBlank()) {
            throw new BusinessException(409, "平台代收分账接收方缺少渠道商户号，无法退分");
        }
        PaymentChannelDTOs.SplitReturnResult splitResult = adapter.returnSplit(
                PaymentChannelDTOs.SplitReturnCommand.builder()
                        .orderSn(refund.getOrderSn())
                        .refundNo(refund.getRefundNo())
                        .platformTradeNo(split.getPlatformTradeNo())
                        .receiverChannelId(receiver.getSubMchid())
                        .amount(reverseAmount)
                        .reason(refund.getReason())
                        .build());
        if (splitResult == null) {
            throw new BusinessException(502, "渠道未返回退分结果");
        }
        if (!splitResult.isSuccess()) {
            // 退分未确认前不得发起退款；渠道以 refundNo 为幂等键，FAILED 后重试不会产生二次退分。
            throw new BusinessException(409, "平台代收退分未完成（渠道状态 "
                    + (splitResult.getStatus() == null ? "未知" : splitResult.getStatus())
                    + "），已中止退款发起，待退分确认后重试");
        }
        // 渠道退分成功后登记本地退分账本；本地账本按 split_detail_id+refund_id 幂等。
        platformCollectionService.recordSplitReverse(split.getId(), refund.getId(), refund.getRefundNo(),
                reverseAmount, refund.getRequestedBy());
    }

    /** 渠道结果共用落迁：SUCCESS 确认、FAILED 释放、PROCESSING 保持预占待查单确认。 */
    private TradeRefund applyChannelResult(TradeRefund refund, PaymentChannelDTOs.RefundResult result) {
        if (result == null) {
            refundTransactionService.finalizeFailure(refund.getId(), "渠道未返回退款结果");
            throw new BusinessException(502, "渠道未返回退款结果");
        }
        if (result.isSuccess()) {
            refundTransactionService.finalizeSuccess(refund.getId(), result.getChannelRefundNo());
            refund.setStatus(RefundTransactionService.STATUS_SUCCESS);
            refund.setChannelRefundNo(result.getChannelRefundNo());
        } else if (RefundTransactionService.STATUS_FAILED.equals(result.getStatus())) {
            refundTransactionService.finalizeFailure(refund.getId(), "渠道退款失败");
            refund.setStatus(RefundTransactionService.STATUS_FAILED);
            refund.setFailureReason("渠道退款失败");
        }
        // PROCESSING：保持预占与记录状态，等待 syncRefund/对账任务确认。
        return refund;
    }

    private PaymentChannelDTOs.RefundCommand commandOf(TradeRefund refund, BigDecimal total) {
        return commandOf(refund, total, null);
    }

    /**
     * M4-B 平台代收退款命令：merchantChannelId 留空，由平台收银台适配器按 payment.platform.sp-mchid 自取；
     * 平台代收链路不得读取商家直收账户（requireAccount）。
     */
    private PaymentChannelDTOs.RefundCommand platformRefundCommandOf(TradeRefund refund, BigDecimal total) {
        return PaymentChannelDTOs.RefundCommand.builder()
                .orderSn(refund.getOrderSn())
                .refundNo(refund.getRefundNo())
                .channelTradeNo(refund.getChannelTradeNo())
                .payAmount(total)
                .refundAmount(refund.getRefundAmount())
                .reason(refund.getReason())
                .build();
    }

    private PaymentChannelDTOs.RefundCommand commandOf(TradeRefund refund, BigDecimal total, String merchantChannelId) {
        String channelMerchantId = merchantChannelId;
        if (channelMerchantId == null) {
            channelMerchantId = requireAccount(refund.getMerchantId(), refund.getChannel()).getSubMchid();
        }
        return PaymentChannelDTOs.RefundCommand.builder()
                .orderSn(refund.getOrderSn())
                .refundNo(refund.getRefundNo())
                .channelTradeNo(refund.getChannelTradeNo())
                .merchantChannelId(channelMerchantId)
                .payAmount(total)
                .refundAmount(refund.getRefundAmount())
                .reason(refund.getReason())
                .build();
    }

    /**
     * 渠道退款 total 参数：普通退款取订单实付；重复付款补偿取该笔重复付款金额。
     */
    private BigDecimal resolveOriginalPayAmount(TradeRefund refund) {
        if (RefundTransactionService.SOURCE_DUPLICATE_PAYMENT.equals(refund.getSource())) {
            return refund.getRefundAmount();
        }
        TradeOrder order = tradeOrderMapper.findByOrderSn(refund.getOrderSn())
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));
        return order.getPayAmount() == null ? refund.getRefundAmount() : order.getPayAmount();
    }

    private MerchantPaymentAccount requireAccount(Long merchantId, String channel) {
        String normalized = PayChannel.normalize(channel);
        MerchantPaymentAccount account = merchantPaymentAccountMapper
                .findByMerchantIdAndChannel(merchantId, normalized)
                .orElseThrow(() -> new BusinessException(409, "商家未登记" + normalized + "收款账户，无法退款"));
        if (!"PASSED".equals(account.getApplyStatus())) {
            throw new BusinessException(409, "商家" + normalized + "收款账户尚未审核通过");
        }
        if (account.getSubMchid() == null || account.getSubMchid().isBlank()) {
            throw new BusinessException(409, "商家" + normalized + "收款账户缺少渠道商户号");
        }
        return account;
    }

    private TradeRefund requireRefund(Long merchantId, String refundNo) {
        TradeRefund refund = tradeRefundMapper.findByRefundNo(refundNo)
                .orElseThrow(() -> new BusinessException(404, "退款记录不存在"));
        if (merchantId != null && !merchantId.equals(refund.getMerchantId())) {
            throw new BusinessException(403, "无权操作非本店退款");
        }
        return refund;
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9_-]{16,64}$")) {
            throw new BusinessException(400, "请提供16至64位的Idempotency-Key（字母、数字、下划线或短横线）");
        }
        return idempotencyKey;
    }
}
