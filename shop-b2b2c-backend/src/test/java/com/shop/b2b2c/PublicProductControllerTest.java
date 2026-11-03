/*
 * 文件：PublicProductControllerTest.java
 * 用途：公开商品组合参数、白名单契约和消除逐条查询的单元回归。
 * 维护注意：只使用Mock和内存序列化，不连接外部环境；SQL真实筛选及库存聚合另在隔离集成用例验证，不能用Mock通过代替MySQL兼容性。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.controller.ProductController;
import com.shop.b2b2c.dto.PublicProductView;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.CardAssetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicProductControllerTest {
    @Test
    void optionalFiltersAreForwardedTogetherWithoutPerProductLookups() {
        for (Long category : new Long[] {null, 8L, Long.MAX_VALUE}) {
            for (Long merchant : new Long[] {null, 9L, Long.MAX_VALUE}) {
                var products = mock(ProductMapper.class);
                var skus = mock(ProductSkuMapper.class);
                var cards = mock(ProductVirtualCardMapper.class);
                var stores = mock(MerchantStoreMapper.class);
                var assets = mock(CardAssetService.class);
                var rows = List.of(new PublicProductView(), new PublicProductView());
                when(products.findPublicProducts(category, merchant)).thenReturn(rows);
                var result = new ProductController(products, skus, cards, stores, assets)
                        .listPublicProducts(category, merchant);
                assertSame(rows, result.getData()); assertEquals(200, result.getCode());
                verify(products).findPublicProducts(category, merchant);
                verifyNoMoreInteractions(products); verifyNoInteractions(skus, cards, stores, assets);
            }
        }
    }

    @Test
    void invalidFiltersAreRejectedBeforeAnyQuery() {
        var products = mock(ProductMapper.class);
        var controller = new ProductController(products, mock(ProductSkuMapper.class),
                mock(ProductVirtualCardMapper.class), mock(MerchantStoreMapper.class), mock(CardAssetService.class));
        for (Long invalid : new Long[] {0L, -1L, Long.MIN_VALUE}) {
            var categoryError = assertThrows(BusinessException.class, () -> controller.listPublicProducts(invalid, 1L));
            var merchantError = assertThrows(BusinessException.class, () -> controller.listPublicProducts(1L, invalid));
            assertEquals(400, categoryError.getCode()); assertEquals(400, merchantError.getCode());
        }
        verifyNoInteractions(products);
    }

    @Test
    void publicProjectionKeepsExactlyTheExistingDisplayFieldsIncludingNulls() {
        var row = new PublicProductView(); row.setId(1L); row.setTotalStock(2147483648L);
        var tree = new ObjectMapper().valueToTree(row);
        assertEquals(11, tree.size());
        for (String field : Set.of("id", "title", "subTitle", "goodsType", "mainImage", "minPrice",
                "merchantId", "storeName", "storeCode", "storeLogo", "totalStock")) assertTrue(tree.has(field));
        assertEquals(2147483648L, tree.get("totalStock").longValue());
        assertTrue(tree.get("subTitle").isNull()); assertTrue(tree.get("storeLogo").isNull());
    }
}
