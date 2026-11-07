/*
 * 文件：ProductEditingService.java
 * 用途：本店商品资料、全量既有SKU售价、上下架、SKU增删及规格维护的事务维护与旧版本冲突检测。
 * 维护注意：
 *  1. 商品后 SKU 的行锁序列和内容摘要共同防并发覆盖；摘要含SKU规格但排除可售库存/时间字段，库存变动不要求重填资料。
 *  2. SQL 只更新白名单列，禁止复用整实体保存；历史订单快照不改（建单时已快照spec_info），违规下架不能由商家自行恢复。
 *  3. T09/Q16：存在未到期 HELD 库存预占时禁止商家下架商品，防止支付回调先成功后无法履约。
 *  4. SKU增删共用商品行锁串行化；新增只写初始库存（自动卡密必须为0），不写库存调整流水；删除仅限零库存且无任何
 *     订单/卡密/预占/调整/审计等引用记录的SKU，外键与uk_merchant_sku_code唯一键作并发兜底。增删后按剩余SKU派生min_price。
 *  5. 规格维护只写spec_data：skuCode创建后不可改（代理细化）；规格本身不触发min_price变化。
 *  6. 详情图文(detail_images)经 normalizeDetailImages 白名单校验（image/text、条数与长度上限）后存规范JSON，
 *     null表示清空；该JSON串纳入revision指纹，并发编辑图文同样触发409冲突。
 */
package com.shop.b2b2c.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.*;
import com.shop.b2b2c.dto.ProductEditingDTOs.*;
import com.shop.b2b2c.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProductEditingService {
    private final ProductEditingMapper mapper;
    private final CategoryMapper categories;
    private final MerchantStoreMapper stores;
    private final InventoryReservationMapper reservations;
    private final ObjectMapper json;

    @Transactional
    public Editor get(Long merchant, Long id) { return snapshot(merchant, id); }

    @Transactional(rollbackFor = Exception.class)
    public Editor update(Long merchant, Long id, Update edit) {
        if (edit == null) throw new BusinessException(400, "商品资料不能为空");
        checkRevisionFormat(edit.revision());
        ManagementGuards.text(edit.title(), 255, true);
        ManagementGuards.text(edit.subTitle(), 255, false);
        ManagementGuards.text(edit.mainImage(), 255, true);
        ManagementGuards.text(edit.serviceDesc(), 10000, false);
        if (edit.categoryId() == null || edit.categoryId() < 1 || edit.skus() == null || edit.skus().isEmpty() || edit.skus().size() > 100) {
            throw new BusinessException(400, "类目和SKU列表不合法，单次最多100个SKU");
        }
        Map<Long, BigDecimal> prices = new HashMap<>();
        for (Price price : edit.skus()) {
            if (price == null || price.id() == null || price.id() < 1 || prices.containsKey(price.id())) {
                throw new BusinessException(400, "SKU编号不合法或重复");
            }
            TradeGuards.requireMoney(price.price());
            prices.put(price.id(), price.price());
        }
        Editor current = snapshot(merchant, id);
        requireRevision(current, edit.revision());
        String detailImagesJson = normalizeDetailImages(edit.detailImages());
        if (categories.findById(edit.categoryId()).isEmpty()) throw new BusinessException(400, "平台类目不存在");
        Set<Long> existing = new HashSet<>();
        current.skus().forEach(sku -> existing.add(sku.getId()));
        if (!existing.equals(prices.keySet())) throw new BusinessException(400, "请提交本商品全部既有SKU，不允许增删或混入其他商品SKU");
        BigDecimal minimum = prices.values().stream().min(BigDecimal::compareTo).orElseThrow();
        for (var sku : current.skus()) {
            BigDecimal next = prices.get(sku.getId());
            if ((sku.getPrice() == null || sku.getPrice().compareTo(next) != 0)
                    && mapper.updatePrice(id, merchant, sku.getId(), next) != 1) throw new BusinessException(409, "SKU已变化，请刷新后重试");
        }
        var product = current.product();
        boolean metadataChanged = !Objects.equals(product.getCategoryId(), edit.categoryId()) || !Objects.equals(product.getTitle(), edit.title())
                || !Objects.equals(product.getSubTitle(), edit.subTitle()) || !Objects.equals(product.getMainImage(), edit.mainImage())
                || !Objects.equals(product.getServiceDesc(), edit.serviceDesc()) || !Objects.equals(product.getDetailImages(), detailImagesJson)
                || !Objects.equals(money(product.getMinPrice()), money(minimum));
        // 不把未变化的列写回：兼容MySQL useAffectedRows=true时无变化更新返回0。
        if (metadataChanged && mapper.updateMetadata(id, merchant, edit, detailImagesJson, minimum) != 1) throw new BusinessException(409, "商品已变化，请刷新后重试");
        return snapshot(merchant, id);
    }

    /**
     * 详情图文规范化：仅接受 image(url 1..255，http(s)://或/开头) 与 text(content 1..2000) 两种条目，
     * 最多 20 条；null 或空列表表示清空（落库 NULL）。返回序列化后的规范 JSON（仅保留白名单字段），非法抛 400。
     * 该JSON串同时进入 revision 指纹，商城端按纯文本/图片渲染，禁止 v-html。
     */
    private String normalizeDetailImages(List<DetailItem> items) {
        if (items == null || items.isEmpty()) return null;
        if (items.size() > 20) throw new BusinessException(400, "详情图文最多 20 条");
        List<Map<String, String>> normalized = new ArrayList<>(items.size());
        for (DetailItem item : items) {
            if (item == null || item.type() == null) throw new BusinessException(400, "详情图文条目不合法");
            switch (item.type()) {
                case "image" -> {
                    String url = item.url() == null ? "" : item.url().trim();
                    if (url.isEmpty() || url.length() > 255
                            || !(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("/"))) {
                        throw new BusinessException(400, "详情图片地址必填、不超过255字符，且须以 http(s):// 或 / 开头");
                    }
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("type", "image"); entry.put("url", url);
                    normalized.add(entry);
                }
                case "text" -> {
                    String content = item.content() == null ? "" : item.content().trim();
                    if (content.isEmpty() || content.length() > 2000) {
                        throw new BusinessException(400, "详情文字必填且不超过2000字符");
                    }
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("type", "text"); entry.put("content", content);
                    normalized.add(entry);
                }
                default -> throw new BusinessException(400, "详情图文仅支持 image 或 text 条目");
            }
        }
        try {
            return json.writeValueAsString(normalized);
        } catch (Exception e) { throw new IllegalStateException("序列化详情图文失败", e); }
    }

    @Transactional(rollbackFor = Exception.class)
    public Editor changeStatus(Long merchant, Long id, Status change) {
        if (change == null || change.status() == null || (change.status() != 0 && change.status() != 1)) {
            throw new BusinessException(400, "商家仅可选择上架或下架");
        }
        checkRevisionFormat(change.revision());
        Editor current = snapshot(merchant, id);
        requireRevision(current, change.revision());
        if (!Integer.valueOf(0).equals(current.product().getStatus()) && !Integer.valueOf(1).equals(current.product().getStatus())) {
            throw new BusinessException(403, "违规下架商品须由平台处理，商家不能自行变更状态");
        }
        if (change.status() == 1) {
            var store = stores.findById(merchant).orElse(null);
            if (store == null || !Integer.valueOf(1).equals(store.getStatus()) || current.skus().isEmpty()) {
                throw new BusinessException(400, "店铺不可营业或商品没有SKU，无法上架");
            }
            TradeGuards.requireGoodsType(current.product().getGoodsType());
            current.skus().forEach(sku -> TradeGuards.requireMoney(sku.getPrice()));
        }
        if (change.status() == 0 && !Integer.valueOf(0).equals(current.product().getStatus())
                && reservations.countActiveHeldByProduct(id) > 0) {
            // T09/Q16：预占有效期 15 分钟；必须等待预占核销或释放/到期后才能下架。
            throw new BusinessException(409, "商品存在未到期的库存预占，暂不能下架");
        }
        if (!Objects.equals(current.product().getStatus(), change.status()) && mapper.updateStatus(id, merchant, change.status()) != 1) {
            throw new BusinessException(409, "商品已变化，请刷新后重试");
        }
        return snapshot(merchant, id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Editor addSku(Long merchant, Long id, SkuDraft draft) {
        if (draft == null) throw new BusinessException(400, "SKU资料不能为空");
        checkRevisionFormat(draft.revision());
        String code = draft.skuCode() == null ? "" : draft.skuCode().strip();
        if (code.isEmpty() || code.length() > 64) throw new BusinessException(400, "SKU编码必填且不超过64字符");
        validateSpec(draft.specData());
        TradeGuards.requireMoney(draft.price());
        int initialStock = draft.stockQuantity() != null ? draft.stockQuantity() : 0;
        Editor current = snapshot(merchant, id);
        requireRevision(current, draft.revision());
        TradeGuards.requireInitialStock(current.product().getGoodsType(), initialStock);
        if (current.skus().size() >= 100) throw new BusinessException(400, "单商品最多100个SKU");
        if (mapper.countSkuCode(merchant, code) > 0) throw new BusinessException(409, "SKU编码在本店已存在，请更换后重试");
        var sku = new com.shop.b2b2c.entity.ProductSku();
        sku.setSkuCode(code); sku.setSpecData(draft.specData()); sku.setPrice(draft.price()); sku.setStockQuantity(initialStock);
        if (mapper.insertSku(id, merchant, sku) != 1 || sku.getId() == null) throw new IllegalStateException("SKU写入失败");
        deriveMinimumAfterChange(merchant, id, current.product().getMinPrice(),
                draft.price().min(current.product().getMinPrice() == null ? draft.price() : current.product().getMinPrice()));
        return snapshot(merchant, id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Editor removeSku(Long merchant, Long id, Long skuId, SkuRemoval removal) {
        if (removal == null) throw new BusinessException(400, "请重新加载商品编辑资料");
        checkRevisionFormat(removal.revision());
        if (skuId == null || skuId < 1) throw new BusinessException(400, "SKU编号不合法");
        Editor current = snapshot(merchant, id);
        requireRevision(current, removal.revision());
        var target = current.skus().stream().filter(sku -> skuId.equals(sku.getId())).findFirst()
                .orElseThrow(() -> new BusinessException(404, "SKU不存在或不属于本商品"));
        if (current.skus().size() <= 1) throw new BusinessException(409, "商品至少保留一个SKU，如需移除请先下架商品");
        if (target.getStockQuantity() != null && target.getStockQuantity() > 0) {
            throw new BusinessException(409, "SKU仍有可售库存，请先通过对应库存业务清零后再删除");
        }
        if (mapper.countSkuReferences(skuId) > 0) {
            throw new BusinessException(409, "SKU已产生订单、卡密或库存等历史记录，不能删除");
        }
        if (mapper.deleteSku(id, merchant, skuId) != 1) throw new BusinessException(409, "SKU已变化，请刷新后重试");
        var remaining = current.skus().stream().filter(sku -> !skuId.equals(sku.getId())).toList();
        deriveMinimumAfterChange(merchant, id, current.product().getMinPrice(),
                remaining.stream().map(com.shop.b2b2c.entity.ProductSku::getPrice).min(BigDecimal::compareTo).orElseThrow());
        return snapshot(merchant, id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Editor updateSpec(Long merchant, Long id, Long skuId, SpecUpdate edit) {
        if (edit == null) throw new BusinessException(400, "规格资料不能为空");
        checkRevisionFormat(edit.revision());
        if (skuId == null || skuId < 1) throw new BusinessException(400, "SKU编号不合法");
        String spec = validateSpec(edit.specData());
        Editor current = snapshot(merchant, id);
        requireRevision(current, edit.revision());
        var target = current.skus().stream().filter(sku -> skuId.equals(sku.getId())).findFirst()
                .orElseThrow(() -> new BusinessException(404, "SKU不存在或不属于本商品"));
        if (!spec.equals(target.getSpecData()) && mapper.updateSpec(id, merchant, skuId, spec) != 1) {
            throw new BusinessException(409, "SKU已变化，请刷新后重试");
        }
        return snapshot(merchant, id);
    }

    /** 规格须为合法JSON对象且不超过255字符，与发布/新增SKU同口径。 */
    private String validateSpec(String specData) {
        if (specData == null || specData.length() > 255) {
            throw new BusinessException(400, "规格须为合法JSON对象且不超过255字符");
        }
        try {
            if (!json.readTree(specData).isObject()) throw new IllegalArgumentException();
        } catch (Exception e) { throw new BusinessException(400, "规格须为合法JSON对象且不超过255字符"); }
        return specData;
    }

    /** SKU集合或价格变化后重算最低售价；仅在实际变化时写定向列，兼容useAffectedRows。 */
    private void deriveMinimumAfterChange(Long merchant, Long id, BigDecimal currentMinimum, BigDecimal nextMinimum) {
        if (nextMinimum == null) return;
        if (currentMinimum != null && currentMinimum.compareTo(nextMinimum) == 0) return;
        if (mapper.updateMinPrice(id, merchant, nextMinimum) != 1) throw new BusinessException(409, "商品已变化，请刷新后重试");
    }

    private Editor snapshot(Long merchant, Long id) {
        if (merchant == null || merchant < 1) throw new BusinessException(403, "当前账号未绑定店铺");
        if (id == null || id < 1) throw new BusinessException(400, "商品编号不合法");
        var product = mapper.lockProduct(id, merchant);
        if (product == null) throw new BusinessException(404, "商品不存在或不属于本店");
        var skus = mapper.lockSkus(id, merchant);
        try {
            var payload = Arrays.asList(product.getId(), product.getMerchantId(), product.getCategoryId(), product.getTitle(),
                    product.getSubTitle(), product.getMainImage(), product.getServiceDesc(), product.getDetailImages(),
                    product.getGoodsType(), product.getStatus(),
                    money(product.getMinPrice()), skus.stream().map(sku -> Arrays.asList(sku.getId(), sku.getSkuCode(), money(sku.getPrice()), sku.getSpecData())).toList());
            String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(payload)));
            return new Editor(product, skus, revision);
        } catch (Exception e) { throw new IllegalStateException("生成商品编辑版本失败", e); }
    }

    private String money(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private void checkRevisionFormat(String revision) {
        if (revision == null || !revision.matches("[a-f0-9]{64}")) throw new BusinessException(400, "请重新加载商品编辑资料");
    }
    private void requireRevision(Editor current, String expected) {
        if (!MessageDigest.isEqual(current.revision().getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(409, "商品已被其他操作修改，请刷新后重新编辑");
        }
    }
}
