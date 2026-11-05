/*
 * 文件：PeerPayServiceImpl.java
 * 用途：代付交易与库存预占的事务实现，协调订单、SKU、卡密及人工服务履约；代付编码统一复用PeerStatus。
 * 维护注意：先锁订单再处理库存/预占/卡密；预占扣库存，付款核销不重复扣。保留无流水旧锁的升级兼容；卡密缺货必须回滚。公开代付详情不能返回卡密或服务凭证；当前无真实支付渠道，不能把内部落账方法当作验签或到账证明。
 */
package com.shop.b2b2c.service.impl;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PeerStatus;
import com.shop.b2b2c.common.TradeGuards;
import com.shop.b2b2c.common.PosterUtils;
import com.shop.b2b2c.dto.PeerPayDTOs;
import com.shop.b2b2c.entity.*;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.PeerPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PeerPayServiceImpl implements PeerPayService {

    private final InventoryReservationMapper reservationMapper;
    private final TradeOrderMapper orderMapper;
    private final TradeOrderItemMapper itemMapper;
    private final MerchantStoreMapper storeMapper;
    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductVirtualCardMapper cardMapper;
    private final PlatformAdConfigMapper adConfigMapper;
    private final com.shop.b2b2c.service.PaymentEventService paymentEventService;

    @Value("${app.frontend.storefront-url:http://localhost:3002}")
    private String storefrontUrl;

    private static final long DEFAULT_LOCK_MINUTES = 15;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createBuyerPeerPay(PeerPayDTOs.BuyerCreateRequest req) {
        TradeGuards.requireQuantity(req.getQuantity());
        if (req.getQuantity() == null || req.getQuantity() <= 0) {
            throw new BusinessException("购买数量必须大于0");
        }
        if (req.getBuyerContact() == null || req.getBuyerContact().trim().isEmpty()) {
            throw new BusinessException("请输入联系手机号或邮箱，用于提取商品与查单");
        }
        if (req.getBuyerContact().trim().length() > 64) {
            throw new BusinessException(400, "联系手机号或邮箱长度不能超过64字符");
        }
        if (req.getPeerMessage() != null && req.getPeerMessage().trim().length() > 200) {
            throw new BusinessException(400, "代付留言长度不能超过200字符");
        }
        if (req.getRecipientAddress() != null && req.getRecipientAddress().trim().length() > 255) {
            throw new BusinessException(400, "收件人地址长度不能超过255字符");
        }

        Product product = productMapper.findById(req.getProductId())
                .orElseThrow(() -> new BusinessException("商品不存在"));
        ProductSku sku = skuMapper.findById(req.getSkuId())
                .orElseThrow(() -> new BusinessException("商品规格SKU不存在"));
        MerchantStore store = storeMapper.findById(product.getMerchantId())
                .orElseThrow(() -> new BusinessException("店铺不存在"));

        TradeGuards.requireSkuOwnership(product, sku);
        TradeGuards.requirePurchasable(product, store);
        TradeGuards.requireMoney(sku.getPrice());

        // 阶段一意向校验：只查物理库存是否存在，不执行真实扣减 (防刷与防恶搞占库存)
        if (sku.getStockQuantity() < req.getQuantity()) {
            throw new BusinessException("商品库存不足，剩余库存: " + sku.getStockQuantity());
        }

        // 生成全局流水号与防撞代付短码
        String orderSn = "ORD" + UUID.randomUUID().toString().replace("-", "");
        String queryToken = UUID.randomUUID().toString().replace("-", "");
        String peerPayToken = "PAY" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        BigDecimal totalAmount = sku.getPrice().multiply(new BigDecimal(req.getQuantity()));
        TradeGuards.requireMoney(totalAmount);
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
        order.setPeerStatus(PeerStatus.INIT); // 两段式 Stage 1: 意向生成
        order.setPeerMessage(req.getPeerMessage() != null && !req.getPeerMessage().trim().isEmpty() 
                ? req.getPeerMessage().trim() : "好心人帮我买个单呗，谢谢老铁！");
        // T08/Q15：所有订单在创建时写数据库截止时间；预占成功后再由 preLock 刷新为预占到期时刻。
        LocalDateTime createdAt = LocalDateTime.now();
        order.setCreatedAt(createdAt);
        order.setLockExpireTime(createdAt.plusMinutes(DEFAULT_LOCK_MINUTES));

        // 保存订单主表
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

        // 生成 9:16 微信裂变防封海报并单字段回写，避免大实体写回竞争
        String cashierUrl = storefrontUrl + "/peer-pay/" + peerPayToken;
        String goodsTypeName = product.getGoodsType() == 3 ? "定制服务" : product.getGoodsType() == 2 ? "自动卡密" : "实物商品";
        String posterBase64 = PosterUtils.generatePeerPayPoster(
                cashierUrl, store.getStoreName(), product.getTitle(), totalAmount, order.getPeerMessage(), goodsTypeName
        );
        orderMapper.updatePosterUrl(order.getId(), posterBase64);

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
        TradeGuards.requireQuantity(req.getQuantity());
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
        TradeGuards.requireSkuOwnership(product, sku);
        TradeGuards.requirePurchasable(product, store);
        TradeGuards.requireMoney(sku.getPrice());

        if (sku.getStockQuantity() < req.getQuantity()) {
            throw new BusinessException("商品库存不足，剩余库存: " + sku.getStockQuantity());
        }

        if (req.getNegotiatedPrice() != null) TradeGuards.requireMoney(req.getNegotiatedPrice());
        // 商家议价定价：支持自主协商优惠改价推单
        BigDecimal unitPrice = (req.getNegotiatedPrice() != null && req.getNegotiatedPrice().compareTo(BigDecimal.ZERO) > 0)
                ? req.getNegotiatedPrice() : sku.getPrice();
        BigDecimal totalAmount = unitPrice.multiply(new BigDecimal(req.getQuantity()));

        TradeGuards.requireMoney(totalAmount);
        BigDecimal commissionRate = store.getCommissionRate() != null ? store.getCommissionRate() : new BigDecimal("3.00");
        BigDecimal commissionAmount = totalAmount.multiply(commissionRate).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
        BigDecimal merchantSettleAmount = totalAmount.subtract(commissionAmount);

        String orderSn = "ORD" + UUID.randomUUID().toString().replace("-", "");
        String queryToken = UUID.randomUUID().toString().replace("-", "");
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
        order.setPeerStatus(PeerStatus.INIT);
        order.setPeerMessage(req.getSellerNote() != null && !req.getSellerNote().isBlank() 
                ? req.getSellerNote().trim() : "这是商家为您定制的支付账单，请查收完成付款");
        // T08/Q15：推单同样写 15 分钟数据库截止时间，禁止依赖内存计时。
        LocalDateTime createdAt = LocalDateTime.now();
        order.setCreatedAt(createdAt);
        order.setLockExpireTime(createdAt.plusMinutes(DEFAULT_LOCK_MINUTES));

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

        String cashierUrl = storefrontUrl + "/peer-pay/" + peerPayToken;
        String goodsTypeName = product.getGoodsType() == 3 ? "定制服务" : product.getGoodsType() == 2 ? "自动卡密" : "实物商品";
        String posterBase64 = PosterUtils.generatePeerPayPoster(
                cashierUrl, store.getStoreName(), product.getTitle(), totalAmount, order.getPeerMessage(), goodsTypeName
        );
        orderMapper.updatePosterUrl(order.getId(), posterBase64);

        String wechatShareText = String.format("【%s · 专属定制账单】\n尊贵的客户您好，这是为您定制的专属订单，请查收：\n📦 商品：%s\n💰 协议专享价：￥%s\n👉 微信点开直接付款：%s\n（微信内打开直接支付，无需加购选规格）",
                store.getStoreName(), product.getTitle(), totalAmount.toPlainString(), cashierUrl);

        Map<String, Object> result = new HashMap<>();
        result.put("orderSn", orderSn);
        result.put("peerPayToken", peerPayToken);
        result.put("cashierUrl", cashierUrl);
        result.put("posterBase64", posterBase64);
        result.put("queryToken", queryToken);
        result.put("buyerContact", order.getBuyerContact());
        result.put("unitPrice", unitPrice);
        result.put("payAmount", totalAmount);
        result.put("wechatShareText", wechatShareText);
        return result;
    }

    @Override
    public PeerPayDTOs.DetailResponse getPeerPayDetail(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(400, "代付凭证不能为空");
        }
        // R26: 无锁只读查询订单，不持 FOR UPDATE 行锁，避免公开详情页拉长事务并阻塞付款
        TradeOrder order = orderMapper.findByPeerPayToken(token)
                .orElseThrow(() -> new BusinessException("代付账单不存在或已失效"));

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
        resp.setPosterUrl(null); // R26: 详情接口减载海报大字段，改由前端按需调用 /api/v1/peer-pay/poster/{token}
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
            resp.setProductId(product.getId());
            resp.setProductTitle(product.getTitle());
            resp.setProductImage(product.getMainImage());
            resp.setServiceDesc(product.getServiceDesc());
            resp.setIsOnlineService(product.getGoodsType() == 3);
        }

        if (firstItem != null) {
            resp.setSkuSpec(firstItem.getSpecInfo());
            resp.setQuantity(firstItem.getQuantity());
        }

        // 分享链接只展示付款摘要，领取内容必须由买家凭独立queryToken查询。

        // 隐私脱敏处理 (如: 138****8000)
        String contact = order.getBuyerContact();
        if (contact != null && contact.length() >= 7) {
            resp.setMaskedBuyerContact(contact.substring(0, 3) + "****" + contact.substring(contact.length() - 4));
        } else {
            resp.setMaskedBuyerContact("尾号***买家");
        }

        // 计算美团风格 15 分钟倒计时剩余秒数
        LocalDateTime now = LocalDateTime.now();
        if (PeerStatus.PAID.equals(order.getPeerStatus())) {
            resp.setLockSecondsRemaining(0L);
        } else if (PeerStatus.RESERVED_LOCK.equals(order.getPeerStatus()) && order.getLockExpireTime() != null) {
            long remaining = Duration.between(now, order.getLockExpireTime()).getSeconds();
            if (remaining <= 0) {
                resp.setPeerStatus(PeerStatus.EXPIRED);
                resp.setLockSecondsRemaining(0L);
            } else {
                resp.setLockSecondsRemaining(remaining);
                resp.setLockExpireTime(order.getLockExpireTime());
            }
        } else if (PeerStatus.INIT.equals(order.getPeerStatus()) && order.getCreatedAt() != null) {
            LocalDateTime orderExpireAt = order.getCreatedAt().plusMinutes(15);
            if (now.isAfter(orderExpireAt)) {
                orderMapper.markInitExpired(order.getId());
                resp.setPeerStatus(PeerStatus.EXPIRED);
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
        return preLockInventory(token, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PeerPayDTOs.PreLockResult preLockInventory(String token, String clientKey) {
        TradeOrder order = orderMapper.findByPeerPayTokenForUpdate(token)
                .orElseThrow(() -> new BusinessException("代付账单不存在"));

        expireIfNecessary(order);

        if (!"UNPAID".equals(order.getOrderStatus())
                || order.getCreatedAt() == null || order.getCreatedAt().plusMinutes(15).isBefore(LocalDateTime.now())) {
            throw new BusinessException("订单已过期或不可预占，请刷新账单");
        }

        String sanitizedKey = (clientKey != null) ? clientKey.trim() : null;
        if (sanitizedKey != null && sanitizedKey.length() > 64) {
            throw new BusinessException(400, "预占幂等凭据长度不能超过64字符");
        }
        if (sanitizedKey != null && sanitizedKey.isEmpty()) {
            sanitizedKey = null;
        }

        // R10 恢复逻辑：若订单已由当前尝试持有有效预占，同尝试凭证可幂等恢复，不重复扣库存
        if (PeerStatus.RESERVED_LOCK.equals(order.getPeerStatus())) {
            InventoryReservation existing = reservationMapper.find(order.getId());
            if (existing != null && "HELD".equals(existing.getStatus())
                    && existing.getExpiresAt() != null && existing.getExpiresAt().isAfter(LocalDateTime.now())) {
                if (sanitizedKey != null && sanitizedKey.equals(existing.getOwnerToken())) {
                    PeerPayDTOs.PreLockResult recovered = new PeerPayDTOs.PreLockResult();
                    recovered.setOwnerToken(existing.getOwnerToken());
                    recovered.setSuccess(true);
                    recovered.setPeerStatus(PeerStatus.RESERVED_LOCK);
                    long remaining = Duration.between(LocalDateTime.now(), existing.getExpiresAt()).getSeconds();
                    recovered.setLockSeconds(Math.max(0, remaining));
                    recovered.setExpireTime(existing.getExpiresAt());
                    recovered.setMessage("已恢复现有库存预占状态");
                    return recovered;
                }
            }
            throw new BusinessException("订单已被其他付款人预占中，请稍候再试");
        }

        if (!PeerStatus.INIT.equals(order.getPeerStatus())) {
            throw new BusinessException("订单状态不可预占，请刷新账单");
        }

        List<TradeOrderItem> items = itemMapper.findByOrderSn(order.getOrderSn());
        if (items.size() != 1) throw new BusinessException("当前仅支持单商品订单");
        TradeOrderItem item = items.get(0);
        Product product = productMapper.findById(item.getProductId()).orElseThrow(() -> new BusinessException("商品不存在"));
        TradeGuards.requirePurchasable(product, storeMapper.findById(order.getMerchantId()).orElse(null));
        if (!product.getGoodsType().equals(order.getGoodsType())) throw new BusinessException("商品交付类型已变化，请重新下单");
        if (item.getQuantity() == null || item.getQuantity() <= 0
                || skuMapper.deductStock(item.getSkuId(), item.getProductId(), order.getMerchantId(), item.getQuantity()) != 1) {
            throw new BusinessException("商品库存不足或SKU归属异常");
        }
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(DEFAULT_LOCK_MINUTES);
        String ownerToken = (sanitizedKey != null) ? sanitizedKey : UUID.randomUUID().toString().replace("-", "");
        InventoryReservation reservation = new InventoryReservation();
        reservation.setOrderId(order.getId());
        reservation.setSkuId(item.getSkuId());
        reservation.setProductId(item.getProductId());
        reservation.setMerchantId(order.getMerchantId());
        reservation.setQuantity(item.getQuantity());
        reservation.setOwnerToken(ownerToken);
        reservation.setExpiresAt(expireTime);
        reservationMapper.deleteReleased(order.getId());
        reservationMapper.insert(reservation);
        order.setPeerStatus(PeerStatus.RESERVED_LOCK);
        order.setLockExpireTime(expireTime);
        orderMapper.save(order);

        PeerPayDTOs.PreLockResult result = new PeerPayDTOs.PreLockResult();
        result.setOwnerToken(reservation.getOwnerToken());
        result.setSuccess(true);
        result.setPeerStatus(PeerStatus.RESERVED_LOCK);
        result.setLockSeconds(DEFAULT_LOCK_MINUTES * 60);
        result.setExpireTime(expireTime);
        result.setMessage("库存预占成功，为您保留 15 分钟支付时间");
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelPreLock(String token, String ownerToken) {
        TradeOrder order = orderMapper.findByPeerPayTokenForUpdate(token)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        InventoryReservation reservation = reservationMapper.find(order.getId());
        if (reservation == null || ownerToken == null || !ownerToken.equals(reservation.getOwnerToken())) {
            throw new BusinessException(403, "无权释放其他付款人的预占");
        }
        if (!"UNPAID".equals(order.getOrderStatus())) return;
        if (release(reservation)) {
            order.setPeerStatus(PeerStatus.INIT);
            order.setLockExpireTime(null);
            orderMapper.save(order);
        }
    }

    private boolean release(InventoryReservation reservation) {
        if (reservation == null || reservationMapper.transition(reservation.getOrderId(), "RELEASED") != 1) return false;
        if (skuMapper.addStock(reservation.getSkuId(), reservation.getMerchantId(), reservation.getQuantity()) != 1) {
            throw new BusinessException("预占库存释放失败");
        }
        return true;
    }

    private void expireIfNecessary(TradeOrder order) {
        if (!"UNPAID".equals(order.getOrderStatus())) return;
        LocalDateTime deadline = PeerStatus.RESERVED_LOCK.equals(order.getPeerStatus()) ? order.getLockExpireTime()
                : order.getCreatedAt() == null ? null : order.getCreatedAt().plusMinutes(15);
        if (deadline != null && !deadline.isAfter(LocalDateTime.now())) {
            release(reservationMapper.find(order.getId()));
            order.setOrderStatus("CLOSED");
            order.setPeerStatus(PeerStatus.EXPIRED);
            orderMapper.save(order);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void expireReservation(Long orderId) {
        TradeOrder order = orderMapper.lockById(orderId);
        if (order != null) expireIfNecessary(order);
    }

    @Override
    public TradeOrder notifyPaymentSuccess(String token, String payerId, String payTradeNo, String channel,
                                           BigDecimal receivedAmount, String currency, Long channelMerchantId) {
        // 不在此方法持有事务/行锁：业务事务由 PaymentEventService 的 TransactionTemplate 统一控制，
        // 避免外层事务占用连接时失败审计再开独立事务造成主连接池自锁 (T05/T11)。
        TradeOrder order = orderMapper.findByPeerPayToken(token)
                .orElseThrow(() -> new BusinessException("代付单不存在"));

        // T07：已支付订单也必须完成参数校验与事件落库，不得在资金核对前静默返回。
        if (payerId == null || payerId.isBlank() || payTradeNo == null || payTradeNo.isBlank()
                || channel == null || !("WECHAT".equals(channel) || "ALIPAY".equals(channel))) {
            throw new BusinessException(400, "缺少可信支付结果");
        }
        if (receivedAmount == null || receivedAmount.signum() <= 0) {
            throw new BusinessException(400, "缺少渠道实收金额，拒绝落账");
        }
        if (currency == null || currency.isBlank()) {
            throw new BusinessException(400, "缺少交易币种，拒绝落账");
        }
        if (channelMerchantId == null) {
            throw new BusinessException(400, "缺少渠道商户号，拒绝落账");
        }

        // R13/T06: 只透传渠道实收要素，禁止用订单应付金额反填实收/币种/商户号。
        com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent event = com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel(channel)
                .payTradeNo(payTradeNo)
                .orderSn(order.getOrderSn())
                .merchantId(channelMerchantId)
                .payAmount(receivedAmount)
                .currency(currency)
                .payerId(payerId)
                .rawPayload("InternalAdapter:" + channel + ":" + payTradeNo)
                .build();
        paymentEventService.processPaymentEvent(event);
        return orderMapper.findById(order.getId()).orElse(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void fulfillVirtualOrder(Long orderId, String serviceVoucher, Long currentMerchantId) {
        TradeOrder order = Optional.ofNullable(orderMapper.lockById(orderId))
                .orElseThrow(() -> new BusinessException("订单不存在"));

        if (!order.getMerchantId().equals(currentMerchantId)) {
            throw new BusinessException("无权操作其他商户订单");
        }

        if (order.getGoodsType() != 3) {
            throw new BusinessException("仅定制服务订单可以手工履约");
        }

        if (!"PAID".equals(order.getOrderStatus())) {
            throw new BusinessException("订单当前状态不可执行线上履约，当前状态: " + order.getOrderStatus());
        }

        if (serviceVoucher == null || serviceVoucher.trim().isEmpty()) {
            throw new BusinessException("请填写线上服务交付凭证（如网盘链接、系统授权凭据或交付完成描述）");
        }

        order.setServiceVoucher(serviceVoucher.trim());
        order.setOrderStatus("COMPLETED");
        order.setSharingStatus("PENDING");
        order.setFinishTime(LocalDateTime.now());
        orderMapper.save(order);

        log.info("商户 [{}] 完成线上服务订单 [{}]", currentMerchantId, order.getOrderSn());
    }

    @Override
    public PlatformAdConfig getSuccessAdBanner() {
        return adConfigMapper.findFirstByAdLocationAndIsEnabledOrderBySortOrderDesc("PAYER_SUCCESS", 1)
                .or(() -> adConfigMapper.findFirstByAdLocationAndIsEnabledOrderBySortOrderDesc("PAYER_SUCCESS_CONVERSION", 1))
                .orElse(null);
    }

    @Override
    public String regeneratePoster(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(400, "代付凭证不能为空");
        }
        // 1. 优先读取已存海报，重试不重复生成，避免CPU重复光栅化 (R26)
        String storedPoster = orderMapper.findStoredPosterByToken(token);
        if (storedPoster != null && !storedPoster.isBlank()) {
            return storedPoster;
        }

        // 2. 无锁只读查询订单基础元数据，绝不持有 FOR UPDATE 行锁 (R26)
        TradeOrder order = orderMapper.findByPeerPayToken(token)
                .orElseThrow(() -> new BusinessException(404, "代付单不存在"));

        MerchantStore store = storeMapper.findById(order.getMerchantId()).orElse(null);
        List<TradeOrderItem> items = itemMapper.findByOrderSn(order.getOrderSn());
        TradeOrderItem item = items.isEmpty() ? null : items.get(0);

        String storeName = store != null ? store.getStoreName() : "官方授权店";
        String productTitle = item != null ? item.getProductTitle() : "精选商品";
        String goodsTypeName = order.getGoodsType() == 3 ? "定制服务" : order.getGoodsType() == 2 ? "自动卡密" : "实物商品";
        String cashierUrl = storefrontUrl + "/peer-pay/" + order.getPeerPayToken();

        // 3. 事务外执行耗时渲染，绝不阻塞并发支付与预占释放 (R26)
        String posterBase64 = PosterUtils.generatePeerPayPoster(
                cashierUrl, storeName, productTitle, order.getPayAmount(), order.getPeerMessage(), goodsTypeName
        );

        // 4. 单字段更新 poster_url，绝不回写并覆盖已被并发支付更新的订单状态 (R26)
        orderMapper.updatePosterUrl(order.getId(), posterBase64);
        return posterBase64;
    }
}

