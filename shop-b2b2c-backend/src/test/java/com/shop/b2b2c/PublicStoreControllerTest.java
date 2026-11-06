/*
 * 文件：PublicStoreControllerTest.java
 * 用途：独立公开店铺接口的营业资格、空店、白名单和无缓存回归测试。
 * 维护注意：Mock仓储、独立MVC，不连接数据库或Redis；不替代完整鉴权链和浏览器路由验收，禁止在期望响应中加入敏感实体字段。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.controller.PublicStoreController;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicStoreControllerTest {
    @Test
    void emptyStoreCanBeReadAnonymouslyWithoutProductLookup() throws Exception {
        var stores = mock(MerchantStoreMapper.class);
        var store = new MerchantStore(); store.setId(7L); store.setStoreName("空店");
        store.setStoreCode("EMPTY"); store.setNotice("真实公告"); store.setContactPhone("public-contact");
        store.setOwnerName("private-owner"); store.setIdCardNumber("private-id");
        store.setCustomPayQrcode("private-payment");
        when(stores.findById(7L)).thenReturn(Optional.of(store));
        var builder = new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
        new com.shop.b2b2c.config.JacksonConfig().longAsStringCustomizer().customize(builder);
        var mapper = builder.build();
        MockMvcBuilders.standaloneSetup(new PublicStoreController(stores))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
            .build()
            .perform(get("/api/v1/public/stores/7"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.length()").value(6))
            .andExpect(jsonPath("$.data.id").value("7")).andExpect(jsonPath("$.data.storeName").value("空店"))
            .andExpect(jsonPath("$.data.notice").value("真实公告"))
            .andExpect(jsonPath("$.data.contactPhone").value("public-contact"))
            .andExpect(jsonPath("$.data.ownerName").doesNotExist())
            .andExpect(jsonPath("$.data.idCardNumber").doesNotExist())
            .andExpect(jsonPath("$.data.customPayQrcode").doesNotExist());
        verify(stores).findById(7L); verifyNoMoreInteractions(stores);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 2, 3, -1, 99})
    void nonOperatingStoresAreUnavailable(Integer status) {
        var stores = mock(MerchantStoreMapper.class);
        var store = new MerchantStore(); store.setStatus(status);
        when(stores.findById(7L)).thenReturn(Optional.of(store));
        var ex = assertThrows(BusinessException.class,
            () -> new PublicStoreController(stores).getStore(7L, new MockHttpServletResponse()));
        assertEquals(404, ex.getCode()); assertEquals("店铺不存在或暂未营业", ex.getMessage());
    }

    @Test
    void missingStoreUsesSameUnavailableMessage() {
        var stores = mock(MerchantStoreMapper.class);
        when(stores.findById(7L)).thenReturn(Optional.empty());
        var ex = assertThrows(BusinessException.class,
            () -> new PublicStoreController(stores).getStore(7L, new MockHttpServletResponse()));
        assertEquals(404, ex.getCode()); assertEquals("店铺不存在或暂未营业", ex.getMessage());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidIdNeverReadsPersistence(Long id) {
        var stores = mock(MerchantStoreMapper.class);
        var ex = assertThrows(BusinessException.class,
            () -> new PublicStoreController(stores).getStore(id, new MockHttpServletResponse()));
        assertEquals(400, ex.getCode()); verifyNoInteractions(stores);
    }
}
