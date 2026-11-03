/*
 * 文件：CardAssetService.java
 * 用途：本店历史卡密去重导入、安全作废及自然幂等重放。
 * 维护注意：独立READ_COMMITTED事务，锁顺序为商户、商品、SKU、卡；导入按商户互斥，所有应用写入路径必须遵守。历史扫描仅在内存计算摘要，不补存明文日志；暂未提供数据库唯一指纹、加密、批次或存量重复修复。作废拒绝HELD预占和数量不一致，不反向锁预占行。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.*;
import com.shop.b2b2c.entity.*;
import com.shop.b2b2c.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class CardAssetService {
    private final CardAssetMapper assets;
    private final ProductEditingMapper products;
    private final InventoryAdjustmentMapper inventory;
    private final ProductVirtualCardMapper cards;
    private final ProductSkuMapper skus;
    private final TransactionTemplate transaction;
    public CardAssetService(CardAssetMapper assets, ProductEditingMapper products, InventoryAdjustmentMapper inventory,
                            ProductVirtualCardMapper cards, ProductSkuMapper skus, PlatformTransactionManager manager) {
        this.assets = assets; this.products = products; this.inventory = inventory; this.cards = cards; this.skus = skus;
        transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Map<String,Object> importCards(Long merchant, Long product, Long sku, List<String> contents) {
        requireEntry(merchant);
        if (product == null || product < 1 || sku == null || sku < 1 || contents == null || contents.isEmpty() || contents.size() > 1000) {
            throw new BusinessException(400, "请选择商品和SKU，每批导入1至1000条卡密");
        }
        List<String> normalized = new ArrayList<>(); Set<String> fingerprints = new HashSet<>();
        for (String content : contents) {
            if (content == null || content.length() > 4096) throw new BusinessException(400, "卡密不能为空且每条最多4096字符");
            String value = content.trim();
            // trim还会移除部分ASCII控制字符，必须在规范化后判断是否为空。
            if (value.isBlank()) throw new BusinessException(400, "卡密不能为空且每条最多4096字符");
            if (!fingerprints.add(fingerprint(value))) throw new BusinessException(400, "本批卡密存在重复，整批未导入");
            normalized.add(value);
        }
        return transaction.execute(status -> {
            var current = lockSku(merchant, product, sku);
            long held = inventory.heldQuantity(merchant, sku);
            if (current.getStockQuantity() < 0 || held < 0 || (long) current.getStockQuantity() + normalized.size() > Integer.MAX_VALUE - held) {
                throw new BusinessException(400, "库存数量异常或超过上限（含待释放预占）");
            }
            for (String previous : assets.historyContents(merchant)) {
                if (previous != null && fingerprints.contains(fingerprint(previous.trim()))) {
                    throw new BusinessException(409, "卡密已存在于本店历史记录，整批未导入；请核对库存");
                }
            }
            for (String content : normalized) {
                var card = new ProductVirtualCard(); card.setMerchantId(merchant); card.setProductId(product); card.setSkuId(sku); card.setCardContent(content);
                if (cards.insert(card) != 1) throw new IllegalStateException("卡密入库失败");
            }
            if (skus.addStock(sku, merchant, normalized.size()) != 1) throw new IllegalStateException("卡密库存更新失败");
            return Map.of("importedCount", normalized.size(), "newStock", current.getStockQuantity() + normalized.size());
        });
    }

    public CardVoidRecord voidCard(Long merchant, Long actor, Long id, String reason) {
        requireEntry(merchant);
        if (actor == null || actor < 1) throw new BusinessException(401, "登录状态已失效");
        if (id == null || id < 1 || reason == null || reason.isBlank() || reason.length() > 255) throw new BusinessException(400, "请填写作废原因，最多255字符");
        return transaction.execute(status -> {
            var initial = assets.findCard(merchant, id);
            if (initial == null) throw new BusinessException(404, "卡密不存在或不属于本店");
            var sku = lockSku(merchant, initial.getProductId(), initial.getSkuId());
            var card = assets.lockCard(merchant, id);
            if (card == null || !Objects.equals(card.getProductId(), initial.getProductId()) || !Objects.equals(card.getSkuId(), initial.getSkuId())) {
                throw new BusinessException(409, "卡密归属已变化，请刷新后重试");
            }
            if (Integer.valueOf(2).equals(card.getStatus())) {
                var record = assets.voidRecord(merchant, id);
                if (record == null || !Objects.equals(record.getReason(), reason.strip())) throw new BusinessException(409, "卡密已作废，请核对原作废记录");
                return record;
            }
            if (!Integer.valueOf(0).equals(card.getStatus()) || card.getOrderSn() != null) throw new BusinessException(409, "卡密已售出或绑定订单，不能作废");
            if (inventory.heldQuantity(merchant, card.getSkuId()) != 0) throw new BusinessException(409, "该SKU有有效预占，请待支付或释放后再作废");
            int before = sku.getStockQuantity();
            if (before < 1 || assets.availableCount(merchant, card.getProductId(), card.getSkuId()) != before) {
                throw new BusinessException(409, "可售库存与在库卡密不一致，请先核对");
            }
            if (assets.markVoid(merchant, id) != 1 || skus.deductStock(card.getSkuId(), card.getProductId(), merchant, 1) != 1) {
                throw new BusinessException(409, "卡密或库存已变化，请刷新后重试");
            }
            var record = new CardVoidRecord(); record.setCardId(id); record.setMerchantId(merchant); record.setProductId(card.getProductId()); record.setSkuId(card.getSkuId());
            record.setActorId(actor); record.setReason(reason.strip()); record.setBeforeStock(before); record.setAfterStock(before - 1);
            if (assets.insertVoid(record) != 1) throw new IllegalStateException("作废记录保存失败");
            return readRecord(merchant, id);
        });
    }

    public CardVoidRecord readRecord(Long merchant, Long id) {
        if (merchant == null || merchant < 1) throw new BusinessException(403, "当前账号未绑定店铺");
        var record = assets.voidRecord(merchant, id);
        if (record == null) throw new BusinessException(404, "没有本店作废记录");
        return record;
    }
    private ProductSku lockSku(Long merchant, Long productId, Long skuId) {
        if (assets.lockMerchant(merchant) == null) throw new BusinessException(404, "店铺不存在");
        var product = products.lockProduct(productId, merchant);
        if (product == null || !Integer.valueOf(2).equals(product.getGoodsType())) throw new BusinessException(403, "仅允许操作本店自动卡密商品");
        var sku = inventory.lockSku(merchant, productId, skuId);
        if (sku == null) throw new BusinessException(404, "SKU不存在或不属于本商品");
        return sku;
    }
    private void requireEntry(Long merchant) {
        if (merchant == null || merchant < 1) throw new BusinessException(403, "当前账号未绑定店铺");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("卡密资产入口不能套外层事务");
    }
    private String fingerprint(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
