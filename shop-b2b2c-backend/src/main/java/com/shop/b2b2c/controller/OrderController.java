/*
 * 文件：OrderController.java
 * 用途：待付款建单、免登录查单、商家发货及平台订单查询入口。
 * 维护注意：HTTP建单强制Idempotency-Key，内部checkout仅在事务中使用；不得伪造到账或直接交付；实物发货必须验证店铺、类型和状态。查单店铺信息走PublicStoreView，查单身份校验和统计口径仍有待处理问题。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.common.TradeGuards;
import com.shop.b2b2c.dto.PeerPayDTOs;
import com.shop.b2b2c.entity.*;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.PeerPayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Tag(name = "交易订单与16688速查 (Order & Quick Query)")
@RestController
@RequiredArgsConstructor
public class OrderController {

    private final TradeOrderMapper orderMapper;
    private final TradeOrderItemMapper itemMapper;
    private final MerchantStoreMapper storeMapper;
    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductVirtualCardMapper cardMapper;
    private final StringRedisTemplate redisTemplate;
    private final PeerPayService peerPayService;
    private final com.shop.b2b2c.service.OrderIdempotencyService idempotency;

    @Data
    public static class CheckoutDTO {
        private Long merchantId;
        private Long productId;
        private Long skuId;
        private Integer quantity;
        private String buyerContact; // 手机号或邮箱 (免密查单凭据)
        private String paymentChannel; // WECHAT / ALIPAY
    }

    @Operation(summary = "【公开】创建单店待付款订单（不扣库存、不履约）")
    @PostMapping("/api/v1/public/orders/checkout")
    public R<Map<String, Object>> checkoutRequest(@RequestBody CheckoutDTO dto,
            @RequestHeader("Idempotency-Key") String key) {
        return R.ok("订单已创建，等待付款；支付通道暂未开放",
                idempotency.execute("checkout:guest", key, dto, () -> checkout(dto).getData()));
    }

    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> checkout(CheckoutDTO dto) {
        TradeGuards.requireQuantity(dto.getQuantity());
        if (dto.getQuantity() == null || dto.getQuantity() <= 0) {
            throw new BusinessException("购买数量必须大于0");
        }
        if (dto.getBuyerContact() == null || dto.getBuyerContact().trim().isEmpty()) {
            throw new BusinessException("请输入联系手机号或邮箱，用于提取商品与查单");
        }

        Product product = productMapper.findById(dto.getProductId())
                .orElseThrow(() -> new BusinessException("商品不存在"));
        ProductSku sku = skuMapper.findById(dto.getSkuId())
                .orElseThrow(() -> new BusinessException("规格SKU不存在"));
        MerchantStore store = storeMapper.findById(product.getMerchantId())
                .orElseThrow(() -> new BusinessException("店铺不存在"));

        TradeGuards.requireSkuOwnership(product, sku);
        TradeGuards.requirePurchasable(product, store);
        TradeGuards.requireMoney(sku.getPrice());
        if (dto.getMerchantId() != null && !store.getId().equals(dto.getMerchantId())) {
            throw new BusinessException("商户与商品不匹配");
        }

        // 校验库存
        if (sku.getStockQuantity() < dto.getQuantity()) {
            throw new BusinessException("商品库存不足，剩余: " + sku.getStockQuantity());
        }

        // 生成唯一订单号 SN
        String orderSn = "ORD" + UUID.randomUUID().toString().replace("-", "");
        String queryToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 计算金额与抽佣
        BigDecimal totalAmount = sku.getPrice().multiply(new BigDecimal(dto.getQuantity()));
        TradeGuards.requireMoney(totalAmount);
        BigDecimal commissionRate = store.getCommissionRate();
        BigDecimal commissionAmount = totalAmount.multiply(commissionRate)
                .divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
        BigDecimal merchantSettleAmount = totalAmount.subtract(commissionAmount);

        // 创建订单主表
        TradeOrder order = new TradeOrder();
        order.setOrderSn(orderSn);
        order.setMerchantId(store.getId());
        order.setBuyerContact(dto.getBuyerContact().trim());
        order.setGoodsType(product.getGoodsType());
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount); // 实付
        order.setCommissionRate(commissionRate);
        order.setCommissionAmount(commissionAmount);
        order.setMerchantSettleAmount(merchantSettleAmount);
        order.setPaymentChannel(dto.getPaymentChannel() != null ? dto.getPaymentChannel() : "WECHAT");
        order.setOrderStatus("UNPAID");
        order.setPeerStatus("INIT");
        order.setQueryToken(queryToken);

        order = orderMapper.save(order);

        // 创建订单明细
        TradeOrderItem item = new TradeOrderItem();
        item.setOrderId(order.getId());
        item.setOrderSn(orderSn);
        item.setProductId(product.getId());
        item.setSkuId(sku.getId());
        item.setSkuCode(sku.getSkuCode());
        item.setProductTitle(product.getTitle());
        item.setSpecInfo(sku.getSpecData());
        item.setPrice(sku.getPrice());
        item.setQuantity(dto.getQuantity());
        item.setSubtotal(totalAmount);
        itemMapper.save(item);

        Map<String, Object> result = new HashMap<>();
        result.put("orderSn", orderSn);
        result.put("buyerContact", order.getBuyerContact());
        result.put("orderStatus", order.getOrderStatus());
        result.put("payAmount", order.getPayAmount());
        result.put("goodsType", order.getGoodsType());
        result.put("cards", List.of());
        result.put("paymentAvailable", false);
        return R.ok("订单已创建，等待付款；支付通道暂未开放", result);
    }

    @Operation(summary = "【16688 招牌】免登录订单极速查单 (凭订单号 + 手机/邮箱)")
    @GetMapping("/api/v1/public/orders/quick-search")
    public R<Map<String, Object>> quickSearch(
            @RequestParam String orderSn,
            @RequestParam String contact) {

        // Redis 频次限制防撞库 (单小时 60 次)
        try {
            String rateKey = "rate:order:query:" + contact;
            Long count = redisTemplate.opsForValue().increment(rateKey, 1);
            if (count != null && count == 1) {
                redisTemplate.expire(rateKey, Duration.ofMinutes(5));
            }
            if (count != null && count > 60) {
                return R.fail("查询过于频繁，请稍候再试");
            }
        } catch (Exception ignored) {}

        TradeOrder order = orderMapper.findByOrderSnAndBuyerContact(orderSn.trim(), contact.trim())
                .orElseThrow(() -> new BusinessException("未找到匹配的订单，请仔细核对订单号与下单时预留的手机/邮箱"));

        List<TradeOrderItem> items = itemMapper.findByOrderSn(order.getOrderSn());
        MerchantStore store = storeMapper.findById(order.getMerchantId()).orElse(null);

        Map<String, Object> res = new HashMap<>();
        res.put("order", order);
        res.put("items", items);
        res.put("store", com.shop.b2b2c.common.PublicStoreView.from(store));
        return R.ok("订单查询成功", res);
    }

    // ==========================================
    // 商家工作台视角 API (/api/v1/merchant/orders)
    // ==========================================

    @Operation(summary = "【商家】查询属于本店的全部订单")
    @GetMapping("/api/v1/merchant/orders")
    public R<List<Map<String, Object>>> listMerchantOrders() {
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        List<TradeOrder> orders = orderMapper.findByMerchantIdOrderByCreatedAtDesc(merchantId);

        List<Map<String, Object>> resList = new ArrayList<>();
        for (TradeOrder o : orders) {
            Map<String, Object> map = new HashMap<>();
            map.put("order", o);
            map.put("items", itemMapper.findByOrderSn(o.getOrderSn()));
            resList.add(map);
        }
        return R.ok(resList);
    }

    @Data
    public static class DeliverDTO {
        private String logisticsCompany;
        private String trackingNumber;
    }

    @Operation(summary = "【商家】实物订单录入运单发货")
    @PostMapping("/api/v1/merchant/orders/{id}/deliver")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> deliverOrder(@PathVariable Long id, @RequestBody DeliverDTO dto) {
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        TradeOrder order = Optional.ofNullable(orderMapper.lockById(id))
                .orElseThrow(() -> new BusinessException("订单不存在"));
        if (!merchantId.equals(order.getMerchantId())) {
            throw new BusinessException(403, "无权操作其他商户订单");
        }
        if (!Integer.valueOf(1).equals(order.getGoodsType())) {
            throw new BusinessException("仅实物订单可以录入物流发货");
        }
        if (dto.getTrackingNumber() == null || dto.getTrackingNumber().isBlank()
                || dto.getLogisticsCompany() == null || dto.getLogisticsCompany().isBlank()) {
            throw new BusinessException("请填写物流公司和运单号");
        }

        if (!"PAID".equals(order.getOrderStatus())) {
            throw new BusinessException("订单状态非【待发货】，无法发货");
        }

        order.setOrderStatus("SHIPPED");
        order.setLogisticsCompany(dto.getLogisticsCompany());
        order.setTrackingNumber(dto.getTrackingNumber());
        order.setDeliveryTime(LocalDateTime.now());
        orderMapper.save(order);

        return R.ok("发货成功", null);
    }

    @Operation(summary = "【商家】★ 代客推单：一键生成定制代付账单短链与海报")
    @PostMapping("/api/v1/merchant/orders/push-pay")
    public R<Map<String, Object>> createPushPayOrder(@RequestBody PeerPayDTOs.PushPayCreateRequest req,
            @RequestHeader("Idempotency-Key") String key) {
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        if (merchantId == null) {
            throw new BusinessException("仅限商家账号操作推单");
        }
        Map<String, Object> res = idempotency.execute("push:" + merchantId + ":" + SecurityUtils.getCurrentUserId(), key, req,
                () -> peerPayService.createPushPayOrder(req, merchantId));
        return R.ok("代客推单生成成功", res);
    }

    @Operation(summary = "【商家】★ 线上服务录入交付凭证 (一经交付锁定退款仲裁)")
    @PostMapping("/api/v1/merchant/orders/{id}/fulfill-virtual")
    public R<Void> fulfillVirtualOrder(@PathVariable Long id, @RequestBody PeerPayDTOs.VirtualFulfillRequest req) {
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        if (merchantId == null) {
            throw new BusinessException("仅限商家账号履约交付");
        }
        peerPayService.fulfillVirtualOrder(id, req.getServiceVoucher(), merchantId);
        return R.ok("线上服务交付凭证已成功录入，订单已完结", null);
    }

    // ==========================================
    // 平台超级管理员视角 API (/api/v1/admin/orders)
    // ==========================================

    @Operation(summary = "【超管】全平台交易大盘与分账监管流水")
    @GetMapping("/api/v1/admin/orders")
    public R<Map<String, Object>> listAdminOrders() {
        List<TradeOrder> orders = orderMapper.findAllByOrderByCreatedAtDesc();

        BigDecimal totalTurnover = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;

        for (TradeOrder o : orders) {
            totalTurnover = totalTurnover.add(o.getPayAmount());
            totalCommission = totalCommission.add(o.getCommissionAmount());
        }

        Map<String, Object> res = new HashMap<>();
        res.put("totalOrders", orders.size());
        res.put("totalTurnover", totalTurnover);
        res.put("totalCommission", totalCommission);
        res.put("orders", orders);
        return R.ok(res);
    }
}
