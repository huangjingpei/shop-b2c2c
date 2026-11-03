/*
 * 文件：InventoryAdjustmentService.java
 * 用途：人工库存增减、追加记录及幂等重放的原子事务和本店记录分页。
 * 维护注意：入口不得套外层事务，以保证READ_COMMITTED；锁顺序为幂等键、商品、SKU，不锁预占行。SKU锁后读取最新已提交HELD总量，为后续释放保留INT容量；禁止自动卡密手调。只保存成功操作，不清理幂等记录，不把重放快照当实时库存。
 */
package com.shop.b2b2c.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.InventoryAdjustmentDTOs.*;
import com.shop.b2b2c.entity.InventoryAdjustment;
import com.shop.b2b2c.mapper.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class InventoryAdjustmentService {
    private final InventoryAdjustmentMapper mapper;
    private final ProductEditingMapper products;
    private final IdempotencyMapper keys;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public InventoryAdjustmentService(InventoryAdjustmentMapper mapper, ProductEditingMapper products, IdempotencyMapper keys,
                                      ObjectMapper json, PlatformTransactionManager manager) {
        this.mapper = mapper; this.products = products; this.keys = keys; this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public View adjust(Long merchant, Long actor, String key, Adjust request) {
        requireIdentity(merchant);
        if (actor == null || actor < 1) throw new BusinessException(401, "登录状态已失效");
        OrderIdempotencyService.requireKey(key);
        if (request == null || request.productId() == null || request.productId() < 1 || request.skuId() == null || request.skuId() < 1
                || request.delta() == null || request.delta() == 0 || request.delta() < -1000000 || request.delta() > 1000000
                || request.expectedStock() == null || request.expectedStock() < 0 || request.reason() == null
                || request.reason().isBlank() || request.reason().length() > 255) throw new BusinessException(400, "请填写合法的商品、SKU、当前可售库存、非零调整数（最多100万）及原因");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("库存调整入口不能套外层事务");
        String storageKey = hash("inventory-adjustment:" + merchant + ":" + actor + "\0" + key);
        String requestHash = hash(encode(request));
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction.execute(status -> {
                    keys.claim(storageKey, requestHash);
                    var record = keys.lock(storageKey);
                    if (!requestHash.equals(record.getRequestHash())) throw new BusinessException(409, "同一幂等键不能用于不同库存调整参数");
                    if (record.getResponseJson() != null) {
                        try { return json.readValue(record.getResponseJson(), View.class); }
                        catch (Exception e) { throw new IllegalStateException("读取库存调整结果失败", e); }
                    }
                    View result = apply(merchant, actor, storageKey, request);
                    if (keys.complete(storageKey, encode(result)) != 1) throw new IllegalStateException("保存库存调整结果失败");
                    return result;
                });
            } catch (DuplicateKeyException e) { if (attempt >= 2) throw e; }
        }
    }

    private View apply(Long merchant, Long actor, String storageKey, Adjust request) {
        var product = products.lockProduct(request.productId(), merchant);
        if (product == null) throw new BusinessException(404, "商品不存在或不属于本店");
        if (!Integer.valueOf(1).equals(product.getGoodsType()) && !Integer.valueOf(3).equals(product.getGoodsType())) {
            throw new BusinessException(400, "自动卡密库存须通过卡密业务维护，不能手工改数");
        }
        var sku = mapper.lockSku(merchant, request.productId(), request.skuId());
        if (sku == null) throw new BusinessException(404, "SKU不存在或不属于本商品");
        int before = sku.getStockQuantity();
        if (before < 0 || before != request.expectedStock()) throw new BusinessException(409, "库存已变化，请核对记录并刷新后重试");
        long after = (long) before + request.delta();
        long held = mapper.heldQuantity(merchant, request.skuId());
        if (after < 0 || held < 0 || after > Integer.MAX_VALUE - held) throw new BusinessException(400, "可售库存不足或总库存超过上限（含待释放预占）");
        if (mapper.adjust(merchant, request.productId(), request.skuId(), before, request.delta()) != 1) throw new BusinessException(409, "库存已变化，请刷新后重试");
        var row = new InventoryAdjustment();
        row.setMerchantId(merchant); row.setProductId(request.productId()); row.setSkuId(request.skuId()); row.setActorId(actor);
        row.setRequestKey(storageKey); row.setDelta(request.delta()); row.setBeforeStock(before); row.setAfterStock((int) after); row.setReason(request.reason().strip());
        if (mapper.insert(row) != 1) throw new IllegalStateException("保存库存调整记录失败");
        var saved = mapper.find(merchant, row.getId());
        if (saved == null) throw new IllegalStateException("读取库存调整记录失败");
        return View.from(saved);
    }

    public Page list(Long merchant, Long sku, Long before, int limit) {
        requireIdentity(merchant);
        if ((sku != null && sku < 1) || (before != null && before < 1) || limit < 1 || limit > 100) throw new BusinessException(400, "库存记录查询参数不合法");
        var rows = mapper.list(merchant, sku, before, limit + 1);
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(View::from).toList();
        return new Page(items, more ? items.get(items.size() - 1).id() : null);
    }
    private void requireIdentity(Long merchant) { if (merchant == null || merchant < 1) throw new BusinessException(403, "当前账号未绑定店铺"); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("库存调整数据编码失败", e); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
