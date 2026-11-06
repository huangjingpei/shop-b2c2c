/*
 * 文件：ProfitSharingChannelContractTest.java
 * 用途：T40 分账通道适配契约用例：占位实现必须保持 disabled，且任何分账/退分调用都明确拒绝。
 * 维护注意：
 * 1. 使用纯 JUnit，不启动 Spring、不连接数据库、不调用真实渠道。
 * 2. 若未来把占位实现改成 enabled=true 或返回伪造成功，本用例必须失败。
 * 3. 真实分账金额守恒、渠道联调与退款先退分仍须资质后由人工/沙箱验证。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.ProfitSharingDTOs;
import com.shop.b2b2c.service.payment.AlipayProfitSharingAdapter;
import com.shop.b2b2c.service.payment.ProfitSharingChannelRegistry;
import com.shop.b2b2c.service.payment.WeChatProfitSharingAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitSharingChannelContractTest {

    @Test
    void placeholderAdaptersStayDisabled() {
        assertFalse(new WeChatProfitSharingAdapter().enabled());
        assertFalse(new AlipayProfitSharingAdapter().enabled());
    }

    @Test
    void registryRejectsDisabledChannelInsteadOfFakingSuccess() {
        ProfitSharingChannelRegistry registry = new ProfitSharingChannelRegistry(
                List.of(new WeChatProfitSharingAdapter(), new AlipayProfitSharingAdapter()));

        BusinessException ex = assertThrows(BusinessException.class, () -> registry.require("WECHAT"));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("待资质与通道联调"));
    }

    @Test
    void placeholderSplitAndReverseNeverReturnSuccess() {
        WeChatProfitSharingAdapter wechat = new WeChatProfitSharingAdapter();
        AlipayProfitSharingAdapter alipay = new AlipayProfitSharingAdapter();

        assertEquals(501,
                assertThrows(BusinessException.class, () -> wechat.split(splitCommand())).getCode());
        assertEquals(501,
                assertThrows(BusinessException.class, () -> alipay.reverse(reverseCommand())).getCode());
    }

    private ProfitSharingDTOs.SplitCommand splitCommand() {
        return ProfitSharingDTOs.SplitCommand.builder()
                .orderSn("SN-T40")
                .platformTradeNo("PLAT-1")
                .receiverChannelId("RECEIVER-1")
                .grossAmount(new BigDecimal("100.00"))
                .platformRetainAmount(new BigDecimal("3.00"))
                .splitAmount(new BigDecimal("97.00"))
                .build();
    }

    private ProfitSharingDTOs.ReverseCommand reverseCommand() {
        return ProfitSharingDTOs.ReverseCommand.builder()
                .orderSn("SN-T40")
                .refundNo("RF-T40")
                .reverseAmount(new BigDecimal("97.00"))
                .build();
    }
}
