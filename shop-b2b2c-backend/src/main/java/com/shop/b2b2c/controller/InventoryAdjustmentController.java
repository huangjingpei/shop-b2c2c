/*
 * 文件：InventoryAdjustmentController.java
 * 用途：商家人工库存调整与本店记录查询入口。
 * 维护注意：写入限店长和超管，商户/操作人来自已验证会话；员工可读本店记录。调用前不得开启业务事务，幂等键为必需请求头，不能接受请求体伪造操作人。
 */
package com.shop.b2b2c.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.*;
import com.shop.b2b2c.dto.InventoryAdjustmentDTOs.*;
import com.shop.b2b2c.service.InventoryAdjustmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/inventory-adjustments")
public class InventoryAdjustmentController {
    private final InventoryAdjustmentService adjustments;
    @PostMapping
    public R<View> adjust(@RequestBody Adjust request, @RequestHeader("Idempotency-Key") String key) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        return R.ok("库存调整已记录", adjustments.adjust(SecurityUtils.requireCurrentMerchantId(), SecurityUtils.getCurrentUserId(), key, request));
    }
    @GetMapping
    public R<Page> list(@RequestParam(required = false) Long skuId, @RequestParam(required = false) Long beforeId,
                        @RequestParam(defaultValue = "20") int limit) {
        return R.ok(adjustments.list(SecurityUtils.requireCurrentMerchantId(), skuId, beforeId, limit));
    }
}
