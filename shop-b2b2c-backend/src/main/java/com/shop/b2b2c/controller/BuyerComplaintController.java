/*
 * 文件：BuyerComplaintController.java
 * 用途：F06 买家侧（免密凭证）投诉接口：提交投诉与举证、按订单查询、查看工单详情与事件、申请平台介入。
 * 维护注意：公开接口不限登录，但必须校验订单归属与查询凭证；不返回其他订单数据；凭证只走请求体，不进 URL/日志。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.R;
import com.shop.b2b2c.dto.ComplaintDTOs;
import com.shop.b2b2c.entity.Complaint;
import com.shop.b2b2c.service.ComplaintService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class BuyerComplaintController {

    private final ComplaintService complaintService;

    @Operation(summary = "买家提交投诉（免密凭证+举证）")
    @PostMapping("/api/v1/public/orders/complaints")
    public R<Complaint> submit(@Valid @RequestBody ComplaintDTOs.BuyerSubmitRequest request) {
        return R.ok(complaintService.submit(request));
    }

    @Operation(summary = "买家按订单查询投诉列表")
    @GetMapping("/api/v1/public/orders/complaints")
    public R<List<Complaint>> list(@RequestParam String orderSn, @RequestParam String contact,
                                   @RequestParam String queryToken) {
        return R.ok(complaintService.listForBuyer(orderSn, contact, queryToken));
    }

    @Operation(summary = "买家查看投诉详情与不可覆盖的操作记录")
    @GetMapping("/api/v1/public/orders/complaints/{id}")
    public R<ComplaintDTOs.ComplaintDetail> detail(@PathVariable Long id, @RequestParam String orderSn,
                                                   @RequestParam String contact, @RequestParam String queryToken) {
        return R.ok(complaintService.detailForBuyer(id, orderSn, contact, queryToken));
    }

    @Operation(summary = "买家申请平台介入仲裁")
    @PostMapping("/api/v1/public/orders/complaints/{id}/escalate")
    public R<Complaint> escalate(@PathVariable Long id,
                                 @Valid @RequestBody ComplaintDTOs.BuyerEscalateRequest request) {
        return R.ok(complaintService.escalate(id, request));
    }
}
