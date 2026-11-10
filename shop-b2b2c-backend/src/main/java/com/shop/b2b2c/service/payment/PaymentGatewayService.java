/*
 * 文件：PaymentGatewayService.java
 * 用途：阶段一真实支付统一入口：发起支付、渠道回调落账、主动查单对账、渠道关单 (T32)。
 * 维护注意：
 *  1. 只有验签通过的渠道回调或查单 SUCCESS 才进入 PaymentEventService；金额、币种、渠道商户号必须与订单所属商家匹配。
 *  2. 阶段一只允许 CollectionMode.MERCHANT_DIRECT；PLATFORM_COLLECT 走平台收银台骨架入口
 *     （PlatformCashierChannelRegistry，Sprint 57）：凭据未配置 409“平台收银台未配置”、
 *     已配置未联调 409“平台收银台已配置但未完成通道联调”，回调拒绝按商家直收渠道履约，不做隐式降级。
 *     Sprint 59 新增平台代收回调独立入口 handlePlatformCallback（对应 payment.platform.notify-path）：
 *     平台服务商凭据验签解密 → 校验订单 PLATFORM_COLLECT 快照 → 二级商户号与商家有效分账接收方比对 →
 *     PaymentEventService 幂等履约 → 生成平台代收结算台账（设计 D3：只生成台账，不在支付成功时分账）；
 *     台账生成失败不阻断到账 ACK，记审计与 ERROR 日志，由 M5 对账按“事件 SUCCESS 且无台账”兜底。
 *  3. 发起支付只读取商家已登记的渠道账户（merchant_payment_account），不在本类写入或伪造账户。
 *  4. 关单只关闭渠道侧交易，本地订单仍由 15 分钟截止任务关闭，避免提前改动订单生命周期语义。
 *  5. G34：queryChannelPayment 只按事件渠道查询渠道事实并核验商户，不触发本地履约；
 *     补偿扫描必须先确认 SUCCESS 且流水号与本地事件一致，再复用 PaymentEventService 的幂等履约路径。
 *  6. F01：每次渠道回调（含验签/归属/资金要素拒绝与未预期异常）都写 payment_callback_audit；
 *     审计写入失败只记 WARN，不得改变回调处理结果，也不得把审计故障返回给渠道触发重试。
 *  7. F08：每次关单尝试都写 payment_close_receipt（SUCCESS/FAILED/SKIPPED），FAILED 由 PaymentCloseRetryJob 退避重试；
 *     回执写入失败不影响本地订单关闭，重试只调用渠道关单，不改订单生命周期。
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
import com.shop.b2b2c.service.PlatformCollectionService;
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
    private final PaymentCloseReceiptService paymentCloseReceiptService;
    /** Sprint 57 平台收银台骨架入口：仅 PLATFORM_COLLECT 订单使用；未配置/未联调时细化 409。 */
    private final PlatformCashierChannelRegistry platformCashierChannelRegistry;
    /** Sprint 59：平台代收回调履约成功后的结算台账生成（D3 只建台账不分账）；字段序即构造器序。 */
    private final PlatformCollectionService platformCollectionService;

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
     * Sprint 59 M6 平台代收支付回调独立入口（payment.platform.notify-path）：
     * 平台服务商凭据验签解密 → 订单须为 PLATFORM_COLLECT 快照 → 回调二级商户号须等于商家当前有效分账接收方 →
     * PaymentEventService 幂等履约；履约成功后生成平台代收结算台账（D3：只建台账，不在支付成功时分账）。
     * 与商家直收回调同一审计口径；台账生成失败不阻断到账 ACK（支付已履约），记审计 reason 与 ERROR 日志，
     * 由 M5 对账按“支付事件 SUCCESS 且无台账”兜底补建。收银台未配置/未联调时 409，渠道稍后重试。
     */
    public PaymentEventDTOs.PaymentProcessingResult handlePlatformCallback(String channel,
                                                                           Map<String, String> headers,
                                                                           String rawBody) {
        long startedAt = System.nanoTime();
        String normalized = PayChannel.normalize(channel);
        PaymentCallbackAudit audit = new PaymentCallbackAudit();
        audit.setChannel(normalized);
        audit.setPayloadSha256(PaymentCallbackAuditService.fingerprint(rawBody, null));
        try {
            PlatformCashierChannelAdapter adapter = platformCashierChannelRegistry.require(normalized);
            PaymentChannelDTOs.ChannelCallback callback = adapter.parseCallback(headers, rawBody, null);
            audit.setOrderSn(callback.getOrderSn());
            audit.setPayTradeNo(callback.getPayTradeNo());
            audit.setChannelMerchantId(callback.getChannelMerchantId());
            TradeOrder order = tradeOrderMapper.findByOrderSn(callback.getOrderSn())
                    .orElseThrow(() -> new BusinessException(404, "支付回调对应的订单不存在"));
            if (!CollectionMode.PLATFORM_COLLECT.equals(CollectionMode.requireKnown(order.getCollectionMode()))) {
                throw new BusinessException(409, "非平台代收订单，拒绝经平台收银台回调履约");
            }
            MerchantPaymentAccount receiver = requireBoundReceiver(order, normalized);
            if (!receiver.getSubMchid().equals(callback.getChannelMerchantId())) {
                throw new BusinessException(401, "渠道二级商户号与订单商家分账接收方不匹配");
            }
            if (callback.getPayAmount() == null) {
                throw new BusinessException(400, "渠道未提供实收金额，拒绝落账");
            }
            if (!"CNY".equalsIgnoreCase(callback.getCurrency())) {
                throw new BusinessException(409, "阶段一仅接受人民币到账");
            }
            PaymentEventDTOs.PaymentProcessingResult result = paymentEventService.processPaymentEvent(
                    PaymentEventDTOs.IncomingPaymentEvent.builder()
                            .channel(normalized)
                            .payTradeNo(callback.getPayTradeNo())
                            .orderSn(order.getOrderSn())
                            .merchantId(order.getMerchantId())
                            .channelMerchantId(receiver.getSubMchid())
                            .payAmount(callback.getPayAmount())
                            .currency("CNY")
                            .payerId(callback.getPayerId())
                            .rawPayload(callback.getRawPayload())
                            .build());
            if (result != null && result.isSuccess() && "SUCCESS".equals(result.getEventStatus())) {
                prepareSettlementBestEffort(order, result, callback, audit);
            }
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

    /** 平台代收结算台账生成尽力而为：失败不阻断到账 ACK，审计留痕，由 M5 对账兜底补建。 */
    private void prepareSettlementBestEffort(TradeOrder order, PaymentEventDTOs.PaymentProcessingResult result,
                                             PaymentChannelDTOs.ChannelCallback callback, PaymentCallbackAudit audit) {
        try {
            platformCollectionService.prepareSettlement(order, result.getEventId(),
                    callback.getPayTradeNo(), callback.getPayAmount());
        } catch (RuntimeException settlementError) {
            log.error("平台代收结算台账生成失败，支付已履约，待对账兜底 orderSn={} payTradeNo={}",
                    order.getOrderSn(), callback.getPayTradeNo(), settlementError);
            audit.setReason("结算台账生成失败: " + settlementError.getMessage());
        }
    }

    /** 平台代收收款归属：商家当前有效分账接收方（merchant_payment_account BOUND 且生效）。 */
    private MerchantPaymentAccount requireBoundReceiver(TradeOrder order, String channel) {
        MerchantPaymentAccount receiver = merchantPaymentAccountMapper
                .findEffectiveBound(order.getMerchantId(), channel, LocalDateTime.now());
        if (receiver == null || receiver.getSubMchid() == null || receiver.getSubMchid().isBlank()) {
            throw new BusinessException(409, "商家未绑定有效的" + channel + "分账接收方，拒绝平台代收链路");
        }
        return receiver;
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
     * 渠道未配置、账户未登记/未审核、适配器未启用时返回 false 并记录 SKIPPED 回执（不自动重试）；
     * 调用失败记录 FAILED 回执，由 PaymentCloseRetryJob 退避重试；不阻断本地关单。
     * 迟到支付仍由 PaymentEventService 拒绝并进入异常队列，不会因关单失败自动履约。
     */
    public boolean closePaymentBestEffort(TradeOrder order) {
        if (order == null || order.getPayChannel() == null || order.getPayChannel().isBlank()) {
            return false;
        }
        String channel = order.getPayChannel().trim().toUpperCase();
        try {
            channel = PayChannel.normalize(order.getPayChannel());
        } catch (BusinessException e) {
            recordCloseAttempt(order, channel, PaymentCloseReceiptService.RESULT_SKIPPED,
                    "渠道编码不受支持: " + e.getMessage());
            return false;
        }
        try {
            MerchantPaymentAccount account = merchantPaymentAccountMapper
                    .findByMerchantIdAndChannel(order.getMerchantId(), channel).orElse(null);
            if (account == null || !"PASSED".equals(account.getApplyStatus())
                    || account.getSubMchid() == null || account.getSubMchid().isBlank()) {
                recordCloseAttempt(order, channel, PaymentCloseReceiptService.RESULT_SKIPPED,
                        "渠道账户未登记或未审核通过");
                return false;
            }
            PaymentChannelAdapter adapter = paymentChannelRegistry.require(channel);
            if (!adapter.enabled()) {
                recordCloseAttempt(order, channel, PaymentCloseReceiptService.RESULT_SKIPPED,
                        "渠道适配器未启用");
                return false;
            }
            adapter.closePayment(commandOf(order, account));
            recordCloseAttempt(order, channel, PaymentCloseReceiptService.RESULT_SUCCESS, null);
            return true;
        } catch (Exception e) {
            recordCloseAttempt(order, channel, PaymentCloseReceiptService.RESULT_FAILED,
                    "关单调用异常: " + e.getMessage());
            log.warn("渠道关单失败，本地订单保持关闭，回执待重试 orderSn={} channel={}",
                    order.getOrderSn(), channel, e);
            return false;
        }
    }

    /**
     * F08：回执写入尽力而为，任何异常只记 WARN，不覆盖关单结果与本地订单状态。
     */
    private void recordCloseAttempt(TradeOrder order, String channel, String result, String reason) {
        try {
            paymentCloseReceiptService.record(order, channel, result, reason);
        } catch (RuntimeException receiptError) {
            log.warn("关单回执写入失败，不影响本地关单 orderSn={} channel={}",
                    order.getOrderSn(), channel, receiptError);
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
        try {
            paymentChannelRegistry.require(channel).closePayment(commandOf(order, account));
            recordCloseAttempt(order, channel, PaymentCloseReceiptService.RESULT_SUCCESS, null);
        } catch (RuntimeException e) {
            recordCloseAttempt(order, channel, PaymentCloseReceiptService.RESULT_FAILED,
                    "关单调用异常: " + e.getMessage());
            throw e;
        }
    }

    private PaymentChannelDTOs.PaymentAttemptView createPayment(TradeOrder order, String payChannel, String clientIp) {
        String collectionMode = CollectionMode.requireKnown(order.getCollectionMode());
        // Sprint 59：状态与支付截止校验提升到模式分流之前，平台代收与商家直收同一口径；
        // 未支付订单允许切换支付渠道（CAS 防并发）。
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
        if (CollectionMode.PLATFORM_COLLECT.equals(collectionMode)) {
            // Sprint 57/59 平台收银台入口：凭据未配置/未完成联调时由 registry 抛 409 细化原因；
            // 二级商户号取商家当前有效分账接收方（merchant_payment_account BOUND），不读商家直收账户。
            PlatformCashierChannelAdapter platformAdapter = platformCashierChannelRegistry.require(channel);
            MerchantPaymentAccount receiver = requireBoundReceiver(order, channel);
            PaymentChannelDTOs.PaymentAttemptView platformView = platformAdapter.createPayment(
                    PaymentChannelDTOs.PaymentCreateCommand.builder()
                            .orderSn(order.getOrderSn())
                            .amount(order.getPayAmount())
                            .subject("订单 " + order.getOrderSn())
                            .merchantChannelId(receiver.getSubMchid())
                            .build());
            if (platformView == null) {
                throw new BusinessException(502, "平台收银台未返回支付参数");
            }
            platformView.setCollectionMode(CollectionMode.PLATFORM_COLLECT);
            return platformView;
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
        if (CollectionMode.PLATFORM_COLLECT.equals(CollectionMode.requireKnown(order.getCollectionMode()))) {
            // 平台代收订单回调必须走平台收银台独立入口（payment.platform.notify-path）并按平台商户号验签；
            // 收银台未配置/未联调前，拒绝商家直收回调入口为其履约，防止渠道归属错配。
            throw new BusinessException(409, "平台代收订单支付回调须由平台收银台独立入口处理"
                    + "（当前平台收银台未配置/未联调），拒绝按商家直收渠道履约");
        }
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
