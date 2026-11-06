/*
 * 文件：ProfitSharingDTOs.java
 * 用途：T40 阶段二分账/退分通道的内部契约 DTO（骨架）；只描述渠道事实，不承载本地账本状态。
 * 维护注意：
 * 1. 金额统一使用元、两位小数 BigDecimal；渠道分/币种换算只在适配器内部完成。
 * 2. SplitResult/ReverseResult 只能由验签或渠道查询结果构造；未联调前不得伪造成功状态。
 * 3. 适配器不写 split_detail/split_reverse/platform_collection_settlement，本地状态只由服务层变更。
 */
package com.shop.b2b2c.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

public class ProfitSharingDTOs {

    /** 发起分账所需的最小上下文；不含密钥，只含平台商户号与接收方渠道商户号。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SplitCommand {
        private String orderSn;
        private String platformTradeNo;
        private String platformMerchantId;
        private String receiverChannelId;
        private BigDecimal grossAmount;
        private BigDecimal platformRetainAmount;
        private BigDecimal splitAmount;
        private String currency;
        private String description;
    }

    /** 分账渠道事实；FINISHED 才允许本地标记完成。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SplitResult {
        private String channel;
        private String channelSplitNo;
        private String status;
        private BigDecimal splitAmount;
        private String rawPayload;
    }

    /** 退分请求；必须先于退款执行。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReverseCommand {
        private String orderSn;
        private String platformTradeNo;
        private String channelSplitNo;
        private String refundNo;
        private String platformMerchantId;
        private String receiverChannelId;
        private BigDecimal reverseAmount;
        private String reason;
    }

    /** 退分渠道事实；FINISHED 才允许本地标记完成。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReverseResult {
        private String channel;
        private String channelReverseNo;
        private String status;
        private BigDecimal reverseAmount;
        private String rawPayload;
    }
}
