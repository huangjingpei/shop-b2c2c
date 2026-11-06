/*
 * 文件：PublicProductControllerTest.java
 * 用途：公开商品组合参数、频道多类目白名单、投影字段契约和消除逐条查询的单元回归。
 * 维护注意：只使用Mock和内存序列化，不连接外部环境；SQL真实筛选及库存聚合另在隔离集成用例验证，不能用Mock通过代替MySQL兼容性。
 * 新增 categoryIds 参数后，控制器调用顺序为 (categoryId, merchantId, q, goodsType, categoryIds)，
 * Mapper 调用顺序为 (categoryId, categoryIds, merchantId, keyword, goodsType)，两者不可混淆。
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
                var categories = mock(CategoryMapper.class);
                var assets = mock(CardAssetService.class);
                var rows = List.of(new PublicProductView(), new PublicProductView());
                when(products.findPublicProducts(category, null, merchant, null, null)).thenReturn(rows);
                var result = new ProductController(products, skus, cards, stores, categories, assets)
                        .listPublicProducts(category, merchant, null, null, null);
                assertSame(rows, result.getData()); assertEquals(200, result.getCode());
                verify(products).findPublicProducts(category, null, merchant, null, null);
                verifyNoMoreInteractions(products); verifyNoInteractions(skus, cards, stores, categories, assets);
            }
        }
    }

    @Test
    void invalidFiltersAreRejectedBeforeAnyQuery() {
        var products = mock(ProductMapper.class);
        var controller = new ProductController(products, mock(ProductSkuMapper.class),
                mock(ProductVirtualCardMapper.class), mock(MerchantStoreMapper.class),
                mock(CategoryMapper.class), mock(CardAssetService.class));
        for (Long invalid : new Long[] {0L, -1L, Long.MIN_VALUE}) {
            var categoryError = assertThrows(BusinessException.class, () -> controller.listPublicProducts(invalid, 1L, null, null, null));
            var merchantError = assertThrows(BusinessException.class, () -> controller.listPublicProducts(1L, invalid, null, null, null));
            assertEquals(400, categoryError.getCode()); assertEquals(400, merchantError.getCode());
        }
        verifyNoInteractions(products);
    }

    @Test
    void searchKeywordIsTrimmedAndLengthLimitedBeforeQuery() {
        var products = mock(ProductMapper.class);
        var controller = new ProductController(products, mock(ProductSkuMapper.class),
                mock(ProductVirtualCardMapper.class), mock(MerchantStoreMapper.class),
                mock(CategoryMapper.class), mock(CardAssetService.class));
        var rows = List.of(new PublicProductView());
        when(products.findPublicProducts(null, null, null, "手机", 2)).thenReturn(rows);

        assertSame(rows, controller.listPublicProducts(null, null, "  手机  ", 2, null).getData());
        verify(products).findPublicProducts(null, null, null, "手机", 2);

        reset(products);
        when(products.findPublicProducts(null, null, null, null, null)).thenReturn(List.of());
        assertTrue(controller.listPublicProducts(null, null, "   ", null, null).getData().isEmpty());
        verify(products).findPublicProducts(null, null, null, null, null);

        assertThrows(BusinessException.class, () -> controller.listPublicProducts(null, null, "x".repeat(51), null, null));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> controller.listPublicProducts(null, null, null, 0, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> controller.listPublicProducts(null, null, null, 4, null)).getCode());
        verify(products, never()).findPublicProducts(null, null, null, "x".repeat(51), null);
    }

    @Test
    void goodsTypeWhitelistAllowsOnlyOneTwoThree() {
        var products = mock(ProductMapper.class);
        var controller = new ProductController(products, mock(ProductSkuMapper.class),
                mock(ProductVirtualCardMapper.class), mock(MerchantStoreMapper.class),
                mock(CategoryMapper.class), mock(CardAssetService.class));
        for (int type : new int[] {1, 2, 3}) {
            when(products.findPublicProducts(null, null, null, null, type)).thenReturn(List.of());
            assertEquals(200, controller.listPublicProducts(null, null, null, type, null).getCode());
        }
        assertThrows(BusinessException.class, () -> controller.listPublicProducts(null, null, null, -1, null));
    }

    @Test
    void channelCategoryIdsAreParsedAndForwardedAsList() {
        var products = mock(ProductMapper.class);
        var controller = new ProductController(products, mock(ProductSkuMapper.class),
                mock(ProductVirtualCardMapper.class), mock(MerchantStoreMapper.class),
                mock(CategoryMapper.class), mock(CardAssetService.class));
        var rows = List.of(new PublicProductView());
        when(products.findPublicProducts(null, List.of(3L, 4L), null, null, null)).thenReturn(rows);

        assertSame(rows, controller.listPublicProducts(null, null, null, null, " 3, 4 ").getData());
        verify(products).findPublicProducts(null, List.of(3L, 4L), null, null, null);
    }

    @Test
    void invalidCategoryIdsAreRejectedBeforeAnyQuery() {
        var products = mock(ProductMapper.class);
        var controller = new ProductController(products, mock(ProductSkuMapper.class),
                mock(ProductVirtualCardMapper.class), mock(MerchantStoreMapper.class),
                mock(CategoryMapper.class), mock(CardAssetService.class));
        for (String invalid : new String[] {"0", "-1", "3,abc", "3,,4", "1,2,3,4,5,6,7,8,9,10,11"}) {
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> controller.listPublicProducts(null, null, null, null, invalid)).getCode(),
                    "应拒绝非法 categoryIds: " + invalid);
        }
        assertEquals(400, assertThrows(BusinessException.class,
                () -> controller.listPublicProducts(3L, null, null, null, "3,4")).getCode());
        verifyNoInteractions(products);
    }

    @Test
    void blankCategoryIdsIsTreatedAsNoFilter() {
        var products = mock(ProductMapper.class);
        var controller = new ProductController(products, mock(ProductSkuMapper.class),
                mock(ProductVirtualCardMapper.class), mock(MerchantStoreMapper.class),
                mock(CategoryMapper.class), mock(CardAssetService.class));
        when(products.findPublicProducts(null, null, null, null, null)).thenReturn(List.of());
        assertTrue(controller.listPublicProducts(null, null, null, null, "   ").getData().isEmpty());
        verify(products).findPublicProducts(null, null, null, null, null);
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
