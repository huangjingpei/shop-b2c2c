/*
 * 文件：BuyerOrderSecurityTest.java
 * 用途：买家领取请求、白名单响应、限流与认证先后顺序的纯单元测试。
 * 维护注意：用Mock仓储/Redis证明拒绝路径不读交付内容；不连接业务库，Lua真实Redis部署还需环境回归。
 */
package com.shop.b2b2c;
import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.dto.*;
import com.shop.b2b2c.entity.*;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuyerOrderSecurityTest {
    @Test
    void genericApiErrorsDoNotReturnInternalExceptionText() {
        var handler = new com.shop.b2b2c.common.GlobalExceptionHandler();
        assertEquals(400, handler.handleUnreadableBody().getCode());
        var response = handler.handleException(new IllegalStateException("private-internal-error"));
        assertEquals(500, response.getCode());
        assertFalse(response.getMessage().contains("private-internal-error"));
    }
    @ParameterizedTest
    @CsvSource({"2,COMPLETED,true,false", "3,COMPLETED,false,true", "1,COMPLETED,false,false",
            "2,PAID,false,false", "3,PAID,false,false", "2,REFUNDED,false,false", "3,UNPAID,false,false"})
    void deliveryVisibilityDependsOnTypeAndCompletion(int type, String status, boolean card, boolean service) {
        var order = new TradeOrder(); order.setGoodsType(type); order.setOrderStatus(status);
        order.setServiceVoucher("private-service"); order.setQueryToken("private-receipt");
        order.setPeerPayToken("public-pay"); order.setPeerPayerId("private-payer"); order.setPayTradeNo("private-trade");
        var item = new TradeOrderItem(); item.setCardDetails("private-card");
        var view = BuyerOrderView.from(order, List.of(item), null);
        assertEquals(card ? "private-card" : null, view.items().get(0).cardDetails());
        assertEquals(service ? "private-service" : null, view.order().serviceVoucher());
        String json = new ObjectMapper().valueToTree(view).toString();
        for (String field : List.of("queryToken", "peerPayToken", "peerPayerId", "payTradeNo", "commissionAmount")) {
            assertFalse(json.contains('"' + field + '"'));
        }
    }

    @Test
    void receiptNormalizationAndTokenComparisonFailClosed() {
        assertEquals(new BuyerLookupRequest("ORD", "buyer", "secret"), new BuyerLookupRequest(" ORD ", " buyer ", " secret ").normalized());
        assertTrue(BuyerLookupRequest.matches("secret", "secret"));
        assertFalse(BuyerLookupRequest.matches("secret", "SECRET"));
        assertFalse(BuyerLookupRequest.matches(null, "secret"));
        assertFalse(BuyerLookupRequest.matches("", ""));
        assertThrows(BusinessException.class, () -> new BuyerLookupRequest("ORD", "buyer", null).normalized());
        assertThrows(BusinessException.class, () -> new BuyerLookupRequest("ORD", "buyer", "x".repeat(65)).normalized());
        assertThrows(BusinessException.class, () -> new BuyerLookupRequest("ÓRD", "buyer", "secret").normalized());
        assertThrows(BusinessException.class, () -> new BuyerLookupRequest("ORD x", "buyer", "secret").normalized());
    }

    @Test
    void wrongTokenAndMissingOrderHaveSameErrorAndNeverReadDelivery() {
        var orders = mock(TradeOrderMapper.class); var items = mock(TradeOrderItemMapper.class);
        var stores = mock(MerchantStoreMapper.class); var limiter = mock(OrderQueryLimiter.class);
        var service = new BuyerOrderQueryService(orders, items, stores, limiter);
        var order = new TradeOrder(); order.setQueryToken("correct-secret");
        when(orders.findByOrderSnAndBuyerContact("ORD", "buyer")).thenReturn(Optional.of(order));
        var request = new BuyerLookupRequest("ORD", "buyer", "wrong-secret");
        var wrong = assertThrows(BusinessException.class, () -> service.lookup(request, "127.0.0.1"));
        when(orders.findByOrderSnAndBuyerContact("ORD", "buyer")).thenReturn(Optional.empty());
        var missing = assertThrows(BusinessException.class, () -> service.lookup(request, "127.0.0.1"));
        assertEquals(wrong.getCode(), missing.getCode()); assertEquals(wrong.getMessage(), missing.getMessage());
        verifyNoInteractions(items, stores);
    }

    @Test
    void deniedRateLimitNeverQueriesDatabase() {
        var orders = mock(TradeOrderMapper.class); var items = mock(TradeOrderItemMapper.class); var stores = mock(MerchantStoreMapper.class);
        var limiter = mock(OrderQueryLimiter.class); doThrow(new BusinessException(503, "unavailable")).when(limiter).check(any(), any());
        var service = new BuyerOrderQueryService(orders, items, stores, limiter);
        assertThrows(BusinessException.class, () -> service.lookup(new BuyerLookupRequest("ORD", "buyer", "secret"), "127.0.0.1"));
        verifyNoInteractions(orders, items, stores);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs={0})
    void limiterRejectsNullAndDeniedRedisResponses(Long result) {
        var redis = mock(StringRedisTemplate.class);
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(result);
        var error = assertThrows(BusinessException.class, () -> new OrderQueryLimiter(redis).check("ORD", "127.0.0.1"));
        assertEquals(result == null ? 503 : 429, error.getCode());
    }

    @Test
    void redisFailureDoesNotAllowLookup() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenThrow(new IllegalStateException("internal credential must not escape"));
        var error = assertThrows(BusinessException.class, () -> new OrderQueryLimiter(redis).check("ORD", "127.0.0.1"));
        assertEquals(503, error.getCode()); assertFalse(error.getMessage().contains("credential"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void limiterUsesHashedStableBucketsAndFiveMinuteAtomicWindow() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(1L);
        var limiter = new OrderQueryLimiter(redis); limiter.check("ORD-private", "192.0.2.1"); limiter.check("ord-PRIVATE", "192.0.2.1");
        var keys = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis, times(4)).execute(any(), keys.capture(), any(), eq("300"));
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(2));
        assertEquals(keys.getAllValues().get(1), keys.getAllValues().get(3));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        assertFalse(keys.getAllValues().toString().contains("ORD-private"));
        assertFalse(keys.getAllValues().toString().contains("192.0.2.1"));
    }
}
