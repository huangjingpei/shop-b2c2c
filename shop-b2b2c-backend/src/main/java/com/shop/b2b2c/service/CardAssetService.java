/*
 * 文件：CardAssetService.java
 * 用途：本店卡密去重导入、成功批次记录、安全作废及自然幂等重放。
 * 维护注意：独立READ_COMMITTED事务，锁顺序为商户、商品、SKU、卡；导入按商户互斥，批次/成员/卡/库存同事务。T14：仅扫描本店 fingerprint IS NULL 的历史行，解密后内存计算摘要并补写指纹；拿不到明文/解密失败的行标记无法补算、计数并跳过，不中断导入，也不输出明文。密钥轮换与默认密钥回退仍待 T15/T21。作废拒绝HELD预占和数量不一致，不反向锁预占行。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.*;
import com.shop.b2b2c.entity.*;
import com.shop.b2b2c.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Slf4j
@Service
public class CardAssetService {
    private final CardAssetMapper assets;
    private final ProductEditingMapper products;
    private final InventoryAdjustmentMapper inventory;
    private final ProductVirtualCardMapper cards;
    private final ProductSkuMapper skus;
    private final CardImportBatchMapper batches;
    private final CardCipherService cipher;
    private final TransactionTemplate transaction;
    public CardAssetService(CardAssetMapper assets, ProductEditingMapper products, InventoryAdjustmentMapper inventory,
                            ProductVirtualCardMapper cards, ProductSkuMapper skus, CardImportBatchMapper batches, PlatformTransactionManager manager) {
        this(assets, products, inventory, cards, skus, batches, manager, new CardCipherService());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CardAssetService(CardAssetMapper assets, ProductEditingMapper products, InventoryAdjustmentMapper inventory,
                            ProductVirtualCardMapper cards, ProductSkuMapper skus, CardImportBatchMapper batches, PlatformTransactionManager manager,
                            CardCipherService cipher) {
        this.assets = assets; this.products = products; this.inventory = inventory; this.cards = cards; this.skus = skus;
        this.batches = batches; this.cipher = cipher != null ? cipher : new CardCipherService();
        transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Map<String,Object> importCards(Long merchant, Long actor, Long product, Long sku, List<String> contents) {
        requireEntry(merchant);
        if (actor == null || actor < 1) throw new BusinessException(401, "登录状态已失效");
        if (product == null || product < 1 || sku == null || sku < 1 || contents == null || contents.isEmpty() || contents.size() > 1000) {
            throw new BusinessException(400, "请选择商品和SKU，每批导入1至1000条卡密");
        }
        List<String> normalized = new ArrayList<>(); Set<String> fingerprints = new HashSet<>();
        for (String content : contents) {
            if (content == null || content.length() > 4096) throw new BusinessException(400, "卡密不能为空且每条最多4096字符");
            String value = content.trim();
            // trim还会移除部分ASCII控制字符，必须在规范化后判断是否为空。
            if (value.isBlank()) throw new BusinessException(400, "卡密不能为空且每条最多4096字符");
            if (!fingerprints.add(cipher.fingerprint(value))) throw new BusinessException(400, "本批卡密存在重复，整批未导入");
            normalized.add(value);
        }
        return transaction.execute(status -> {
            var current = lockSku(merchant, product, sku);
            long held = inventory.heldQuantity(merchant, sku);
            if (current.getStockQuantity() < 0 || held < 0 || (long) current.getStockQuantity() + normalized.size() > Integer.MAX_VALUE - held) {
                throw new BusinessException(400, "库存数量异常或超过上限（含待释放预占）");
            }
            // R25: 优先通过数据库索引指纹直接查找，避免全店历史明文全量读入内存
            String existing = assets.findExistingFingerprint(merchant, fingerprints);
            if (existing == null) {
                // T14: 仅补算仍无指纹的历史行，使其参与本次去重
                existing = backfillAndMatch(merchant, fingerprints);
            }
            if (existing != null) {
                throw new BusinessException(409, "卡密已存在于本店历史记录，整批未导入；请核对库存");
            }
            var batch = new CardImportBatch(); batch.setMerchantId(merchant); batch.setActorId(actor); batch.setProductId(product); batch.setSkuId(sku);
            batch.setImportedCount(normalized.size()); batch.setBeforeStock(current.getStockQuantity()); batch.setAfterStock(current.getStockQuantity() + normalized.size());
            if (batches.insert(batch) != 1 || batch.getId() == null || batch.getId() < 1) throw new IllegalStateException("导入批次记录失败");
            for (String content : normalized) {
                var card = new ProductVirtualCard();
                card.setMerchantId(merchant);
                card.setProductId(product);
                card.setSkuId(sku);
                card.setCardFingerprint(cipher.fingerprint(content));
                card.setCardContent(cipher.encrypt(content)); // R05: AES-256-GCM分级加密存储
                if (cards.insert(card) != 1) throw new IllegalStateException("卡密入库失败");
                if (card.getId() == null || card.getId() < 1 || batches.link(batch.getId(), card.getId()) != 1) throw new IllegalStateException("导入批次关联失败");
            }
            if (skus.addStock(sku, merchant, normalized.size()) != 1) throw new IllegalStateException("卡密库存更新失败");
            return Map.of("importedCount", normalized.size(), "newStock", batch.getAfterStock(), "batchId", batch.getId());
        });
    }

    /**
     * T14：补算本店历史无指纹行的明文摘要，并返回与本次导入重复的指纹（无重复返回 null）。
     * 解密失败、正文为空等无法补算的行只标记 fingerprint_unavailable=1 并计数告警，不能中断导入。
     * 补写指纹使用条件更新（card_fingerprint IS NULL），可重入且不覆盖已有指纹。
     */
    private String backfillAndMatch(Long merchant, Set<String> incoming) {
        List<ProductVirtualCard> pending = assets.findUnfingerprinted(merchant);
        if (pending == null || pending.isEmpty()) return null;
        int unavailable = 0;
        for (ProductVirtualCard previous : pending) {
            String content = previous == null ? null : previous.getCardContent();
            if (content == null || content.isBlank()) {
                unavailable++;
                if (previous != null) assets.markFingerprintState(merchant, previous.getId(), 1);
                continue;
            }
            String plaintext;
            try {
                plaintext = cipher.decrypt(content);
            } catch (RuntimeException e) {
                // 密钥缺失或历史密钥不匹配：明确标记不可补算，继续处理其余历史行
                unavailable++;
                assets.markFingerprintState(merchant, previous.getId(), 1);
                continue;
            }
            if (plaintext == null || plaintext.isBlank()) {
                unavailable++;
                assets.markFingerprintState(merchant, previous.getId(), 1);
                continue;
            }
            String fingerprint = cipher.fingerprint(plaintext);
            if (incoming.contains(fingerprint)) return fingerprint;
            // 同店铺已有该指纹时不补写，避免唯一键冲突；该行仍已在内存中完成比对
            if (assets.findExistingFingerprint(merchant, List.of(fingerprint)) == null) {
                assets.fillFingerprint(merchant, previous.getId(), fingerprint);
            } else {
                assets.markFingerprintState(merchant, previous.getId(), 0);
            }
        }
        if (unavailable > 0) {
            log.warn("卡密历史指纹补算跳过 {} 行：缺少明文或解密失败，已标记 fingerprint_unavailable=1", unavailable);
        }
        return null;
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
