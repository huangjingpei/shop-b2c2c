/*
 * 文件：CardAssetController.java
 * 用途：店长/超管卡密作废及本店作废记录查询接口。
 * 维护注意：商户和操作人只从已校验会话取得；作废按卡ID自然幂等，原因不能覆盖原记录。响应不含卡密内容；所有路径受merchant鉴权，不开启外层事务。
 */
package com.shop.b2b2c.controller;
import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.*;
import com.shop.b2b2c.entity.CardVoidRecord;
import com.shop.b2b2c.service.CardAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/merchant/cards")
public class CardAssetController {
    private final CardAssetService assets;
    public record VoidRequest(String reason) {}
    @PostMapping("/{id}/void")
    public R<CardVoidRecord> voidCard(@PathVariable Long id, @RequestBody VoidRequest request) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        return R.ok("卡密已作废", assets.voidCard(SecurityUtils.requireCurrentMerchantId(), SecurityUtils.getCurrentUserId(), id, request.reason()));
    }
    @GetMapping("/{id}/void-record")
    public R<CardVoidRecord> record(@PathVariable Long id) { return R.ok(assets.readRecord(SecurityUtils.requireCurrentMerchantId(), id)); }
}
