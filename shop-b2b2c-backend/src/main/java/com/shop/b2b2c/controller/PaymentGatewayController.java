/*
 * 文件：PaymentGatewayController.java
 * 用途：阶段一真实支付统一入口与渠道异步通知端点 (T32)。
 * 维护注意：
 *  1. /api/v1/payment/callback/** 必须公网可达但不经登录态；安全性完全依赖适配器验签，控制器不得跳过验签。
 *  2. 回调失败按渠道协议返回失败，触发渠道重试；成功才返回渠道要求的 ACK，禁止把内部异常细节原样回显。
 *  3. 买家发起支付需订单访问凭证并走限流；商家查单/关单仅店长或超管，且必须校验订单归属。
 *  4. 回调路径与 notify-base-url 拼接规则固定，改动需同步 PaymentChannelProperties。
 *  5. Sprint 59：/callback/platform 为平台代收（微信电商收付通）专属入口，路径与 payment.platform.notify-path
 *     保持一致；安全性由平台服务商凭据验签保证，收银台未配置/未联调时按渠道协议返回失败触发重试。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.dto.PaymentChannelDTOs;
import com.shop.b2b2c.service.ClientIpResolver;
import com.shop.b2b2c.service.TradeCostLimiter;
import com.shop.b2b2c.service.payment.PaymentGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Tag(name = "真实支付渠道 (Stage One Direct Pay)")
@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class PaymentGatewayController {

    private final PaymentGatewayService paymentGatewayService;
    private final ClientIpResolver ipResolver;
    private final TradeCostLimiter costLimiter;

    @Operation(summary = "【买家端】发起真实渠道支付（微信/支付宝商家直收）")
    @PostMapping("/create")
    public R<PaymentChannelDTOs.PaymentAttemptView> createPayment(
            @RequestBody PaymentChannelDTOs.CreatePaymentRequest request,
            HttpServletRequest servletRequest) {
        if (request == null || request.getOrderSn() == null || request.getOrderSn().isBlank()) {
            throw new BusinessException(400, "订单号不能为空");
        }
        String ip = servletRequest != null ? ipResolver.resolve(servletRequest) : null;
        costLimiter.checkPreLock(ip, request.getOrderSn());
        PaymentChannelDTOs.PaymentAttemptView view = paymentGatewayService.createPayment(request, ip);
        return R.ok("支付参数已生成", view);
    }

    @Operation(summary = "微信支付 APIv3 异步通知")
    @PostMapping("/callback/wechat")
    public ResponseEntity<Map<String, Object>> wechatCallback(
            @RequestHeader Map<String, String> headers,
            @RequestBody(required = false) String body) {
        Map<String, Object> ack = new LinkedHashMap<>();
        try {
            paymentGatewayService.handleCallback("WECHAT", headers, body, null);
            ack.put("code", "SUCCESS");
            ack.put("message", "成功");
            return ResponseEntity.ok(ack);
        } catch (BusinessException e) {
            log.warn("微信支付回调处理失败: {}", e.getMessage());
            ack.put("code", "FAIL");
            ack.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ack);
        }
    }

    @Operation(summary = "微信电商收付通平台代收异步通知（Sprint 59 M6 联调入口）")
    @PostMapping("/callback/platform")
    public ResponseEntity<Map<String, Object>> platformCallback(
            @RequestHeader Map<String, String> headers,
            @RequestBody(required = false) String body) {
        // 平台收银台当前仅注册 WECHAT 渠道；路径固定对应 payment.platform.notify-path。
        Map<String, Object> ack = new LinkedHashMap<>();
        try {
            paymentGatewayService.handlePlatformCallback("WECHAT", headers, body);
            ack.put("code", "SUCCESS");
            ack.put("message", "成功");
            return ResponseEntity.ok(ack);
        } catch (BusinessException e) {
            log.warn("平台代收回调处理失败: {}", e.getMessage());
            ack.put("code", "FAIL");
            ack.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ack);
        }
    }

    @Operation(summary = "支付宝异步通知")
    @PostMapping("/callback/alipay")
    public ResponseEntity<String> alipayCallback(@RequestParam Map<String, String> params) {
        try {
            paymentGatewayService.handleCallback("ALIPAY", null, null, params);
            return ResponseEntity.ok("success");
        } catch (BusinessException e) {
            log.warn("支付宝异步通知处理失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("fail");
        }
    }
}
