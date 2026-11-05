/*
 * 文件：PeerPayController.java
 * 用途：买家代付账单、预占、授权取消、海报与成功页广告接口。
 * 维护注意：
 *  1. 建单强制 Idempotency-Key 并受应用成本限流预算；不提供 Mock 支付或未经验证的公开到账入口。
 *  2. 取消必须提交 X-Reservation-Token。分享 token 仅定位账单，不能替代预占所有者凭证。
 *  3. T10：Idempotency-Key 只作为尝试键；恢复预占必须回传服务端上次签发的 X-Reservation-Token，最多重发 3 次。
 *  4. T36：建单、预占、海报限流IP由ClientIpResolver解析，仅可信代理才读X-Forwarded-For。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.dto.PeerPayDTOs;
import com.shop.b2b2c.entity.PlatformAdConfig;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.service.OrderIdempotencyService;
import com.shop.b2b2c.service.PeerPayService;
import com.shop.b2b2c.service.TradeCostLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@Tag(name = "找人代付与两段式极速收银 (Peer Pay & Cashier)")
@RestController
@RequestMapping("/api/v1/peer-pay")
@RequiredArgsConstructor
public class PeerPayController {

    private final com.shop.b2b2c.service.ClientIpResolver ipResolver;
    private final PeerPayService peerPayService;
    private final OrderIdempotencyService idempotency;
    private final TradeCostLimiter costLimiter;

    @Operation(summary = "【买家端】发起求助代付 (生成代付短链与微信裂变海报)")
    @PostMapping("/buyer/create")
    public R<Map<String, Object>> createBuyerPeerPay(@RequestBody PeerPayDTOs.BuyerCreateRequest req,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        if (req == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        costLimiter.checkOrderCreation(request != null ? ipResolver.resolve(request) : null, req.getBuyerContact());
        Map<String, Object> res = idempotency.execute("buyer:guest", key, req, () -> peerPayService.createBuyerPeerPay(req));
        return R.ok("求助代付发起成功", res);
    }

    @Operation(summary = "【公开/免登录】查询代付详情 (含自愈到期、隐私脱敏、海报减载)")
    @GetMapping("/detail/{token}")
    public R<PeerPayDTOs.DetailResponse> getPeerPayDetail(@PathVariable String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(400, "代付凭证不能为空");
        }
        PeerPayDTOs.DetailResponse detail = peerPayService.getPeerPayDetail(token);
        return R.ok("获取代付账单成功", detail);
    }

    @Operation(summary = "【代付两段式核心】调起收银台时原子预占库存 (锁 15 分钟)")
    @PostMapping("/pre-lock/{token}")
    public R<PeerPayDTOs.PreLockResult> preLockInventory(@PathVariable String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Reservation-Token", required = false) String reservationToken,
            HttpServletRequest request) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(400, "代付凭证不能为空");
        }
        costLimiter.checkPreLock(request != null ? ipResolver.resolve(request) : null, token);
        PeerPayDTOs.PreLockResult result = peerPayService.preLockInventory(token, idempotencyKey, reservationToken);
        return R.ok(result.getMessage(), result);
    }

    @Operation(summary = "【公开】付款人放弃/离开收银台主动释放预占锁")
    @PostMapping("/cancel-lock/{token}")
    public R<Void> cancelPreLock(@PathVariable String token,
            @RequestHeader("X-Reservation-Token") String ownerToken) {
        peerPayService.cancelPreLock(token, ownerToken);
        return R.ok("预占库存已释放", null);
    }

    @Operation(summary = "【公开】获取代付成功页【我也想开店】引流转化广告位")
    @GetMapping("/success-ad")
    public R<PlatformAdConfig> getSuccessAd() {
        PlatformAdConfig ad = peerPayService.getSuccessAdBanner();
        return R.ok("获取广告位成功", ad);
    }

    @Operation(summary = "【公开】按需获取/重新生成微信防封海报")
    @GetMapping("/poster/{token}")
    public R<String> regeneratePoster(@PathVariable String token,
            HttpServletRequest request) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(400, "代付凭证不能为空");
        }
        costLimiter.checkPosterGeneration(request != null ? ipResolver.resolve(request) : null, token);
        String base64 = peerPayService.regeneratePoster(token);
        return R.ok("海报获取成功", base64);
    }
}
