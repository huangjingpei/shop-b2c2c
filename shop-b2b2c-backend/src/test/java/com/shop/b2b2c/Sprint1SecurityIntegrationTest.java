package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.controller.OrderController;
import com.shop.b2b2c.controller.ProductController;
import com.shop.b2b2c.dto.PeerPayDTOs;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeOrderItem;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.PeerPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real mapper SQL and Spring transactions; no external MySQL/Redis needed. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sprint1;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.continue-on-error=false",
        "spring.sql.init.schema-locations=classpath:sprint1-schema.sql",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "payment.mock=true", "inventory.expiry-delay-ms=3600000"
})
@AutoConfigureMockMvc
class Sprint1SecurityIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PeerPayService payments;
    @Autowired com.shop.b2b2c.config.ReservationExpiryJob expiryJob;
    @Autowired TradeOrderMapper orders;
    @Autowired TradeOrderItemMapper items;
    @Autowired ProductSkuMapper skus;
    @Autowired ProductController products;
    @Autowired OrderController orderController;
    @SpyBean ProductVirtualCardMapper cards;
    @MockBean StringRedisTemplate redis;

    @BeforeEach
    void fixtures() {
        for (String table : List.of("inventory_reservation", "trade_order_item", "trade_order", "product_virtual_card", "product_sku", "product", "merchant_store")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (int id = 1; id <= 2; id++) {
            jdbc.update("INSERT INTO merchant_store(id,store_code,store_name,owner_name,id_card_number,contact_phone) VALUES(?,?,?,?,?,?)",
                    id, "S" + id, "store" + id, "owner", "redacted", "12345678");
            jdbc.update("INSERT INTO product(id,merchant_id,category_id,title,goods_type,main_image,min_price) VALUES(?,?,1,'goods',2,'image',10)", id, id);
            jdbc.update("INSERT INTO product_sku(id,merchant_id,product_id,sku_code,spec_data,price,stock_quantity) VALUES(?,?,?,?, '{}',10,1)",
                    id, id, id, "SKU" + id);
        }
        jdbc.update("INSERT INTO product_virtual_card(id,merchant_id,product_id,sku_id,card_content,status) VALUES(1,1,1,1,'secret',0)");
    }

    private TradeOrder unpaid(String token, int quantity) {
        TradeOrder order = new TradeOrder();
        order.setOrderSn("ORD-" + token);
        order.setMerchantId(1L);
        order.setBuyerContact("buyer@example.com");
        order.setQueryToken("query-" + token);
        order.setPeerPayToken(token);
        order.setGoodsType(2);
        order.setTotalAmount(java.math.BigDecimal.TEN);
        order.setPayAmount(java.math.BigDecimal.TEN);
        order.setPeerStatus("RESERVED_LOCK");
        order.setLockExpireTime(LocalDateTime.now().plusMinutes(5));
        orders.save(order);
        TradeOrderItem item = new TradeOrderItem();
        item.setOrderId(order.getId());
        item.setOrderSn(order.getOrderSn());
        item.setProductId(1L);
        item.setSkuId(1L);
        item.setSkuCode("SKU1");
        item.setProductTitle("goods");
        item.setQuantity(quantity);
        item.setPrice(java.math.BigDecimal.TEN);
        item.setSubtotal(java.math.BigDecimal.TEN);
        items.save(item);
        return order;
    }

    private TradeOrder pay(String token) {
        return payments.notifyPaymentSuccess(token, "verified-payer", "trade-" + token, "WECHAT");
    }

    private TradeOrder intent(String token) {
        TradeOrder order = unpaid(token, 1);
        order.setPeerStatus("INIT");
        orders.save(order);
        return order;
    }

    @Test
    void reservationDeductsOnceAndOnlyOwnerCanRelease() {
        intent("reserve");
        var lock = payments.preLockInventory("reserve");
        assertEquals(0, skus.selectById(1).getStockQuantity());
        assertThrows(BusinessException.class, () -> payments.cancelPreLock("reserve", "wrong-owner"));
        assertEquals(0, skus.selectById(1).getStockQuantity());
        payments.cancelPreLock("reserve", lock.getOwnerToken());
        payments.cancelPreLock("reserve", lock.getOwnerToken());
        assertEquals(1, skus.selectById(1).getStockQuantity());
        var next = payments.preLockInventory("reserve");
        assertNotEquals(lock.getOwnerToken(), next.getOwnerToken());
        assertThrows(BusinessException.class, () -> payments.cancelPreLock("reserve", lock.getOwnerToken()));
    }

    @Test
    void lastUnitCanOnlyBeReservedByOneOrder() throws Exception {
        intent("hold-one"); intent("hold-two");
        AtomicInteger winners = new AtomicInteger();
        race(2, i -> {
            try { payments.preLockInventory(i == 0 ? "hold-one" : "hold-two"); winners.incrementAndGet(); }
            catch (BusinessException expected) { }
        });
        assertEquals(1, winners.get());
        assertEquals(0, skus.selectById(1).getStockQuantity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE status='HELD'", Integer.class));
    }

    @Test
    void reservedPaymentConsumesHoldWithoutSecondDeductionOrLaterRelease() {
        TradeOrder order = intent("consume");
        var lock = payments.preLockInventory("consume");
        assertEquals("COMPLETED", pay("consume").getOrderStatus());
        payments.cancelPreLock("consume", lock.getOwnerToken());
        payments.expireReservation(order.getId());
        assertEquals(0, skus.selectById(1).getStockQuantity());
        assertEquals("CONSUMED", jdbc.queryForObject("SELECT status FROM inventory_reservation WHERE order_id=?", String.class, order.getId()));
    }

    @Test
    void expiredReservationReleasesOnceAndCannotBePaidOrReservedAgain() {
        TradeOrder order = intent("timeout");
        payments.preLockInventory("timeout");
        jdbc.update("UPDATE trade_order SET lock_expire_time=? WHERE id=?", LocalDateTime.now().minusMinutes(1), order.getId());
        jdbc.update("UPDATE inventory_reservation SET expires_at=? WHERE order_id=?", LocalDateTime.now().minusMinutes(1), order.getId());
        expiryJob.expire();
        expiryJob.expire();
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals("CLOSED", orders.selectById(order.getId()).getOrderStatus());
        assertThrows(BusinessException.class, () -> pay("timeout"));
        assertThrows(BusinessException.class, () -> payments.preLockInventory("timeout"));
    }

    @Test
    void reservedPaymentFailureRestoresHoldAndKeepsStockUnavailable() {
        TradeOrder order = intent("hold-rollback");
        payments.preLockInventory("hold-rollback");
        doReturn(0).when(cards).claimCard(anyLong(), anyLong(), anyLong(), anyString());
        assertThrows(BusinessException.class, () -> pay("hold-rollback"));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        assertEquals("HELD", jdbc.queryForObject("SELECT status FROM inventory_reservation WHERE order_id=?", String.class, order.getId()));
        assertEquals(0, skus.selectById(1).getStockQuantity());
    }

    @Test
    void staleOrCancelledIntentsCannotAcquireStock() {
        TradeOrder order = intent("stale");
        jdbc.update("UPDATE trade_order SET created_at=? WHERE id=?", LocalDateTime.now().minusHours(1), order.getId());
        assertThrows(BusinessException.class, () -> payments.preLockInventory("stale"));
        order.setPeerStatus("CANCELLED"); orders.save(order);
        assertThrows(BusinessException.class, () -> payments.preLockInventory("stale"));
        assertEquals(1, skus.selectById(1).getStockQuantity());
    }

    @Test
    void checkoutOnlyCreatesUnpaidOrderAndDoesNotDeliver() throws Exception {
        mvc.perform(post("/api/v1/public/orders/checkout").contentType("application/json")
                        .content("{\"merchantId\":1,\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"buyer@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("UNPAID"))
                .andExpect(jsonPath("$.data.cards").isEmpty()).andExpect(jsonPath("$.data.paymentAvailable").value(false));
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(0, cards.selectById(1).getStatus());
        assertNull(orders.selectList(null).get(0).getPayTradeNo());
    }

    @Test
    void publicMockRouteIsAbsentEvenWhenLegacyFlagIsTrue() throws Exception {
        mvc.perform(post("/api/v1/peer-pay/mock-pay/anything").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void checkoutRejectsCrossProductSkuWithoutSideEffects() throws Exception {
        mvc.perform(post("/api/v1/public/orders/checkout").contentType("application/json")
                        .content("{\"productId\":1,\"skuId\":2,\"quantity\":1,\"buyerContact\":\"buyer\"}"))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals(0, orders.selectCount(null));
        assertEquals(1, skus.selectById(2).getStockQuantity());
    }

    @Test
    void buyerAndPushOrdersRejectMismatchedSku() {
        PeerPayDTOs.BuyerCreateRequest buyer = new PeerPayDTOs.BuyerCreateRequest();
        buyer.setProductId(1L); buyer.setSkuId(2L); buyer.setQuantity(1); buyer.setBuyerContact("buyer");
        assertThrows(BusinessException.class, () -> payments.createBuyerPeerPay(buyer));
        PeerPayDTOs.PushPayCreateRequest push = new PeerPayDTOs.PushPayCreateRequest();
        push.setProductId(1L); push.setSkuId(2L); push.setQuantity(1);
        assertThrows(BusinessException.class, () -> payments.createPushPayOrder(push, 1L));
        assertEquals(0, orders.selectCount(null));
    }

    @Test
    void concurrentOrdersCannotOversellLastUnit() throws Exception {
        unpaid("one", 1); unpaid("two", 1);
        AtomicInteger completed = new AtomicInteger();
        race(2, i -> {
            try { pay(i == 0 ? "one" : "two"); completed.incrementAndGet(); }
            catch (BusinessException expected) { /* loser remains unpaid */ }
        });
        assertEquals(1, completed.get());
        assertEquals(0, skus.selectById(1).getStockQuantity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE order_status='UNPAID'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM product_virtual_card WHERE status=1", Integer.class));
    }

    @Test
    void concurrentNotificationsFulfillExactlyOnce() throws Exception {
        unpaid("replay", 1);
        race(8, i -> pay("replay"));
        assertEquals(0, skus.selectById(1).getStockQuantity());
        assertEquals("COMPLETED", orders.findByPeerPayToken("replay").orElseThrow().getOrderStatus());
        verify(cards, times(1)).claimCard(anyLong(), anyLong(), anyLong(), anyString());
        assertEquals("ORD-replay", cards.selectById(1).getOrderSn());
    }

    @Test
    void concurrentOrdersCannotAssignTheSameCardEvenWhenSkuStockIsHigher() throws Exception {
        jdbc.update("UPDATE product_sku SET stock_quantity=2 WHERE id=1");
        unpaid("card-one", 1); unpaid("card-two", 1);
        race(2, i -> pay(i == 0 ? "card-one" : "card-two"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order_item WHERE card_details IS NOT NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE order_status='COMPLETED'", Integer.class));
        assertTrue(List.of("ORD-card-one", "ORD-card-two").contains(cards.selectById(1).getOrderSn()));
    }

    @Test
    void unboundAccountCannotUseMerchantAssetEndpoints() {
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class, CALLS_REAL_METHODS)) {
            security.when(SecurityUtils::getCurrentMerchantId).thenReturn(null);
            assertThrows(BusinessException.class, () -> products.listCards(null));
            assertThrows(BusinessException.class, products::listMerchantProducts);
            assertThrows(BusinessException.class, orderController::listMerchantOrders);
            assertThrows(BusinessException.class, () -> products.createProduct(new ProductController.CreateProductDTO()));
        }
    }

    @Test
    void cardClaimFailureRollsBackPaymentAndStock() {
        TradeOrder order = unpaid("rollback", 1);
        doReturn(0).when(cards).claimCard(anyLong(), anyLong(), anyLong(), anyString());
        assertThrows(BusinessException.class, () -> pay("rollback"));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(0, cards.selectById(1).getStatus());
        assertNull(items.findByOrderSn(order.getOrderSn()).get(0).getCardDetails());
        verify(redis, never()).delete(anyString());
    }

    @Test
    void shippedReplayDoesNotDeductOrRevertState() {
        TradeOrder order = unpaid("shipped", 1);
        order.setOrderStatus("SHIPPED"); orders.save(order);
        assertEquals("SHIPPED", pay("shipped").getOrderStatus());
        assertEquals(1, skus.selectById(1).getStockQuantity());
        verify(cards, never()).claimCard(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void expiredOrUnreservedPaymentIsRejected() {
        TradeOrder order = unpaid("expired", 1);
        order.setLockExpireTime(LocalDateTime.now().minusMinutes(1)); orders.save(order);
        assertThrows(BusinessException.class, () -> pay("expired"));
        order.setPeerStatus("INIT"); order.setLockExpireTime(LocalDateTime.now().plusMinutes(5)); orders.save(order);
        assertThrows(BusinessException.class, () -> pay("expired"));
        assertEquals(1, skus.selectById(1).getStockQuantity());
    }

    @Test
    void crossTenantDeliveryIsRejectedAndOrderUnchanged() {
        TradeOrder order = unpaid("delivery", 1);
        order.setMerchantId(2L); order.setGoodsType(1); order.setOrderStatus("PAID"); orders.save(order);
        OrderController.DeliverDTO dto = new OrderController.DeliverDTO();
        dto.setLogisticsCompany("carrier"); dto.setTrackingNumber("tracking");
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            assertThrows(BusinessException.class, () -> orderController.deliverOrder(order.getId(), dto));
        }
        assertEquals("PAID", orders.selectById(order.getId()).getOrderStatus());
        assertNull(orders.selectById(order.getId()).getTrackingNumber());
    }

    @Test
    void merchantCanDeliverOwnPhysicalOrderButNotVirtualOrder() {
        TradeOrder order = unpaid("own", 1);
        order.setOrderStatus("PAID"); orders.save(order);
        OrderController.DeliverDTO dto = new OrderController.DeliverDTO();
        dto.setLogisticsCompany("carrier"); dto.setTrackingNumber("tracking");
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            assertThrows(BusinessException.class, () -> orderController.deliverOrder(order.getId(), dto));
            order.setGoodsType(1); orders.save(order);
            orderController.deliverOrder(order.getId(), dto);
        }
        assertEquals("SHIPPED", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    void cardReadAndImportCannotCrossTenantOrProductBoundary() {
        ProductController.BatchImportCardDTO dto = new ProductController.BatchImportCardDTO();
        dto.setProductId(2L); dto.setSkuId(2L); dto.setCardContents(List.of("foreign"));
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            assertThrows(BusinessException.class, () -> products.listCards(2L));
            assertThrows(BusinessException.class, () -> products.importCards(dto));
            dto.setProductId(1L);
            assertThrows(BusinessException.class, () -> products.importCards(dto));
            assertEquals(1, products.listCards(1L).getData().size());
        }
        assertEquals(1, cards.selectCount(null));
        assertEquals(1, skus.selectById(2).getStockQuantity());
    }

    @Test
    void ownCardImportAddsInventoryAndUsesSessionTenant() {
        ProductController.BatchImportCardDTO dto = new ProductController.BatchImportCardDTO();
        dto.setProductId(1L); dto.setSkuId(1L); dto.setCardContents(List.of("new-secret"));
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            products.importCards(dto);
        }
        assertEquals(2, skus.selectById(1).getStockQuantity());
        assertEquals(2, cards.findByMerchantId(1L).size());
    }

    @Test
    void conditionalUpdatesRejectWrongOwnerAndRepeatClaims() {
        assertEquals(0, skus.deductStock(1L, 1L, 2L, 1));
        assertEquals(0, skus.deductStock(1L, 2L, 1L, 1));
        assertEquals(0, skus.deductStock(1L, 1L, 1L, -1));
        assertEquals(0, cards.claimCard(1L, 2L, 1L, "wrong-owner"));
        assertEquals(1, cards.claimCard(1L, 1L, 1L, "winner"));
        assertEquals(0, cards.claimCard(1L, 1L, 1L, "loser"));
        assertEquals("winner", cards.selectById(1).getOrderSn());
    }

    private void race(int count, java.util.function.IntConsumer action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    try { start.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    action.accept(index);
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
