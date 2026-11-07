/*
 * 文件：MerchantComplaintController.java
 * 用途：F06 商家端投诉接口：本店工单分页、详情（含追加操作记录）、提交答复与举证。
 * 维护注意：权限由后端 StpUtil.checkRoleOr("SUPERADMIN","MERCHANT_ADMIN") 强制；商家只能处理本店工单，
 * 超管可跨店；列表与详情不返回 buyer_id（买家联系方式）。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.dto.ComplaintDTOs;
import com.shop.b2b2c.entity.Complaint;
import com.shop.b2b2c.service.ComplaintService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MerchantComplaintController {

    private final ComplaintService complaintService;

    @Operation(summary = "商家查询本店投诉工单")
    @GetMapping("/api/v1/merchant/complaints")
    public R<IPage<Complaint>> list(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        Long merchantId = StpUtil.hasRole("SUPERADMIN") ? null : SecurityUtils.requireCurrentMerchantId();
        return R.ok(complaintService.listForOperator(merchantId, status, page, size));
    }

    @Operation(summary = "商家查看投诉详情与操作记录")
    @GetMapping("/api/v1/merchant/complaints/{id}")
    public R<ComplaintDTOs.ComplaintDetail> detail(@PathVariable Long id) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        Long merchantId = StpUtil.hasRole("SUPERADMIN") ? null : SecurityUtils.requireCurrentMerchantId();
        return R.ok(complaintService.detailForMerchant(id, merchantId));
    }

    @Operation(summary = "商家答复投诉并提交举证")
    @PostMapping("/api/v1/merchant/complaints/{id}/reply")
    public R<Complaint> reply(@PathVariable Long id,
                              @Valid @RequestBody ComplaintDTOs.MerchantReplyRequest request) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        Long merchantId = StpUtil.hasRole("SUPERADMIN") ? null : SecurityUtils.requireCurrentMerchantId();
        Object username = StpUtil.getTokenSession().get("username");
        return R.ok(complaintService.reply(id, merchantId, StpUtil.getLoginIdAsLong(),
                username == null ? null : username.toString(), request));
    }
}
