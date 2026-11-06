/*
 * 文件：MerchantRefundController.java
 * 用途：T33 商家/超管退款申请、退款记录查询、退款查单同步与重复付款补偿入口。
 * 维护注意：
 *  1. 路径必须位于 /api/v1/merchant/**，才能被 SaTokenConfigure 的商家登录与角色拦截覆盖。
 *  2. 资金类操作仅店长或超管；员工不得发起退款或补偿。角色检查不替代订单/退款归属校验。
 *  3. 退款与重复付款补偿都强制 Idempotency-Key；幂等键只防重复请求，不替代渠道退款单号。
 *  4. 平台超管 merchantId 传 null 表示跨店，归属校验由服务层按记录商户处理。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.dto.RefundDTOs;
import com.shop.b2b2c.service.payment.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "商家退款与重复付款补偿 (Stage One Refund)")
@RestController
@RequestMapping("/api/v1/merchant/refunds")
@RequiredArgsConstructor
public class MerchantRefundController {

    private final RefundService refundService;

    @Operation(summary = "申请订单退款（仅店长/超管）")
    @PostMapping("/orders/{orderSn}")
    public R<RefundDTOs.RefundView> applyRefund(@PathVariable String orderSn,
                                                @RequestBody RefundDTOs.RefundRequest request,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireFinanceRole();
        Long merchantId = currentMerchantId();
        return R.ok("退款已受理", refundService.applyOrderRefund(merchantId, orderSn, request,
                idempotencyKey, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "查询订单退款记录")
    @GetMapping("/orders/{orderSn}")
    public R<List<RefundDTOs.RefundView>> listRefunds(@PathVariable String orderSn) {
        requireFinanceRole();
        return R.ok(refundService.listOrderRefunds(currentMerchantId(), orderSn));
    }

    @Operation(summary = "退款查单同步（仅店长/超管）")
    @PostMapping("/{refundNo}/sync")
    public R<RefundDTOs.RefundView> syncRefund(@PathVariable String refundNo) {
        requireFinanceRole();
        return R.ok("同步完成", refundService.syncRefund(currentMerchantId(), refundNo));
    }

    @Operation(summary = "重复付款补偿队列")
    @GetMapping("/duplicate-payments")
    public R<List<RefundDTOs.DuplicatePaymentView>> duplicatePayments(
            @RequestParam(defaultValue = "50") int limit) {
        requireFinanceRole();
        return R.ok(refundService.listDuplicatePayments(currentMerchantId(), limit));
    }

    @Operation(summary = "重复付款原路退回（仅店长/超管）")
    @PostMapping("/duplicate-payments/{eventId}/compensate")
    public R<RefundDTOs.RefundView> compensateDuplicatePayment(
            @PathVariable Long eventId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireFinanceRole();
        return R.ok("补偿已受理", refundService.compensateDuplicatePayment(currentMerchantId(), eventId,
                idempotencyKey, StpUtil.getLoginIdAsLong()));
    }

    private void requireFinanceRole() {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
    }

    private Long currentMerchantId() {
        return StpUtil.hasRole("SUPERADMIN") ? null : SecurityUtils.requireCurrentMerchantId();
    }
}
