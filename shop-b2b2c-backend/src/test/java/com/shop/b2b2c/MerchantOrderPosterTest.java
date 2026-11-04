/*
 * 文件：MerchantOrderPosterTest.java
 * 用途：已存海报按需接口的租户参数、空结果、非法ID/类型绑定及无缓存单元回归。
 * 维护注意：Mock会话与Mapper，不连接数据库；SQL归属隔离和列表减载另由H2接口用例验证。占位海报只验证透传，不代表图像解码验收。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.controller.MerchantOrderPosterController;
import com.shop.b2b2c.mapper.TradeOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MerchantOrderPosterTest {
    @Test
    void malformedAndOverflowingPathIdsReturn400WithoutLeakingInput() throws Exception {
        var orders = mock(TradeOrderMapper.class);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new MerchantOrderPosterController(orders))
                .setControllerAdvice(new com.shop.b2b2c.common.GlobalExceptionHandler()).build();
        for (String id : new String[] {"private-invalid-id", "9223372036854775808"}) {
            String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/merchant/orders/" + id + "/poster"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(400))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains(id));
        }
        verifyNoInteractions(orders);
    }

    @Test
    void existingPosterUsesSessionMerchantAndOnlyReadsOneField() {
        var orders = mock(TradeOrderMapper.class);
        when(orders.findStoredPoster(9L, 1L)).thenReturn("data:image/png;base64,aW1hZ2U=");
        var response = new MockHttpServletResponse();
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            var result = new MerchantOrderPosterController(orders).poster(9L, response);
            assertEquals("data:image/png;base64,aW1hZ2U=", result.getData());
            assertEquals(200, result.getCode());
            assertEquals("no-store", response.getHeader("Cache-Control"));
            verify(orders).findStoredPoster(9L, 1L); verifyNoMoreInteractions(orders);
        }
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void absentOrBlankPosterHasUniformNotFoundResult(String poster) {
        var orders = mock(TradeOrderMapper.class);
        when(orders.findStoredPoster(9L, 1L)).thenReturn(poster);
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            var response = new MockHttpServletResponse();
            var ex = assertThrows(BusinessException.class, () -> new MerchantOrderPosterController(orders).poster(9L, response));
            assertEquals(404, ex.getCode()); assertEquals("未找到已保存海报", ex.getMessage());
            assertEquals("no-store", response.getHeader("Cache-Control"));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidIdCannotReadPoster(Long id) {
        var orders = mock(TradeOrderMapper.class);
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            var ex = assertThrows(BusinessException.class, () -> new MerchantOrderPosterController(orders).poster(id, new MockHttpServletResponse()));
            assertEquals(400, ex.getCode()); verifyNoInteractions(orders);
        }
    }

    @Test
    void unboundMerchantNeverReadsPersistence() {
        var orders = mock(TradeOrderMapper.class);
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenThrow(new BusinessException(403, "未绑定店铺"));
            assertThrows(BusinessException.class, () -> new MerchantOrderPosterController(orders).poster(9L, new MockHttpServletResponse()));
            verifyNoInteractions(orders);
        }
    }
}
