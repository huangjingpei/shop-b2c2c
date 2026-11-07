/*
 * 文件：MerchantRefundRequestController.java
 * 用途：F05 商家端退款申请审批接口：待审批列表、同意（执行原路退款）、拒绝。
 * 维护注意：审批权限由后端 StpUtil.checkRoleOr("SUPERADMIN","MERCHANT_ADMIN") 强制；仅可处理本店申请；超管可跨店。
 */
package com.shop.b2b2c.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.dto.RefundRequestDTOs;
import com.shop.b2b2c.entity.RefundRequest;
import com.shop.b2b2c.service.RefundRequestService;
import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MerchantRefundRequestController {

    private final RefundRequestService refundRequestService;

    @Operation(summary = "商家查询退款申请列表")
    @GetMapping("/api/v1/merchant/refund-requests")
    public R<IPage<RefundRequest>> list(@RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        Long merchantId = StpUtil.hasRole("SUPERADMIN") ? null : SecurityUtils.requireCurrentMerchantId();
        return R.ok(refundRequestService.listForMerchant(merchantId, status, page, size));
    }

    @Operation(summary = "商家同意退款申请（执行原路退款）")
    @PostMapping("/api/v1/merchant/refund-requests/{id}/approve")
    public R<RefundRequest> approve(@PathVariable Long id) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        Long merchantId = StpUtil.hasRole("SUPERADMIN") ? null : SecurityUtils.requireCurrentMerchantId();
        return R.ok(refundRequestService.approve(id, merchantId, StpUtil.getLoginIdAsLong(),
                StpUtil.hasRole("SUPERADMIN") ? "SUPERADMIN" : "MERCHANT_ADMIN"));
    }

    @Operation(summary = "商家拒绝退款申请")
    @PostMapping("/api/v1/merchant/refund-requests/{id}/reject")
    public R<RefundRequest> reject(@PathVariable Long id, @Valid @RequestBody RefundRequestDTOs.RejectRequest request) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        Long merchantId = StpUtil.hasRole("SUPERADMIN") ? null : SecurityUtils.requireCurrentMerchantId();
        return R.ok(refundRequestService.reject(id, merchantId, StpUtil.getLoginIdAsLong(),
                StpUtil.hasRole("SUPERADMIN") ? "SUPERADMIN" : "MERCHANT_ADMIN", request.rejectReason()));
    }
}
