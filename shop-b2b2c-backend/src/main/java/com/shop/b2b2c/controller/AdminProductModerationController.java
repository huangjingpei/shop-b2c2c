/*
 * 文件：AdminProductModerationController.java
 * 用途：F11 平台超管的商品治理入口：商品分页查询、违规下架、审核恢复与处置记录查询。
 * 维护注意：
 *  1. 路径位于 /api/v1/admin/**，由 SaTokenConfigure 统一要求 SUPERADMIN，控制器内仍显式校验一次。
 *  2. 违规下架/恢复的状态推进与审计写入由 ProductModerationService 在同一事务完成，控制器不做业务判断。
 *  3. 列表只读视图含店铺名；处置原因/审核备注长度在服务层统一校验（trim 后 1..255 / ≤255）。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.dto.ProductModerationDTOs;
import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductViolationAudit;
import com.shop.b2b2c.service.ProductModerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "平台商品治理 (Admin Product Moderation)")
@RestController
@RequestMapping("/api/v1/admin/products")
@RequiredArgsConstructor
public class AdminProductModerationController {

    private final ProductModerationService moderationService;

    @Operation(summary = "全平台商品分页查询（含违规状态与店铺名）")
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) Long merchantId,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(moderationService.list(merchantId, status, keyword, page, size));
    }

    @Operation(summary = "违规下架商品")
    @PostMapping("/{id}/violation-takedown")
    public R<Product> takedown(@PathVariable Long id,
                               @RequestBody(required = false) ProductModerationDTOs.TakedownRequest request) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok("商品已违规下架", moderationService.takedown(id,
                request == null ? null : request.getReason(), StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "审核恢复商品（回到处置前状态）")
    @PostMapping("/{id}/restore")
    public R<Product> restore(@PathVariable Long id,
                              @RequestBody(required = false) ProductModerationDTOs.RestoreRequest request) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok("商品已恢复", moderationService.restore(id,
                request == null ? null : request.getNote(), StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "商品违规处置记录")
    @GetMapping("/{id}/violation-audits")
    public R<List<ProductViolationAudit>> audits(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "100") int limit) {
        StpUtil.checkRole("SUPERADMIN");
        return R.ok(moderationService.audits(id, limit));
    }
}
