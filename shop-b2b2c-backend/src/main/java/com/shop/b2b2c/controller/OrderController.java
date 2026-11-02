package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
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
import java.time.format.DateTimeFormatter;
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

    @Data
    public static class CheckoutDTO {
        private Long merchantId;
        private Long productId;
        private Long skuId;
        private Integer quantity;
        private String buyerContact; // 手机号或邮箱 (免密查单凭据)
        private String paymentChannel; // WECHAT / ALIPAY
    }

    @Operation(summary = "【公开】单店极速下单支付 (支持实体/虚拟卡密自动发放)")
    @PostMapping("/api/v1/public/orders/checkout")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> checkout(@RequestBody CheckoutDTO dto) {
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

        // 校验库存
        if (sku.getStockQuantity() < dto.getQuantity()) {
            throw new BusinessException("商品库存不足，剩余: " + sku.getStockQuantity());
        }

        // 生成唯一订单号 SN
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String randomSuffix = String.format("%04d", new Random().nextInt(10000));
        String orderSn = "ORD" + timestamp + randomSuffix;
        String queryToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 计算金额与抽佣
        BigDecimal totalAmount = sku.getPrice().multiply(new BigDecimal(dto.getQuantity()));
        BigDecimal commissionRate = store.getCommissionRate();
        BigDecimal commissionAmount = totalAmount.multiply(commissionRate)
                .divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
        BigDecimal merchantSettleAmount = totalAmount.subtract(commissionAmount);

        // 扣减 SKU 库存
        sku.setStockQuantity(sku.getStockQuantity() - dto.getQuantity());
        skuMapper.save(sku);

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
        order.setPayTradeNo("PAY" + System.currentTimeMillis());
        order.setQueryToken(queryToken);

        // 交付逻辑：如果是虚拟卡密商品，秒级分配库存
        List<String> deliveredCards = new ArrayList<>();
        if (product.getGoodsType() == 2) {
            List<ProductVirtualCard> availableCards = cardMapper.findBySkuIdAndStatus(sku.getId(), 0);
            if (availableCards.size() < dto.getQuantity()) {
                throw new BusinessException("卡密可用库存不足，请联系客服补货");
            }
            for (int i = 0; i < dto.getQuantity(); i++) {
                ProductVirtualCard card = availableCards.get(i);
                card.setStatus(1); // 已售出
                card.setOrderSn(orderSn);
                cardMapper.save(card);
                deliveredCards.add(card.getCardContent());
            }
            order.setOrderStatus("COMPLETED"); // 虚拟卡密自动完结
            order.setSharingStatus("FINISHED"); // 自动触发二级分账
            order.setFinishTime(LocalDateTime.now());
        } else {
            // 实物商品，标记为已支付待发货
            order.setOrderStatus("PAID");
            order.setSharingStatus("PENDING");
        }

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
        if (!deliveredCards.isEmpty()) {
            item.setCardDetails(String.join("\n", deliveredCards));
        }
        itemMapper.save(item);

        Map<String, Object> result = new HashMap<>();
        result.put("orderSn", orderSn);
        result.put("buyerContact", order.getBuyerContact());
        result.put("orderStatus", order.getOrderStatus());
        result.put("payAmount", order.getPayAmount());
        result.put("goodsType", order.getGoodsType());
        result.put("cards", deliveredCards);
        return R.ok("下单支付成功", result);
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
        res.put("store", store);
        return R.ok("订单查询成功", res);
    }

    // ==========================================
    // 商家工作台视角 API (/api/v1/merchant/orders)
    // ==========================================

    @Operation(summary = "【商家】查询属于本店的全部订单")
    @GetMapping("/api/v1/merchant/orders")
    public R<List<Map<String, Object>>> listMerchantOrders() {
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        List<TradeOrder> orders;
        if (merchantId != null) {
            orders = orderMapper.findByMerchantIdOrderByCreatedAtDesc(merchantId);
        } else {
            orders = orderMapper.findAllByOrderByCreatedAtDesc();
        }

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
    public R<Void> deliverOrder(@PathVariable Long id, @RequestBody DeliverDTO dto) {
        TradeOrder order = orderMapper.findById(id)
                .orElseThrow(() -> new BusinessException("订单不存在"));

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
    public R<Map<String, Object>> createPushPayOrder(@RequestBody PeerPayDTOs.PushPayCreateRequest req) {
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        if (merchantId == null) {
            throw new BusinessException("仅限商家账号操作推单");
        }
        Map<String, Object> res = peerPayService.createPushPayOrder(req, merchantId);
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
