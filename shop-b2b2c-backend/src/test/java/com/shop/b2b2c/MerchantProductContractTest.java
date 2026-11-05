/*
 * 文件：MerchantProductContractTest.java
 * 用途：锁定商家本店商品列表 {product, skus} 响应契约，覆盖按当前商户查询、SKU聚合、空列表与非商家拒绝。
 * 维护注意：使用Mockito静态模拟当前商户，不连接数据库；若后续改为批量查询SKU，需同步保持包装字段名与租户范围不变。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.controller.ProductController;
import com.shop.b2b2c.dto.MerchantProductView;
import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductSku;
import com.shop.b2b2c.mapper.CategoryMapper;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import com.shop.b2b2c.mapper.ProductMapper;
import com.shop.b2b2c.mapper.ProductSkuMapper;
import com.shop.b2b2c.mapper.ProductVirtualCardMapper;
import com.shop.b2b2c.service.CardAssetService;
import com.shop.b2b2c.service.CardCipherService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantProductContractTest {

    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final ProductSkuMapper skuMapper = mock(ProductSkuMapper.class);
    private final ProductVirtualCardMapper cardMapper = mock(ProductVirtualCardMapper.class);
    private final MerchantStoreMapper storeMapper = mock(MerchantStoreMapper.class);
    private final CategoryMapper categoryMapper = mock(CategoryMapper.class);
    private final CardAssetService cardAssets = mock(CardAssetService.class);

    private final ProductController controller = new ProductController(
            productMapper, skuMapper, cardMapper, storeMapper, categoryMapper, cardAssets, new CardCipherService());

    private static Product product(long id, long merchantId) {
        Product product = new Product();
        product.setId(id);
        product.setMerchantId(merchantId);
        product.setTitle("商品" + id);
        return product;
    }

    private static ProductSku sku(long id, long productId, long merchantId) {
        ProductSku sku = new ProductSku();
        sku.setId(id);
        sku.setProductId(productId);
        sku.setMerchantId(merchantId);
        sku.setPrice(new BigDecimal("9.90"));
        sku.setStockQuantity(3);
        return sku;
    }

    @Test
    void merchantListReturnsProductWithItsOwnSkus() {
        Product p1 = product(11, 7);
        Product p2 = product(12, 7);
        when(productMapper.findByMerchantId(7L)).thenReturn(List.of(p1, p2));
        when(skuMapper.findByProductId(11L)).thenReturn(List.of(sku(111, 11, 7), sku(112, 11, 7)));
        when(skuMapper.findByProductId(12L)).thenReturn(List.of());

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(7L);

            var response = controller.listMerchantProducts();

            assertEquals(200, response.getCode());
            assertEquals(2, response.getData().size());
            MerchantProductView first = response.getData().get(0);
            assertEquals(p1, first.product());
            assertEquals(2, first.skus().size());
            assertEquals(0, response.getData().get(1).skus().size());
            verify(productMapper).findByMerchantId(7L);
            verify(skuMapper, never()).findByProductId(99L);
        }
    }

    @Test
    void emptyMerchantProductsReturnEmptyContractList() {
        when(productMapper.findByMerchantId(7L)).thenReturn(List.of());
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(7L);
            var response = controller.listMerchantProducts();
            assertEquals(200, response.getCode());
            assertEquals(0, response.getData().size());
            verify(skuMapper, never()).findByProductId(any());
        }
    }

    @Test
    void nonMerchantCannotReadMerchantProductList() {
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenThrow(new BusinessException(403, "无权访问商家数据"));
            assertThrows(BusinessException.class, controller::listMerchantProducts);
            verify(productMapper, never()).findByMerchantId(any());
        }
    }
}
