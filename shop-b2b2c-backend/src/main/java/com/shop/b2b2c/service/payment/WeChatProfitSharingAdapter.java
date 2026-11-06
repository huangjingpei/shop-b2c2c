/*
 * 文件：WeChatProfitSharingAdapter.java
 * 用途：T40 微信分账通道占位实现（骨架）；未取得分账资质与通道联调前不得产生真实调用。
 * 维护注意：
 * 1. enabled() 固定 false；任何 split/query/reverse 都必须拒绝，不得返回伪造成功。
 * 2. 取得资质后在此接入微信电商收付通分账 API，验签/解密/金额换算只在本类内部完成。
 * 3. 本类不写本地账本、不改订单/退款状态。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.dto.ProfitSharingDTOs;
import org.springframework.stereotype.Component;

@Component
public class WeChatProfitSharingAdapter implements ProfitSharingChannelAdapter {

    private static final String UNAVAILABLE = "微信分账通道待资质与通道联调，当前未启用";

    @Override
    public String channel() {
        return PayChannel.WECHAT;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public ProfitSharingDTOs.SplitResult split(ProfitSharingDTOs.SplitCommand command) {
        throw new BusinessException(501, UNAVAILABLE);
    }

    @Override
    public ProfitSharingDTOs.SplitResult querySplit(ProfitSharingDTOs.SplitCommand command) {
        throw new BusinessException(501, UNAVAILABLE);
    }

    @Override
    public ProfitSharingDTOs.ReverseResult reverse(ProfitSharingDTOs.ReverseCommand command) {
        throw new BusinessException(501, UNAVAILABLE);
    }
}
