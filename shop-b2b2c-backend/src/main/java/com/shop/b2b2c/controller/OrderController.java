/*
 * 文件：OrderController.java
 * 用途：待付款建单、免登录查单、商家发货、平台订单及统一状态统计入口；建单代付编码复用PeerStatus。
 * 维护注意：HTTP建单强制Idempotency-Key；不得伪造到账或交付。列表/统计不读取海报正文，商家按需接口另行校验归属。查单用独立凭证/白名单；统计用OrderStatistics，金额不是账本收入，退款及已结算指标不可用。T24：商家/平台订单列表改为 created_at+id keyset 分页（不透明 cursor，非法参数 400），返回 page.total/hasMore/nextCursor，禁止 OFFSET 与静默截断。T26：推单海报在幂等事务提交后由 attachPoster 生成，不在建单事务内光栅化。Q13-A（2026-11-06 口径澄清）：实物订单发货对店长与员工开放；推单/改价、资金操作、卡密明文与虚拟履约仍仅店长。T36：建单/查单限流IP由ClientIpResolver解析，仅可信代理才读X-Forwarded-For。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.CollectionMode;
import com.shop.b2b2c.common.OrderCursor;
import com.shop.b2b2c.common.PayChannel;
import com.shop.b2b2c.common.PeerStatus;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Tag(name = "交易订单与16688速查 (Order & Quick Query)")
@RestController
@RequiredArgsConstructor
public class OrderController {

    /** T08/Q15：所有新订单统一 15 分钟数据库支付截止时间，不再依赖内存计时或 NULL 放行。 */
    private static final long ORDER_LOCK_MINUTES = 15L;

    /** T24：订单状态筛选白名单，未列出的值一律 400，避免拼写错误静默返回空列表。 */
    private static final Set<String> ORDER_STATUSES = Set.of(
            "UNPAID", "PAID", "SHIPPED", "COMPLETED", "REFUNDED", "CLOSED", "CANCELLED", "EXPIRED");

    private final com.shop.b2b2c.service.ClientIpResolver ipResolver;
    private final TradeOrderMapper orderMapper;
    private final TradeOrderItemMapper itemMapper;
    private final MerchantStoreMapper storeMapper;
    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductVirtualCardMapper cardMapper;
    private final com.shop.b2b2c.service.BuyerOrderQueryService buyerQueries;
    private final PeerPayService peerPayService;
    private final com.shop.b2b2c.service.OrderIdempotencyService idempotency;
    private final com.shop.b2b2c.service.CardCipherService cardCipher;
    private final com.shop.b2b2c.service.TradeCostLimiter costLimiter;
    private final com.shop.b2b2c.service.ServiceFeeSnapshotService feeSnapshotService;
    private final com.shop.b2b2c.service.MerchantFeePolicyService feePolicyService;
    private final com.shop.b2b2c.service.MerchantAccountService merchantAccountService;

    @Data
    public static class CheckoutDTO {
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long merchantId;
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long productId;
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long skuId;
        private Integer quantity;
        private String buyerContact; // 手机号或邮箱 (免密查单凭据)
        private String paymentChannel; // WECHAT / ALIPAY
    }

    @Operation(summary = "【公开】创建单店待付款订单（不扣库存、不履约）")
    @PostMapping("/api/v1/public/orders/checkout")
    public R<Map<String, Object>> checkoutRequest(@RequestBody CheckoutDTO dto,
            @RequestHeader("Idempotency-Key") String key,
            jakarta.servlet.http.HttpServletRequest request) {
        if (dto == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        costLimiter.checkOrderCreation(request != null ? ipResolver.resolve(request) : null, dto.getBuyerContact());
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
        if (dto.getBuyerContact().trim().length() > 64) {
            throw new BusinessException(400, "联系手机号或邮箱长度不能超过64字符");
        }
        if (dto.getPaymentChannel() != null && dto.getPaymentChannel().trim().length() > 32) {
            throw new BusinessException(400, "支付通道标识长度不能超过32字符");
        }

        Product product = productMapper.findById(dto.getProductId())
                .orElseThrow(() -> new BusinessException("商品不存在"));
        ProductSku sku = skuMapper.findById(dto.getSkuId())
                .orElseThrow(() -> new BusinessException("规格SKU不存在"));
        MerchantStore store = storeMapper.findById(product.getMerchantId())
                .orElseThrow(() -> new BusinessException("店铺不存在"));

        TradeGuards.requireSkuOwnership(product, sku);
        TradeGuards.requirePurchasable(product, store);
        // T35：欠费超额度/账期到期停新单收款；已支付订单履约与退款不受影响。
        merchantAccountService.requireCollectionAllowed(store.getId());
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
        String queryToken = UUID.randomUUID().toString().replace("-", "");

        // 计算金额；费率一律取当前生效政策，不再读取 merchant_store.commission_rate。
        BigDecimal totalAmount = sku.getPrice().multiply(new BigDecimal(dto.getQuantity()));
        TradeGuards.requireMoney(totalAmount);

        // 创建订单主表
        TradeOrder order = new TradeOrder();
        order.setOrderSn(orderSn);
        order.setMerchantId(store.getId());
        order.setBuyerContact(dto.getBuyerContact().trim());
        order.setGoodsType(product.getGoodsType());
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount); // 实付
        // T34/T39：固化订单级服务费快照与费率政策版本，后续费率调整不回溯本单。
        feeSnapshotService.apply(order, feePolicyService.getEffectivePolicy(store.getId()), totalAmount);
        // T32：阶段一商家直收；渠道只接受 WECHAT/ALIPAY，不接受旧 A/B/C 或 MOCK。
        String payChannel = PayChannel.normalize(
                dto.getPaymentChannel() == null || dto.getPaymentChannel().isBlank()
                        ? (store.getPayChannel() == null || store.getPayChannel().isBlank()
                            ? PayChannel.WECHAT : store.getPayChannel())
                        : dto.getPaymentChannel());
        order.setCollectionMode(CollectionMode.MERCHANT_DIRECT);
        order.setPayChannel(payChannel);
        order.setPaymentChannel(payChannel);
        order.setOrderStatus("UNPAID");
        order.setPeerStatus(PeerStatus.INIT);
        order.setQueryToken(queryToken);
        // T08/Q15：普通直购单与代付单统一写入数据库截止时间，服务重启或内存任务丢失也不会越过 15 分钟。
        LocalDateTime createdAt = LocalDateTime.now();
        order.setCreatedAt(createdAt);
        order.setLockExpireTime(createdAt.plusMinutes(ORDER_LOCK_MINUTES));

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
        result.put("queryToken", queryToken);
        result.put("orderStatus", order.getOrderStatus());
        result.put("payAmount", order.getPayAmount());
        result.put("goodsType", order.getGoodsType());
        result.put("cards", List.of());
        result.put("paymentAvailable", false);
        return R.ok("订单已创建，等待付款；支付通道暂未开放", result);
    }

    @Operation(summary = "已停用的GET查单入口：领取凭证和联系方式不能放URL")
    @GetMapping("/api/v1/public/orders/quick-search")
    @ResponseStatus(org.springframework.http.HttpStatus.GONE)
    public R<Void> retiredQuickSearch() {
        return R.fail(410, "请使用POST查单并提供买家领取凭证");
    }

    @Operation(summary = "买家订单查询：订单号、联系方式及独立领取凭证")
    @PostMapping("/api/v1/public/orders/quick-search")
    public R<com.shop.b2b2c.dto.BuyerOrderView> quickSearch(
            @RequestBody com.shop.b2b2c.dto.BuyerLookupRequest request,
            jakarta.servlet.http.HttpServletRequest servletRequest, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return R.ok("订单查询成功", buyerQueries.lookup(request, ipResolver.resolve(servletRequest)));
    }

    // ==========================================
    // 商家工作台视角 API (/api/v1/merchant/orders)
    // ==========================================

    @Operation(summary = "【商家】查询本店订单（T24: created_at+id keyset 分页、状态筛选、总数与 hasMore）")
    @GetMapping("/api/v1/merchant/orders")
    public R<Map<String, Object>> listMerchantOrders(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "status", required = false) String status,
            jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        int size = normalizePageSize(pageSize);
        String orderStatus = normalizeOrderStatus(status);
        OrderCursor before = OrderCursor.decode(cursor);
        List<TradeOrder> fetched = orderMapper.findMerchantKeyset(merchantId, orderStatus, before, size + 1);
        long total = orderMapper.countMerchant(merchantId, orderStatus);
        boolean hasMore = fetched.size() > size;
        if (hasMore) {
            fetched.remove(size);
        }

        // R24: 批量拉取订单明细，消除循环逐单N+1查询
        List<String> orderSns = fetched.stream()
                .map(TradeOrder::getOrderSn)
                .filter(Objects::nonNull)
                .toList();
        List<TradeOrderItem> allItems = itemMapper.findByOrderSns(orderSns);
        Map<String, List<TradeOrderItem>> itemsBySn = allItems.stream()
                .filter(it -> it.getOrderSn() != null)
                .collect(java.util.stream.Collectors.groupingBy(TradeOrderItem::getOrderSn));

        List<Map<String, Object>> resList = new ArrayList<>();
        for (TradeOrder o : fetched) {
            // R04: 严格剥离买家私密提取凭证与海报
            o.setQueryToken(null);
            o.setPosterUrl(null);
            if (Integer.valueOf(2).equals(o.getGoodsType()) && o.getServiceVoucher() != null) {
                o.setServiceVoucher("系统卡密已自动交付");
            }

            Map<String, Object> map = new HashMap<>();
            map.put("order", o);

            List<TradeOrderItem> items = itemsBySn.getOrDefault(o.getOrderSn(), Collections.emptyList());
            for (TradeOrderItem item : items) {
                if (item.getCardDetails() != null && !item.getCardDetails().isBlank()) {
                    item.setCardDetails("已自动发放 (共" + (item.getQuantity() != null ? item.getQuantity() : 1) + "张)");
                }
            }
            map.put("items", items);
            resList.add(map);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("orders", resList);
        res.put("page", pageMetadata(fetched, size, total, hasMore));
        return R.ok(res);
    }

    /** T24：页码只接受 1-100，越界直接 400，禁止服务端静默截断。 */
    private static int normalizePageSize(Integer pageSize) {
        int size = pageSize == null ? 20 : pageSize;
        if (size < 1 || size > 100) {
            throw new BusinessException(400, "pageSize 必须在 1 到 100 之间");
        }
        return size;
    }

    /** T24/T31：状态筛选只接受已知订单状态，未知值 400。 */
    private static String normalizeOrderStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        if (!ORDER_STATUSES.contains(normalized)) {
            throw new BusinessException(400, "订单状态筛选值不正确");
        }
        return normalized;
    }

    private static Map<String, Object> pageMetadata(List<TradeOrder> rows, int pageSize, long total, boolean hasMore) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("hasMore", hasMore);
        page.put("total", total);
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            TradeOrder last = rows.get(rows.size() - 1);
            nextCursor = OrderCursor.encode(last.getCreatedAt(), last.getId());
        }
        page.put("nextCursor", nextCursor);
        return page;
    }

    public R<Map<String, Object>> getMerchantOrderDetail(Long id) {
        return getMerchantOrderDetail(id, null);
    }

    @Operation(summary = "【商家】查询单个订单详情与授权凭据 (R04: 单单授权与员工脱敏)")
    @GetMapping("/api/v1/merchant/orders/{id}")
    public R<Map<String, Object>> getMerchantOrderDetail(@PathVariable Long id,
            jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        if (id == null || id <= 0) {
            throw new BusinessException(400, "订单编号不正确");
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        TradeOrder order = orderMapper.findById(id)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));
        if (!merchantId.equals(order.getMerchantId())) {
            throw new BusinessException(403, "无权访问其他商户订单");
        }
        // 买家专属提取凭证 queryToken 绝不向商家端暴露
        order.setQueryToken(null);
        order.setPosterUrl(null);

        List<TradeOrderItem> items = itemMapper.findByOrderSn(order.getOrderSn());
        // 细粒度权限：仅限店长(MERCHANT_ADMIN)或超管(SUPERADMIN)有权查阅卡密明文快照；普通员工(MERCHANT_STAFF)脱敏遮蔽
        boolean hasCardSecretPermission = cn.dev33.satoken.stp.StpUtil.isLogin()
                && (cn.dev33.satoken.stp.StpUtil.hasRole("SUPERADMIN") || cn.dev33.satoken.stp.StpUtil.hasRole("MERCHANT_ADMIN"));
        if (!hasCardSecretPermission) {
            if (Integer.valueOf(2).equals(order.getGoodsType()) && order.getServiceVoucher() != null) {
                order.setServiceVoucher("系统卡密已自动交付 (员工账号无权查阅明文)");
            }
            for (TradeOrderItem item : items) {
                if (item.getCardDetails() != null && !item.getCardDetails().isBlank()) {
                    item.setCardDetails("已自动发放 (员工账号无权查阅明文)");
                }
            }
        } else {
            for (TradeOrderItem item : items) {
                if (item.getCardDetails() != null && !item.getCardDetails().isBlank()) {
                    item.setCardDetails(cardCipher.decrypt(item.getCardDetails()));
                }
            }
        }

        Map<String, Object> map = new HashMap<>();
        map.put("order", order);
        map.put("items", items);
        return R.ok(map);
    }

    @Operation(summary = "【商家】本店订单状态统计（T25: 默认近90天，库内 GROUP BY，非资金账本）")
    @GetMapping("/api/v1/merchant/orders/statistics")
    public R<com.shop.b2b2c.common.OrderStatistics> merchantStatistics(
            @RequestParam(value = "from", required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime to,
            jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        java.time.LocalDateTime[] range = statisticsRange(from, to);
        return R.ok(com.shop.b2b2c.common.OrderStatistics.fromAggregates(
                orderMapper.aggregateOrderStatistics(merchantId, range[0], range[1])));
    }

    /**
     * T25：统计默认只扫近 90 天，避免全表加载；显式范围必须合法且不超过 366 天。
     * 返回 [from, to)，to 不含。
     */
    private static java.time.LocalDateTime[] statisticsRange(java.time.LocalDateTime from, java.time.LocalDateTime to) {
        java.time.LocalDateTime end = to != null ? to : java.time.LocalDateTime.now();
        java.time.LocalDateTime start = from != null ? from : end.minusDays(90);
        if (start.isAfter(end)) {
            throw new BusinessException(400, "统计时间范围不正确");
        }
        if (java.time.Duration.between(start, end).toDays() > 366) {
            throw new BusinessException(400, "统计时间范围不得超过 366 天");
        }
        return new java.time.LocalDateTime[]{start, end};
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
        if (id == null || id <= 0) {
            throw new BusinessException(400, "订单编号不正确");
        }
        // Q13-A 澄清：实物订单发货对店长与员工开放；租户归属与状态校验仍必须通过，员工不得跨店发货。
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
            // 重复发货/状态不符属于可预期的并发冲突，必须显式 400，不能落到默认 500。
            throw new BusinessException(400, "订单状态非【待发货】，无法发货");
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
        com.shop.b2b2c.service.AuthSessionService.requireManagementRole();
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        if (merchantId == null) {
            throw new BusinessException("仅限商家账号操作推单");
        }
        Map<String, Object> res = idempotency.execute("push:" + merchantId + ":" + SecurityUtils.getCurrentUserId(), key, req,
                () -> peerPayService.createPushPayOrder(req, merchantId));
        // T26：推单海报在幂等事务提交后按需生成，失败不影响已落库订单。
        peerPayService.attachPoster(res);
        return R.ok("代客推单生成成功", res);
    }

    @Operation(summary = "【商家】★ 线上服务录入交付凭证 (一经交付锁定退款仲裁)")
    @PostMapping("/api/v1/merchant/orders/{id}/fulfill-virtual")
    public R<Void> fulfillVirtualOrder(@PathVariable Long id, @RequestBody PeerPayDTOs.VirtualFulfillRequest req) {
        if (id == null || id <= 0) {
            throw new BusinessException(400, "订单编号不正确");
        }
        com.shop.b2b2c.service.AuthSessionService.requireManagementRole();
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

    @Operation(summary = "【超管】平台订单与状态统计（T24: keyset 分页；T25: 只做库内聚合）")
    @GetMapping("/api/v1/admin/orders")
    public R<Map<String, Object>> listAdminOrders(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "from", required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime to,
            jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        try {
            if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
                cn.dev33.satoken.stp.StpUtil.checkRole("SUPERADMIN");
            }
        } catch (cn.dev33.satoken.exception.SaTokenContextException ignored) {
        }
        int size = normalizePageSize(pageSize);
        String orderStatus = normalizeOrderStatus(status);
        OrderCursor before = OrderCursor.decode(cursor);
        List<TradeOrder> fetched = orderMapper.findAllKeyset(orderStatus, before, size + 1);
        long total = orderMapper.countAll(orderStatus);
        boolean hasMore = fetched.size() > size;
        if (hasMore) {
            fetched.remove(size);
        }

        for (TradeOrder o : fetched) {
            o.setQueryToken(null);
            o.setPosterUrl(null);
            if (Integer.valueOf(2).equals(o.getGoodsType()) && o.getServiceVoucher() != null) {
                o.setServiceVoucher("系统卡密已自动交付");
            }
        }

        // T25: 默认近 90 天，只做库内 GROUP BY 聚合
        java.time.LocalDateTime[] range = statisticsRange(from, to);
        var statistics = com.shop.b2b2c.common.OrderStatistics.fromAggregates(
                orderMapper.aggregateOrderStatistics(null, range[0], range[1]));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("statistics", statistics);
        res.put("orders", fetched);
        res.put("page", pageMetadata(fetched, size, total, hasMore));
        return R.ok(res);
    }

    public R<Map<String, Object>> getAdminOrderDetail(Long id) {
        return getAdminOrderDetail(id, null);
    }

    @Operation(summary = "【超管】平台单个订单详情监管审计")
    @GetMapping("/api/v1/admin/orders/{id}")
    public R<Map<String, Object>> getAdminOrderDetail(@PathVariable Long id,
            jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        if (id == null || id <= 0) {
            throw new BusinessException(400, "订单编号不正确");
        }
        try {
            if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
                cn.dev33.satoken.stp.StpUtil.checkRole("SUPERADMIN");
            }
        } catch (cn.dev33.satoken.exception.SaTokenContextException ignored) {
        }
        TradeOrder order = orderMapper.findById(id)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));
        order.setQueryToken(null);
        order.setPosterUrl(null);

        Map<String, Object> map = new HashMap<>();
        map.put("order", order);
        List<TradeOrderItem> items = itemMapper.findByOrderSn(order.getOrderSn());
        for (TradeOrderItem item : items) {
            if (item.getCardDetails() != null && !item.getCardDetails().isBlank()) {
                item.setCardDetails(cardCipher.decrypt(item.getCardDetails()));
            }
        }
        map.put("items", items);
        return R.ok(map);
    }
}
