/*
 * 文件：CardAssetController.java
 * 用途：店长/超管卡密作废、本店作废记录查询及存量一致性核对接口。
 * 维护注意：商户和操作人只从已校验会话取得；作废按卡ID自然幂等，原因不能覆盖原记录。响应不含卡密内容；所有路径受merchant鉴权，不开启外层事务。一致性核对为本店后台账号只读，不修正任何漂移。
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
    /** O4 商家写接口营业状态闸门。 */
    private final com.shop.b2b2c.service.MerchantStoreGuard storeGuard;
    public record VoidRequest(String reason) {}
    @PostMapping("/{id}/void")
    public R<CardVoidRecord> voidCard(@PathVariable Long id, @RequestBody VoidRequest request) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        storeGuard.requireOpen(merchantId);
        return R.ok("卡密已作废", assets.voidCard(merchantId, SecurityUtils.getCurrentUserId(), id, request.reason()));
    }
    /** F12 存量一致性核对：本店后台账号只读（含员工），打烊/冻结店铺也可核对；响应no-store。 */
    @GetMapping("/consistency")
    public R<java.util.Map<String,Object>> consistency(jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        return R.ok(assets.consistencyCheck(SecurityUtils.requireCurrentMerchantId()));
    }
    public R<CardVoidRecord> record(Long id) {
        return record(id, null);
    }
    @GetMapping("/{id}/void-record")
    public R<CardVoidRecord> record(@PathVariable Long id, jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        return R.ok(assets.readRecord(SecurityUtils.requireCurrentMerchantId(), id));
    }
}
