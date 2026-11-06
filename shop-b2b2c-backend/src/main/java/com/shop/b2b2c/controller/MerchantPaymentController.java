/*
 * 文件：MerchantPaymentController.java
 * 用途：商家/超管对阶段一真实支付订单的主动查单与渠道关单入口 (T32)。
 * 维护注意：
 *  1. 路径必须位于 /api/v1/merchant/**，才能被 SaTokenConfigure 的商家登录与角色拦截覆盖；不得挪到公开前缀。
 *  2. 资金类操作仅店长或超管；员工即使能登录商家工作台也不得查单/关单。
 *  3. 订单归属由 PaymentGatewayService 校验，角色检查不替代归属校验。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
import com.shop.b2b2c.service.payment.PaymentGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "商家支付对账 (Stage One Payment)")
@RestController
@RequestMapping("/api/v1/merchant/payment")
@RequiredArgsConstructor
public class MerchantPaymentController {

    private final PaymentGatewayService paymentGatewayService;

    @Operation(summary = "主动查单并对账（仅店长/超管）")
    @PostMapping("/query/{orderSn}")
    public R<PaymentChannelDTOs.ChannelQueryResult> queryPayment(@PathVariable String orderSn) {
        requireFinanceRole();
        Long merchantId = StpUtil.hasRole("SUPERADMIN") ? null : SecurityUtils.requireCurrentMerchantId();
        return R.ok("查单完成", paymentGatewayService.queryPayment(merchantId, orderSn));
    }

    @Operation(summary = "关闭渠道侧支付（仅店长/超管）")
    @PostMapping("/close/{orderSn}")
    public R<Void> closePayment(@PathVariable String orderSn) {
        requireFinanceRole();
        Long merchantId = StpUtil.hasRole("SUPERADMIN") ? null : SecurityUtils.requireCurrentMerchantId();
        paymentGatewayService.closePayment(merchantId, orderSn);
        return R.ok();
    }

    private void requireFinanceRole() {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
    }
}
