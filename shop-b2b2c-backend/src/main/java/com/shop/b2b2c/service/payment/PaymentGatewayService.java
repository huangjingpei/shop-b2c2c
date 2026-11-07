/*
 * 文件：PaymentGatewayService.java
 * 用途：阶段一真实支付统一入口：发起支付、渠道回调落账、主动查单对账、渠道关单 (T32)。
 * 维护注意：
 *  1. 只有验签通过的渠道回调或查单 SUCCESS 才进入 PaymentEventService；金额、币种、渠道商户号必须与订单所属商家匹配。
 *  2. 阶段一只允许 CollectionMode.MERCHANT_DIRECT；PLATFORM_COLLECT 明确拒绝，不做隐式降级。
 *  3. 发起支付只读取商家已登记的渠道账户（merchant_payment_account），不在本类写入或伪造账户。
 *  4. 关单只关闭渠道侧交易，本地订单仍由 15 分钟截止任务关闭，避免提前改动订单生命周期语义。
 *  5. G34：queryChannelPayment 只按事件渠道查询渠道事实并核验商户，不触发本地履约；
 *     补偿扫描必须先确认 SUCCESS 且流水号与本地事件一致，再复用 PaymentEventService 的幂等履约路径。
 *  6. F01：每次渠道回调（含验签/归属/资金要素拒绝与未预期异常）都写 payment_callback_audit；
 *     审计写入失败只记 WARN，不得改变回调处理结果，也不得把审计故障返回给渠道触发重试。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
import com.shop.b2b2c.dto.PaymentEventDTOs;
import com.shop.b2b2c.entity.MerchantPaymentAccount;
import com.shop.b2b2c.entity.PaymentCallbackAudit;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.mapper.MerchantPaymentAccountMapper;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import com.shop.b2b2c.service.PaymentEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentGatewayService {

    private final TradeOrderMapper tradeOrderMapper;
    private final MerchantPaymentAccountMapper merchantPaymentAccountMapper;
    private final PaymentChannelRegistry paymentChannelRegistry;
    private final PaymentEventService paymentEventService;
    private final PaymentCallbackAuditService paymentCallbackAuditService;

    public PaymentChannelDTOs.PaymentAttemptView createPayment(PaymentChannelDTOs.CreatePaymentRequest request,
                                                               String clientIp) {
        TradeOrder order = resolvePayerOrder(request);
        return createPayment(order, request.getPayChannel(), clientIp);
    }

    public PaymentChannelDTOs.PaymentAttemptView createPaymentForMerchant(Long merchantId, String orderSn,
                                                                          String payChannel, String clientIp) {
        TradeOrder order = tradeOrderMapper.findByOrderSn(orderSn)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));
        requireMerchantOwns(order, merchantId);
        return createPayment(order, payChannel, clientIp);
    }

    public PaymentEventDTOs.PaymentProcessingResult handleCallback(String channel, Map<String, String> headers,
                                                                   String rawBody,
                                                                   Map<String, String> formParams) {
        long startedAt = System.nanoTime();
        PaymentCallbackAudit audit = new PaymentCallbackAudit();
        audit.setChannel(channel);
        audit.setPayloadSha256(PaymentCallbackAuditService.fingerprint(rawBody, formParams));
        try {
            PaymentChannelAdapter adapter = paymentChannelRegistry.require(channel);
            PaymentChannelDTOs.ChannelCallback callback = adapter.parseCallback(headers, rawBody, formParams);
            audit.setOrderSn(callback.getOrderSn());
            audit.setPayTradeNo(callback.getPayTradeNo());
            audit.setChannelMerchantId(callback.getChannelMerchantId());
            TradeOrder order = tradeOrderMapper.findByOrderSn(callback.getOrderSn())
                    .orElseThrow(() -> new BusinessException(404, "支付回调对应的订单不存在"));
            MerchantPaymentAccount account = requireMatchedAccount(order, callback.getChannel(),
                    callback.getChannelMerchantId());
            PaymentEventDTOs.PaymentProcessingResult result =
                    paymentEventService.processPaymentEvent(toIncoming(order, callback, account));
            audit.setOutcome(PaymentCallbackAuditService.OUTCOME_ACCEPTED);
            audit.setProcessingResult(result == null ? null : result.getEventStatus());
            return result;
        } catch (BusinessException e) {
            audit.setOutcome(PaymentCallbackAuditService.OUTCOME_REJECTED);
            audit.setReason(e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            audit.setOutcome(PaymentCallbackAuditService.OUTCOME_ERROR);
            audit.setReason(e.getMessage());
            throw e;
        } finally {
            persistCallbackAudit(audit, startedAt);
        }
    }

    /**
     * F01：审计写入尽力而为。任何异常只记录 WARN，不得覆盖返回给渠道的 ACK/失败结果。
     */
    private void persistCallbackAudit(PaymentCallbackAudit audit, long startedAt) {
        try {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            audit.setDurationMs((int) Math.min(Integer.MAX_VALUE, Math.max(0L, elapsedMs)));
            paymentCallbackAuditService.record(audit);
        } catch (RuntimeException auditError) {
            log.warn("支付回调审计写入失败，支付处理结果不受影响 channel={} orderSn={}",
                    audit.getChannel(), audit.getOrderSn(), auditError);
        }
    }

    public PaymentChannelDTOs.ChannelQueryResult queryPayment(Long merchantId, String orderSn) {
        TradeOrder order = tradeOrderMapper.findByOrderSn(orderSn)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));
        if (merchantId != null) {
            requireMerchantOwns(order, merchantId);
        }
        String channel = PayChannel.normalize(order.getPayChannel());
        MerchantPaymentAccount account = requireAccount(order, channel);
        PaymentChannelDTOs.ChannelQueryResult result = queryChannel(channel, order, account);
        if (result.isSuccess() && "UNPAID".equals(order.getOrderStatus())) {
            requireSameMerchant(result.getChannelMerchantId(), account);
            PaymentChannelDTOs.ChannelCallback callback = PaymentChannelDTOs.ChannelCallback.builder()
                    .channel(channel)
                    .payTradeNo(result.getPayTradeNo())
                    .orderSn(result.getOrderSn())
                    .channelMerchantId(result.getChannelMerchantId())
                    .payAmount(result.getPayAmount())
                    .currency(result.getCurrency())
                    .payerId(result.getPayerId())
                    .rawPayload(result.getRawPayload())
                    .build();
            paymentEventService.processPaymentEvent(toIncoming(order, callback, account));
            result = PaymentChannelDTOs.ChannelQueryResult.builder()
                    .channel(result.getChannel())
                    .orderSn(result.getOrderSn())
                    .payTradeNo(result.getPayTradeNo())
                    .tradeState("SUCCESS")
                    .channelMerchantId(result.getChannelMerchantId())
                    .payAmount(result.getPayAmount())
                    .currency(result.getCurrency())
                    .payerId(result.getPayerId())
                    .rawPayload(result.getRawPayload())
                    .build();
        }
        return result;
    }

    /**
     * G34 补偿扫描专用：按支付事件记录的渠道查询渠道事实，只返回查单结果，不触发本地履约。
     * 调用方须自行确认 SUCCESS 与流水号一致，再交给 PaymentEventService 走占位/行锁/CAS 的幂等履约。
     */
    public PaymentChannelDTOs.ChannelQueryResult queryChannelPayment(Long merchantId, String channel,
                                                                     String orderSn) {
        TradeOrder order = tradeOrderMapper.findByOrderSn(orderSn)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));
        if (merchantId != null) {
            requireMerchantOwns(order, merchantId);
        }
        String normalized = PayChannel.normalize(channel);
        MerchantPaymentAccount account = requireAccount(order, normalized);
        PaymentChannelDTOs.ChannelQueryResult result = queryChannel(normalized, order, account);
        if (result.isSuccess()) {
            requireSameMerchant(result.getChannelMerchantId(), account);
        }
        return result;
    }

    private PaymentChannelDTOs.ChannelQueryResult queryChannel(String channel, TradeOrder order,
                                                               MerchantPaymentAccount account) {
        PaymentChannelDTOs.ChannelQueryResult result =
                paymentChannelRegistry.require(channel).queryPayment(commandOf(order, account));
        if (result == null) {
            throw new BusinessException(502, "渠道查单未返回结果");
        }
        return result;
    }

    /**
     * F08：订单本地关闭后的渠道关单尽力而为调用。
     * 渠道未配置、账户未登记/未审核、适配器未启用或调用失败时返回 false 并记录 WARN，不阻断本地关单；
     * 迟到支付仍由 PaymentEventService 拒绝并进入异常队列，不会因关单失败自动履约。
     */
    public boolean closePaymentBestEffort(TradeOrder order) {
        if (order == null || order.getPayChannel() == null || order.getPayChannel().isBlank()) {
            return false;
        }
        try {
            String channel = PayChannel.normalize(order.getPayChannel());
            MerchantPaymentAccount account = merchantPaymentAccountMapper
                    .findByMerchantIdAndChannel(order.getMerchantId(), channel).orElse(null);
            if (account == null || !"PASSED".equals(account.getApplyStatus())
                    || account.getSubMchid() == null || account.getSubMchid().isBlank()) {
                return false;
            }
            PaymentChannelAdapter adapter = paymentChannelRegistry.require(channel);
            if (!adapter.enabled()) {
                return false;
            }
            adapter.closePayment(commandOf(order, account));
            return true;
        } catch (Exception e) {
            log.warn("渠道关单失败，本地订单保持关闭，需人工核对渠道侧交易 orderSn={} channel={}",
                    order.getOrderSn(), order.getPayChannel(), e);
            return false;
        }
    }

    public void closePayment(Long merchantId, String orderSn) {
        TradeOrder order = tradeOrderMapper.findByOrderSn(orderSn)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));
        if (merchantId != null) {
            requireMerchantOwns(order, merchantId);
        }
        if (!"UNPAID".equals(order.getOrderStatus())) {
            throw new BusinessException(409, "只有未支付订单可以关闭渠道支付");
        }
        String channel = PayChannel.normalize(order.getPayChannel());
        MerchantPaymentAccount account = requireAccount(order, channel);
        paymentChannelRegistry.require(channel).closePayment(commandOf(order, account));
    }

    private PaymentChannelDTOs.PaymentAttemptView createPayment(TradeOrder order, String payChannel, String clientIp) {
        CollectionMode.requireStageOne(order.getCollectionMode());
        if (!"UNPAID".equals(order.getOrderStatus())) {
            throw new BusinessException(409, "订单当前状态不允许发起支付");
        }
        LocalDateTime deadline = order.getLockExpireTime() != null
                ? order.getLockExpireTime()
                : (order.getCreatedAt() == null ? null : order.getCreatedAt().plusMinutes(15));
        if (deadline != null && LocalDateTime.now().isAfter(deadline)) {
            throw new BusinessException(409, "订单已超过支付截止时间，请重新下单");
        }
        String channel = PayChannel.normalize(payChannel);
        if (order.getPayChannel() == null || order.getPayChannel().isBlank()
                || !channel.equalsIgnoreCase(order.getPayChannel())) {
            int switched = tradeOrderMapper.switchPayChannelIfUnpaid(order.getId(), channel);
            if (switched == 0) {
                throw new BusinessException(409, "订单状态已变化，无法切换支付渠道");
            }
            order.setPayChannel(channel);
        }
        MerchantPaymentAccount account = requireAccount(order, channel);
        PaymentChannelDTOs.PaymentAttemptView view = paymentChannelRegistry.require(channel)
                .createPayment(commandOf(order, account));
        if (view == null) {
            throw new BusinessException(502, "渠道未返回支付参数");
        }
        view.setCollectionMode(CollectionMode.MERCHANT_DIRECT);
        return view;
    }

    private PaymentChannelDTOs.PaymentCreateCommand commandOf(TradeOrder order, MerchantPaymentAccount account) {
        return PaymentChannelDTOs.PaymentCreateCommand.builder()
                .orderSn(order.getOrderSn())
                .amount(order.getPayAmount())
                .subject("订单 " + order.getOrderSn())
                .merchantChannelId(account.getSubMchid())
                .build();
    }

    private MerchantPaymentAccount requireAccount(TradeOrder order, String channel) {
        MerchantPaymentAccount account = merchantPaymentAccountMapper
                .findByMerchantIdAndChannel(order.getMerchantId(), channel)
                .orElseThrow(() -> new BusinessException(409, "商家未登记" + channel + "收款账户，无法发起支付"));
        if (!"PASSED".equals(account.getApplyStatus())) {
            throw new BusinessException(409, "商家" + channel + "收款账户尚未审核通过");
        }
        if (account.getSubMchid() == null || account.getSubMchid().isBlank()) {
            throw new BusinessException(409, "商家" + channel + "收款账户缺少渠道商户号");
        }
        return account;
    }

    private MerchantPaymentAccount requireMatchedAccount(TradeOrder order, String channel,
                                                         String callbackMerchantId) {
        MerchantPaymentAccount account = requireAccount(order, channel);
        requireSameMerchant(callbackMerchantId, account);
        return account;
    }

    private void requireSameMerchant(String callbackMerchantId, MerchantPaymentAccount account) {
        if (callbackMerchantId == null || !callbackMerchantId.equals(account.getSubMchid())) {
            throw new BusinessException(401, "渠道商户号与订单商家收款账户不匹配");
        }
    }

    private TradeOrder resolvePayerOrder(PaymentChannelDTOs.CreatePaymentRequest request) {
        if (request == null || request.getOrderSn() == null || request.getOrderSn().isBlank()) {
            throw new BusinessException(400, "订单号不能为空");
        }
        TradeOrder order = tradeOrderMapper.findByOrderSn(request.getOrderSn())
                .orElseThrow(() -> new BusinessException(404, "订单不存在或访问凭证无效"));
        String token = request.getAccessToken();
        if (token == null || token.isBlank()) {
            throw new BusinessException(401, "缺少订单访问凭证");
        }
        boolean matched = token.equals(order.getPeerPayToken()) || token.equals(order.getQueryToken());
        if (!matched) {
            throw new BusinessException(404, "订单不存在或访问凭证无效");
        }
        return order;
    }

    private PaymentEventDTOs.IncomingPaymentEvent toIncoming(TradeOrder order,
                                                             PaymentChannelDTOs.ChannelCallback callback,
                                                             MerchantPaymentAccount account) {
        CollectionMode.requireStageOne(order.getCollectionMode());
        if (callback.getPayAmount() == null) {
            throw new BusinessException(400, "渠道未提供实收金额，拒绝落账");
        }
        if (!"CNY".equalsIgnoreCase(callback.getCurrency())) {
            throw new BusinessException(409, "阶段一仅接受人民币到账");
        }
        return PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel(callback.getChannel())
                .payTradeNo(callback.getPayTradeNo())
                .orderSn(order.getOrderSn())
                .merchantId(order.getMerchantId())
                .channelMerchantId(account.getSubMchid())
                .payAmount(callback.getPayAmount())
                .currency("CNY")
                .payerId(callback.getPayerId())
                .rawPayload(callback.getRawPayload())
                .build();
    }

    private void requireMerchantOwns(TradeOrder order, Long merchantId) {
        if (merchantId == null || !merchantId.equals(order.getMerchantId())) {
            throw new BusinessException(403, "无权操作非本店订单");
        }
    }
}
