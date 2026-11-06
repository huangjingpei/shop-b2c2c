/*
 * 文件：ProfitSharingChannelAdapter.java
 * 用途：T40 阶段二分账/退分渠道适配契约（骨架）：分账、查分、退分。
 * 维护注意：
 * 1. 只有取得支付/分账资质并完成通道联调后才允许实现真实调用；当前两个实现均为占位，enabled() 必须为 false。
 * 2. 适配器只返回渠道事实，不写本地账本、不改订单/退款状态；本地状态由 PlatformCollectionService 推进。
 * 3. 未启用或凭据缺失时必须明确拒绝，不得返回伪造成功；金额换算与验签只在适配器内部完成。
 */
package com.shop.b2b2c.service.payment;

import com.shop.b2b2c.dto.ProfitSharingDTOs;

public interface ProfitSharingChannelAdapter {

    String channel();

    boolean enabled();

    ProfitSharingDTOs.SplitResult split(ProfitSharingDTOs.SplitCommand command);

    ProfitSharingDTOs.SplitResult querySplit(ProfitSharingDTOs.SplitCommand command);

    ProfitSharingDTOs.ReverseResult reverse(ProfitSharingDTOs.ReverseCommand command);
}
