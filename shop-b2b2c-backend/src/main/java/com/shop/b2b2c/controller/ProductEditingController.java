/*
 * 文件：ProductEditingController.java
 * 用途：商家商品编辑快照、资料/SKU售价保存、上下架及SKU增删HTTP入口。
 * 维护注意：租户只从已验证会话取得，不能从请求体选择；所有接口位于受保护的merchant路径，摘要不是权限令牌。事务与防覆盖由服务层执行，不对公开接口返回编辑版本。新增SKU视为产生新可售单元，与发布新品同口径校验资质有效期（O9代理细化，待业务确认）；删除SKU仅作减损不加资质闸门。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.dto.ProductEditingDTOs.*;
import com.shop.b2b2c.service.ProductEditingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/products")
public class ProductEditingController {
    private final ProductEditingService editing;
    /** O4 商家写接口营业状态闸门。 */
    private final com.shop.b2b2c.service.MerchantStoreGuard storeGuard;
    @GetMapping("/{id}/editor")
    public R<Editor> get(@PathVariable Long id) { return R.ok(editing.get(SecurityUtils.requireCurrentMerchantId(), id)); }
    @PutMapping("/{id}")
    public R<Editor> update(@PathVariable Long id, @RequestBody Update edit) {
        try {
            if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
                cn.dev33.satoken.stp.StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
            }
        } catch (cn.dev33.satoken.exception.SaTokenContextException ignored) {
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        storeGuard.requireOpen(merchantId);
        return R.ok("商品资料已保存", editing.update(merchantId, id, edit));
    }
    @PatchMapping("/{id}/status")
    public R<Editor> status(@PathVariable Long id, @RequestBody Status change) {
        try {
            if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
                cn.dev33.satoken.stp.StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
            }
        } catch (cn.dev33.satoken.exception.SaTokenContextException ignored) {
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        storeGuard.requireOpen(merchantId);
        return R.ok("商品状态已更新", editing.changeStatus(merchantId, id, change));
    }
    @PostMapping("/{id}/skus")
    public R<Editor> addSku(@PathVariable Long id, @RequestBody SkuDraft draft) {
        try {
            if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
                cn.dev33.satoken.stp.StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
            }
        } catch (cn.dev33.satoken.exception.SaTokenContextException ignored) {
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        storeGuard.requireOpen(merchantId);
        storeGuard.requireQualificationValid(merchantId);
        return R.ok("SKU已新增", editing.addSku(merchantId, id, draft));
    }
    @DeleteMapping("/{id}/skus/{skuId}")
    public R<Editor> removeSku(@PathVariable Long id, @PathVariable Long skuId, @RequestBody SkuRemoval removal) {
        try {
            if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
                cn.dev33.satoken.stp.StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
            }
        } catch (cn.dev33.satoken.exception.SaTokenContextException ignored) {
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        storeGuard.requireOpen(merchantId);
        return R.ok("SKU已删除", editing.removeSku(merchantId, id, skuId, removal));
    }
}
