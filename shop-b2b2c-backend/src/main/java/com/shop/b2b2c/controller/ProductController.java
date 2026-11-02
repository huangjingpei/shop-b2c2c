package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.common.TradeGuards;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductSku;
import com.shop.b2b2c.entity.ProductVirtualCard;
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
@RequiredArgsConstructor
public class ProductController {

    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductVirtualCardMapper cardMapper;
    private final MerchantStoreMapper storeMapper;

    // ==========================================
    // 16688 买家端公开货源流 API
    // ==========================================

    @Operation(summary = "【公开】货源大厅商品流 (对标 16688 卡片式流)")
    @GetMapping("/api/v1/public/products")
    public R<List<Map<String, Object>>> listPublicProducts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long merchantId) {

        List<Product> products;
        if (categoryId != null) {
            products = productMapper.findByCategoryIdAndStatusOrderBySortOrderAsc(categoryId, 1);
        } else if (merchantId != null) {
            products = productMapper.findByMerchantIdAndStatus(merchantId, 1);
        } else {
            products = productMapper.findByStatusOrderBySortOrderAsc(1);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        Map<Long, MerchantStore> storeCache = new HashMap<>();

        for (Product p : products) {
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", p.getId());
            vo.put("title", p.getTitle());
            vo.put("subTitle", p.getSubTitle());
            vo.put("goodsType", p.getGoodsType());
            vo.put("mainImage", p.getMainImage());
            vo.put("minPrice", p.getMinPrice());
            vo.put("merchantId", p.getMerchantId());

            MerchantStore store = storeCache.computeIfAbsent(p.getMerchantId(),
                    id -> storeMapper.findById(id).orElse(null));
            if (store != null) {
                vo.put("storeName", store.getStoreName());
                vo.put("storeCode", store.getStoreCode());
                vo.put("storeLogo", store.getLogoUrl());
            }

            // 查询可用库存总数
            List<ProductSku> skus = skuMapper.findByProductId(p.getId());
            int totalStock = skus.stream().mapToInt(ProductSku::getStockQuantity).sum();
            vo.put("totalStock", totalStock);

            result.add(vo);
        }

        return R.ok(result);
    }

    @Operation(summary = "【公开】商品详情与 SKU 变体信息")
    @GetMapping("/api/v1/public/products/{id}")
    public R<Map<String, Object>> getProductDetail(@PathVariable Long id) {
        Product product = productMapper.findById(id)
                .orElseThrow(() -> new BusinessException("商品不存在或已下架"));

        List<ProductSku> skus = skuMapper.findByProductId(id);
        MerchantStore store = storeMapper.findById(product.getMerchantId()).orElse(null);

        Map<String, Object> res = new HashMap<>();
        res.put("product", product);
        res.put("skus", skus);
        res.put("store", store);
        return R.ok(res);
    }

    // ==========================================
    // 商家工作台视角 API (/api/v1/merchant/products)
    // ==========================================

    @Operation(summary = "【商家】查询本店商品列表")
    @GetMapping("/api/v1/merchant/products")
    public R<List<Product>> listMerchantProducts() {
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        return R.ok(productMapper.findByMerchantId(merchantId));
    }

    @Data
    public static class CreateProductDTO {
        private String title;
        private String subTitle;
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
        Long merchantId = SecurityUtils.requireCurrentMerchantId();

        Product product = new Product();
        product.setMerchantId(merchantId);
        product.setCategoryId(dto.getCategoryId());
        product.setTitle(dto.getTitle());
        product.setSubTitle(dto.getSubTitle());
        product.setGoodsType(dto.getGoodsType() != null ? dto.getGoodsType() : 1);
        product.setMainImage(dto.getMainImage());
        product.setMinPrice(dto.getMinPrice());
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
        private Long productId;
        private Long skuId;
        private List<String> cardContents; // 每行一个卡密
    }

    @Operation(summary = "【商家】16688虚拟卡密库批量录入")
    @PostMapping("/api/v1/merchant/cards/import")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> importCards(@RequestBody BatchImportCardDTO dto) {
        Long merchantId = SecurityUtils.requireCurrentMerchantId();

        ProductSku sku = skuMapper.findById(dto.getSkuId())
                .orElseThrow(() -> new BusinessException("指定SKU不存在"));
        Product product = productMapper.findById(dto.getProductId())
                .orElseThrow(() -> new BusinessException("商品不存在"));
        TradeGuards.requireSkuOwnership(product, sku);
        if (!merchantId.equals(product.getMerchantId()) || !Integer.valueOf(2).equals(product.getGoodsType())) {
            throw new BusinessException(403, "仅允许向本店虚拟商品导入卡密");
        }
        if (dto.getCardContents() == null || dto.getCardContents().isEmpty()) {
            throw new BusinessException("请输入卡密内容");
        }


        List<ProductVirtualCard> cards = new ArrayList<>();
        for (String content : dto.getCardContents()) {
            if (content != null && !content.trim().isEmpty()) {
                ProductVirtualCard card = new ProductVirtualCard();
                card.setProductId(dto.getProductId());
                card.setSkuId(dto.getSkuId());
                card.setMerchantId(merchantId);
                card.setCardContent(content.trim());
                card.setStatus(0); // 未售出
                cards.add(card);
            }
        }
        for (ProductVirtualCard card : cards) {
            cardMapper.insert(card);
        }

        // 更新 SKU 库存
        if (cards.isEmpty() || skuMapper.addStock(sku.getId(), merchantId, cards.size()) != 1) {
            throw new BusinessException("卡密内容为空或库存更新失败");
        }

        Map<String, Object> res = new HashMap<>();
        res.put("importedCount", cards.size());
        res.put("newStock", skuMapper.findById(sku.getId()).orElseThrow().getStockQuantity());
        return R.ok("卡密批量导入成功", res);
    }

    @Operation(summary = "【商家】查询虚拟卡密库存")
    @GetMapping("/api/v1/merchant/cards")
    public R<List<ProductVirtualCard>> listCards(@RequestParam(required = false) Long skuId) {
        Long merchantId = SecurityUtils.requireCurrentMerchantId();
        if (skuId != null) {
            ProductSku sku = skuMapper.findById(skuId)
                    .orElseThrow(() -> new BusinessException("SKU不存在"));
            if (!merchantId.equals(sku.getMerchantId())) {
                throw new BusinessException(403, "无权访问其他商户卡密");
            }
            return R.ok(cardMapper.findAvailableForMerchant(skuId, merchantId));
        }
        return R.ok(cardMapper.findByMerchantId(merchantId));
    }
}
