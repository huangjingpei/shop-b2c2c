/*
 * 文件：MockPlatformCashierChannelAdapter.java
 * 用途：问题 1 方案 A / Sprint 57 平台收银台占位实现（骨架）：平台代收支付链路尚未联调，
 *      所有方法必须拒绝，不得返回伪造成功；凭据与联调到位后由真实微信电商收付通实现替换。
 *      M4-B：退款/退款查单/退分/退分查单同为占位拒绝。
 * 维护注意：
 *  1. enabled() 固定 false；不得在本类返回任何伪造的支付参数、支付成功、退款成功或退分成功。
 *  2. 本类不写本地账本、不改订单/退款状态。
 *  3. 方法级拒绝统一 501“未完成通道联调”；“未配置/已配置未联调”的细化原因由
 *     PlatformCashierChannelRegistry 在入口给出，正常链路不会进入本类方法。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MockPlatformCashierChannelAdapter implements PlatformCashierChannelAdapter {

    private static final String UNAVAILABLE = "平台收银台未完成通道联调，暂不能发起平台代收支付";
    private static final String UNAVAILABLE_REFUND = "平台收银台未完成通道联调，暂不能办理平台代收退款/退分";

    @Override
    public String channel() {
        return PayChannel.WECHAT;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public PaymentChannelDTOs.PaymentAttemptView createPayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        throw new BusinessException(501, UNAVAILABLE);
    }

    @Override
    public PaymentChannelDTOs.ChannelCallback parseCallback(Map<String, String> headers, String rawBody,
                                                            Map<String, String> formParams) {
        throw new BusinessException(501, UNAVAILABLE);
    }

    @Override
    public PaymentChannelDTOs.ChannelQueryResult queryPayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        throw new BusinessException(501, UNAVAILABLE);
    }

    @Override
    public void closePayment(PaymentChannelDTOs.PaymentCreateCommand command) {
        throw new BusinessException(501, UNAVAILABLE);
    }

    @Override
    public PaymentChannelDTOs.RefundResult refundPayment(PaymentChannelDTOs.RefundCommand command) {
        throw new BusinessException(501, UNAVAILABLE_REFUND);
    }

    @Override
    public PaymentChannelDTOs.RefundResult queryRefund(PaymentChannelDTOs.RefundCommand command) {
        throw new BusinessException(501, UNAVAILABLE_REFUND);
    }

    @Override
    public PaymentChannelDTOs.SplitReturnResult returnSplit(PaymentChannelDTOs.SplitReturnCommand command) {
        throw new BusinessException(501, UNAVAILABLE_REFUND);
    }

    @Override
    public PaymentChannelDTOs.SplitReturnResult querySplitReturn(PaymentChannelDTOs.SplitReturnCommand command) {
        throw new BusinessException(501, UNAVAILABLE_REFUND);
    }
}
