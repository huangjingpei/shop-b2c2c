/*
 * 文件：ProductController.java
 * 用途：商品浏览、商家发布、本店卡密去重导入、成功批次记录、卡密脱敏列表、按SKU限量查看明文及导出审计入口。公开列表复用固定白名单单SQL组合查询。
 * 维护注意：自动卡密发布初始库存为0；导入委托独立READ_COMMITTED资产事务，禁止在入口加外层事务。先验证租户再去重增库存，公开详情验证交易资格和店铺白名单，类型3不走卡密导入。T15：列表绝不解密正文，只返回指纹尾号派生的脱敏标识；明文查看/导出必须在接口层要求店长或超管，导出需显式 confirm 并由服务层写审计。脱敏仅是接口返回，不等于加密或密钥轮换。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.ManagementGuards;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.common.TradeGuards;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductSku;
import com.shop.b2b2c.entity.ProductVirtualCard;
import com.shop.b2b2c.mapper.CategoryMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.ProductMapper;
import com.shop.b2b2c.mapper.ProductSkuMapper;
import com.shop.b2b2c.mapper.ProductVirtualCardMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@Tag(name = "商品与卡密库 (Product & Virtual Card)")
@RestController
public class ProductController {

    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductVirtualCardMapper cardMapper;
    private final MerchantStoreMapper storeMapper;
    private final CategoryMapper categoryMapper;
    private final com.shop.b2b2c.service.CardAssetService cardAssets;
    private final com.shop.b2b2c.service.CardCipherService cardCipher;

    public ProductController(ProductMapper productMapper, ProductSkuMapper skuMapper, ProductVirtualCardMapper cardMapper,
                             MerchantStoreMapper storeMapper, CategoryMapper categoryMapper, com.shop.b2b2c.service.CardAssetService cardAssets) {
        this(productMapper, skuMapper, cardMapper, storeMapper, categoryMapper, cardAssets, new com.shop.b2b2c.service.CardCipherService());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductController(ProductMapper productMapper, ProductSkuMapper skuMapper, ProductVirtualCardMapper cardMapper,
                             MerchantStoreMapper storeMapper, CategoryMapper categoryMapper, com.shop.b2b2c.service.CardAssetService cardAssets,
                             com.shop.b2b2c.service.CardCipherService cardCipher) {
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
        this.cardMapper = cardMapper;
        this.storeMapper = storeMapper;
        this.categoryMapper = categoryMapper;
        this.cardAssets = cardAssets;
        this.cardCipher = cardCipher != null ? cardCipher : new com.shop.b2b2c.service.CardCipherService();
    }

    // ==========================================
    // 16688 买家端公开货源流 API
    // ==========================================

    @Operation(summary = "【公开】货源大厅商品流 (对标 16688 卡片式流)")
    @GetMapping("/api/v1/public/products")
    public R<List<com.shop.b2b2c.dto.PublicProductView>> listPublicProducts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer goodsType) {
        if ((categoryId != null && categoryId <= 0) || (merchantId != null && merchantId <= 0)) {
            throw new BusinessException(400, "分类或店铺编号不正确");
        }
        if (goodsType != null && goodsType != 1 && goodsType != 2 && goodsType != 3) {
            throw new BusinessException(400, "商品类型只能是 1、2 或 3");
        }
        String keyword = normalizeKeyword(q);
        return R.ok(productMapper.findPublicProducts(categoryId, merchantId, keyword, goodsType));
    }

    /** T30：关键词去首尾空白；空串视为未搜索，超过 50 字符直接 400，避免把模糊匹配负担转嫁给数据库。 */
    private static String normalizeKeyword(String q) {
        if (q == null) return null;
        String trimmed = q.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 50) throw new BusinessException(400, "搜索关键词不能超过50个字符");
        return trimmed;
    }

    @Operation(summary = "【公开】商品详情与 SKU 变体信息")
    @GetMapping("/api/v1/public/products/{id}")
    public R<Map<String, Object>> getProductDetail(@PathVariable Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(400, "商品编号不正确");
        }
        Product product = productMapper.findById(id)
                .orElseThrow(() -> new BusinessException("商品不存在或已下架"));

        List<ProductSku> skus = skuMapper.findByProductId(id);
        MerchantStore store = storeMapper.findById(product.getMerchantId()).orElse(null);

        Map<String, Object> res = new HashMap<>();
        TradeGuards.requirePurchasable(product, store);
        res.put("product", product);
        res.put("skus", skus);
        res.put("store", com.shop.b2b2c.common.PublicStoreView.from(store));
        return R.ok(res);
    }

    // ==========================================
    // 商家工作台视角 API (/api/v1/merchant/products)
    // ==========================================

    @Operation(summary = "【商家】查询本店商品列表（含SKU，供推单选品联动）")
    @GetMapping("/api/v1/merchant/products")
    public R<List<com.shop.b2b2c.dto.MerchantProductView>> listMerchantProducts() {
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        List<com.shop.b2b2c.dto.MerchantProductView> views = productMapper.findByMerchantId(merchantId).stream()
                .map(product -> new com.shop.b2b2c.dto.MerchantProductView(product, skuMapper.findByProductId(product.getId())))
                .toList();
        return R.ok(views);
    }

    @Data
    public static class CreateProductDTO {
        private String title;
        private String subTitle;
        private String serviceDesc;
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long categoryId;
        private Integer goodsType; // 1-实物, 2-虚拟
        private String mainImage;
        private BigDecimal minPrice;
        private List<SkuDTO> skus;
    }

    @Data
    public static class SkuDTO {
        private String skuCode;
        private String specData;
        private BigDecimal price;
        private Integer stockQuantity;
    }

    @Operation(summary = "【商家】发布新商品与规格变体")
    @PostMapping("/api/v1/merchant/products")
    @Transactional(rollbackFor = Exception.class)
    public R<Product> createProduct(@RequestBody CreateProductDTO dto) {
        try {
            if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
                cn.dev33.satoken.stp.StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
            }
        } catch (cn.dev33.satoken.exception.SaTokenContextException ignored) {
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();

        if (dto.getTitle() == null || dto.getTitle().isBlank() || dto.getTitle().length() > 255
                || dto.getMainImage() == null || dto.getMainImage().isBlank() || dto.getMainImage().length() > 255
                || dto.getCategoryId() == null || dto.getCategoryId() <= 0
                || dto.getSkus() == null || dto.getSkus().isEmpty() || dto.getSkus().size() > 100) {
            throw new BusinessException(400, "商品必填项不完整，且单次最多100个SKU");
        }
        ManagementGuards.text(dto.getSubTitle(), 255, false);
        ManagementGuards.text(dto.getServiceDesc(), 10000, false);
        TradeGuards.requireGoodsType(dto.getGoodsType());
        if (categoryMapper.findById(dto.getCategoryId()).isEmpty()) {
            throw new BusinessException(400, "平台类目不存在");
        }
        Set<String> codes = new HashSet<>();
        BigDecimal calculatedMinPrice = null;
        for (SkuDTO sku : dto.getSkus()) {
            if (sku == null || sku.getSkuCode() == null || sku.getSkuCode().isBlank() || sku.getSkuCode().length() > 64
                    || !codes.add(sku.getSkuCode())) {
                throw new BusinessException(400, "SKU编码或库存不合法");
            }
            TradeGuards.requireMoney(sku.getPrice());
            TradeGuards.requireInitialStock(dto.getGoodsType(), sku.getStockQuantity());
            if (calculatedMinPrice == null || sku.getPrice().compareTo(calculatedMinPrice) < 0) {
                calculatedMinPrice = sku.getPrice();
            }
            try {
                if (sku.getSpecData() == null || sku.getSpecData().length() > 255 || !new com.fasterxml.jackson.databind.ObjectMapper().readTree(sku.getSpecData()).isObject()) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception e) { throw new BusinessException(400, "规格须为合法JSON对象且不超过255字符"); }
        }
        Product product = new Product();
        // T17/Q14-A：SKU 编码店内唯一；同店冲突明确 409，不泄露其他店铺是否使用该编码。
        for (SkuDTO sku : dto.getSkus()) {
            if (skuMapper.existsSkuCode(merchantId, sku.getSkuCode())) {
                throw new BusinessException(409, "SKU编码在本店已存在，请更换后重试");
            }
        }
        product.setMerchantId(merchantId);
        product.setCategoryId(dto.getCategoryId());
        product.setTitle(dto.getTitle());
        product.setSubTitle(dto.getSubTitle());
        product.setServiceDesc(dto.getServiceDesc());
        product.setGoodsType(dto.getGoodsType() != null ? dto.getGoodsType() : 1);
        product.setMainImage(dto.getMainImage());
        product.setMinPrice(calculatedMinPrice != null ? calculatedMinPrice : dto.getMinPrice());
        product.setStatus(1);
        product = productMapper.save(product);

        if (dto.getSkus() != null && !dto.getSkus().isEmpty()) {
            for (SkuDTO s : dto.getSkus()) {
                ProductSku sku = new ProductSku();
                sku.setProductId(product.getId());
                sku.setMerchantId(merchantId);
                sku.setSkuCode(s.getSkuCode());
                sku.setSpecData(s.getSpecData());
                sku.setPrice(s.getPrice());
                sku.setStockQuantity(s.getStockQuantity() != null ? s.getStockQuantity() : 0);
                skuMapper.save(sku);
            }
        }

        return R.ok("商品发布成功", product);
    }

    @Data
    public static class BatchImportCardDTO {
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long productId;
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long skuId;
        private List<String> cardContents; // 每行一个卡密
    }

    @Operation(summary = "【商家】本店卡密去重导入")
    @PostMapping("/api/v1/merchant/cards/import")
    public R<Map<String, Object>> importCards(@RequestBody BatchImportCardDTO dto) {
        com.shop.b2b2c.service.AuthSessionService.requireManagementRole();
        if (dto == null) throw new BusinessException(400, "请输入卡密导入资料");
        return R.ok("卡密批量导入成功", cardAssets.importCards(SecurityUtils.requireCurrentMerchantId(),
                SecurityUtils.getCurrentUserId(), dto.getProductId(), dto.getSkuId(), dto.getCardContents()));
    }

    @Operation(summary = "【商家】查询虚拟卡密库存 (T15: 脱敏列表+分页, 不解密正文)")
    @GetMapping("/api/v1/merchant/cards")
    public R<Map<String, Object>> listCards(@RequestParam(required = false) Long skuId,
            @RequestParam(required = false) Boolean onlyAvailable,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer limit,
            jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        // R04/Q13-A: 敏感资产严格授权，仅店长与超管可查阅卡密库存；员工在接口层直接拒绝。
        com.shop.b2b2c.service.AuthSessionService.requireManagementRole();
        if (beforeId != null && beforeId < 1) throw new BusinessException(400, "分页游标不正确");
        int bounded = limit == null ? 20 : limit;
        if (bounded < 1 || bounded > 50) throw new BusinessException(400, "每页条数必须在1至50之间");
        if (skuId != null) {
            requireOwnedSku(merchantId, skuId);
        }
        // 多取一条用于判断是否还有下一页；返回体不含 cardContent，也不逐条解密。
        List<ProductVirtualCard> rows = cardMapper.findMerchantCards(merchantId, skuId,
                Boolean.TRUE.equals(onlyAvailable), beforeId, bounded + 1);
        boolean hasMore = rows.size() > bounded;
        if (hasMore) {
            rows = rows.subList(0, bounded);
        }
        List<com.shop.b2b2c.dto.MerchantCardView> items = rows.stream()
                .map(com.shop.b2b2c.dto.MerchantCardView::masked).toList();
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("nextBeforeId", hasMore && !items.isEmpty() ? items.get(items.size() - 1).getId() : null);
        return R.ok(page);
    }

    /** 仅用于兼容旧单元测试的便捷入口；响应同样只含脱敏标识。 */
    public R<List<com.shop.b2b2c.dto.MerchantCardView>> listCards(Long skuId) {
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        com.shop.b2b2c.service.AuthSessionService.requireManagementRole();
        if (skuId != null) requireOwnedSku(merchantId, skuId);
        return R.ok(cardMapper.findMerchantCards(merchantId, skuId, false, null, 20).stream()
                .map(com.shop.b2b2c.dto.MerchantCardView::masked).toList());
    }

    @Operation(summary = "【商家】按 SKU 限量查看卡密明文 (店长/超管, limit≤50)")
    @GetMapping("/api/v1/merchant/cards/reveal")
    public R<Map<String, Object>> revealCards(@RequestParam Long skuId,
            @RequestParam(required = false) Integer limit,
            jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        com.shop.b2b2c.service.AuthSessionService.requireManagementRole();
        List<com.shop.b2b2c.dto.MerchantCardView> items = cardAssets.revealCards(merchantId, skuId, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skuId", skuId); result.put("items", items);
        return R.ok(result);
    }

    @Data
    public static class ExportCardsDTO {
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.shop.b2b2c.common.StrictIdDeserializer.class)
        private Long skuId;
        private Boolean confirm;
        private Boolean onlyAvailable;
        private Integer limit;
    }

    @Operation(summary = "【商家】导出 SKU 卡密明文 (二次确认+审计, 店长/超管)")
    @PostMapping("/api/v1/merchant/cards/export")
    public R<Map<String, Object>> exportCards(@RequestBody ExportCardsDTO dto,
            jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        // 导出属于明文批量外带，权限必须在接口层拒绝员工，不能靠前端隐藏。
        com.shop.b2b2c.service.AuthSessionService.requireManagementRole();
        if (dto == null) throw new BusinessException(400, "请选择需要导出的SKU");
        return R.ok("卡密导出完成", cardAssets.exportCards(merchantId, SecurityUtils.getCurrentUserId(),
                dto.getSkuId(), dto.getConfirm(), dto.getOnlyAvailable(), dto.getLimit()));
    }

    private void requireOwnedSku(Long merchantId, Long skuId) {
        if (skuId == null || skuId <= 0) throw new BusinessException(400, "SKU编号不正确");
        ProductSku sku = skuMapper.findById(skuId).orElseThrow(() -> new BusinessException("SKU不存在"));
        if (!merchantId.equals(sku.getMerchantId())) throw new BusinessException(403, "无权访问其他商户卡密");
    }

    public R<ProductVirtualCard> getCardDetail(Long id) {
        return getCardDetail(id, null);
    }

    @Operation(summary = "【商家】查询单个卡密资产详情 (R04: 单单授权与跨店隔离)")
    @GetMapping("/api/v1/merchant/cards/{id}")
    public R<ProductVirtualCard> getCardDetail(@PathVariable Long id,
            jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        if (id == null || id <= 0) {
            throw new BusinessException(400, "卡密编号不正确");
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        com.shop.b2b2c.service.AuthSessionService.requireManagementRole();
        ProductVirtualCard card = cardMapper.selectById(id);
        if (card == null || !merchantId.equals(card.getMerchantId())) {
            throw new BusinessException(404, "卡密不存在或无权访问");
        }
        if (card.getCardContent() != null) {
            card.setCardContent(cardCipher.decrypt(card.getCardContent()));
        }
        return R.ok(card);
    }
}
