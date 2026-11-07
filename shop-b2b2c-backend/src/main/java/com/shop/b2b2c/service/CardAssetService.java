/*
 * 文件：CardAssetService.java
 * 用途：本店卡密去重导入、成功批次记录、安全作废、店长按SKU限量查看明文及导出审计、存量一致性核对。
 * 维护注意：独立READ_COMMITTED事务，锁顺序为商户、商品、SKU、卡；导入按商户互斥，批次/成员/卡/库存同事务。T14：仅对本店 fingerprint IS NULL 的行做内存解密比对，不再回写指纹或做历史补算；解密失败/缺少明文的行标记 fingerprint_unavailable=1、计数并跳过，不中断导入，也不输出明文。密钥按当前单密钥假设处理，轮换不在本轮范围。T15：列表只返回脱敏标识+指纹，不解密；明文查看必须按SKU限量且仅店长/超管；导出必须显式二次确认并写入 card_access_audit，审计失败不得返回明文。作废拒绝HELD预占和数量不一致，不反向锁预占行。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.*;
import com.shop.b2b2c.dto.MerchantCardView;
import com.shop.b2b2c.entity.*;
import com.shop.b2b2c.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.util.*;

@Slf4j
@Service
public class CardAssetService {
    /** 按 SKU 明文查看的默认/最大条数；超过上限必须拒绝，不能静默截断。 */
    public static final int REVEAL_DEFAULT_LIMIT = 20;
    public static final int REVEAL_MAX_LIMIT = 50;
    /** 单次导出上限；导出必须显式二次确认。 */
    public static final int EXPORT_MAX_LIMIT = 500;

    private final CardAssetMapper assets;
    private final ProductEditingMapper products;
    private final InventoryAdjustmentMapper inventory;
    private final ProductVirtualCardMapper cards;
    private final ProductSkuMapper skus;
    private final CardImportBatchMapper batches;
    private final CardAccessAuditMapper audits;
    private final CardCipherService cipher;
    private final TransactionTemplate transaction;
    public CardAssetService(CardAssetMapper assets, ProductEditingMapper products, InventoryAdjustmentMapper inventory,
                            ProductVirtualCardMapper cards, ProductSkuMapper skus, CardImportBatchMapper batches, PlatformTransactionManager manager) {
        this(assets, products, inventory, cards, skus, batches, manager, new CardCipherService(), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CardAssetService(CardAssetMapper assets, ProductEditingMapper products, InventoryAdjustmentMapper inventory,
                            ProductVirtualCardMapper cards, ProductSkuMapper skus, CardImportBatchMapper batches, PlatformTransactionManager manager,
                            CardCipherService cipher, CardAccessAuditMapper audits) {
        this.assets = assets; this.products = products; this.inventory = inventory; this.cards = cards; this.skus = skus;
        this.batches = batches; this.cipher = cipher != null ? cipher : new CardCipherService(); this.audits = audits;
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
            // R25: 优先通过数据库索引指纹直接查找，避免全店无指纹行全量读入内存
            String existing = assets.findExistingFingerprint(merchant, fingerprints);
            if (existing == null) {
                // T14: 无指纹行只做内存解密比对，不回写指纹
                existing = matchUnfingerprinted(merchant, fingerprints);
            }
            if (existing != null) {
                throw new BusinessException(409, "卡密已存在于本店已有记录，整批未导入；请核对库存");
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
     * 开发期基线清理：本店无指纹行只在内存解密比对，不再回写指纹（迁移脚本不做历史补算）。
     * 解密失败、正文为空等无法比对的行只标记 fingerprint_unavailable=1 并计数告警，不能中断导入，也不输出明文。
     */
    private String matchUnfingerprinted(Long merchant, Set<String> incoming) {
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
                // 密钥缺失或正文损坏：明确标记不可比对，继续处理其余行
                unavailable++;
                assets.markFingerprintState(merchant, previous.getId(), 1);
                continue;
            }
            if (plaintext == null || plaintext.isBlank()) {
                unavailable++;
                assets.markFingerprintState(merchant, previous.getId(), 1);
                continue;
            }
            if (incoming.contains(cipher.fingerprint(plaintext))) return cipher.fingerprint(plaintext);
        }
        if (unavailable > 0) {
            log.warn("卡密无指纹行比对跳过 {} 行：缺少明文或解密失败，已标记 fingerprint_unavailable=1", unavailable);
        }
        return null;
    }

    /**
     * T15：店长/超管按 SKU 限量查看明文。limit 必须显式在 1..50；越界直接拒绝而不是截断。
     * 调用方必须先通过接口层角色校验；本方法只负责租户归属与限量。
     */
    public List<MerchantCardView> revealCards(Long merchant, Long skuId, Integer limit) {
        requireEntry(merchant);
        int bounded = requireLimit(limit, REVEAL_DEFAULT_LIMIT, REVEAL_MAX_LIMIT);
        requireOwnedSku(merchant, skuId);
        return cards.findMerchantCards(merchant, skuId, false, null, bounded).stream()
                .map(card -> MerchantCardView.reveal(card, cipher.decrypt(card.getCardContent())))
                .toList();
    }

    /**
     * T15：按 SKU 导出明文卡密，必须先显式二次确认并写审计。
     * 审计与读取在同一事务内完成；审计写入失败直接抛错，不返回明文。
     */
    public Map<String,Object> exportCards(Long merchant, Long actor, Long skuId, Boolean confirm,
                                          Boolean onlyAvailable, Integer limit) {
        requireEntry(merchant);
        if (actor == null || actor < 1) throw new BusinessException(401, "登录状态已失效");
        if (skuId == null || skuId < 1) throw new BusinessException(400, "请选择需要导出的SKU");
        if (!Boolean.TRUE.equals(confirm)) throw new BusinessException(400, "导出卡密明文需要二次确认");
        int bounded = requireLimit(limit, EXPORT_MAX_LIMIT, EXPORT_MAX_LIMIT);
        boolean unsoldOnly = onlyAvailable == null || Boolean.TRUE.equals(onlyAvailable);
        return transaction.execute(status -> {
            requireOwnedSku(merchant, skuId);
            List<ProductVirtualCard> rows = cards.findMerchantCards(merchant, skuId, unsoldOnly, null, bounded);
            List<MerchantCardView> items = rows.stream()
                    .map(card -> MerchantCardView.reveal(card, cipher.decrypt(card.getCardContent())))
                    .toList();
            CardAccessAudit audit = new CardAccessAudit();
            audit.setMerchantId(merchant); audit.setActorId(actor); audit.setAction("EXPORT_PLAINTEXT");
            audit.setSkuId(skuId); audit.setCardCount(items.size());
            if (audits == null || audits.insert(audit) != 1) throw new IllegalStateException("卡密导出审计写入失败");
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("skuId", skuId); result.put("count", items.size()); result.put("items", items);
            return result;
        });
    }

    private int requireLimit(Integer limit, int defaultValue, int max) {
        int value = limit == null ? defaultValue : limit;
        if (value < 1 || value > max) throw new BusinessException(400, "查看/导出条数必须在1至" + max + "之间");
        return value;
    }

    private void requireOwnedSku(Long merchant, Long skuId) {
        if (skuId == null || skuId < 1) throw new BusinessException(400, "SKU编号不正确");
        var sku = skus.findById(skuId).orElseThrow(() -> new BusinessException(404, "SKU不存在"));
        if (!merchant.equals(sku.getMerchantId())) throw new BusinessException(403, "无权访问其他商户卡密");
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

    /**
     * F12 存量一致性核对：只读汇总本店卡密商品每个SKU的账面库存与在库未售卡数、预占与异常指纹。
     * consistent只判定stockQuantity==availableCards；异常指纹单列计数，提示人工按SKU筛选后走作废流程。
     * 核对结果不自动修复漂移，也不返回卡密正文或指纹；单条聚合SQL读取，无需事务。
     */
    public Map<String,Object> consistencyCheck(Long merchant) {
        requireEntry(merchant);
        List<com.shop.b2b2c.dto.CardConsistencyRow> rows = assets.consistencyRows(merchant);
        int abnormal = 0;
        long unavailableTotal = 0;
        for (var row : rows) {
            boolean consistent = row.getStockQuantity() != null && row.getStockQuantity().equals(row.getAvailableCards());
            row.setConsistent(consistent);
            long unavailable = row.getFingerprintUnavailable() == null ? 0 : row.getFingerprintUnavailable();
            unavailableTotal += unavailable;
            if (!consistent || unavailable > 0) abnormal++;
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("abnormalCount", abnormal);
        result.put("fingerprintUnavailableTotal", unavailableTotal);
        return result;
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
}
