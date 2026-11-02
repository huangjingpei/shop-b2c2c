package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.R;
import com.shop.b2b2c.dto.PeerPayDTOs;
import com.shop.b2b2c.entity.PlatformAdConfig;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.service.PeerPayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    private final PeerPayService peerPayService;

    @Operation(summary = "【买家端】发起求助代付 (生成代付短链与微信裂变海报)")
    @PostMapping("/buyer/create")
    public R<Map<String, Object>> createBuyerPeerPay(@RequestBody PeerPayDTOs.BuyerCreateRequest req) {
        Map<String, Object> res = peerPayService.createBuyerPeerPay(req);
        return R.ok("求助代付发起成功", res);
    }

    @Operation(summary = "【公开/免登录】查询代付详情 (含自愈到期、隐私脱敏)")
    @GetMapping("/detail/{token}")
    public R<PeerPayDTOs.DetailResponse> getPeerPayDetail(@PathVariable String token) {
        PeerPayDTOs.DetailResponse detail = peerPayService.getPeerPayDetail(token);
        return R.ok("获取代付账单成功", detail);
    }

    @Operation(summary = "【代付两段式核心】调起收银台时原子预占库存 (锁 15 分钟)")
    @PostMapping("/pre-lock/{token}")
    public R<PeerPayDTOs.PreLockResult> preLockInventory(@PathVariable String token) {
        PeerPayDTOs.PreLockResult result = peerPayService.preLockInventory(token);
        return R.ok(result.getMessage(), result);
    }

    @Operation(summary = "【公开】付款人放弃/离开收银台主动释放预占锁")
    @PostMapping("/cancel-lock/{token}")
    public R<Void> cancelPreLock(@PathVariable String token) {
        peerPayService.cancelPreLock(token);
        return R.ok("预占库存已释放", null);
    }

    @Operation(summary = "【沙箱/Mock支付桩】模拟微信/支付宝代付成功 (真实扣减库存与三方对账)")
    @PostMapping("/mock-pay/{token}")
    public R<TradeOrder> mockPay(
            @PathVariable String token,
            @RequestBody(required = false) PeerPayDTOs.MockPayRequest req) {
        String payerId = (req != null && req.getPayerId() != null) ? req.getPayerId() : "MOCK_PAYER_WECHAT_GUEST";
        String channel = (req != null && req.getChannel() != null) ? req.getChannel() : "WECHAT";
        String payTradeNo = "MOCK_WX_TRD_" + System.currentTimeMillis();

        TradeOrder order = peerPayService.notifyPaymentSuccess(token, payerId, payTradeNo, channel);
        return R.ok("代付成功！已为您完成支付", order);
    }

    @Operation(summary = "【公开】获取代付成功页【我也想开店】引流转化广告位")
    @GetMapping("/success-ad")
    public R<PlatformAdConfig> getSuccessAd() {
        PlatformAdConfig ad = peerPayService.getSuccessAdBanner();
        return R.ok("获取广告位成功", ad);
    }

    @Operation(summary = "【公开】重新生成微信防封海报")
    @GetMapping("/poster/{token}")
    public R<String> regeneratePoster(@PathVariable String token) {
        String base64 = peerPayService.regeneratePoster(token);
        return R.ok("海报生成成功", base64);
    }
}
