/*
 * 文件：MerchantCollectionModeDTOs.java
 * 用途：M1 收款模式总闸与商家切换接口的请求/响应结构。
 * 维护注意：
 *  1. 响应只暴露模式、总闸状态与渠道绑定是否就绪，不返回银行卡号、sub_mchid 明文或渠道报文。
 *  2. mode 只接受 MERCHANT_DIRECT/PLATFORM_COLLECT，服务端再次校验，前端不得自行放行。
 *  3. reason 只用于审计说明，不代表审批结论；本设计不引入双人复核。
 */
package com.shop.b2b2c.dto;

import lombok.Data;

import java.time.LocalDateTime;

public class MerchantCollectionModeDTOs {

    @Data
    public static class ConfigUpdateRequest {
        private Boolean enabled;
    }

    @Data
    public static class SwitchRequest {
        private String mode;
        private String reason;
    }

    @Data
    public static class ConfigView {
        private Boolean enabled;
        private boolean emergencyDisabled;
        private Long version;
        private Long updatedBy;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class MerchantModeView {
        private Long merchantId;
        /** 商家库里配置的模式；即使总闸关闭也如实返回，便于运营预配置。 */
        private String configuredMode;
        /** 服务端解析后对新订单实际生效的模式：环境应急关闭 > 全局总闸 > 商家配置。 */
        private String effectiveMode;
        private boolean globalEnabled;
        private boolean emergencyDisabled;
        private String payChannel;
        private String bindingStatus;
        /** 当前渠道是否满足切平台代收的前置条件（BOUND + sub_mchid + settlement_account_id）。 */
        private boolean bindingReady;
        /** bindingReady=false 时的具体原因；就绪时为空。 */
        private String blockReason;
    }
}
