/*
 * 文件：BuyerRefundRequestController.java
 * 用途：F05 买家侧（免密凭证）退款申请接口：申请、查询、撤回。认证复用 quick-search 的 orderSn+contact+queryToken 凭证。
 * 维护注意：公开接口不限登录，但必须校验订单归属与查询凭证；不返回 buyer_contact 等敏感字段；限流由网关层处理。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.R;
import com.shop.b2b2c.dto.RefundRequestDTOs;
import com.shop.b2b2c.entity.RefundRequest;
import com.shop.b2b2c.service.RefundRequestService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class BuyerRefundRequestController {

    private final RefundRequestService refundRequestService;

    @Operation(summary = "买家申请退款（免密凭证）")
    @PostMapping("/api/v1/public/orders/refund-requests")
    public R<RefundRequest> apply(@Valid @RequestBody RefundRequestDTOs.BuyerApplyRequest request) {
        return R.ok(refundRequestService.apply(request));
    }

    @Operation(summary = "买家查询订单退款申请")
    @GetMapping("/api/v1/public/orders/refund-requests")
    public R<List<RefundRequest>> list(@RequestParam String orderSn,
                                       @RequestParam String contact,
                                       @RequestParam String queryToken) {
        return R.ok(refundRequestService.listForBuyer(orderSn, contact, queryToken));
    }

    @Operation(summary = "买家撤回退款申请")
    @PostMapping("/api/v1/public/orders/refund-requests/{id}/cancel")
    public R<Map<String, Object>> cancel(@PathVariable Long id,
                                         @Valid @RequestBody RefundRequestDTOs.BuyerCancelRequest request) {
        refundRequestService.cancel(id, request.orderSn(), request.contact(), request.queryToken());
        return R.ok(Map.of("cancelled", true));
    }
}
