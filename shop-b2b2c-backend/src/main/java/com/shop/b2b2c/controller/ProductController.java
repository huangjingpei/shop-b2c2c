/*
 * 文件：ProductController.java
 * 用途：商品浏览、商家发布、本店卡密去重导入及成功批次记录入口。公开列表复用固定白名单单SQL组合查询。
 * 维护注意：自动卡密发布初始库存为0；导入委托独立READ_COMMITTED资产事务，禁止在入口加外层事务。先验证租户再去重增库存，公开详情验证交易资格和店铺白名单，类型3不走卡密导入。
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
            @RequestParam(required = false) Long merchantId) {
        if ((categoryId != null && categoryId <= 0) || (merchantId != null && merchantId <= 0)) {
            throw new BusinessException(400, "分类或店铺编号不正确");
        }
        return R.ok(productMapper.findPublicProducts(categoryId, merchantId));
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
        try {
            if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
                cn.dev33.satoken.stp.StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
            }
        } catch (cn.dev33.satoken.exception.SaTokenContextException ignored) {
        }
        if (dto == null) throw new BusinessException(400, "请输入卡密导入资料");
        return R.ok("卡密批量导入成功", cardAssets.importCards(SecurityUtils.requireCurrentMerchantId(),
                SecurityUtils.getCurrentUserId(), dto.getProductId(), dto.getSkuId(), dto.getCardContents()));
    }

    public R<List<ProductVirtualCard>> listCards(Long skuId) {
        return listCards(skuId, null);
    }

    @Operation(summary = "【商家】查询虚拟卡密库存 (R04: 权限隔离与no-store)")
    @GetMapping("/api/v1/merchant/cards")
    public R<List<ProductVirtualCard>> listCards(@RequestParam(required = false) Long skuId,
            jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
        }
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        // R04: 敏感资产严格授权，仅店长与超管可批量查阅卡密库存；员工账号无权全量导出
        try {
            if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
                cn.dev33.satoken.stp.StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
            }
        } catch (cn.dev33.satoken.exception.SaTokenContextException ignored) {
        }
        List<ProductVirtualCard> result;
        if (skuId != null) {
            if (skuId <= 0) {
                throw new BusinessException(400, "SKU编号不正确");
            }
            ProductSku sku = skuMapper.findById(skuId)
                    .orElseThrow(() -> new BusinessException("SKU不存在"));
            if (!merchantId.equals(sku.getMerchantId())) {
                throw new BusinessException(403, "无权访问其他商户卡密");
            }
            result = cardMapper.findAvailableForMerchant(skuId, merchantId);
        } else {
            result = cardMapper.findByMerchantId(merchantId);
        }
        for (ProductVirtualCard c : result) {
            if (c.getCardContent() != null) {
                c.setCardContent(cardCipher.decrypt(c.getCardContent()));
            }
        }
        return R.ok(result);
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
        try {
            if (cn.dev33.satoken.stp.StpUtil.isLogin()) {
                cn.dev33.satoken.stp.StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN");
            }
        } catch (cn.dev33.satoken.exception.SaTokenContextException ignored) {
        }
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
