/*
 * 文件：CardImportBatchController.java
 * 用途：受保护的本店导入批次与成员状态查询入口。
 * 维护注意：不接受请求指定商户，不返回卡密正文，不以批次ID代替授权；返回no-store避免私有记录缓存。写入仍通过统一卡密导入事务。
 */
package com.shop.b2b2c.controller;
import com.shop.b2b2c.common.*;
import com.shop.b2b2c.dto.CardImportDTOs.*;
import com.shop.b2b2c.entity.CardImportBatch;
import com.shop.b2b2c.service.CardImportBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/card-import-batches")
public class CardImportBatchController {
    private final CardImportBatchService batches;
    @GetMapping
    public R<Page<CardImportBatch>> list(@RequestParam(required = false) Long skuId, @RequestParam(required = false) Long beforeId,
                                        @RequestParam(defaultValue = "20") int limit, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return R.ok(batches.list(SecurityUtils.requireCurrentMerchantId(), skuId, beforeId, limit));
    }
    @GetMapping("/{id}/cards")
    public R<Page<Item>> members(@PathVariable Long id, @RequestParam(required = false) Long beforeId,
                                  @RequestParam(defaultValue = "20") int limit, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return R.ok(batches.members(SecurityUtils.requireCurrentMerchantId(), id, beforeId, limit));
    }
}
