/*
 * 文件：CardAssetController.java
 * 用途：店长/超管卡密作废与已售卡补发、本店作废/补发记录查询及存量一致性核对接口。
 * 维护注意：商户和操作人只从已校验会话取得；作废/补发分别按卡ID自然幂等，原因不能覆盖原记录。补发响应含新卡明文，必须no-store且服务端已写REISSUE_PLAINTEXT审计；其余响应不含卡密内容。所有路径受merchant鉴权，不开启外层事务。一致性核对为本店后台账号只读，不修正任何漂移。
 */
package com.shop.b2b2c.controller;
import cn.dev33.satoken.stp.StpUtil;
import com.shop.b2b2c.common.*;
import com.shop.b2b2c.entity.CardReissue;
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
    public record ReissueRequest(String reason) {}
    @PostMapping("/{id}/void")
    public R<CardVoidRecord> voidCard(@PathVariable Long id, @RequestBody VoidRequest request) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        storeGuard.requireOpen(merchantId);
        return R.ok("卡密已作废", assets.voidCard(merchantId, SecurityUtils.getCurrentUserId(), id, request.reason()));
    }
    /**
     * F12 补发授权：店长/超管对已售卡补发一张同SKU新卡，复用原订单号。
     * 响应含新卡明文（交付买家用），必须no-store；同原因重放幂等返回原记录与新卡明文。
     */
    @PostMapping("/{id}/reissue")
    public R<java.util.Map<String,Object>> reissueCard(@PathVariable Long id, @RequestBody ReissueRequest request,
                                                       jakarta.servlet.http.HttpServletResponse response) {
        StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        storeGuard.requireOpen(merchantId);
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        return R.ok("卡密已补发", assets.reissueCard(merchantId, SecurityUtils.getCurrentUserId(), id, request.reason()));
    }
    /** F12 补发记录查询：本店后台账号（含员工）只读，不含卡密内容。 */
    @GetMapping("/{id}/reissue-record")
    public R<CardReissue> reissueRecord(@PathVariable Long id) {
        return R.ok(assets.readReissueRecord(SecurityUtils.requireCurrentMerchantId(), id));
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
