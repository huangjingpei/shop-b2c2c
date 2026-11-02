package com.shop.b2b2c.service.impl;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PosterUtils;
import com.shop.b2b2c.dto.PeerPayDTOs;
import com.shop.b2b2c.entity.*;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.PeerPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PeerPayServiceImpl implements PeerPayService {

    private final TradeOrderMapper orderMapper;
    private final TradeOrderItemMapper itemMapper;
    private final MerchantStoreMapper storeMapper;
    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductVirtualCardMapper cardMapper;
    private final PlatformAdConfigMapper adConfigMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.frontend.storefront-url:http://localhost:3002}")
    private String storefrontUrl;

    private static final String PRELOCK_REDIS_PREFIX = "peer:lock:";
    private static final long DEFAULT_LOCK_MINUTES = 15;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createBuyerPeerPay(PeerPayDTOs.BuyerCreateRequest req) {
        if (req.getQuantity() == null || req.getQuantity() <= 0) {
            throw new BusinessException("购买数量必须大于0");
        }
        if (req.getBuyerContact() == null || req.getBuyerContact().trim().isEmpty()) {
            throw new BusinessException("请输入联系手机号或邮箱，用于提取商品与查单");
        }

        Product product = productMapper.findById(req.getProductId())
                .orElseThrow(() -> new BusinessException("商品不存在"));
        ProductSku sku = skuMapper.findById(req.getSkuId())
                .orElseThrow(() -> new BusinessException("商品规格SKU不存在"));
        MerchantStore store = storeMapper.findById(product.getMerchantId())
                .orElseThrow(() -> new BusinessException("店铺不存在"));

        // 阶段一意向校验：只查物理库存是否存在，不执行真实扣减 (防刷与防恶搞占库存)
        if (sku.getStockQuantity() < req.getQuantity()) {
            throw new BusinessException("商品库存不足，剩余库存: " + sku.getStockQuantity());
        }

        // 生成全局流水号与防撞代付短码
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String randomSuffix = String.format("%04d", new Random().nextInt(10000));
        String orderSn = "ORD" + timestamp + randomSuffix;
        String queryToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String peerPayToken = "PAY" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        BigDecimal totalAmount = sku.getPrice().multiply(new BigDecimal(req.getQuantity()));
        BigDecimal commissionRate = store.getCommissionRate() != null ? store.getCommissionRate() : new BigDecimal("3.00");
        BigDecimal commissionAmount = totalAmount.multiply(commissionRate).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
        BigDecimal merchantSettleAmount = totalAmount.subtract(commissionAmount);

        // 创建订单主表 (peer_status = 'INIT', 标记两段式意向生成)
        TradeOrder order = new TradeOrder();
        order.setOrderSn(orderSn);
        order.setMerchantId(store.getId());
        order.setBuyerContact(req.getBuyerContact().trim());
        order.setGoodsType(product.getGoodsType());
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount);
        order.setCommissionRate(commissionRate);
        order.setCommissionAmount(commissionAmount);
        order.setMerchantSettleAmount(merchantSettleAmount);
        order.setOrderStatus("UNPAID");
        order.setSharingStatus("PENDING");
        order.setPaymentChannel(store.getPaymentChannelType() != null ? store.getPaymentChannelType() : "WECHAT");
        order.setQueryToken(queryToken);

        // 代付专属元数据
        order.setPeerPayToken(peerPayToken);
        order.setPeerPayType(1); // 1-买家求助代付
        order.setPeerStatus("INIT"); // 两段式 Stage 1: 意向生成
        order.setPeerMessage(req.getPeerMessage() != null && !req.getPeerMessage().trim().isEmpty() 
                ? req.getPeerMessage().trim() : "好心人帮我买个单呗，谢谢老铁！");

        // 生成 9:16 微信裂变防封海报
        String cashierUrl = storefrontUrl + "/peer-pay/" + peerPayToken;
        String goodsTypeName = product.getGoodsType() == 2 ? "虚拟服务/卡密" : "实物商品";
        String posterBase64 = PosterUtils.generatePeerPayPoster(
                cashierUrl, store.getStoreName(), product.getTitle(), totalAmount, order.getPeerMessage(), goodsTypeName
        );
        order.setPosterUrl(posterBase64);

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
        item.setQuantity(req.getQuantity());
        item.setSubtotal(totalAmount);
        itemMapper.save(item);

        String wechatShareText = String.format("【%s】\n🎁 好友求助代付：%s\n💰 代付金额：￥%s\n👉 微信直接点开付款：%s\n（免注册免登录，微信内直接打开即可完成支付）",
                order.getPeerMessage(), product.getTitle(), totalAmount.toPlainString(), cashierUrl);

        Map<String, Object> result = new HashMap<>();
        result.put("orderSn", orderSn);
        result.put("peerPayToken", peerPayToken);
        result.put("cashierUrl", cashierUrl);
        result.put("posterBase64", posterBase64);
        result.put("peerMessage", order.getPeerMessage());
        result.put("payAmount", totalAmount);
        result.put("queryToken", queryToken);
        result.put("wechatShareText", wechatShareText);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createPushPayOrder(PeerPayDTOs.PushPayCreateRequest req, Long merchantId) {
        if (req.getQuantity() == null || req.getQuantity() <= 0) {
            throw new BusinessException("商品数量必须大于0");
        }

        Product product = productMapper.findById(req.getProductId())
                .orElseThrow(() -> new BusinessException("商品不存在"));
        if (!product.getMerchantId().equals(merchantId)) {
            throw new BusinessException("无权操作非本店商品");
        }

        ProductSku sku = skuMapper.findById(req.getSkuId())
                .orElseThrow(() -> new BusinessException("商品规格SKU不存在"));
        MerchantStore store = storeMapper.findById(merchantId)
                .orElseThrow(() -> new BusinessException("商户店铺不存在"));

        if (sku.getStockQuantity() < req.getQuantity()) {
            throw new BusinessException("商品库存不足，剩余库存: " + sku.getStockQuantity());
        }

        // 商家议价定价：支持自主协商优惠改价推单
        BigDecimal unitPrice = (req.getNegotiatedPrice() != null && req.getNegotiatedPrice().compareTo(BigDecimal.ZERO) > 0)
                ? req.getNegotiatedPrice() : sku.getPrice();
        BigDecimal totalAmount = unitPrice.multiply(new BigDecimal(req.getQuantity()));

        BigDecimal commissionRate = store.getCommissionRate() != null ? store.getCommissionRate() : new BigDecimal("3.00");
        BigDecimal commissionAmount = totalAmount.multiply(commissionRate).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
        BigDecimal merchantSettleAmount = totalAmount.subtract(commissionAmount);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String randomSuffix = String.format("%04d", new Random().nextInt(10000));
        String orderSn = "ORD" + timestamp + randomSuffix;
        String queryToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String peerPayToken = "PUSH" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        TradeOrder order = new TradeOrder();
        order.setOrderSn(orderSn);
        order.setMerchantId(merchantId);
        order.setBuyerContact(req.getCustomerContact() != null && !req.getCustomerContact().isBlank() 
                ? req.getCustomerContact().trim() : "商户代客推单客户");
        order.setGoodsType(product.getGoodsType());
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount);
        order.setCommissionRate(commissionRate);
        order.setCommissionAmount(commissionAmount);
        order.setMerchantSettleAmount(merchantSettleAmount);
        order.setOrderStatus("UNPAID");
        order.setSharingStatus("PENDING");
        order.setPaymentChannel(store.getPaymentChannelType() != null ? store.getPaymentChannelType() : "WECHAT");
        order.setQueryToken(queryToken);

        order.setPeerPayToken(peerPayToken);
        order.setPeerPayType(2); // 2-卖家代客推单
        order.setPeerStatus("INIT");
        order.setPeerMessage(req.getSellerNote() != null && !req.getSellerNote().isBlank() 
                ? req.getSellerNote().trim() : "这是商家为您定制的支付账单，请查收完成付款");

        String cashierUrl = storefrontUrl + "/peer-pay/" + peerPayToken;
        String goodsTypeName = product.getGoodsType() == 2 ? "虚拟服务/卡密" : "实物商品";
        String posterBase64 = PosterUtils.generatePeerPayPoster(
                cashierUrl, store.getStoreName(), product.getTitle(), totalAmount, order.getPeerMessage(), goodsTypeName
        );
        order.setPosterUrl(posterBase64);

        order = orderMapper.save(order);

        TradeOrderItem item = new TradeOrderItem();
        item.setOrderId(order.getId());
        item.setOrderSn(orderSn);
        item.setProductId(product.getId());
        item.setSkuId(sku.getId());
        item.setSkuCode(sku.getSkuCode());
        item.setProductTitle(product.getTitle());
        item.setSpecInfo(sku.getSpecData());
        item.setPrice(unitPrice);
        item.setQuantity(req.getQuantity());
        item.setSubtotal(totalAmount);
        itemMapper.save(item);

        String wechatShareText = String.format("【%s · 专属定制账单】\n尊贵的客户您好，这是为您定制的专属订单，请查收：\n📦 商品：%s\n💰 协议专享价：￥%s\n👉 微信点开直接付款：%s\n（微信内打开直接支付，无需加购选规格）",
                store.getStoreName(), product.getTitle(), totalAmount.toPlainString(), cashierUrl);

        Map<String, Object> result = new HashMap<>();
        result.put("orderSn", orderSn);
        result.put("peerPayToken", peerPayToken);
        result.put("cashierUrl", cashierUrl);
        result.put("posterBase64", posterBase64);
        result.put("unitPrice", unitPrice);
        result.put("payAmount", totalAmount);
        result.put("wechatShareText", wechatShareText);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PeerPayDTOs.DetailResponse getPeerPayDetail(String token) {
        TradeOrder order = orderMapper.findByPeerPayToken(token)
                .orElseThrow(() -> new BusinessException("代付账单不存在或已失效"));

        // 自愈机制：如果状态为 RESERVED_LOCK 但已经超时超过 15 分钟，自动回退释放为 INIT
        if ("RESERVED_LOCK".equals(order.getPeerStatus())) {
            if (order.getLockExpireTime() != null && LocalDateTime.now().isAfter(order.getLockExpireTime())) {
                log.info("代付单 [{}] 预占锁超时到达，自愈释放锁回到 INIT", order.getOrderSn());
                order.setPeerStatus("INIT");
                order.setLockExpireTime(null);
                orderMapper.save(order);
                redisTemplate.delete(PRELOCK_REDIS_PREFIX + order.getOrderSn());
            }
        }

        List<TradeOrderItem> items = itemMapper.findByOrderSn(order.getOrderSn());
        TradeOrderItem firstItem = items.isEmpty() ? null : items.get(0);
        MerchantStore store = storeMapper.findById(order.getMerchantId()).orElse(null);
        Product product = firstItem != null ? productMapper.findById(firstItem.getProductId()).orElse(null) : null;

        PeerPayDTOs.DetailResponse resp = new PeerPayDTOs.DetailResponse();
        resp.setToken(order.getPeerPayToken());
        resp.setOrderSn(order.getOrderSn());
        resp.setGoodsType(order.getGoodsType());
        resp.setPeerPayType(order.getPeerPayType());
        resp.setPeerStatus(order.getPeerStatus());
        resp.setOrderStatus(order.getOrderStatus());
        resp.setPayAmount(order.getPayAmount());
        resp.setPeerMessage(order.getPeerMessage());
        resp.setPosterUrl(order.getPosterUrl());
        resp.setCreatedAt(order.getCreatedAt());

        if (store != null) {
            resp.setStoreId(store.getId());
            resp.setStoreName(store.getStoreName());
            resp.setStoreLogo(store.getLogoUrl());
            resp.setStoreNotice(store.getNotice());
            resp.setContactPhone(store.getContactPhone());
            resp.setPaymentChannelType(store.getPaymentChannelType());
            resp.setCustomPayQrcode(store.getCustomPayQrcode());
        }

        if (product != null) {
            resp.setProductTitle(product.getTitle());
            resp.setProductImage(product.getMainImage());
            resp.setServiceDesc(product.getServiceDesc());
            resp.setIsOnlineService(product.getGoodsType() == 2);
        }

        if (firstItem != null) {
            resp.setSkuSpec(firstItem.getSpecInfo());
            resp.setQuantity(firstItem.getQuantity());
        }

        // 线上服务凭证：已完结时展示
        if ("COMPLETED".equals(order.getOrderStatus())) {
            resp.setServiceVoucher(order.getServiceVoucher());
        }

        // 隐私脱敏处理 (如: 138****8000)
        String contact = order.getBuyerContact();
        if (contact != null && contact.length() >= 7) {
            resp.setMaskedBuyerContact(contact.substring(0, 3) + "****" + contact.substring(contact.length() - 4));
        } else {
            resp.setMaskedBuyerContact("尾号***买家");
        }

        // 计算美团风格 15 分钟倒计时剩余秒数
        LocalDateTime now = LocalDateTime.now();
        if ("PAID".equals(order.getPeerStatus())) {
            resp.setLockSecondsRemaining(0L);
        } else if ("RESERVED_LOCK".equals(order.getPeerStatus()) && order.getLockExpireTime() != null) {
            long remaining = Duration.between(now, order.getLockExpireTime()).getSeconds();
            resp.setLockSecondsRemaining(Math.max(0, remaining));
            resp.setLockExpireTime(order.getLockExpireTime());
        } else if ("INIT".equals(order.getPeerStatus()) && order.getCreatedAt() != null) {
            LocalDateTime orderExpireAt = order.getCreatedAt().plusMinutes(15);
            if (now.isAfter(orderExpireAt)) {
                order.setPeerStatus("EXPIRED");
                orderMapper.save(order);
                resp.setPeerStatus("EXPIRED");
                resp.setLockSecondsRemaining(0L);
            } else {
                long remaining = Duration.between(now, orderExpireAt).getSeconds();
                resp.setLockSecondsRemaining(Math.max(0, remaining));
                resp.setLockExpireTime(orderExpireAt);
            }
        } else {
            resp.setLockSecondsRemaining(0L);
        }

        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PeerPayDTOs.PreLockResult preLockInventory(String token) {
        TradeOrder order = orderMapper.findByPeerPayToken(token)
                .orElseThrow(() -> new BusinessException("代付账单不存在"));

        if ("PAID".equals(order.getOrderStatus()) || "COMPLETED".equals(order.getOrderStatus())) {
            throw new BusinessException("该订单已完成支付，请勿重复付款");
        }

        // 互斥检查：如果已被其他付款人预占且锁未到期，则拦截
        if ("RESERVED_LOCK".equals(order.getPeerStatus())) {
            if (order.getLockExpireTime() != null && LocalDateTime.now().isBefore(order.getLockExpireTime())) {
                long waitSeconds = Duration.between(LocalDateTime.now(), order.getLockExpireTime()).getSeconds();
                throw new BusinessException("已有付款人正在支付中，请稍候约 " + (waitSeconds / 60 + 1) + " 分钟（若超时未付将自动释放）");
            }
        }

        // 检查实际物理库存
        List<TradeOrderItem> items = itemMapper.findByOrderSn(order.getOrderSn());
        for (TradeOrderItem item : items) {
            ProductSku sku = skuMapper.findById(item.getSkuId())
                    .orElseThrow(() -> new BusinessException("商品SKU不存在"));
            if (sku.getStockQuantity() < item.getQuantity()) {
                throw new BusinessException("抱歉，当前商品库存已被购罄，无法支付");
            }
        }

        // 原子预占：设置 Redis 键 (15 分钟失效)
        String lockKey = PRELOCK_REDIS_PREFIX + order.getOrderSn();
        Boolean success = redisTemplate.opsForValue().setIfAbsent(lockKey, "LOCKED", DEFAULT_LOCK_MINUTES, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(success)) {
            throw new BusinessException("并发支付冲突，请刷新后重试");
        }

        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(DEFAULT_LOCK_MINUTES);
        order.setPeerStatus("RESERVED_LOCK");
        order.setLockExpireTime(expireTime);
        orderMapper.save(order);

        PeerPayDTOs.PreLockResult result = new PeerPayDTOs.PreLockResult();
        result.setSuccess(true);
        result.setPeerStatus("RESERVED_LOCK");
        result.setLockSeconds(DEFAULT_LOCK_MINUTES * 60);
        result.setExpireTime(expireTime);
        result.setMessage("库存预占成功，为您保留 15 分钟支付时间");
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelPreLock(String token) {
        TradeOrder order = orderMapper.findByPeerPayToken(token).orElse(null);
        if (order != null && "RESERVED_LOCK".equals(order.getPeerStatus())) {
            order.setPeerStatus("INIT");
            order.setLockExpireTime(null);
            orderMapper.save(order);
            redisTemplate.delete(PRELOCK_REDIS_PREFIX + order.getOrderSn());
            log.info("代付单 [{}] 付款人主动离开收银台，锁已即时释放", order.getOrderSn());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TradeOrder notifyPaymentSuccess(String token, String payerId, String payTradeNo, String channel) {
        TradeOrder order = orderMapper.findByPeerPayToken(token)
                .orElseThrow(() -> new BusinessException("代付单不存在"));

        if ("PAID".equals(order.getOrderStatus()) || "COMPLETED".equals(order.getOrderStatus())) {
            log.warn("代付单 [{}] 已支付过，忽略重复通知", order.getOrderSn());
            return order;
        }

        List<TradeOrderItem> items = itemMapper.findByOrderSn(order.getOrderSn());
        if (items.isEmpty()) {
            throw new BusinessException("订单明细异常");
        }

        // 阶段二两段式落地：真实扣减物理库存
        for (TradeOrderItem item : items) {
            ProductSku sku = skuMapper.findById(item.getSkuId())
                    .orElseThrow(() -> new BusinessException("商品SKU不存在"));
            if (sku.getStockQuantity() < item.getQuantity()) {
                throw new BusinessException("商品库存不足，扣减失败");
            }
            sku.setStockQuantity(sku.getStockQuantity() - item.getQuantity());
            skuMapper.save(sku);
        }

        // 绑定三方完整对账角色 <卖家, 买家, 付款人>
        order.setPeerStatus("PAID");
        order.setPeerPayerId(payerId != null && !payerId.isBlank() ? payerId : "WECHAT_GUEST_" + UUID.randomUUID().toString().substring(0, 8));
        order.setPayTradeNo(payTradeNo != null && !payTradeNo.isBlank() ? payTradeNo : "MOCK_PAY_" + System.currentTimeMillis());
        order.setPaymentChannel(channel != null ? channel : "MOCK");
        order.setLockExpireTime(null);

        // 如果是虚拟卡密，自动发放分配卡密库
        if (order.getGoodsType() == 2) {
            TradeOrderItem firstItem = items.get(0);
            List<ProductVirtualCard> availableCards = cardMapper.findBySkuIdAndStatus(firstItem.getSkuId(), 0);
            if (availableCards.size() >= firstItem.getQuantity()) {
                List<String> deliveredCards = new ArrayList<>();
                for (int i = 0; i < firstItem.getQuantity(); i++) {
                    ProductVirtualCard card = availableCards.get(i);
                    card.setStatus(1); // 已售出
                    card.setOrderSn(order.getOrderSn());
                    cardMapper.save(card);
                    deliveredCards.add(card.getCardContent());
                }
                firstItem.setCardDetails(String.join("\n", deliveredCards));
                itemMapper.save(firstItem);

                order.setOrderStatus("COMPLETED");
                order.setSharingStatus("FINISHED");
                order.setServiceVoucher("系统卡密秒级自动交付: " + String.join(" | ", deliveredCards));
                order.setFinishTime(LocalDateTime.now());
            } else {
                // 无预置卡密，则为【线上定制服务】，标记待卖家录入凭证
                order.setOrderStatus("PAID");
                order.setSharingStatus("PENDING");
                log.info("线上服务代付单 [{}] 付款成功，等待卖家提供交付凭证", order.getOrderSn());
            }
        } else {
            // 实物商品：标记待发货
            order.setOrderStatus("PAID");
            order.setSharingStatus("PENDING");
        }

        order = orderMapper.save(order);
        redisTemplate.delete(PRELOCK_REDIS_PREFIX + order.getOrderSn());
        log.info("代付单 [{}] 支付成功落地！三方角色绑定完成: 卖家ID={}, 买家={}, 付款人={}",
                order.getOrderSn(), order.getMerchantId(), order.getBuyerContact(), order.getPeerPayerId());

        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void fulfillVirtualOrder(Long orderId, String serviceVoucher, Long currentMerchantId) {
        TradeOrder order = orderMapper.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));

        if (!order.getMerchantId().equals(currentMerchantId)) {
            throw new BusinessException("无权操作其他商户订单");
        }

        if (order.getGoodsType() != 2) {
            throw new BusinessException("该订单为实物商品，请走快递发货流程");
        }

        if (!"PAID".equals(order.getOrderStatus())) {
            throw new BusinessException("订单当前状态不可执行线上履约，当前状态: " + order.getOrderStatus());
        }

        if (serviceVoucher == null || serviceVoucher.trim().isEmpty()) {
            throw new BusinessException("请填写线上服务交付凭证（如网盘链接、系统授权凭据或交付完成描述）");
        }

        order.setServiceVoucher(serviceVoucher.trim());
        order.setOrderStatus("COMPLETED");
        order.setSharingStatus("FINISHED");
        order.setFinishTime(LocalDateTime.now());
        orderMapper.save(order);

        log.info("商户 [{}] 完成线上服务订单 [{}] 凭证交付: {}", currentMerchantId, order.getOrderSn(), serviceVoucher);
    }

    @Override
    public PlatformAdConfig getSuccessAdBanner() {
        return adConfigMapper.findFirstByAdLocationAndIsEnabledOrderBySortOrderDesc("PAYER_SUCCESS", 1)
                .or(() -> adConfigMapper.findFirstByAdLocationAndIsEnabledOrderBySortOrderDesc("PAYER_SUCCESS_CONVERSION", 1))
                .orElse(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String regeneratePoster(String token) {
        TradeOrder order = orderMapper.findByPeerPayToken(token)
                .orElseThrow(() -> new BusinessException("代付单不存在"));

        MerchantStore store = storeMapper.findById(order.getMerchantId()).orElse(null);
        List<TradeOrderItem> items = itemMapper.findByOrderSn(order.getOrderSn());
        TradeOrderItem item = items.isEmpty() ? null : items.get(0);

        String storeName = store != null ? store.getStoreName() : "官方授权店";
        String productTitle = item != null ? item.getProductTitle() : "精选商品";
        String goodsTypeName = order.getGoodsType() == 2 ? "虚拟服务/卡密" : "实物商品";
        String cashierUrl = storefrontUrl + "/peer-pay/" + order.getPeerPayToken();

        String posterBase64 = PosterUtils.generatePeerPayPoster(
                cashierUrl, storeName, productTitle, order.getPayAmount(), order.getPeerMessage(), goodsTypeName
        );
        order.setPosterUrl(posterBase64);
        orderMapper.save(order);
        return posterBase64;
    }
}

