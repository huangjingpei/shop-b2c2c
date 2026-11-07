/*
 * 文件：AdminComplaintController.java
 * 用途：F06 平台超管投诉治理接口：全平台工单分页、详情（含追加操作记录）、进入仲裁后的裁决（可联动原路退款）。
 * 维护注意：/api/v1/admin/** 由 SaTokenConfigure 统一要求 SUPERADMIN，控制器内再显式 checkRole；
 * 裁决只能在 ARBITRATING 状态执行，退款金额由服务端校验可退余额，渠道失败会回滚工单状态。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.dto.ComplaintDTOs;
import com.shop.b2b2c.entity.Complaint;
import com.shop.b2b2c.service.ComplaintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "平台投诉仲裁 (Admin Complaint)")
@RestController
@RequiredArgsConstructor
public class AdminComplaintController {

    private final ComplaintService complaintService;

    @Operation(summary = "全平台投诉工单分页查询")
    @GetMapping("/api/v1/admin/complaints")
    public R<IPage<Complaint>> list(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(complaintService.listForOperator(null, status, page, size));
    }

    @Operation(summary = "查看投诉详情与不可覆盖的操作记录")
    @GetMapping("/api/v1/admin/complaints/{id}")
    public R<ComplaintDTOs.ComplaintDetail> detail(@PathVariable Long id) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(complaintService.detailForAdmin(id));
    }

    @Operation(summary = "平台裁决（支持/驳回，可联动原路退款）")
    @PostMapping("/api/v1/admin/complaints/{id}/arbitrate")
    public R<Complaint> arbitrate(@PathVariable Long id,
                                  @Valid @RequestBody ComplaintDTOs.ArbitrateRequest request) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(complaintService.arbitrate(id, StpUtil.getLoginIdAsLong(), request));
    }
}
