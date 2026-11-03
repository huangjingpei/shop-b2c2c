/*
 * 文件：ProductEditingController.java
 * 用途：商家商品编辑快照、资料/SKU售价保存及上下架HTTP入口。
 * 维护注意：租户只从已验证会话取得，不能从请求体选择；所有接口位于受保护的merchant路径，摘要不是权限令牌。事务与防覆盖由服务层执行，不对公开接口返回编辑版本。
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
    @GetMapping("/{id}/editor")
    public R<Editor> get(@PathVariable Long id) { return R.ok(editing.get(SecurityUtils.requireCurrentMerchantId(), id)); }
    @PutMapping("/{id}")
    public R<Editor> update(@PathVariable Long id, @RequestBody Update edit) {
        return R.ok("商品资料已保存", editing.update(SecurityUtils.requireCurrentMerchantId(), id, edit));
    }
    @PatchMapping("/{id}/status")
    public R<Editor> status(@PathVariable Long id, @RequestBody Status change) {
        return R.ok("商品状态已更新", editing.changeStatus(SecurityUtils.requireCurrentMerchantId(), id, change));
    }
}
