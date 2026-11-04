/*
 * 文件：Sprint1SecurityIntegrationTest.java
 * 用途：跨Sprint累计的建单幂等、商品/库存/卡密批次、状态统计、就绪探针、海报减载及租户安全集成回归。
 * 维护注意：名称保留Sprint1但覆盖后续修复；H2独立内存库、Mock Redis，Sa-Token使用仅测试注入的内存DAO验证真实令牌生命周期；认证限流计数模拟不等于真实Redis验收。并发测试不能加测试级外层事务掩盖真实提交；调整模型时同步隔离结构，不连接业务数据库。
 */
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
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.shop.b2b2c.entity.MerchantStore;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real mapper SQL and Spring transactions; no external MySQL/Redis needed. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sprint1;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.continue-on-error=false",
        "spring.sql.init.schema-locations=classpath:sprint1-schema.sql",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "payment.mock=true", "inventory.expiry-delay-ms=3600000",
        "app.security.cors-allowed-origins=https://admin.example.test",
        "app.security.login-account-limit=3", "app.security.login-ip-limit=5"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(Sprint1SecurityIntegrationTest.SessionTestConfig.class)
class Sprint1SecurityIntegrationTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class SessionTestConfig {
        @org.springframework.context.annotation.Bean(destroyMethod = "destroy")
        @org.springframework.context.annotation.Primary
        cn.dev33.satoken.dao.SaTokenDao isolatedTokenDao() {
            return new cn.dev33.satoken.dao.SaTokenDaoDefaultImpl();
        }
    }
    private static final String AUTH_PASSWORD = "isolated-auth-test-password";
    private static final String AUTH_HASH = cn.dev33.satoken.secure.BCrypt.hashpw(AUTH_PASSWORD);
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PeerPayService payments;
    @Autowired com.shop.b2b2c.service.OrderIdempotencyService idempotency;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.shop.b2b2c.config.ReservationExpiryJob expiryJob;
    @Autowired TradeOrderMapper orders;
    @Autowired TradeOrderItemMapper items;
    @SpyBean ProductSkuMapper skus;
    @Autowired ProductController products;
    @Autowired OrderController orderController;
    @SpyBean ProductVirtualCardMapper cards;
    @SpyBean ProductEditingMapper editingMapper;
    @Autowired com.shop.b2b2c.service.ProductEditingService editing;
    @SpyBean InventoryAdjustmentMapper adjustmentsMapper;
    @Autowired com.shop.b2b2c.service.InventoryAdjustmentService adjustments;
    @SpyBean CardAssetMapper cardAssetsMapper;
    @Autowired com.shop.b2b2c.service.CardAssetService cardAssets;
    @SpyBean CardImportBatchMapper cardBatches;
    @Autowired com.shop.b2b2c.service.CardImportBatchService batchQueries;
    @MockBean StringRedisTemplate redis;
    @SpyBean com.shop.b2b2c.service.ReadinessService readiness;
    @Autowired MerchantStoreMapper stores;
    @Autowired com.shop.b2b2c.service.PaymentEventService paymentEventService;
    @Autowired com.shop.b2b2c.mapper.PaymentEventMapper paymentEvents;

    @BeforeEach
    void fixtures() {
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(1L);
        for (String table : List.of("payment_event", "card_import_item", "card_import_batch", "virtual_card_void", "inventory_adjustment", "sys_user", "order_idempotency", "inventory_reservation", "trade_order_item", "trade_order", "product_virtual_card", "product_sku", "product", "merchant_store")) {
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
        jdbc.update("MERGE INTO category(id,parent_id,name,sort_order) KEY(id) VALUES(1,0,'standard',0)");
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthSessions() {
        cn.dev33.satoken.stp.StpUtil.logout(200L);
    }

    private com.shop.b2b2c.dto.ProductEditingDTOs.Update productEdit(String revision, String title, String price) {
        jdbc.update("MERGE INTO category(id,parent_id,name,sort_order) KEY(id) VALUES(1,0,'standard',0)");
        return new com.shop.b2b2c.dto.ProductEditingDTOs.Update(revision, 1L, title, null, "updated-image", null,
                List.of(new com.shop.b2b2c.dto.ProductEditingDTOs.Price(1L, new java.math.BigDecimal(price))));
    }

    private void publicCatalogFixture() {
        for (int id = 3; id <= 7; id++) {
            long merchant = id == 4 ? 2L : id == 6 ? 999L : 1L;
            int category = id == 3 || id == 4 ? 2 : 1;
            jdbc.update("INSERT INTO product(id,merchant_id,category_id,title,goods_type,main_image,min_price,status) VALUES(?,?,?,?,1,'image',12.30,?)",
                    id, merchant, category, "catalog-" + id, id == 5 ? 0 : 1);
        }
    }

    @Test
    void orderListsAndStatisticsDoNotLoadPosterButStoredContentIsRetained() throws Exception {
        TradeOrder order = intent("poster-list");
        String largePoster = "POSTER-MARKER-".repeat(10000);
        order.setPosterUrl(largePoster); orders.save(order);
        assertNull(orders.findByMerchantIdOrderByCreatedAtDesc(1L).get(0).getPosterUrl());
        assertNull(orders.findAllByOrderByCreatedAtDesc().get(0).getPosterUrl());
        assertEquals(largePoster, orders.findById(order.getId()).orElseThrow().getPosterUrl());
        assertEquals(largePoster, orders.findStoredPoster(order.getId(), 1L));
        assertNull(orders.findStoredPoster(order.getId(), 2L));
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        String body = mvc.perform(get("/api/v1/merchant/orders").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data[0].order.posterUrl").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("POSTER-MARKER")); assertTrue(body.length() < largePoster.length());
        mvc.perform(get("/api/v1/merchant/orders/statistics").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.totalOrders").value(1)).andExpect(jsonPath("$.data.pendingOrders").value(1));
    }

    @Test
    void storedPosterReadIsAuthenticatedAndScopedWithoutRegeneration() throws Exception {
        TradeOrder own = intent("poster-own"); own.setPeerPayType(1);
        own.setPosterUrl("data:image/png;base64,aW1hZ2U="); orders.save(own);
        TradeOrder other = intent("poster-other"); other.setMerchantId(2L); other.setPosterUrl("other-private-poster"); orders.save(other);
        TradeOrder empty = intent("poster-empty");
        String ownPath = "/api/v1/merchant/orders/" + own.getId() + "/poster";
        mvc.perform(get(ownPath)).andExpect(status().isUnauthorized());
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        mvc.perform(get(ownPath).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200)).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data").value("data:image/png;base64,aW1hZ2U="));
        for (Long id : List.of(other.getId(), empty.getId(), Long.MAX_VALUE)) {
            mvc.perform(get("/api/v1/merchant/orders/" + id + "/poster").param("merchantId", "2")
                    .header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.code").value(404)).andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        assertEquals("INIT", orders.findById(own.getId()).orElseThrow().getPeerStatus());
        assertEquals("data:image/png;base64,aW1hZ2U=", orders.findById(own.getId()).orElseThrow().getPosterUrl());
        assertEquals(1, skus.selectById(1L).getStockQuantity());
    }

    @Test
    void unboundAdministratorCannotReadAnyMerchantPoster() throws Exception {
        TradeOrder order = intent("poster-unbound");
        authUser("SUPERADMIN", null); String token = loginToken();
        mvc.perform(get("/api/v1/merchant/orders/" + order.getId() + "/poster").param("merchantId", "1")
                .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void readinessIsAnonymousUsesHttp503AndDoesNotChangeLiveness() throws Exception {
        doReturn(new com.shop.b2b2c.service.ReadinessService.Report("UP", "UP", "UP")).when(readiness).check();
        mvc.perform(get("/api/v1/auth/ready"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.status").value("UP"));
        doReturn(new com.shop.b2b2c.service.ReadinessService.Report("DOWN", "UP", "DOWN")).when(readiness).check();
        mvc.perform(get("/api/v1/auth/ready"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(503)).andExpect(jsonPath("$.data.status").value("DOWN"))
                .andExpect(jsonPath("$.data.redis").value("DOWN"))
                .andExpect(jsonPath("$.data.message").doesNotExist()).andExpect(jsonPath("$.data.password").doesNotExist());
        mvc.perform(get("/api/v1/auth/health"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void readinessExecutesIsolatedDatabaseCheckAndFailsWhenRedisCannotBeConfirmed() {
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.RedisCallback<Boolean>>any())).thenReturn(true);
        try (var probe = new com.shop.b2b2c.service.ReadinessService(jdbc, redis)) {
            assertTrue(probe.check().ready());
        }
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.RedisCallback<Boolean>>any())).thenReturn(null);
        try (var probe = new com.shop.b2b2c.service.ReadinessService(jdbc, redis)) {
            assertEquals(new com.shop.b2b2c.service.ReadinessService.Report("DOWN", "UP", "DOWN"), probe.check());
        }
    }

    @Test
    void serviceFeeCodesRoundTripWithoutChangingOperatingStatus() throws Exception {
        authUser("SUPERADMIN", null); String token = loginToken();
        for (String code : List.of("NORMAL", "TRIAL", "OVERDUE", "EXEMPTED", "ARREARS")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/merchants/1/service-fee")
                    .header("Authorization", "Bearer " + token).contentType("application/json")
                    .content(json.writeValueAsString(java.util.Map.of("feeStatus", code, "monthlyFee", 0, "feeFreeUntil", "2028-02-29"))))
                    .andExpect(jsonPath("$.code").value(200));
            assertEquals(code, jdbc.queryForObject("SELECT fee_status FROM merchant_store WHERE id=1", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT status FROM merchant_store WHERE id=1", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT monthly_fee FROM merchant_store WHERE id=1", java.math.BigDecimal.class).signum());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/merchants/1/service-fee")
                .header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"feeStatus\":\"UNKNOWN\",\"monthlyFee\":99}"))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals("ARREARS", jdbc.queryForObject("SELECT fee_status FROM merchant_store WHERE id=1", String.class));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/merchants/1/service-fee")
                .header("Authorization", "Bearer " + token).contentType("application/json").content("{\"monthlyFee\":10}"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("ARREARS", jdbc.queryForObject("SELECT fee_status FROM merchant_store WHERE id=1", String.class));
        assertEquals(java.time.LocalDate.of(2028, 2, 29), jdbc.queryForObject("SELECT fee_free_until FROM merchant_store WHERE id=1", java.sql.Timestamp.class).toLocalDateTime().toLocalDate());
    }

    @Test
    void merchantCannotChangeServiceFeeEvenWithAnotherStoreId() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/merchants/2/service-fee")
                .contentType("application/json").content("{\"feeStatus\":\"EXEMPTED\"}"))
                .andExpect(status().isUnauthorized());
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/merchants/2/service-fee")
                .header("Authorization", "Bearer " + token).contentType("application/json").content("{\"feeStatus\":\"EXEMPTED\"}"))
                .andExpect(status().isForbidden());
        assertEquals("NORMAL", jdbc.queryForObject("SELECT fee_status FROM merchant_store WHERE id=2", String.class));
    }

    @Test
    void defaultPeerStatusSurvivesMapperPersistenceAndMatchesDatabaseDefault() {
        var order = new TradeOrder();
        order.setOrderSn("STATE-ENTITY"); order.setMerchantId(1L); order.setBuyerContact("fixture-contact");
        order.setQueryToken("state-query"); order.setTotalAmount(java.math.BigDecimal.TEN);
        order.setPayAmount(java.math.BigDecimal.TEN);
        orders.save(order);
        assertEquals("INIT", orders.findById(order.getId()).orElseThrow().getPeerStatus());
        jdbc.update("INSERT INTO trade_order(order_sn,merchant_id,buyer_contact,query_token,total_amount,pay_amount) VALUES('STATE-SQL',1,'fixture-contact','sql-query',10,10)");
        assertEquals("INIT", orders.findByOrderSn("STATE-SQL").orElseThrow().getPeerStatus());
    }

    @Test
    void peerStateContractCoversHoldReleaseReholdAndPaidResponse() throws Exception {
        TradeOrder order = intent("state-flow");
        mvc.perform(get("/api/v1/peer-pay/detail/state-flow"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.peerStatus").value("INIT"));
        var held = payments.preLockInventory("state-flow");
        assertEquals("RESERVED_LOCK", held.getPeerStatus());
        assertEquals("RESERVED_LOCK", orders.findById(order.getId()).orElseThrow().getPeerStatus());
        payments.cancelPreLock("state-flow", held.getOwnerToken());
        assertEquals("INIT", orders.findById(order.getId()).orElseThrow().getPeerStatus());
        payments.preLockInventory("state-flow");
        assertEquals("PAID", pay("state-flow").getPeerStatus());
        mvc.perform(get("/api/v1/peer-pay/detail/state-flow"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.peerStatus").value("PAID"))
                .andExpect(jsonPath("$.data.serviceVoucher").doesNotExist());
        assertEquals(0, skus.selectById(1L).getStockQuantity());
    }

    @Test
    void legacyAndUnknownPeerStatesCannotBecomePaymentReservations() {
        for (String state : List.of("INTENT", "LOCKED", "UNKNOWN", "EXPIRED", "CANCELLED")) {
            String token = "state-" + state;
            TradeOrder order = unpaid(token, 1); order.setPeerStatus(state); orders.save(order);
            assertThrows(BusinessException.class, () -> payments.preLockInventory(token));
            assertThrows(BusinessException.class, () -> pay(token));
            var stored = orders.findById(order.getId()).orElseThrow();
            assertEquals(state, stored.getPeerStatus()); assertEquals("UNPAID", stored.getOrderStatus());
            assertEquals(1, skus.selectById(1L).getStockQuantity());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE order_id=?", Integer.class, order.getId()));
            assertEquals(0, cards.selectById(1L).getStatus());
        }
    }

    @Test
    void publicCatalogCombinesFiltersAndPreservesStableDisplayOrder() throws Exception {
        publicCatalogFixture();
        assertEquals(List.of(1L, 2L, 3L, 4L, 7L), products.listPublicProducts(null, null).getData().stream()
                .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertEquals(List.of(3L, 4L), products.listPublicProducts(2L, null).getData().stream()
                .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertEquals(List.of(1L, 3L, 7L), products.listPublicProducts(null, 1L).getData().stream()
                .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertEquals(List.of(3L), products.listPublicProducts(2L, 1L).getData().stream()
                .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertTrue(products.listPublicProducts(999L, 1L).getData().isEmpty());
        assertTrue(products.listPublicProducts(null, 999L).getData().isEmpty());
        jdbc.update("UPDATE product SET sort_order=-1 WHERE id=3");
        mvc.perform(get("/api/v1/public/products").param("merchantId", "1"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data[0].id").value(3))
                .andExpect(jsonPath("$.data[0].minPrice").value(12.30))
                .andExpect(jsonPath("$.data[0].storeName").value("store1"))
                .andExpect(jsonPath("$.data[0].totalStock").value(0))
                .andExpect(jsonPath("$.data[0].subTitle").isEmpty())
                .andExpect(jsonPath("$.data[0].serviceDesc").doesNotExist())
                .andExpect(jsonPath("$.data[0].ownerName").doesNotExist());
        mvc.perform(get("/api/v1/public/products").param("categoryId", "0"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void publicCatalogStockUsesOnlyOwnedSkusAndDoesNotOverflowInt() {
        publicCatalogFixture();
        jdbc.update("INSERT INTO product_sku(id,merchant_id,product_id,sku_code,spec_data,price,stock_quantity) VALUES(10,1,1,'LARGE','{}',10,2147483647)");
        jdbc.update("INSERT INTO product_sku(id,merchant_id,product_id,sku_code,spec_data,price,stock_quantity) VALUES(11,2,1,'BAD-OWNER','{}',10,500)");
        var rows = products.listPublicProducts(1L, 1L).getData();
        assertEquals(List.of(1L, 7L), rows.stream().map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertEquals(Long.valueOf(2147483648L), rows.get(0).getTotalStock());
        assertEquals(Long.valueOf(0L), rows.get(1).getTotalStock());
    }

    @Test
    void publicCatalogOmitsAllNonOperatingStoreStates() {
        for (int status : new int[] {0, 2, 3, 99}) {
            jdbc.update("UPDATE merchant_store SET status=? WHERE id=1", status);
            assertTrue(products.listPublicProducts(null, 1L).getData().isEmpty());
            assertEquals(List.of(2L), products.listPublicProducts(null, null).getData().stream()
                    .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        }
        jdbc.update("UPDATE merchant_store SET status=1 WHERE id=1");
        assertEquals(1, products.listPublicProducts(1L, 1L).getData().size());
    }

    @Test
    void publicEmptyStoreIsAnonymousAndUsesAllowlistThroughSecurityChain() throws Exception {
        jdbc.update("INSERT INTO merchant_store(id,store_code,store_name,owner_name,id_card_number,contact_phone) VALUES(?,?,?,?,?,?)",
                99L, "EMPTY99", "真实空店", "private-owner", "private-id", "public-contact");
        mvc.perform(get("/api/v1/public/stores/99"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.storeName").value("真实空店"))
                .andExpect(jsonPath("$.data.ownerName").doesNotExist())
                .andExpect(jsonPath("$.data.idCardNumber").doesNotExist());
        mvc.perform(get("/api/v1/public/products").param("merchantId", "99"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").isEmpty());
        jdbc.update("UPDATE merchant_store SET status=3 WHERE id=99");
        mvc.perform(get("/api/v1/public/stores/99"))
                .andExpect(jsonPath("$.code").value(404)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/v1/public/stores/999999"))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void merchantEditorEnforcesHttpAuthenticationAndTenantBoundary() throws Exception {
        mvc.perform(get("/api/v1/merchant/products/1/editor")).andExpect(status().isUnauthorized());
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        mvc.perform(get("/api/v1/merchant/products/1/editor").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.revision").isNotEmpty());
        mvc.perform(get("/api/v1/merchant/products/2/editor").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(404)).andExpect(jsonPath("$.data").isEmpty());
        var body = productEdit(editing.get(1L, 1L).revision(), "owned", "12.00");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/merchant/products/2")
                .header("Authorization", "Bearer " + token).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(404));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/merchant/products/2/status")
                .header("Authorization", "Bearer " + token).contentType("application/json")
                .content(json.writeValueAsString(new com.shop.b2b2c.dto.ProductEditingDTOs.Status(body.revision(), 0))))
                .andExpect(jsonPath("$.code").value(404));
        assertEquals("goods", jdbc.queryForObject("SELECT title FROM product WHERE id=2", String.class));
    }

    @Test
    void editorWritesWhitelistAndDerivesMinimumWithoutChangingInventoryOrOrders() throws Exception {
        TradeOrder order = unpaid("edit-history", 1);
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        var request = productEdit(editing.get(1L, 1L).revision(), "new-title", "12.50");
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(request);
        body.put("stockQuantity", 999); body.put("merchantId", 2); body.put("goodsType", 1); body.put("status", 0); body.put("minPrice", 1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("skus").get(0)).put("stockQuantity", 999);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/merchant/products/1")
                .header("Authorization", "Bearer " + token).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.product.title").value("new-title"))
                .andExpect(jsonPath("$.data.product.minPrice").value(12.5));
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM product WHERE id=1", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT goods_type FROM product WHERE id=1", Integer.class));
        assertEquals(1L, jdbc.queryForObject("SELECT merchant_id FROM product WHERE id=1", Long.class));
        assertEquals(0, orders.selectById(order.getId()).getPayAmount().compareTo(java.math.BigDecimal.TEN));
        assertEquals("goods", items.findByOrderSn(order.getOrderSn()).get(0).getProductTitle());
    }

    @Test
    void skuListMustExactlyMatchOwnedProductAndMinimumUsesAllPrices() {
        jdbc.update("INSERT INTO product_sku(id,merchant_id,product_id,sku_code,spec_data,price,stock_quantity) VALUES(3,1,1,'SKU3','{}',20,5)");
        String revision = editing.get(1L, 1L).revision();
        var partial = productEdit(revision, "partial", "9.00");
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.update(1L, 1L, partial)).getCode());
        var full = new com.shop.b2b2c.dto.ProductEditingDTOs.Update(revision, 1L, "complete", null, "image", null,
                List.of(new com.shop.b2b2c.dto.ProductEditingDTOs.Price(1L, new java.math.BigDecimal("9.00")),
                        new com.shop.b2b2c.dto.ProductEditingDTOs.Price(3L, new java.math.BigDecimal("7.00"))));
        var result = editing.update(1L, 1L, full);
        assertEquals(0, result.product().getMinPrice().compareTo(new java.math.BigDecimal("7.00")));
        assertEquals(5, skus.selectById(3).getStockQuantity());
        assertEquals(0, skus.selectById(2).getPrice().compareTo(java.math.BigDecimal.TEN));
    }

    @Test
    void editingDoesNotRestoreReservedStockAndDoesNotBlockOwnerRelease() {
        String revision = editing.get(1L, 1L).revision();
        intent("edit-reserved"); var lock = payments.preLockInventory("edit-reserved");
        editing.update(1L, 1L, productEdit(revision, "changed", "15.00"));
        assertEquals(0, skus.selectById(1).getStockQuantity());
        payments.cancelPreLock("edit-reserved", lock.getOwnerToken());
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(0, skus.selectById(1).getPrice().compareTo(new java.math.BigDecimal("15.00")));
    }

    @Test
    void editRollsBackAllSkuChangesWhenMetadataWriteFails() {
        var request = productEdit(editing.get(1L, 1L).revision(), "should-rollback", "15.00");
        doReturn(0).when(editingMapper).updateMetadata(any(), any(), any(), any());
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.update(1L, 1L, request)).getCode());
        assertEquals(0, skus.selectById(1).getPrice().compareTo(java.math.BigDecimal.TEN));
        assertEquals("goods", jdbc.queryForObject("SELECT title FROM product WHERE id=1", String.class));
    }

    @Test
    void concurrentEditsFromSameRevisionHaveExactlyOneWinner() throws Exception {
        String revision = editing.get(1L, 1L).revision();
        var first = productEdit(revision, "first", "11.00"); var second = productEdit(revision, "second", "12.00");
        AtomicInteger successes = new AtomicInteger(), conflicts = new AtomicInteger();
        race(2, i -> {
            try { editing.update(1L, 1L, i == 0 ? first : second); successes.incrementAndGet(); }
            catch (BusinessException e) { assertEquals(409, e.getCode()); conflicts.incrementAndGet(); }
        });
        assertEquals(1, successes.get()); assertEquals(1, conflicts.get());
        var saved = editing.get(1L, 1L);
        assertEquals(saved.product().getTitle().equals("first") ? "11" : "12", saved.skus().get(0).getPrice().stripTrailingZeros().toPlainString());
    }

    @Test
    void offSaleHidesPublicDetailAndOnSaleRestoresItWithoutUnbanning() throws Exception {
        var current = editing.get(1L, 1L);
        var off = editing.changeStatus(1L, 1L, new com.shop.b2b2c.dto.ProductEditingDTOs.Status(current.revision(), 0));
        mvc.perform(get("/api/v1/public/products/1")).andExpect(jsonPath("$.code").value(400));
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.changeStatus(1L, 1L,
                new com.shop.b2b2c.dto.ProductEditingDTOs.Status(current.revision(), 1))).getCode());
        editing.changeStatus(1L, 1L, new com.shop.b2b2c.dto.ProductEditingDTOs.Status(off.revision(), 1));
        mvc.perform(get("/api/v1/public/products/1")).andExpect(jsonPath("$.code").value(200));
        jdbc.update("UPDATE product SET status=2 WHERE id=1");
        assertEquals(403, assertThrows(BusinessException.class, () -> editing.changeStatus(1L, 1L,
                new com.shop.b2b2c.dto.ProductEditingDTOs.Status(editing.get(1L, 1L).revision(), 1))).getCode());
    }

    @Test
    void newOrdersUseEditedPriceAndOffSalePreventsNewCheckout() {
        var updated = editing.update(1L, 1L, productEdit(editing.get(1L, 1L).revision(), "new-catalog", "16.50"));
        var response = orderController.checkout(checkoutRequest()).getData();
        var order = orders.findByOrderSn((String) response.get("orderSn")).orElseThrow();
        assertEquals(0, order.getPayAmount().compareTo(new java.math.BigDecimal("16.50")));
        assertEquals("new-catalog", items.findByOrderSn(order.getOrderSn()).get(0).getProductTitle());
        editing.changeStatus(1L, 1L, new com.shop.b2b2c.dto.ProductEditingDTOs.Status(updated.revision(), 0));
        assertThrows(BusinessException.class, () -> orderController.checkout(checkoutRequest()));
        assertEquals(1, orders.selectCount(null));
    }

    private com.shop.b2b2c.dto.InventoryAdjustmentDTOs.Adjust adjustment(int delta, int expected) {
        return new com.shop.b2b2c.dto.InventoryAdjustmentDTOs.Adjust(1L, 1L, delta, expected, "stock-taking");
    }
    private void physicalProduct() { jdbc.update("UPDATE product SET goods_type=1 WHERE id=1"); }

    @Test
    void inventoryHttpUsesSessionActorAndRequiresIdempotencyKey() throws Exception {
        physicalProduct(); authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(adjustment(2, 1));
        body.put("actorId", 999); body.put("merchantId", 2);
        mvc.perform(post("/api/v1/merchant/inventory-adjustments").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/merchant/inventory-adjustments").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "stock-http-key-1234").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.actorId").value(200))
                .andExpect(jsonPath("$.data.beforeStock").value(1)).andExpect(jsonPath("$.data.afterStock").value(3))
                .andExpect(jsonPath("$.data.requestKey").doesNotExist());
        mvc.perform(get("/api/v1/merchant/inventory-adjustments").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.items.length()").value(1)).andExpect(jsonPath("$.data.items[0].requestKey").doesNotExist());
        assertEquals(3, skus.selectById(1).getStockQuantity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment", Integer.class));
    }

    @Test
    void inventoryStaffMayReadButCannotWrite() throws Exception {
        physicalProduct(); authUser("MERCHANT_STAFF", 1L); String token = loginToken();
        mvc.perform(post("/api/v1/merchant/inventory-adjustments").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "stock-staff-key-1234").contentType("application/json").content(json.writeValueAsString(adjustment(1, 1))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/merchant/inventory-adjustments").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment", Integer.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"productId", "skuId", "delta", "expectedStock"})
    void inventoryNeverTruncatesDecimalJsonToWholeUnits(String field) throws Exception {
        physicalProduct(); authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(adjustment(1, 1)); body.put(field, 1.5);
        mvc.perform(post("/api/v1/merchant/inventory-adjustments").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "stock-decimal-key-123").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment", Integer.class));
    }

    @Test
    void inventoryReplayRemainsOriginalAfterLaterAdjustmentAndConflictDoesNotWrite() {
        physicalProduct();
        var first = adjustments.adjust(1L, 200L, "stock-replay-key-1234", adjustment(2, 1));
        adjustments.adjust(1L, 200L, "stock-later-key-12345", adjustment(1, 3));
        assertEquals(first, adjustments.adjust(1L, 200L, "stock-replay-key-1234", adjustment(2, 1)));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> adjustments.adjust(1L, 200L, "stock-replay-key-1234", adjustment(3, 1))).getCode());
        assertEquals(4, skus.selectById(1).getStockQuantity());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment", Integer.class));
    }

    @Test
    void inventoryRejectsVirtualForeignProductAndForeignSkuWithoutPersistingClaims() {
        assertEquals(400, assertThrows(BusinessException.class, () -> adjustments.adjust(1L, 200L, "stock-virtual-key-123", adjustment(1, 1))).getCode());
        physicalProduct();
        var foreignProduct = new com.shop.b2b2c.dto.InventoryAdjustmentDTOs.Adjust(2L, 2L, 1, 1, "wrong-owner");
        var foreignSku = new com.shop.b2b2c.dto.InventoryAdjustmentDTOs.Adjust(1L, 2L, 1, 1, "wrong-sku");
        assertEquals(404, assertThrows(BusinessException.class, () -> adjustments.adjust(1L, 200L, "stock-foreign-key-123", foreignProduct)).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> adjustments.adjust(1L, 200L, "stock-foreign-sku-123", foreignSku)).getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment", Integer.class));
    }

    @Test
    void concurrentInventoryRetriesProduceOneAdjustmentAndOneRecord() throws Exception {
        physicalProduct(); var results = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        race(6, i -> results.add(adjustments.adjust(1L, 200L, "stock-concurrent-key-123", adjustment(2, 1)).id()));
        assertEquals(1, new java.util.HashSet<>(results).size()); assertEquals(3, skus.selectById(1).getStockQuantity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment", Integer.class));
    }

    @Test
    void concurrentDifferentInventoryKeysCannotBothUseStaleExpectedStock() throws Exception {
        physicalProduct(); AtomicInteger success = new AtomicInteger(), conflict = new AtomicInteger();
        race(2, i -> {
            try { adjustments.adjust(1L, 200L, "stock-different-key-123-" + i, adjustment(1, 1)); success.incrementAndGet(); }
            catch (BusinessException e) { assertEquals(409, e.getCode()); conflict.incrementAndGet(); }
        });
        assertEquals(1, success.get()); assertEquals(1, conflict.get()); assertEquals(2, skus.selectById(1).getStockQuantity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class));
    }

    private com.shop.b2b2c.dto.PeerPayDTOs.PreLockResult reservePhysicalForAdjustment(String token) {
        physicalProduct(); var order = intent(token); order.setGoodsType(1); orders.save(order);
        return payments.preLockInventory(token);
    }

    @Test
    void manualAdjustmentPreservesHeldUnitsAndReleaseAddsOnlyHeldQuantity() {
        var lock = reservePhysicalForAdjustment("stock-held");
        var result = adjustments.adjust(1L, 200L, "stock-held-key-12345", adjustment(2, 0));
        assertEquals(0, result.beforeStock()); assertEquals(2, result.afterStock());
        payments.cancelPreLock("stock-held", lock.getOwnerToken());
        assertEquals(3, skus.selectById(1).getStockQuantity());
    }

    @Test
    void manualAdjustmentReservesIntCapacityForFutureHeldRelease() {
        var lock = reservePhysicalForAdjustment("stock-capacity");
        jdbc.update("UPDATE product_sku SET stock_quantity=? WHERE id=1", Integer.MAX_VALUE - 1);
        assertEquals(400, assertThrows(BusinessException.class, () -> adjustments.adjust(1L, 200L,
                "stock-capacity-key-123", adjustment(1, Integer.MAX_VALUE - 1))).getCode());
        payments.cancelPreLock("stock-capacity", lock.getOwnerToken());
        assertEquals(Integer.MAX_VALUE, skus.selectById(1).getStockQuantity());
    }

    @Test
    void inventoryAuditInsertFailureRollsBackStockAndIdempotency() {
        physicalProduct();
        doThrow(new IllegalStateException("isolated-audit-failure")).when(adjustmentsMapper).insert(any());
        assertThrows(IllegalStateException.class, () -> adjustments.adjust(1L, 200L, "stock-rollback-key-123", adjustment(2, 1)));
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class));
    }

    @Test
    void inventoryCursorPagesAreStableAndTenantScoped() {
        physicalProduct();
        for (int i = 0; i < 3; i++) adjustments.adjust(1L, 200L, "stock-page-key-1234-" + i, adjustment(1, i + 1));
        var first = adjustments.list(1L, 1L, null, 2);
        assertEquals(2, first.items().size()); assertNotNull(first.nextBeforeId());
        var last = adjustments.list(1L, 1L, first.nextBeforeId(), 2);
        assertEquals(1, last.items().size()); assertNull(last.nextBeforeId());
        assertTrue(last.items().get(0).id() < first.nextBeforeId());
        assertTrue(adjustments.list(2L, 1L, null, 2).items().isEmpty());
        assertTrue(adjustments.list(1L, 2L, null, 2).items().isEmpty());
    }

    @Test
    void inventoryEntryRejectsOuterTransactionRatherThanUsingWrongIsolation() {
        physicalProduct(); var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> adjustments.adjust(1L, 200L,
                "stock-outer-key-12345", adjustment(1, 1))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment", Integer.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
    void cardImportRejectsDuplicatesFromAllHistoricalStates(int state) {
        jdbc.update("UPDATE product_virtual_card SET status=? WHERE id=1", state);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> cardAssets.importCards(1L, 200L, 1L, 1L, List.of("fresh-fixture", " secret "))).getCode());
        assertEquals(1, cards.selectCount(null)); assertEquals(1, skus.selectById(1).getStockQuantity());
    }

    @Test
    void concurrentCardImportsAcrossOwnProductsHaveOneWinner() throws Exception {
        jdbc.update("INSERT INTO product(id,merchant_id,category_id,title,goods_type,main_image,min_price) VALUES(3,1,1,'goods3',2,'image',10)");
        jdbc.update("INSERT INTO product_sku(id,merchant_id,product_id,sku_code,spec_data,price,stock_quantity) VALUES(3,1,3,'SKU3','{}',10,0)");
        AtomicInteger success = new AtomicInteger(), duplicates = new AtomicInteger();
        race(2, i -> {
            try { cardAssets.importCards(1L, 200L, i == 0 ? 1L : 3L, i == 0 ? 1L : 3L, List.of("shared-new-fixture")); success.incrementAndGet(); }
            catch (BusinessException e) { assertEquals(409, e.getCode()); duplicates.incrementAndGet(); }
        });
        assertEquals(1, success.get()); assertEquals(1, duplicates.get());
        assertEquals(2, cards.selectCount(null)); assertEquals(2, skus.selectById(1).getStockQuantity() + skus.selectById(3).getStockQuantity());
    }

    @Test
    void cardImportScopesHistoryToMerchantAndRollsBackStockFailure() {
        assertEquals(1, cardAssets.importCards(2L, 200L, 2L, 2L, List.of("secret")).get("importedCount"));
        doReturn(0).when(skus).addStock(1L, 1L, 2);
        assertThrows(IllegalStateException.class, () -> cardAssets.importCards(1L, 200L, 1L, 1L, List.of("new-a", "new-b")));
        assertEquals(1, skus.selectById(1).getStockQuantity()); assertEquals(2, cards.selectCount(null));
        assertEquals(1, cards.findByMerchantId(1L).size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"productId", "skuId"})
    void cardImportRejectsDecimalIdsButAcceptsLegacyIntegerStrings(String field) throws Exception {
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        var body = json.createObjectNode().put("productId", "1").put("skuId", "1"); body.putArray("cardContents").add("http-card-fixture");
        mvc.perform(post("/api/v1/merchant/cards/import").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(body))).andExpect(jsonPath("$.code").value(200));
        body.put(field, 1.5);
        mvc.perform(post("/api/v1/merchant/cards/import").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        assertEquals(2, cards.selectCount(null));
    }

    @Test
    void cardVoidHttpUsesSessionActorAndReturnsNoCardContent() throws Exception {
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        mvc.perform(post("/api/v1/merchant/cards/1/void").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"reason\":\"expired-fixture\",\"actorId\":999}"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.actorId").value(200))
                .andExpect(jsonPath("$.data.beforeStock").value(1)).andExpect(jsonPath("$.data.afterStock").value(0))
                .andExpect(jsonPath("$.data.cardContent").doesNotExist());
        mvc.perform(get("/api/v1/merchant/cards/1/void-record").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.reason").value("expired-fixture")).andExpect(jsonPath("$.data.cardContent").doesNotExist());
        assertEquals(2, cards.selectById(1).getStatus()); assertEquals(0, skus.selectById(1).getStockQuantity());
    }

    @Test
    void cardVoidCannotStealHeldCardAndWorksAfterOwnerReleases() {
        intent("card-void-held"); var lock = payments.preLockInventory("card-void-held");
        assertEquals(409, assertThrows(BusinessException.class, () -> cardAssets.voidCard(1L, 200L, 1L, "expired")).getCode());
        assertEquals(0, cards.selectById(1).getStatus());
        payments.cancelPreLock("card-void-held", lock.getOwnerToken());
        cardAssets.voidCard(1L, 200L, 1L, "expired");
        assertEquals(0, skus.selectById(1).getStockQuantity()); assertEquals(2, cards.selectById(1).getStatus());
    }

    @Test
    void cardVoidIsIdempotentButCannotRewriteReasonOrReimportVoidedContent() {
        var original = cardAssets.voidCard(1L, 200L, 1L, "expired");
        assertEquals(original, cardAssets.voidCard(1L, 200L, 1L, " expired "));
        assertEquals(409, assertThrows(BusinessException.class, () -> cardAssets.voidCard(1L, 200L, 1L, "new-reason")).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> cardAssets.importCards(1L, 200L, 1L, 1L, List.of("secret"))).getCode());
        assertEquals(0, skus.selectById(1).getStockQuantity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM virtual_card_void", Integer.class));
    }

    @Test
    void cardVoidRejectsInventoryDriftForeignTenantAndEmployeeWrite() throws Exception {
        jdbc.update("UPDATE product_sku SET stock_quantity=2 WHERE id=1");
        assertEquals(409, assertThrows(BusinessException.class, () -> cardAssets.voidCard(1L, 200L, 1L, "reason")).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> cardAssets.voidCard(2L, 200L, 1L, "reason")).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> cardAssets.readRecord(2L, 1L)).getCode());
        authUser("MERCHANT_STAFF", 1L); String token = loginToken();
        mvc.perform(post("/api/v1/merchant/cards/1/void").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"reason\":\"staff\"}")).andExpect(status().isForbidden());
        assertEquals(0, cards.selectById(1).getStatus()); assertEquals(2, skus.selectById(1).getStockQuantity());
    }

    @Test
    void cardVoidAuditFailureRollsBackStateAndStock() {
        doThrow(new IllegalStateException("isolated-void-record-failure")).when(cardAssetsMapper).insertVoid(any());
        assertThrows(IllegalStateException.class, () -> cardAssets.voidCard(1L, 200L, 1L, "expired"));
        assertEquals(0, cards.selectById(1).getStatus()); assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM virtual_card_void", Integer.class));
    }
    @Test
    void cardVoidStockFailureRollsBackAlreadyChangedCardState() {
        doReturn(0).when(skus).deductStock(1L, 1L, 1L, 1);
        assertEquals(409, assertThrows(BusinessException.class, () -> cardAssets.voidCard(1L, 200L, 1L, "expired")).getCode());
        assertEquals(0, cards.selectById(1).getStatus()); assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM virtual_card_void", Integer.class));
    }

    @Test
    void concurrentCardVoidRetriesDeductExactlyOnce() throws Exception {
        race(5, i -> assertEquals(0, cardAssets.voidCard(1L, 200L, 1L, "expired").getAfterStock()));
        assertEquals(0, skus.selectById(1).getStockQuantity()); assertEquals(2, cards.selectById(1).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM virtual_card_void", Integer.class));
    }

    @Test
    void cardVoidAndPaymentCannotBothConsumeLastCard() throws Exception {
        TradeOrder order = unpaid("card-void-payment", 1); AtomicInteger success = new AtomicInteger();
        race(2, i -> {
            try { if (i == 0) cardAssets.voidCard(1L, 200L, 1L, "expired"); else pay("card-void-payment"); success.incrementAndGet(); }
            catch (BusinessException expected) { /* 另一方占用最后一份资产后必须拒绝。 */ }
        });
        assertEquals(1, success.get()); assertEquals(0, skus.selectById(1).getStockQuantity());
        int state = cards.selectById(1).getStatus(); assertTrue(state == 1 || state == 2);
        assertEquals(state == 2 ? 1 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM virtual_card_void", Integer.class));
        assertEquals(state == 1 ? "COMPLETED" : "UNPAID", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    void cardAssetEntryRejectsOuterTransaction() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> cardAssets.importCards(1L, 200L, 1L, 1L, List.of("fixture"))));
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> cardAssets.voidCard(1L, 200L, 1L, "reason")));
    }

    @Test
    void newCardProductStartsEmptyThenImportAndVoidMaintainExactInventory() {
        var dto = new ProductController.CreateProductDTO(); dto.setTitle("new-virtual"); dto.setMainImage("image");
        dto.setCategoryId(1L); dto.setGoodsType(2); dto.setMinPrice(java.math.BigDecimal.TEN);
        var initial = new ProductController.SkuDTO(); initial.setSkuCode("NEW-VIRTUAL"); initial.setSpecData("{}");
        initial.setPrice(java.math.BigDecimal.TEN); initial.setStockQuantity(5); dto.setSkus(List.of(initial));
        Long productId;
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            assertEquals(400, assertThrows(BusinessException.class, () -> products.createProduct(dto)).getCode());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class));
            initial.setStockQuantity(0); productId = products.createProduct(dto).getData().getId();
        }
        Long skuId = skus.findByProductId(productId).get(0).getId();
        assertEquals(0, skus.selectById(skuId).getStockQuantity());
        cardAssets.importCards(1L, 200L, productId, skuId, List.of("new-product-card-a", "new-product-card-b"));
        assertEquals(2, skus.selectById(skuId).getStockQuantity());
        var imported = cards.findAvailableForMerchant(skuId, 1L);
        cardAssets.voidCard(1L, 200L, imported.get(0).getId(), "expired");
        assertEquals(1, skus.selectById(skuId).getStockQuantity());
        assertEquals(1, cards.findAvailableForMerchant(skuId, 1L).size());
    }

    @Test
    void importBatchHttpUsesSessionActorAndHidesCardContent() throws Exception {
        mvc.perform(get("/api/v1/merchant/card-import-batches")).andExpect(status().isUnauthorized());
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        String response = mvc.perform(post("/api/v1/merchant/cards/import").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"productId\":1,\"skuId\":1,\"actorId\":999,\"cardContents\":[\"batch-private-fixture\"]}"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.batchId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long batch = json.readTree(response).path("data").path("batchId").asLong();
        mvc.perform(get("/api/v1/merchant/card-import-batches").header("Authorization", "Bearer " + token))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.items[0].actorId").value(200))
                .andExpect(jsonPath("$.data.items[0].importedCount").value(1));
        String members = mvc.perform(get("/api/v1/merchant/card-import-batches/" + batch + "/cards").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].cardContent").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(members.contains("batch-private-fixture"));
    }

    @Test
    void failedBatchLinkRollsBackBatchCardsAndStock() {
        doReturn(0).when(cardBatches).link(any(), any());
        assertThrows(IllegalStateException.class, () -> cardAssets.importCards(1L, 200L, 1L, 1L, List.of("batch-new-a", "batch-new-b")));
        assertEquals(1, cards.selectCount(null)); assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM card_import_batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM card_import_item", Integer.class));
    }

    @Test
    void stockFailureRollsBackFullyLinkedBatch() {
        doReturn(0).when(skus).addStock(1L, 1L, 2);
        assertThrows(IllegalStateException.class, () -> cardAssets.importCards(1L, 200L, 1L, 1L, List.of("batch-new-a", "batch-new-b")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM card_import_batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM card_import_item", Integer.class));
        assertEquals(1, cards.selectCount(null)); assertEquals(1, skus.selectById(1).getStockQuantity());
    }

    @Test
    void duplicateImportDoesNotLeaveEmptyBatchAndKeepsHistoricalCardsUnassigned() {
        assertTrue(batchQueries.list(1L, null, null, 20).items().isEmpty());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> cardAssets.importCards(1L, 200L, 1L, 1L, List.of("secret"))).getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM card_import_batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM card_import_item", Integer.class));
    }

    @Test
    void batchAndMemberPagesUseIndependentCursorsAndEnforceTenant() {
        Long latest = null;
        for (int i = 0; i < 3; i++) latest = (Long) cardAssets.importCards(1L, 200L, 1L, 1L,
                List.of("batch-a-" + i, "batch-b-" + i, "batch-c-" + i)).get("batchId");
        var first = batchQueries.list(1L, 1L, null, 2); var last = batchQueries.list(1L, 1L, first.nextBeforeId(), 2);
        assertEquals(2, first.items().size()); assertEquals(1, last.items().size()); assertNull(last.nextBeforeId());
        var members = batchQueries.members(1L, latest, null, 2);
        assertEquals(2, members.items().size()); assertNotNull(members.nextBeforeId());
        var rest = batchQueries.members(1L, latest, members.nextBeforeId(), 2);
        assertEquals(1, rest.items().size()); assertNull(rest.nextBeforeId());
        assertTrue(rest.items().get(0).cardId() < members.nextBeforeId());
        assertTrue(batchQueries.list(2L, null, null, 20).items().isEmpty());
        final Long batch = latest;
        assertEquals(404, assertThrows(BusinessException.class, () -> batchQueries.members(2L, batch, null, 20)).getCode());
        assertTrue(batchQueries.list(1L, 2L, null, 20).items().isEmpty());
    }

    @Test
    void batchMembersShowCurrentStateWithoutRewritingImportSnapshot() {
        cardAssets.voidCard(1L, 200L, 1L, "legacy-expired");
        long batch = (Long) cardAssets.importCards(1L, 200L, 1L, 1L, List.of("member-a", "member-b")).get("batchId");
        unpaid("batch-payment", 1); pay("batch-payment");
        var available = cards.findAvailableForMerchant(1L, 1L).get(0);
        cardAssets.voidCard(1L, 200L, available.getId(), "expired");
        var members = batchQueries.members(1L, batch, null, 20);
        assertEquals(java.util.Set.of(1, 2), members.items().stream().map(com.shop.b2b2c.dto.CardImportDTOs.Item::status).collect(java.util.stream.Collectors.toSet()));
        var original = batchQueries.list(1L, 1L, null, 20).items().get(0);
        assertEquals(2, original.getImportedCount()); assertEquals(2, original.getAfterStock());
        assertEquals(0, skus.selectById(1).getStockQuantity());
    }

    @Test
    void corruptedCrossTenantMemberLinkCannotExposeForeignCard() throws Exception {
        long batch = (Long) cardAssets.importCards(1L, 200L, 1L, 1L, List.of("owned-member")).get("batchId");
        jdbc.update("INSERT INTO product_virtual_card(id,merchant_id,product_id,sku_id,card_content,status,order_sn) VALUES(900000,2,2,2,'foreign-fixture',1,'FOREIGN-ORDER')");
        cardBatches.link(batch, 900000L);
        var members = batchQueries.members(1L, batch, null, 20);
        assertEquals(1, members.items().size()); assertFalse(json.writeValueAsString(members).contains("FOREIGN-ORDER"));
    }

    private void authUser(String role, Long merchantId) {
        jdbc.update("INSERT INTO sys_user(id,username,password,role_code,merchant_id,is_active) VALUES(200,'auth-fixture',?,?,?,1)", AUTH_HASH, role, merchantId);
    }

    private void statisticsOrder(String token, long merchant, String state, String peer, String amount, String commission) {
        var order = new TradeOrder(); order.setOrderSn("STAT-" + token); order.setMerchantId(merchant); order.setBuyerContact("fixture-contact");
        order.setPeerPayToken("stat-" + token); order.setQueryToken("private-stat-" + token); order.setOrderStatus(state); order.setPeerStatus(peer);
        order.setPayAmount(new java.math.BigDecimal(amount)); order.setTotalAmount(new java.math.BigDecimal(amount));
        order.setCommissionAmount(new java.math.BigDecimal(commission)); order.setSharingStatus("FINISHED"); orders.save(order);
    }

    @Test
    void merchantStatisticsEnforcesAuthenticationAndIgnoresClientTenantSelection() throws Exception {
        mvc.perform(get("/api/v1/merchant/orders/statistics")).andExpect(status().isUnauthorized());
        statisticsOrder("intent", 1L, "UNPAID", "INIT", "1000", "30");
        statisticsOrder("paid", 1L, "PAID", "PAID", "10", "0.30");
        statisticsOrder("refund", 1L, "REFUNDED", "PAID", "2", "0.06");
        statisticsOrder("expired", 1L, "UNPAID", "EXPIRED", "500", "15");
        statisticsOrder("foreign", 2L, "COMPLETED", "PAID", "9000", "270");
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        mvc.perform(get("/api/v1/merchant/orders/statistics").param("merchantId", "2").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalOrders").value(4)).andExpect(jsonPath("$.data.paidOrders").value(2))
                .andExpect(jsonPath("$.data.paidTurnover").value("12.00")).andExpect(jsonPath("$.data.pendingIntentAmount").value("1000.00"))
                .andExpect(jsonPath("$.data.estimatedCommission").value("0.30")).andExpect(jsonPath("$.data.closedOrders").value(1))
                .andExpect(jsonPath("$.data.actualRefundAmount").isEmpty()).andExpect(jsonPath("$.data.financialLedgerAvailable").value(false))
                .andExpect(jsonPath("$.data.orders").doesNotExist()).andExpect(jsonPath("$.data.queryToken").doesNotExist());
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void platformStatisticsKeepsCompatibleKeysButExcludesIntentAndMarksUnknownFinance() throws Exception {
        statisticsOrder("intent", 1L, "UNPAID", "INIT", "1000", "30");
        statisticsOrder("paid", 1L, "PAID", "PAID", "10", "0.30");
        statisticsOrder("refund", 1L, "REFUNDED", "PAID", "2", "0.06");
        statisticsOrder("foreign", 2L, "COMPLETED", "PAID", "9000", "270");
        authUser("SUPERADMIN", null); String token = loginToken();
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.totalOrders").value(4)).andExpect(jsonPath("$.data.orders.length()").value(4))
                .andExpect(jsonPath("$.data.totalTurnover").value(9012.0)).andExpect(jsonPath("$.data.totalCommission").value(270.3))
                .andExpect(jsonPath("$.data.statistics.paidOrders").value(3)).andExpect(jsonPath("$.data.statistics.paidTurnover").value("9012.00"))
                .andExpect(jsonPath("$.data.statistics.estimatedCommission").value("270.30"))
                .andExpect(jsonPath("$.data.statistics.settledCommission").isEmpty()).andExpect(jsonPath("$.data.statistics.netTurnover").isEmpty());
    }

    @Test
    void emptyMerchantStatisticsReturnsKnownZeroRatherThanReadingOtherStore() throws Exception {
        statisticsOrder("foreign-only", 2L, "PAID", "PAID", "500", "15");
        authUser("MERCHANT_STAFF", 1L); String token = loginToken();
        mvc.perform(get("/api/v1/merchant/orders/statistics").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.totalOrders").value(0)).andExpect(jsonPath("$.data.paidTurnover").value("0.00"))
                .andExpect(jsonPath("$.data.settledCommission").isEmpty());
    }

    private String loginToken() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("username", "auth-fixture", "password", AUTH_PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("token").asText();
    }

    @Test
    void allowedPreflightDoesNotRequireLoginOrCookieCredentials() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/merchant/products")
                .header("Origin", "https://admin.example.test").header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type,Idempotency-Key"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://admin.example.test"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(get("/api/v1/auth/me").header("Origin", "https://admin.example.test"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Access-Control-Allow-Origin", "https://admin.example.test"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"https://evil.example.test", "https://admin.example.test.evil.test", "https://admin.example.test:8443", "null"})
    void unlistedOriginsCannotReadEvenPublicApi(String origin) throws Exception {
        mvc.perform(get("/api/v1/auth/health").header("Origin", origin))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void sameOriginAndNonBrowserRequestsRemainUsable() throws Exception {
        mvc.perform(get("/api/v1/auth/health").header("Origin", "http://localhost"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/v1/auth/health")).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/auth/login")
                .header("Origin", "https://evil.example.test").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unsupportedCorsHeadersAreRejected() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/auth/login")
                .header("Origin", "https://admin.example.test").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-Private-Header"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicHttpsOriginWorksBehindHttpBackendWithoutTrustingForwardedHeaders() throws Exception {
        authUser("MERCHANT_ADMIN", 1L);
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").header("Origin", "https://admin.example.test")
                .with(request -> { request.setScheme("http"); request.setServerName("admin.example.test"); return request; })
                .content(json.writeValueAsString(java.util.Map.of("username", "auth-fixture", "password", AUTH_PASSWORD))))
                .andExpect(jsonPath("$.code").value(200)).andExpect(header().string("Access-Control-Allow-Origin", "https://admin.example.test"));
    }

    @Test
    void loginTrimsAccountButPreservesPasswordInput() throws Exception {
        authUser("MERCHANT_ADMIN", 1L);
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("username", " auth-fixture ", "password", AUTH_PASSWORD))))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("username", "auth-fixture", "password", " " + AUTH_PASSWORD + " "))))
                .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.data.token").doesNotExist());
    }

    private void simulateLoginBuckets() {
        var counts = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenAnswer(invocation -> {
            String key = ((List<String>) invocation.getArgument(1)).get(0);
            int limit = Integer.parseInt(invocation.getArgument(2).toString());
            return counts.computeIfAbsent(key, unused -> new AtomicInteger()).incrementAndGet() <= limit ? 1L : 0L;
        });
    }

    @Test
    void accountAttemptsAreSharedAcrossConnectionsAndSuccessDoesNotResetThem() throws Exception {
        authUser("MERCHANT_ADMIN", 1L); simulateLoginBuckets();
        for (int attempt = 1; attempt <= 4; attempt++) {
            var login = post("/api/v1/auth/login").contentType("application/json")
                    .content(json.writeValueAsString(java.util.Map.of("username", "auth-fixture", "password", AUTH_PASSWORD)));
            final int connection = attempt;
            mvc.perform(login.with(request -> { request.setRemoteAddr("192.0.2." + connection); return request; }))
                    .andExpect(jsonPath("$.code").value(attempt <= 3 ? 200 : 429));
        }
    }

    @Test
    void addressLimitAppliesAcrossUnknownAccountsAndIgnoresSpoofedForwardingHeaders() throws Exception {
        simulateLoginBuckets();
        for (int attempt = 1; attempt <= 6; attempt++) {
            mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                    .header("X-Forwarded-For", "192.0.2." + attempt).header("Forwarded", "for=192.0.2." + attempt)
                    .content(json.writeValueAsString(java.util.Map.of("username", "missing-" + attempt, "password", AUTH_PASSWORD))))
                    .andExpect(jsonPath("$.code").value(attempt <= 5 ? 500 : 429));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"nullIp", "nullAccount", "exception"})
    void rateStorageFailureNeverIssuesLoginToken(String scenario) throws Exception {
        authUser("MERCHANT_ADMIN", 1L);
        if (scenario.equals("nullAccount")) when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(1L, null);
        else if (scenario.equals("exception")) when(redis.<Long>execute(any(), anyList(), any(), any())).thenThrow(new IllegalStateException("private-detail"));
        else when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(null);
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("username", "auth-fixture", "password", AUTH_PASSWORD))))
                .andExpect(jsonPath("$.code").value(503)).andExpect(jsonPath("$.data.token").doesNotExist());
        doReturn(1L).when(redis).execute(any(), anyList(), any(), any());
        assertFalse(loginToken().isBlank());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"unknownRole", "disabled", "wrongPassword", "missingUser"})
    void invalidLoginNeverIssuesToken(String scenario) throws Exception {
        authUser(scenario.equals("unknownRole") ? "UNKNOWN" : "MERCHANT_ADMIN", 1L);
        if (scenario.equals("disabled")) jdbc.update("UPDATE sys_user SET is_active=0 WHERE id=200");
        if (scenario.equals("missingUser")) jdbc.update("DELETE FROM sys_user WHERE id=200");
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("username", "auth-fixture", "password",
                        scenario.equals("wrongPassword") ? "wrong-fixture-password" : AUTH_PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(scenario.equals("unknownRole") ? 403 : 500))
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void loginMeRoleBoundaryAndServerLogout() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        authUser("MERCHANT_ADMIN", 1L);
        String token = loginToken();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roleCode").value("MERCHANT_ADMIN"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
        mvc.perform(get("/api/v1/merchant/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/merchant/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));
        assertNotEquals(token, loginToken());
    }

    @Test
    void logoutOnlyRevokesCurrentIndependentLogin() throws Exception {
        authUser("SUPERADMIN", null);
        String first = loginToken(), second = loginToken();
        assertNotEquals(first, second);
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + first))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + first))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + first))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + second))
                .andExpect(jsonPath("$.code").value(200));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"disabled", "deleted", "roleChanged", "merchantChanged", "unbound"})
    void changedAccountRevokesOldTokenOnProtectedRequest(String scenario) throws Exception {
        authUser("MERCHANT_ADMIN", 1L);
        String token = loginToken();
        switch (scenario) {
            case "disabled" -> jdbc.update("UPDATE sys_user SET is_active=0 WHERE id=200");
            case "deleted" -> jdbc.update("DELETE FROM sys_user WHERE id=200");
            case "roleChanged" -> jdbc.update("UPDATE sys_user SET role_code='MERCHANT_STAFF' WHERE id=200");
            case "merchantChanged" -> jdbc.update("UPDATE sys_user SET merchant_id=2 WHERE id=200");
            case "unbound" -> jdbc.update("UPDATE sys_user SET merchant_id=NULL WHERE id=200");
        }
        mvc.perform(get("/api/v1/merchant/products").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void freshLoginCannotReauthorizeOldTokenAfterTenantChange() throws Exception {
        authUser("MERCHANT_ADMIN", 1L);
        String old = loginToken();
        jdbc.update("UPDATE sys_user SET merchant_id=2 WHERE id=200");
        String fresh = loginToken();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + fresh))
                .andExpect(jsonPath("$.data.merchantId").value(2));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + old))
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + fresh))
                .andExpect(jsonPath("$.code").value(200));
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
        assertEquals("EXPIRED", orders.selectById(order.getId()).getPeerStatus());
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
        mvc.perform(post("/api/v1/public/orders/checkout").header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType("application/json")
                        .content("{\"merchantId\":1,\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"buyer@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("UNPAID"))
                .andExpect(jsonPath("$.data.cards").isEmpty()).andExpect(jsonPath("$.data.paymentAvailable").value(false));
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(0, cards.selectById(1).getStatus());
        assertNull(orders.selectList(null).get(0).getPayTradeNo());
    }

    @Test
    void publicMockRouteIsAbsentEvenWhenLegacyFlagIsTrue() throws Exception {
        mvc.perform(post("/api/v1/peer-pay/mock-pay/anything").header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void r10IdempotentAttemptRecoveryRestoresOwnerTokenWithoutDoubleDeductingStock() throws Exception {
        intent("r10-recovery");
        assertEquals(1, skus.selectById(1).getStockQuantity());

        // 1. 首次预占成功，携带尝试凭证 attempt-key-alpha
        var firstLock = payments.preLockInventory("r10-recovery", "attempt-key-alpha");
        assertEquals("attempt-key-alpha", firstLock.getOwnerToken());
        assertEquals("RESERVED_LOCK", firstLock.getPeerStatus());
        assertEquals(0, skus.selectById(1).getStockQuantity());

        // 2. 模拟客户端丢响应后重试（相同尝试凭据）：幂等恢复原预占，不重复扣库存
        var retryLock = payments.preLockInventory("r10-recovery", "attempt-key-alpha");
        assertEquals("attempt-key-alpha", retryLock.getOwnerToken());
        assertTrue(retryLock.getSuccess());
        assertEquals("RESERVED_LOCK", retryLock.getPeerStatus());
        assertEquals(0, skus.selectById(1).getStockQuantity()); // 库存仍为 0，未发生二次扣减

        // 3. 仿冒者/其他尝试凭证介入：被拒绝，且不能窃取 ownerToken
        var exOther = assertThrows(BusinessException.class, () -> payments.preLockInventory("r10-recovery", "attempt-key-beta"));
        assertTrue(exOther.getMessage().contains("已被其他付款人预占中"));
        var exNull = assertThrows(BusinessException.class, () -> payments.preLockInventory("r10-recovery", null));
        assertTrue(exNull.getMessage().contains("已被其他付款人预占中"));
        assertEquals(0, skus.selectById(1).getStockQuantity());

        // 4. 超长凭据校验（>64字符直接拦截，防止数据库截断溢出）
        String excessiveKey = "k".repeat(65);
        var exExcessive = assertThrows(BusinessException.class, () -> payments.preLockInventory("r10-recovery", excessiveKey));
        assertEquals(400, exExcessive.getCode());

        // 5. 原持有者释放预占：库存回滚恢复
        payments.cancelPreLock("r10-recovery", "attempt-key-alpha");
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals("INIT", orders.lockByPeerPayToken("r10-recovery").getPeerStatus());

        // 6. 释放后另一尝试可以成功预占
        var nextLock = payments.preLockInventory("r10-recovery", "attempt-key-beta");
        assertEquals("attempt-key-beta", nextLock.getOwnerToken());
        assertEquals(0, skus.selectById(1).getStockQuantity());
    }

    @Test
    void r10HttpEndpointHonorsIdempotencyKeyHeaderForPreLockRecovery() throws Exception {
        intent("r10-http");

        // 首次调起预占
        mvc.perform(post("/api/v1/peer-pay/pre-lock/r10-http")
                        .header("Idempotency-Key", "http-client-attempt-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ownerToken").value("http-client-attempt-1"))
                .andExpect(jsonPath("$.data.peerStatus").value("RESERVED_LOCK"));

        // 重发请求（网络重试）
        mvc.perform(post("/api/v1/peer-pay/pre-lock/r10-http")
                        .header("Idempotency-Key", "http-client-attempt-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ownerToken").value("http-client-attempt-1"))
                .andExpect(jsonPath("$.data.message").value("已恢复现有库存预占状态"));

        // 其它客户端调起被拒
        mvc.perform(post("/api/v1/peer-pay/pre-lock/r10-http")
                        .header("Idempotency-Key", "different-client"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("订单已被其他付款人预占中，请稍候再试"));
    }

    @Test
    void r12UnpaidDirectCheckoutOrdersAreAutomaticallyClosedAfterFifteenMinutes() {
        TradeOrder directOrder = unpaid("r12-direct", 1);
        directOrder.setPeerStatus("INIT");
        directOrder.setLockExpireTime(null);
        orders.save(directOrder);

        // 设置创建时间在 20 分钟前（超过 15 分钟超期时限），无预占记录
        jdbc.update("UPDATE trade_order SET created_at=?, lock_expire_time=NULL WHERE id=?",
                LocalDateTime.now().minusMinutes(20), directOrder.getId());

        expiryJob.expireUnpaidOrders();

        TradeOrder closed = orders.selectById(directOrder.getId());
        assertEquals("CLOSED", closed.getOrderStatus());
        assertEquals("EXPIRED", closed.getPeerStatus());
    }

    @Test
    void r12PaidOrdersAndRecentUnpaidOrdersAreNotClosedByExpiryJob() {
        TradeOrder paidOrder = unpaid("r12-paid", 1);
        paidOrder.setOrderStatus("PAID");
        paidOrder.setPeerStatus("PAID");
        orders.save(paidOrder);
        jdbc.update("UPDATE trade_order SET created_at=? WHERE id=?", LocalDateTime.now().minusMinutes(30), paidOrder.getId());

        TradeOrder freshUnpaid = unpaid("r12-fresh", 1);
        freshUnpaid.setPeerStatus("INIT");
        freshUnpaid.setLockExpireTime(null);
        orders.save(freshUnpaid);
        jdbc.update("UPDATE trade_order SET created_at=? WHERE id=?", LocalDateTime.now().minusMinutes(5), freshUnpaid.getId());

        expiryJob.expireUnpaidOrders();

        // 已支付订单绝不关闭
        assertEquals("PAID", orders.selectById(paidOrder.getId()).getOrderStatus());
        assertEquals("PAID", orders.selectById(paidOrder.getId()).getPeerStatus());

        // 未满 15 分钟的意向单保持 UNPAID
        assertEquals("UNPAID", orders.selectById(freshUnpaid.getId()).getOrderStatus());
    }

    @Test
    void r09MerchantProfileUpdateDoesNotOverwriteBannedStatusOrPlatformCommission() throws Exception {
        // 1. 初始化：店铺1为正常营业 (1)，平台抽成为 3.00%
        jdbc.update("UPDATE merchant_store SET status=1, commission_rate=3.00, notice='old notice' WHERE id=1");

        // 2. 超管将店铺封禁 (status=3) 并调整费率为 5.00%
        authUser("SUPERADMIN", null);
        String adminToken = loginToken();
        mvc.perform(put("/api/v1/admin/merchants/1/status?status=3")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/merchants/1/commission?rate=5.00")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 登出超管并清理会话与用户记录，准备切换为商家身份
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");

        // 3. 商家登录修改自己店铺的展示公告与电话
        authUser("MERCHANT_ADMIN", 1L);
        String merchantToken = loginToken();
        mvc.perform(put("/api/v1/merchant/profile")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType("application/json")
                        .content("{\"notice\":\"new notice\",\"contactPhone\":\"13900001111\"}"))
                .andExpect(status().isOk());

        // 4. 验证：公告与电话被成功更新，但超管设置的封禁状态 (3) 和平台费率 (5.00%) 绝未被冲刷回滚！
        var store = stores.findById(1L).orElseThrow();
        assertEquals("new notice", store.getNotice());
        assertEquals("13900001111", store.getContactPhone());
        assertEquals(3, store.getStatus(), "R09: 商家资料更新绝不能覆盖超管设置的封禁状态");
        assertEquals(new java.math.BigDecimal("5.00"), store.getCommissionRate(), "R09: 商家资料更新绝不能覆盖超管设置的平台抽成比例");
    }

    @Test
    void r13PaymentEventProcessingHappyPathAndIdempotency() {
        TradeOrder order = unpaid("r13-happy", 1);
        var event = com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel("WECHAT")
                .payTradeNo("TX_HAPPY_001")
                .orderSn(order.getOrderSn())
                .merchantId(1L)
                .payAmount(new java.math.BigDecimal("10.00"))
                .currency("CNY")
                .payerId("wx_happy_user")
                .rawPayload("{\"appid\":\"wx123\"}")
                .build();

        // 1. 初次到账核验与履约
        var result = paymentEventService.processPaymentEvent(event);
        assertTrue(result.getSuccess());
        assertEquals("SUCCESS", result.getEventStatus());
        assertEquals("COMPLETED", result.getOrderStatus());

        // 2. 检查流水表与订单履约
        var pe = paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_HAPPY_001");
        assertNotNull(pe);
        assertEquals("SUCCESS", pe.getEventStatus());
        assertEquals(1L, pe.getMerchantId());
        assertEquals(new java.math.BigDecimal("10.00"), pe.getPayAmount());

        TradeOrder updatedOrder = orders.findById(order.getId()).orElseThrow();
        assertEquals("COMPLETED", updatedOrder.getOrderStatus());
        assertEquals("TX_HAPPY_001", updatedOrder.getPayTradeNo());
        assertEquals(0, skus.selectById(1).getStockQuantity());
        assertEquals(1, cards.selectById(1).getStatus()); // 卡密已分配

        // 3. 幂等重放相同到账事件：直接返回成功，不重复扣库存，不产生二次发卡
        var replayResult = paymentEventService.processPaymentEvent(event);
        assertTrue(replayResult.getSuccess());
        assertEquals("SUCCESS", replayResult.getEventStatus());
        assertEquals("COMPLETED", replayResult.getOrderStatus());
        assertEquals(0, skus.selectById(1).getStockQuantity());
    }

    @Test
    void r13DuplicateChannelTradeNoForDifferentOrderRejected() {
        TradeOrder orderA = unpaid("r13-dup-a", 1);
        TradeOrder orderB = unpaid("r13-dup-b", 1);

        var eventA = com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel("ALIPAY")
                .payTradeNo("ALI_DUP_001")
                .orderSn(orderA.getOrderSn())
                .merchantId(1L)
                .payAmount(new java.math.BigDecimal("10.00"))
                .currency("CNY")
                .payerId("alipay_user_a")
                .build();
        paymentEventService.processPaymentEvent(eventA);

        var eventB = com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel("ALIPAY")
                .payTradeNo("ALI_DUP_001")
                .orderSn(orderB.getOrderSn())
                .merchantId(1L)
                .payAmount(new java.math.BigDecimal("10.00"))
                .currency("CNY")
                .payerId("alipay_user_b")
                .build();

        var ex = assertThrows(BusinessException.class, () -> paymentEventService.processPaymentEvent(eventB));
        assertTrue(ex.getMessage().contains("已被其他订单使用"));
        assertEquals("UNPAID", orders.selectById(orderB.getId()).getOrderStatus());
    }

    @Test
    void r13AmountMismatchRecordedAndRejected() {
        TradeOrder order = unpaid("r13-amount", 1);
        var event = com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel("WECHAT")
                .payTradeNo("TX_AMOUNT_ERR")
                .orderSn(order.getOrderSn())
                .merchantId(1L)
                .payAmount(new java.math.BigDecimal("9.99"))
                .currency("CNY")
                .payerId("wx_payer")
                .build();

        var ex = assertThrows(BusinessException.class, () -> paymentEventService.processPaymentEvent(event));
        assertTrue(ex.getMessage().contains("实付金额与订单应付金额不符"));

        var pe = paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_AMOUNT_ERR");
        assertNotNull(pe);
        assertEquals("AMOUNT_MISMATCH", pe.getEventStatus());
        assertTrue(pe.getErrorMessage().contains("实付金额"));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        assertEquals(0, cards.selectById(1).getStatus()); // 卡密未被发放
    }

    @Test
    void r13CurrencyMismatchRecordedAndRejected() {
        TradeOrder order = unpaid("r13-currency", 1);
        var event = com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel("STRIPE")
                .payTradeNo("TX_CURRENCY_ERR")
                .orderSn(order.getOrderSn())
                .merchantId(1L)
                .payAmount(new java.math.BigDecimal("10.00"))
                .currency("USD")
                .payerId("stripe_customer")
                .build();

        var ex = assertThrows(BusinessException.class, () -> paymentEventService.processPaymentEvent(event));
        assertTrue(ex.getMessage().contains("交易币种不符"));

        var pe = paymentEvents.findByChannelAndTradeNo("STRIPE", "TX_CURRENCY_ERR");
        assertNotNull(pe);
        assertEquals("CURRENCY_MISMATCH", pe.getEventStatus());
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    void r13MerchantMismatchRecordedAndRejected() {
        TradeOrder order = unpaid("r13-merchant", 1); // merchantId = 1
        var event = com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel("WECHAT")
                .payTradeNo("TX_MERCHANT_ERR")
                .orderSn(order.getOrderSn())
                .merchantId(2L) // mismatched merchant ID
                .payAmount(new java.math.BigDecimal("10.00"))
                .currency("CNY")
                .payerId("wx_payer")
                .build();

        var ex = assertThrows(BusinessException.class, () -> paymentEventService.processPaymentEvent(event));
        assertTrue(ex.getMessage().contains("支付事件商户与订单商户不一致"));

        var pe = paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_MERCHANT_ERR");
        assertNotNull(pe);
        assertEquals("MERCHANT_MISMATCH", pe.getEventStatus());
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    void r13LatePaymentOnClosedOrderIsIsolatedWithoutDeliveringAssets() {
        TradeOrder order = unpaid("r13-late", 1);
        order.setOrderStatus("CLOSED");
        order.setPeerStatus("EXPIRED");
        orders.save(order);

        var event = com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel("WECHAT")
                .payTradeNo("TX_LATE_001")
                .orderSn(order.getOrderSn())
                .merchantId(1L)
                .payAmount(new java.math.BigDecimal("10.00"))
                .currency("CNY")
                .payerId("wx_late_payer")
                .build();

        var ex = assertThrows(BusinessException.class, () -> paymentEventService.processPaymentEvent(event));
        assertTrue(ex.getMessage().contains("订单已超时关闭，支付资金已进入自动退款与补偿流程"));

        var pe = paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_LATE_001");
        assertNotNull(pe);
        assertEquals("LATE_PAYMENT", pe.getEventStatus());
        assertEquals("CLOSED", orders.selectById(order.getId()).getOrderStatus());
        assertEquals(0, cards.selectById(1).getStatus()); // 绝对不可向超时关单发放数字卡密
    }

    @Test
    void r13PeerPayNotifyPaymentSuccessDelegatesToPaymentEventService() {
        TradeOrder order = intent("r13-peer-adapter");
        payments.preLockInventory("r13-peer-adapter");

        TradeOrder paidOrder = payments.notifyPaymentSuccess("r13-peer-adapter", "verified_peer_payer", "TX_PEER_ADAPTER_123", "WECHAT");
        assertEquals("COMPLETED", paidOrder.getOrderStatus());
        assertEquals("TX_PEER_ADAPTER_123", paidOrder.getPayTradeNo());

        var pe = paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_PEER_ADAPTER_123");
        assertNotNull(pe);
        assertEquals("SUCCESS", pe.getEventStatus());
        assertEquals("verified_peer_payer", pe.getPayerId());
    }

    @Test
    void checkoutRejectsCrossProductSkuWithoutSideEffects() throws Exception {
        mvc.perform(post("/api/v1/public/orders/checkout").header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType("application/json")
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
        race(2, i -> {
            try { pay(i == 0 ? "card-one" : "card-two"); }
            catch (BusinessException expected) { }
        });
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
            security.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
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
            security.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
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

    @Test
    void cardShortageRollsBackInsteadOfBecomingManualService() {
        TradeOrder order = unpaid("no-cards", 1);
        jdbc.update("DELETE FROM product_virtual_card");
        assertThrows(BusinessException.class, () -> pay("no-cards"));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertThrows(BusinessException.class, () -> payments.fulfillVirtualOrder(order.getId(), "manual", 1L));
    }

    @Test
    void explicitCustomServiceRequiresManualFulfillmentAndNeverAllocatesCards() {
        jdbc.update("UPDATE product SET goods_type=3 WHERE id=1");
        TradeOrder order = unpaid("service", 1); order.setGoodsType(3); orders.save(order);
        assertEquals("PAID", pay("service").getOrderStatus());
        assertEquals(0, cards.selectById(1).getStatus());
        payments.fulfillVirtualOrder(order.getId(), "service-result", 1L);
        assertEquals("COMPLETED", orders.selectById(order.getId()).getOrderStatus());
        assertEquals("service-result", orders.selectById(order.getId()).getServiceVoucher());
    }

    @Test
    void banAfterReservationRejectsPaymentAndOwnerCanRecoverStock() {
        TradeOrder order = intent("banned-after-hold");
        var held = payments.preLockInventory("banned-after-hold");
        jdbc.update("UPDATE merchant_store SET status=3 WHERE id=1");
        assertThrows(BusinessException.class, () -> pay("banned-after-hold"));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        assertEquals(0, cards.selectById(1).getStatus());
        payments.cancelPreLock("banned-after-hold", held.getOwnerToken());
        assertEquals(1, skus.selectById(1).getStockQuantity());
    }

    @Test
    void offShelfOrBannedGoodsCannotBeViewedOrderedOrReserved() {
        TradeOrder order = intent("blocked");
        jdbc.update("UPDATE product SET status=0 WHERE id=1");
        assertThrows(BusinessException.class, () -> products.getProductDetail(1L));
        assertThrows(BusinessException.class, () -> payments.preLockInventory("blocked"));
        jdbc.update("UPDATE product SET status=1 WHERE id=1");
        jdbc.update("UPDATE merchant_store SET status=3 WHERE id=1");
        assertThrows(BusinessException.class, () -> products.getProductDetail(1L));
        assertTrue(products.listPublicProducts(null, 1L).getData().isEmpty());
        assertThrows(BusinessException.class, () -> payments.preLockInventory("blocked"));
        OrderController.CheckoutDTO checkout = new OrderController.CheckoutDTO();
        checkout.setProductId(1L); checkout.setSkuId(1L); checkout.setQuantity(1); checkout.setBuyerContact("buyer");
        assertThrows(BusinessException.class, () -> orderController.checkout(checkout));
        PeerPayDTOs.BuyerCreateRequest buyer = new PeerPayDTOs.BuyerCreateRequest();
        buyer.setProductId(1L); buyer.setSkuId(1L); buyer.setQuantity(1); buyer.setBuyerContact("buyer");
        assertThrows(BusinessException.class, () -> payments.createBuyerPeerPay(buyer));
        PeerPayDTOs.PushPayCreateRequest push = new PeerPayDTOs.PushPayCreateRequest();
        push.setProductId(1L); push.setSkuId(1L); push.setQuantity(1);
        assertThrows(BusinessException.class, () -> payments.createPushPayOrder(push, 1L));
        assertEquals(1, orders.selectCount(null));
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    void productValidationRejectsInvalidSkuBeforePersistingAnyProduct() {
        ProductController.CreateProductDTO dto = new ProductController.CreateProductDTO();
        dto.setTitle("new"); dto.setMainImage("image"); dto.setCategoryId(1L); dto.setGoodsType(3);
        dto.setMinPrice(java.math.BigDecimal.TEN);
        ProductController.SkuDTO sku = new ProductController.SkuDTO();
        sku.setSkuCode("unique"); sku.setPrice(java.math.BigDecimal.TEN); sku.setStockQuantity(1); sku.setSpecData("[]");
        dto.setSkus(List.of(sku));
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            assertThrows(BusinessException.class, () -> products.createProduct(dto));
            sku.setSpecData("{}"); sku.setPrice(new java.math.BigDecimal("-1"));
            assertThrows(BusinessException.class, () -> products.createProduct(dto));
            sku.setPrice(java.math.BigDecimal.TEN); sku.setStockQuantity(-1);
            assertThrows(BusinessException.class, () -> products.createProduct(dto));
            sku.setStockQuantity(1); dto.setSkus(List.of(sku, sku));
            assertThrows(BusinessException.class, () -> products.createProduct(dto));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class));
            dto.setSkus(List.of(sku));
            assertEquals(3, products.createProduct(dto).getData().getGoodsType());
        }
    }

    @Test
    void newOrderNumbersUseFullUuidAndRejectInvalidNegotiation() {
        OrderController.CheckoutDTO dto = new OrderController.CheckoutDTO();
        dto.setProductId(1L); dto.setSkuId(1L); dto.setQuantity(1); dto.setBuyerContact("buyer");
        String first = (String) orderController.checkout(dto).getData().get("orderSn");
        String second = (String) orderController.checkout(dto).getData().get("orderSn");
        assertTrue(first.matches("ORD[0-9a-f]{32}")); assertNotEquals(first, second);
        PeerPayDTOs.PushPayCreateRequest push = new PeerPayDTOs.PushPayCreateRequest();
        push.setProductId(1L); push.setSkuId(1L); push.setQuantity(1); push.setNegotiatedPrice(new java.math.BigDecimal("-1"));
        assertThrows(BusinessException.class, () -> payments.createPushPayOrder(push, 1L));
        assertEquals(2, orders.selectCount(null));
    }

    @Test
    void publicProductAndQuickSearchNeverExposePrivateStoreFields() throws Exception {
        unpaid("privacy", 1);
        for (var request : List.of(get("/api/v1/public/products/1"),
                post("/api/v1/public/orders/quick-search").contentType("application/json").content("{\"orderSn\":\"ORD-privacy\",\"contact\":\"buyer@example.com\",\"queryToken\":\"query-privacy\"}"))) {
            mvc.perform(request).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.store.id").value(1))
                    .andExpect(jsonPath("$.data.store.storeName").value("store1"))
                    .andExpect(jsonPath("$.data.store.ownerName").doesNotExist())
                    .andExpect(jsonPath("$.data.store.idCardNumber").doesNotExist())
                    .andExpect(jsonPath("$.data.store.commissionRate").doesNotExist())
                    .andExpect(jsonPath("$.data.store.feeStatus").doesNotExist());
        }
    }

    private OrderController.CheckoutDTO checkoutRequest() {
        var dto = new OrderController.CheckoutDTO();
        dto.setProductId(1L); dto.setSkuId(1L); dto.setQuantity(1); dto.setBuyerContact("buyer@example.com");
        return dto;
    }

    @Test
    void concurrentIdempotentRequestsCreateOneOrderAndReturnSameResult() throws Exception {
        var dto = checkoutRequest();
        var responses = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        race(8, i -> responses.add((String) idempotency.execute("checkout:guest", "concurrent-key-1234", dto,
                () -> orderController.checkout(dto).getData()).get("orderSn")));
        assertEquals(1, new java.util.HashSet<>(responses).size());
        assertEquals(1, orders.selectCount(null));
        assertEquals(1, items.selectCount(null));
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class));
    }

    @Test
    void sameKeyWithChangedParametersIsRejectedButReplaySurvivesCatalogChanges() {
        var dto = checkoutRequest();
        var first = idempotency.execute("checkout:guest", "parameter-key-1234", dto, () -> orderController.checkout(dto).getData());
        jdbc.update("UPDATE product SET status=0 WHERE id=1");
        assertEquals(first, idempotency.execute("checkout:guest", "parameter-key-1234", dto,
                () -> { throw new AssertionError("重放不能再执行业务"); }));
        dto.setQuantity(2);
        BusinessException error = assertThrows(BusinessException.class, () -> idempotency.execute("checkout:guest", "parameter-key-1234", dto,
                () -> { throw new AssertionError("冲突不能执行业务"); }));
        assertEquals(409, error.getCode());
        assertEquals(1, orders.selectCount(null));
    }

    @Test
    void failedOrderAndIdempotencyClaimRollBackTogetherAndSameKeyCanRetry() {
        var dto = checkoutRequest();
        assertThrows(BusinessException.class, () -> idempotency.execute("checkout:guest", "rollback-key-12345", dto, () -> {
            orderController.checkout(dto); throw new BusinessException("injected failure");
        }));
        assertEquals(0, orders.selectCount(null));
        assertEquals(0, items.selectCount(null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class));
        idempotency.execute("checkout:guest", "rollback-key-12345", dto, () -> orderController.checkout(dto).getData());
        assertEquals(1, orders.selectCount(null));
    }

    @Test
    void uniqueOrderCollisionRetriesWholeTransactionWithoutLeavingExtraOrders() {
        var dto = checkoutRequest(); var attempts = new AtomicInteger();
        idempotency.execute("checkout:guest", "collision-key-1234", dto, () -> {
            var result = orderController.checkout(dto).getData();
            if (attempts.incrementAndGet() < 3) throw new org.springframework.dao.DuplicateKeyException("injected order number collision");
            return result;
        });
        assertEquals(3, attempts.get());
        assertEquals(1, orders.selectCount(null));
        assertEquals(1, items.selectCount(null));
    }

    @Test
    void scopesKeepGuestCheckoutAndBuyerRequestsSeparate() {
        var dto = checkoutRequest();
        var checkout = idempotency.execute("checkout:guest", "scope-key-12345678", dto, () -> java.util.Map.of("marker", "checkout"));
        var buyer = idempotency.execute("buyer:guest", "scope-key-12345678", dto, () -> java.util.Map.of("marker", "buyer"));
        assertNotEquals(checkout, buyer);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class));
    }

    @Test
    void checkoutHttpRequiresKeyAndReturnsOriginalOrderOnRetry() throws Exception {
        String body = "{\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"buyer\"}";
        mvc.perform(post("/api/v1/public/orders/checkout").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        String first = mvc.perform(post("/api/v1/public/orders/checkout").header("Idempotency-Key", "http-key-123456789")
                .contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/v1/public/orders/checkout").header("Idempotency-Key", "http-key-123456789")
                .contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString();
        assertEquals(first, second); assertEquals(1, orders.selectCount(null));
    }

    @Test
    void buyerHttpReplayReturnsOriginalShareAndQueryCredentials() throws Exception {
        String body = "{\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"buyer\"}";
        String first = mvc.perform(post("/api/v1/peer-pay/buyer/create").header("Idempotency-Key", "buyer-key-12345678")
                .contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/v1/peer-pay/buyer/create").header("Idempotency-Key", "buyer-key-12345678")
                .contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString();
        assertEquals(first, second); assertEquals(1, orders.selectCount(null));
    }

    @Test
    void merchantPushReplayIsScopedToSessionMerchantAndOperator() {
        var push = new PeerPayDTOs.PushPayCreateRequest(); push.setProductId(1L); push.setSkuId(1L); push.setQuantity(1);
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentMerchantId).thenReturn(1L);
            security.when(SecurityUtils::getCurrentUserId).thenReturn(11L);
            var first = orderController.createPushPayOrder(push, "merchant-key-12345").getData();
            assertEquals(first, orderController.createPushPayOrder(push, "merchant-key-12345").getData());
            security.when(SecurityUtils::getCurrentUserId).thenReturn(12L);
            assertNotEquals(first.get("orderSn"), orderController.createPushPayOrder(push, "merchant-key-12345").getData().get("orderSn"));
            security.when(SecurityUtils::getCurrentMerchantId).thenReturn(2L);
            security.when(SecurityUtils::getCurrentUserId).thenReturn(22L);
            push.setProductId(2L); push.setSkuId(2L);
            assertNotEquals(first.get("orderSn"), orderController.createPushPayOrder(push, "merchant-key-12345").getData().get("orderSn"));
        }
        assertEquals(3, orders.selectCount(null));
    }

    @Test
    void fingerprintIgnoresMapPropertyOrderAndRejectsOuterTransactionUsage() {
        var first = new java.util.LinkedHashMap<String,Object>(); first.put("a", 1); first.put("b", 2);
        var second = new java.util.LinkedHashMap<String,Object>(); second.put("b", 2); second.put("a", 1);
        var response = idempotency.execute("canonical", "canonical-key-1234", first, () -> java.util.Map.of("value", "original"));
        assertEquals(response, idempotency.execute("canonical", "canonical-key-1234", second,
                () -> { throw new AssertionError("字段顺序不应触发重复业务"); }));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.execute(status -> {
            assertThrows(IllegalStateException.class, () -> idempotency.execute("outer", "outer-key-12345678", first,
                    () -> { throw new AssertionError("外部事务中不应执行业务"); }));
            return null;
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class));
    }

    @Test
    void missingReservationOwnershipHeaderReturns400WithoutReleasingInventory() throws Exception {
        intent("missing-owner-header"); payments.preLockInventory("missing-owner-header");
        mvc.perform(post("/api/v1/peer-pay/cancel-lock/missing-owner-header"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertEquals(0, skus.selectById(1).getStockQuantity());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder receiptLookup(String token, String receipt) {
        return post("/api/v1/public/orders/quick-search").contentType("application/json").content(
                "{\"orderSn\":\"ORD-" + token + "\",\"contact\":\"buyer@example.com\",\"queryToken\":\"" + receipt + "\"}");
    }

    @Test
    void completedCashierNeverExposesDeliveryOrPrivateReceipt() throws Exception {
        TradeOrder order = unpaid("cashier-privacy", 1);
        order.setOrderStatus("COMPLETED"); order.setPeerStatus("PAID"); order.setServiceVoucher("private-service-secret");
        order.setPeerPayerId("private-payer"); orders.save(order);
        var item = items.findByOrderSn(order.getOrderSn()).get(0); item.setCardDetails("private-card-secret"); items.save(item);
        for (int type : List.of(2, 3)) {
            order.setGoodsType(type); orders.save(order);
            String body = mvc.perform(get("/api/v1/peer-pay/detail/cashier-privacy")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.serviceVoucher").doesNotExist())
                    .andExpect(jsonPath("$.data.queryToken").doesNotExist()).andReturn().getResponse().getContentAsString();
            for (String secret : List.of("private-card-secret", "private-service-secret", "private-payer", order.getQueryToken())) assertFalse(body.contains(secret));
        }
    }

    @Test
    void buyerReceiptReturnsCompletedCardsThroughExplicitWhitelist() throws Exception {
        TradeOrder order = unpaid("receipt-card", 1); pay("receipt-card");
        mvc.perform(receiptLookup("receipt-card", order.getQueryToken())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.items[0].cardDetails").value("secret"))
                .andExpect(jsonPath("$.data.order.queryToken").doesNotExist())
                .andExpect(jsonPath("$.data.order.peerPayToken").doesNotExist())
                .andExpect(jsonPath("$.data.order.peerPayerId").doesNotExist())
                .andExpect(jsonPath("$.data.order.payTradeNo").doesNotExist())
                .andExpect(jsonPath("$.data.order.commissionAmount").doesNotExist());
        for (String wrong : List.of("wrong-secret", "receipt-card", "")) {
            mvc.perform(receiptLookup("receipt-card", wrong)).andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    @Test
    void buyerCanRetrieveCompletedCustomServiceButNotBeforeCompletion() throws Exception {
        TradeOrder order = unpaid("receipt-service", 1); order.setGoodsType(3); order.setServiceVoucher("private-service"); orders.save(order);
        mvc.perform(receiptLookup("receipt-service", order.getQueryToken())).andExpect(jsonPath("$.data.order.serviceVoucher").isEmpty());
        order.setOrderStatus("COMPLETED"); orders.save(order);
        mvc.perform(receiptLookup("receipt-service", order.getQueryToken())).andExpect(jsonPath("$.data.order.serviceVoucher").value("private-service"))
                .andExpect(jsonPath("$.data.items[0].cardDetails").isEmpty());
    }

    @Test
    void deprecatedGetNeverReturnsAssetsEvenWithCorrectCredentialsInUrl() throws Exception {
        TradeOrder order = unpaid("retired-query", 1); pay("retired-query");
        mvc.perform(get("/api/v1/public/orders/quick-search").param("orderSn", order.getOrderSn())
                .param("contact", order.getBuyerContact()).param("queryToken", order.getQueryToken()))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value(410)).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void malformedLookupCannotRevealWhetherOrderOrContactExists() throws Exception {
        unpaid("lookup-error", 1);
        String wrongReceipt = mvc.perform(receiptLookup("lookup-error", "wrong")).andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(receiptLookup("missing-order", "wrong")).andReturn().getResponse().getContentAsString();
        assertEquals(wrongReceipt, missing);
        mvc.perform(post("/api/v1/public/orders/quick-search").contentType("application/json")
                        .content("{\"orderSn\":\"ORD-lookup-error\",\"contact\":\"buyer@example.com\"}"))
                .andExpect(jsonPath("$.code").value(400)).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void redisFailureOrRateLimitNeverDowngradesToAnonymousDelivery() throws Exception {
        TradeOrder order = unpaid("lookup-rate", 1); pay("lookup-rate");
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(0L);
        mvc.perform(receiptLookup("lookup-rate", order.getQueryToken())).andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.data").isEmpty());
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenThrow(new IllegalStateException("internal-cache-error"));
        mvc.perform(receiptLookup("lookup-rate", order.getQueryToken())).andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void creationIssuesPrivateReceiptWithoutPuttingItIntoPaymentShare() throws Exception {
        var dto = checkoutRequest(); String receipt = (String) orderController.checkout(dto).getData().get("queryToken");
        assertTrue(receipt.matches("[0-9a-f]{32}"));
        PeerPayDTOs.BuyerCreateRequest request = new PeerPayDTOs.BuyerCreateRequest();
        request.setProductId(1L); request.setSkuId(1L); request.setQuantity(1); request.setBuyerContact("buyer@example.com");
        var created = payments.createBuyerPeerPay(request); String buyerReceipt = (String) created.get("queryToken");
        assertTrue(buyerReceipt.matches("[0-9a-f]{32}"));
        assertFalse(created.get("cashierUrl").toString().contains(buyerReceipt));
        assertFalse(created.get("wechatShareText").toString().contains(buyerReceipt));
        PeerPayDTOs.PushPayCreateRequest push = new PeerPayDTOs.PushPayCreateRequest();
        push.setProductId(1L); push.setSkuId(1L); push.setQuantity(1);
        var pushed = payments.createPushPayOrder(push, 1L); String pushedReceipt = (String) pushed.get("queryToken");
        assertEquals("商户代客推单客户", pushed.get("buyerContact"));
        assertTrue(pushedReceipt.matches("[0-9a-f]{32}"));
        assertFalse(pushed.get("cashierUrl").toString().contains(pushedReceipt));
        assertFalse(pushed.get("wechatShareText").toString().contains(pushedReceipt));
    }

    @Test
    void serviceDeliveryLogsDoNotPrintBuyerOrVoucherSecrets() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(com.shop.b2b2c.service.impl.PeerPayServiceImpl.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            jdbc.update("UPDATE product SET goods_type=3 WHERE id=1");
            TradeOrder order = unpaid("redacted-log", 1); order.setGoodsType(3); orders.save(order); pay("redacted-log");
            payments.fulfillVirtualOrder(order.getId(), "unique-private-voucher", 1L);
            String logs = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(logs.contains("unique-private-voucher")); assertFalse(logs.contains("buyer@example.com")); assertFalse(logs.contains("verified-payer"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void correctReceiptCannotBeUsedWithAnotherOrderOrContact() throws Exception {
        TradeOrder order = unpaid("bound-receipt", 1); pay("bound-receipt");
        mvc.perform(receiptLookup("another-order", order.getQueryToken())).andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(post("/api/v1/public/orders/quick-search").contentType("application/json").content(
                        "{\"orderSn\":\"ORD-bound-receipt\",\"contact\":\"other@example.com\",\"queryToken\":\"" + order.getQueryToken() + "\"}"))
                .andExpect(jsonPath("$.code").value(400)).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void untrustedForwardedHeadersCannotChangeRateLimitIdentity() throws Exception {
        TradeOrder order = unpaid("forwarded-rate", 1);
        mvc.perform(receiptLookup("forwarded-rate", order.getQueryToken()).header("X-Forwarded-For", "192.0.2.1"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(receiptLookup("forwarded-rate", order.getQueryToken()).header("X-Forwarded-For", "198.51.100.2"))
                .andExpect(jsonPath("$.code").value(200));
        var keys = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis, times(4)).execute(any(), keys.capture(), any(), eq("300"));
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(2));
        assertEquals(keys.getAllValues().get(1), keys.getAllValues().get(3));
    }

    @Test
    void malformedReceiptBodyIsRejectedWithoutEchoingItsCredentials() throws Exception {
        String body = mvc.perform(post("/api/v1/public/orders/quick-search").contentType("application/json")
                        .content("{\"queryToken\":\"unique-private-parser-secret\",broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("unique-private-parser-secret"));
        assertFalse(body.contains("JsonParseException"));
    }

    @Test
    void r04MerchantAndAdminOrderListsDoNotLeakQueryTokenOrCardSecrets() throws Exception {
        TradeOrder order = new TradeOrder();
        order.setOrderSn("ORD-R04-LIST-01");
        order.setMerchantId(1L);
        order.setBuyerContact("r04buyer@example.com");
        order.setGoodsType(2);
        order.setOrderStatus("COMPLETED");
        order.setPeerStatus("PAID");
        order.setPayAmount(new java.math.BigDecimal("50.00"));
        order.setTotalAmount(new java.math.BigDecimal("50.00"));
        order.setQueryToken("secretquerytoken1234567890abcdef");
        order.setServiceVoucher("PLAIN-CARD-SERIAL-9999");
        order.setPosterUrl("data:image/png;base64,largeposterdata");
        orders.save(order);

        jdbc.update("INSERT INTO trade_order_item(order_id, order_sn, product_id, sku_id, sku_code, product_title, spec_info, price, quantity, subtotal, card_details) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                order.getId(), order.getOrderSn(), 1L, 1L, "SKU-01", "Virtual Card Product", "100元面值",
                new java.math.BigDecimal("50.00"), 2, new java.math.BigDecimal("100.00"),
                "CARD-SECRET-AAA-111\nCARD-SECRET-BBB-222");

        // 1. Merchant order list check
        authUser("MERCHANT_ADMIN", 1L);
        String merchantToken = loginToken();
        String merchantListResp = mvc.perform(get("/api/v1/merchant/orders")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].order.queryToken").doesNotExist())
                .andExpect(jsonPath("$.data[0].order.posterUrl").doesNotExist())
                .andExpect(jsonPath("$.data[0].order.serviceVoucher").value("系统卡密已自动交付"))
                .andExpect(jsonPath("$.data[0].items[0].cardDetails").value("已自动发放 (共2张)"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(merchantListResp.contains("secretquerytoken1234567890abcdef"));
        assertFalse(merchantListResp.contains("CARD-SECRET-AAA-111"));
        assertFalse(merchantListResp.contains("CARD-SECRET-BBB-222"));
        assertFalse(merchantListResp.contains("PLAIN-CARD-SERIAL-9999"));

        // 2. Admin order list check
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("SUPERADMIN", null);
        String adminToken = loginToken();

        String adminListResp = mvc.perform(get("/api/v1/admin/orders")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orders[0].queryToken").doesNotExist())
                .andExpect(jsonPath("$.data.orders[0].posterUrl").doesNotExist())
                .andExpect(jsonPath("$.data.orders[0].serviceVoucher").value("系统卡密已自动交付"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(adminListResp.contains("secretquerytoken1234567890abcdef"));
        assertFalse(adminListResp.contains("PLAIN-CARD-SERIAL-9999"));
    }

    @Test
    void r04SingleOrderDetailEndpointAllowsAuthorizedMerchantToViewCardDetails() throws Exception {
        TradeOrder order = new TradeOrder();
        order.setOrderSn("ORD-R04-DETAIL-01");
        order.setMerchantId(1L);
        order.setBuyerContact("r04buyer@example.com");
        order.setGoodsType(2);
        order.setOrderStatus("COMPLETED");
        order.setPeerStatus("PAID");
        order.setPayAmount(new java.math.BigDecimal("50.00"));
        order.setTotalAmount(new java.math.BigDecimal("50.00"));
        order.setQueryToken("secretquerytoken-detail-123456");
        order.setServiceVoucher("CARD-VOUCHER-SECRET");
        orders.save(order);

        jdbc.update("INSERT INTO trade_order_item(order_id, order_sn, product_id, sku_id, sku_code, product_title, spec_info, price, quantity, subtotal, card_details) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                order.getId(), order.getOrderSn(), 1L, 1L, "SKU-01", "Virtual Card Product", "100元面值",
                new java.math.BigDecimal("50.00"), 1, new java.math.BigDecimal("50.00"),
                "CARD-SECRET-UNMASKED-XYZ");

        authUser("MERCHANT_ADMIN", 1L);
        String token = loginToken();

        mvc.perform(get("/api/v1/merchant/orders/" + order.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.order.queryToken").doesNotExist())
                .andExpect(jsonPath("$.data.order.posterUrl").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].cardDetails").value("CARD-SECRET-UNMASKED-XYZ"));
    }

    @Test
    void r04SingleOrderDetailRejectsCrossTenantAccess() throws Exception {
        TradeOrder order = new TradeOrder();
        order.setOrderSn("ORD-R04-TENANT-01");
        order.setMerchantId(1L);
        order.setBuyerContact("cross-buyer@example.com");
        order.setQueryToken("cross-query-token-12345");
        order.setGoodsType(2);
        order.setTotalAmount(new java.math.BigDecimal("50.00"));
        order.setPayAmount(new java.math.BigDecimal("50.00"));
        order.setOrderStatus("COMPLETED");
        orders.save(order);

        authUser("MERCHANT_ADMIN", 2L);
        String token = loginToken();

        mvc.perform(get("/api/v1/merchant/orders/" + order.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void r04MerchantStaffRoleInSingleOrderDetailMasksCardSecrets() throws Exception {
        TradeOrder order = new TradeOrder();
        order.setOrderSn("ORD-R04-STAFF-01");
        order.setMerchantId(1L);
        order.setBuyerContact("staffview@example.com");
        order.setQueryToken("staff-query-token-12345");
        order.setGoodsType(2);
        order.setTotalAmount(new java.math.BigDecimal("50.00"));
        order.setPayAmount(new java.math.BigDecimal("50.00"));
        order.setOrderStatus("COMPLETED");
        orders.save(order);

        jdbc.update("INSERT INTO trade_order_item(order_id, order_sn, product_id, sku_id, sku_code, product_title, spec_info, price, quantity, subtotal, card_details) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                order.getId(), order.getOrderSn(), 1L, 1L, "SKU-01", "Virtual Card Product", "100元面值",
                new java.math.BigDecimal("50.00"), 2, new java.math.BigDecimal("100.00"),
                "STAFF-SECRET-111\nSTAFF-SECRET-222");

        authUser("MERCHANT_STAFF", 1L);
        String token = loginToken();

        String resp = mvc.perform(get("/api/v1/merchant/orders/" + order.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].cardDetails").value("已自动发放 (员工账号无权查阅明文)"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(resp.contains("STAFF-SECRET-111"));
        assertFalse(resp.contains("STAFF-SECRET-222"));
    }

    @Test
    void r04MerchantCardsListRestrictedToStoreManagerAndSetsNoStore() throws Exception {
        jdbc.update("INSERT INTO product_virtual_card(id, product_id, sku_id, merchant_id, card_content, status) " +
                "VALUES(1001, 1, 1, 1, 'RAW-CARD-1001-PASS', 0)");
        jdbc.update("INSERT INTO product_virtual_card(id, product_id, sku_id, merchant_id, card_content, status) " +
                "VALUES(1002, 1, 1, 2, 'RAW-CARD-1002-PASS', 0)");

        // 1. Staff is rejected with 403
        authUser("MERCHANT_STAFF", 1L);
        String staffToken = loginToken();

        mvc.perform(get("/api/v1/merchant/cards")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // 2. Merchant Admin can access, Cache-Control: no-store
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("MERCHANT_ADMIN", 1L);
        String adminToken = loginToken();

        mvc.perform(get("/api/v1/merchant/cards")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        // 3. Single card detail: own store works, other store returns 404
        mvc.perform(get("/api/v1/merchant/cards/1001")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.cardContent").value("RAW-CARD-1001-PASS"));

        mvc.perform(get("/api/v1/merchant/cards/1002")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void r02MerchantStaffRoleCannotPerformPrivilegedOperations() throws Exception {
        physicalProduct();

        authUser("MERCHANT_STAFF", 1L);
        String staffToken = loginToken();

        // 1. Staff cannot update store profile or custom pay QR code
        MerchantStore storeUpdate = new MerchantStore();
        storeUpdate.setNotice("Staff malicious notice");
        storeUpdate.setCustomPayQrcode("https://evil.test/pay.png");
        mvc.perform(put("/api/v1/merchant/profile")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(storeUpdate)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        assertNotEquals("Staff malicious notice", jdbc.queryForObject("SELECT notice FROM merchant_store WHERE id=1", String.class));

        // 2. Staff cannot import virtual cards
        ProductController.BatchImportCardDTO cardDto = new ProductController.BatchImportCardDTO();
        cardDto.setProductId(1L);
        cardDto.setSkuId(1L);
        cardDto.setCardContents(List.of("STAFF-UNAUTHORIZED-CARD-1"));
        mvc.perform(post("/api/v1/merchant/cards/import")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(cardDto)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product_virtual_card WHERE card_content='STAFF-UNAUTHORIZED-CARD-1'", Integer.class));

        // 3. Staff cannot create new products
        ProductController.CreateProductDTO createDto = new ProductController.CreateProductDTO();
        createDto.setTitle("Staff Created Product");
        createDto.setMainImage("https://example.test/img.png");
        createDto.setCategoryId(1L);
        createDto.setGoodsType(1);
        ProductController.SkuDTO skuDto = new ProductController.SkuDTO();
        skuDto.setSkuCode("SKU-STAFF-NEW");
        skuDto.setPrice(new java.math.BigDecimal("99.00"));
        skuDto.setStockQuantity(10);
        skuDto.setSpecData("{\"color\":\"red\"}");
        createDto.setSkus(List.of(skuDto));
        mvc.perform(post("/api/v1/merchant/products")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(createDto)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE title='Staff Created Product'", Integer.class));

        // 4. Staff cannot edit product prices or status
        var editRequest = productEdit(editing.get(1L, 1L).revision(), "staff-tampered", "1.00");
        mvc.perform(put("/api/v1/merchant/products/1")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(editRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        assertNotEquals("staff-tampered", jdbc.queryForObject("SELECT title FROM product WHERE id=1", String.class));

        mvc.perform(patch("/api/v1/merchant/products/1/status")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(new com.shop.b2b2c.dto.ProductEditingDTOs.Status(editRequest.revision(), 0))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM product WHERE id=1", Integer.class));

        // 5. Staff cannot manually fulfill virtual order
        TradeOrder vOrder = new TradeOrder();
        vOrder.setOrderSn("ORD-R02-VIRTUAL");
        vOrder.setMerchantId(1L);
        vOrder.setBuyerContact("vbuyer@example.com");
        vOrder.setGoodsType(3);
        vOrder.setTotalAmount(new java.math.BigDecimal("30.00"));
        vOrder.setPayAmount(new java.math.BigDecimal("30.00"));
        vOrder.setOrderStatus("PAID");
        vOrder.setQueryToken("vquerytoken12345");
        orders.save(vOrder);

        mvc.perform(post("/api/v1/merchant/orders/" + vOrder.getId() + "/fulfill-virtual")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content("{\"serviceVoucher\":\"staff-delivered\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        assertNull(orders.selectById(vOrder.getId()).getServiceVoucher());

        // 6. Switch to MERCHANT_ADMIN -> authorized operations succeed
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("MERCHANT_ADMIN", 1L);
        String adminToken = loginToken();

        mvc.perform(put("/api/v1/merchant/profile")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(storeUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("Staff malicious notice", jdbc.queryForObject("SELECT notice FROM merchant_store WHERE id=1", String.class));
    }

    @Test
    void r03CorsPreflightAllowsReservationTokenForAllowedOrigins() throws Exception {
        String allowedHeaders = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/peer-pay/cancel-lock/some-token")
                        .header("Origin", "https://admin.example.test")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-Reservation-Token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://admin.example.test"))
                .andReturn().getResponse().getHeader("Access-Control-Allow-Headers");
        assertNotNull(allowedHeaders);
        assertTrue(allowedHeaders.contains("X-Reservation-Token"));

        // Untrusted origin does not receive Access-Control-Allow-Origin
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/peer-pay/cancel-lock/some-token")
                        .header("Origin", "https://evil-untrusted-origin.test")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-Reservation-Token"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void r03ReservationReleaseWithCorrectTokenSucceedsAndIncorrectFails() throws Exception {
        TradeOrder order = intent("r03-release");
        var lock = payments.preLockInventory("r03-release", "r03-key-1");
        assertNotNull(lock.getOwnerToken());

        // 1. Release with wrong token fails with 403
        mvc.perform(post("/api/v1/peer-pay/cancel-lock/r03-release")
                        .header("X-Reservation-Token", "wrong-owner-token"))
                .andExpect(jsonPath("$.code").value(403));

        // 2. Release with correct X-Reservation-Token succeeds
        mvc.perform(post("/api/v1/peer-pay/cancel-lock/r03-release")
                        .header("X-Reservation-Token", lock.getOwnerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. Releasing again with same token is idempotent
        mvc.perform(post("/api/v1/peer-pay/cancel-lock/r03-release")
                        .header("X-Reservation-Token", lock.getOwnerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void r05CardAssetTieredEncryptionAndDecryptionProtection() throws Exception {
        // Clear card fixture from setup so we have a known state
        jdbc.update("DELETE FROM product_virtual_card WHERE merchant_id=1");

        authUser("MERCHANT_ADMIN", 1L);
        String adminToken = loginToken();

        String cardPlain = "ACTIVATE-CODE-98765-SECRET";
        var importDto = new ProductController.BatchImportCardDTO();
        importDto.setProductId(1L);
        importDto.setSkuId(1L);
        importDto.setCardContents(List.of(cardPlain));

        // 1. Merchant Admin imports card
        mvc.perform(post("/api/v1/merchant/cards/import")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(importDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2. Verify database: card_content is ENCRYPTED with ENC:v1: and plaintext DOES NOT appear in DB
        String dbCardContent = jdbc.queryForObject(
                "SELECT card_content FROM product_virtual_card WHERE sku_id=1 AND status=0",
                String.class);
        assertNotNull(dbCardContent);
        assertTrue(dbCardContent.startsWith("ENC:v1:"), "Database card_content must start with ENC:v1:");
        assertFalse(dbCardContent.contains("ACTIVATE-CODE"), "Database must never store plaintext card!");

        // Verify card_fingerprint is stored as 64-char SHA-256
        String dbFingerprint = jdbc.queryForObject(
                "SELECT card_fingerprint FROM product_virtual_card WHERE sku_id=1 AND status=0",
                String.class);
        assertNotNull(dbFingerprint);
        assertEquals(64, dbFingerprint.length());

        // 3. Authorized manager queries single card detail: cardContent is DECRYPTED
        Long cardId = jdbc.queryForObject("SELECT id FROM product_virtual_card WHERE sku_id=1 AND status=0", Long.class);
        mvc.perform(get("/api/v1/merchant/cards/" + cardId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cardContent").value(cardPlain));

        // 4. Create and fulfill order via payment event
        TradeOrder order = unpaid("r05-fulfill", 1);

        paymentEventService.processPaymentEvent(com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel("WECHAT")
                .payTradeNo("TX_R05_001")
                .orderSn(order.getOrderSn())
                .merchantId(1L)
                .payAmount(new java.math.BigDecimal("10.00"))
                .currency("CNY")
                .rawPayload("{}")
                .build());

        // 5. Verify database after fulfillment:
        // - trade_order_item.card_details is ENCRYPTED with ENC:v1:
        String dbItemCardDetails = jdbc.queryForObject(
                "SELECT card_details FROM trade_order_item WHERE order_sn=?",
                String.class, order.getOrderSn());
        assertNotNull(dbItemCardDetails);
        assertTrue(dbItemCardDetails.startsWith("ENC:v1:"), "trade_order_item.card_details must be encrypted in DB");
        assertFalse(dbItemCardDetails.contains("ACTIVATE-CODE"), "No plain cards in order item DB!");

        // - trade_order.service_voucher is an audit summary, does NOT contain plaintext cards
        String dbServiceVoucher = jdbc.queryForObject(
                "SELECT service_voucher FROM trade_order WHERE order_sn=?",
                String.class, order.getOrderSn());
        assertNotNull(dbServiceVoucher);
        assertEquals("系统卡密已自动交付 (共1张)", dbServiceVoucher);

        // 6. Authorized buyer queries order with queryToken: cardDetails is DECRYPTED
        mvc.perform(receiptLookup("r05-fulfill", order.getQueryToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].cardDetails").value(cardPlain));

        // 7. Authorized manager queries order: cardDetails is DECRYPTED
        mvc.perform(get("/api/v1/merchant/orders/" + order.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].cardDetails").value(cardPlain));

        // 8. Staff queries order: cardDetails is MASKED
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("MERCHANT_STAFF", 1L);
        String staffToken = loginToken();
        mvc.perform(get("/api/v1/merchant/orders/" + order.getId())
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].cardDetails").value("已自动发放 (员工账号无权查阅明文)"));
    }

    @Test
    void r25CardDeduplicationByFingerprintWithoutStoreScanning() throws Exception {
        // Clear card fixtures from setup
        jdbc.update("DELETE FROM product_virtual_card");

        authUser("MERCHANT_ADMIN", 1L);
        String adminToken1 = loginToken();

        String cardCode = "CARD-DEDUP-UNIQUE-XYZ-123";

        // 1. Initial import of cardCode into store 1 / sku 1 succeeds
        var import1 = new ProductController.BatchImportCardDTO();
        import1.setProductId(1L);
        import1.setSkuId(1L);
        import1.setCardContents(List.of(cardCode));

        mvc.perform(post("/api/v1/merchant/cards/import")
                        .header("Authorization", "Bearer " + adminToken1)
                        .contentType("application/json")
                        .content(json.writeValueAsString(import1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2. Importing the same cardCode again into store 1 is REJECTED with 409
        mvc.perform(post("/api/v1/merchant/cards/import")
                        .header("Authorization", "Bearer " + adminToken1)
                        .contentType("application/json")
                        .content(json.writeValueAsString(import1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));

        // 3. Duplicate within the same batch is REJECTED with 400
        var intraBatchDup = new ProductController.BatchImportCardDTO();
        intraBatchDup.setProductId(1L);
        intraBatchDup.setSkuId(1L);
        intraBatchDup.setCardContents(List.of("UNIQUE-INTRA-1", " UNIQUE-INTRA-1 "));

        mvc.perform(post("/api/v1/merchant/cards/import")
                        .header("Authorization", "Bearer " + adminToken1)
                        .contentType("application/json")
                        .content(json.writeValueAsString(intraBatchDup)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 4. Cross-store isolation: store 2 CAN import the same cardCode
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("MERCHANT_ADMIN", 2L);
        String adminToken2 = loginToken();

        var import2 = new ProductController.BatchImportCardDTO();
        import2.setProductId(2L);
        import2.setSkuId(2L);
        import2.setCardContents(List.of(cardCode));

        mvc.perform(post("/api/v1/merchant/cards/import")
                        .header("Authorization", "Bearer " + adminToken2)
                        .contentType("application/json")
                        .content(json.writeValueAsString(import2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. Database unique constraint uk_merchant_fingerprint directly blocks duplicate insert
        String fp = new com.shop.b2b2c.service.CardCipherService().fingerprint(cardCode);
        assertThrows(Exception.class, () -> {
            jdbc.update("INSERT INTO product_virtual_card(product_id, sku_id, merchant_id, card_fingerprint, card_content, status) " +
                    "VALUES(1, 1, 1, ?, 'ENC:direct-insert', 0)", fp);
        });
    }

    @Test
    void r15DatabaseStorageLayerCheckConstraintsDefenseInDepth() {
        // 1. product_sku: stock_quantity cannot be negative
        assertThrows(Exception.class, () -> {
            jdbc.update("INSERT INTO product_sku(id, product_id, merchant_id, sku_code, spec_data, price, stock_quantity) " +
                    "VALUES(991, 1, 1, 'SKU-NEG-STOCK', '{}', 10.00, -1)");
        });

        // 2. product_sku: price cannot be negative
        assertThrows(Exception.class, () -> {
            jdbc.update("INSERT INTO product_sku(id, product_id, merchant_id, sku_code, spec_data, price, stock_quantity) " +
                    "VALUES(992, 1, 1, 'SKU-NEG-PRICE', '{}', -0.01, 10)");
        });

        // 3. inventory_reservation: quantity must be strictly greater than 0
        assertThrows(Exception.class, () -> {
            jdbc.update("INSERT INTO inventory_reservation(order_id, sku_id, product_id, merchant_id, quantity, owner_token, status, expires_at) " +
                    "VALUES(993, 1, 1, 1, 0, 'tok-zero', 'HELD', CURRENT_TIMESTAMP)");
        });
        assertThrows(Exception.class, () -> {
            jdbc.update("INSERT INTO inventory_reservation(order_id, sku_id, product_id, merchant_id, quantity, owner_token, status, expires_at) " +
                    "VALUES(994, 1, 1, 1, -5, 'tok-neg', 'HELD', CURRENT_TIMESTAMP)");
        });

        // 4. trade_order: total_amount and pay_amount cannot be negative
        assertThrows(Exception.class, () -> {
            jdbc.update("INSERT INTO trade_order(order_sn, merchant_id, buyer_contact, query_token, total_amount, pay_amount) " +
                    "VALUES('SN-NEG-TOTAL', 1, '13800000000', 'qtok-neg', -10.00, 10.00)");
        });
        assertThrows(Exception.class, () -> {
            jdbc.update("INSERT INTO trade_order(order_sn, merchant_id, buyer_contact, query_token, total_amount, pay_amount) " +
                    "VALUES('SN-NEG-PAY', 1, '13800000000', 'qtok-neg-pay', 10.00, -0.01)");
        });

        // 5. trade_order_item: quantity > 0, price >= 0, subtotal >= 0
        assertThrows(Exception.class, () -> {
            jdbc.update("INSERT INTO trade_order_item(order_id, order_sn, product_id, sku_id, sku_code, product_title, price, quantity, subtotal) " +
                    "VALUES(995, 'SN-ITEM-ZERO-QTY', 1, 1, 'SKU1', 'Test', 10.00, 0, 0.00)");
        });
        assertThrows(Exception.class, () -> {
            jdbc.update("INSERT INTO trade_order_item(order_id, order_sn, product_id, sku_id, sku_code, product_title, price, quantity, subtotal) " +
                    "VALUES(996, 'SN-ITEM-NEG-PRICE', 1, 1, 'SKU1', 'Test', -5.00, 1, 5.00)");
        });

        // 6. Legitimate entries satisfy CHECK constraints and persist successfully
        int insertedSku = jdbc.update("INSERT INTO product_sku(id, product_id, merchant_id, sku_code, spec_data, price, stock_quantity) " +
                "VALUES(997, 1, 1, 'SKU-LEGAL-R15', '{}', 0.00, 0)");
        assertEquals(1, insertedSku);
    }

    @Test
    void r24BoundedOrderListingsBatchItemsAndLightweightStatistics() throws Exception {
        // Clean order tables for isolated assertion
        jdbc.update("DELETE FROM trade_order_item");
        jdbc.update("DELETE FROM trade_order");

        // Create 3 orders for merchant 1
        TradeOrder o1 = unpaid("r24-m1-order1", 2);
        TradeOrder o2 = unpaid("r24-m1-order2", 1);
        TradeOrder o3 = unpaid("r24-m1-order3", 3);

        // Create 1 order for merchant 2
        TradeOrder o4 = unpaid("r24-m2-order4", 1);
        o4.setMerchantId(2L);
        orders.save(o4);

        // 1. Merchant 1: Paged query with pageSize=2 returns exactly first page (2 orders)
        authUser("MERCHANT_ADMIN", 1L);
        String tokenM1 = loginToken();

        mvc.perform(get("/api/v1/merchant/orders")
                        .header("Authorization", "Bearer " + tokenM1)
                        .param("page", "1")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].items").isArray())
                .andExpect(jsonPath("$.data[0].order.queryToken").doesNotExist())
                .andExpect(jsonPath("$.data[0].order.posterUrl").doesNotExist());

        // Merchant 1: Page 2 returns the remaining 1 order
        mvc.perform(get("/api/v1/merchant/orders")
                        .header("Authorization", "Bearer " + tokenM1)
                        .param("page", "2")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1));

        // 2. Merchant 1 Statistics: Uses lightweight projection (findStatisticsProjectionByMerchantId)
        mvc.perform(get("/api/v1/merchant/orders/statistics")
                        .header("Authorization", "Bearer " + tokenM1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalOrders").value(3))
                .andExpect(jsonPath("$.data.pendingOrders").value(3))
                .andExpect(jsonPath("$.data.paidOrders").value(0));

        // 3. Superadmin: Admin order listing supports bounded pagination and platform statistics
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("SUPERADMIN", null);
        String adminToken = loginToken();

        mvc.perform(get("/api/v1/admin/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "1")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalOrders").value(4))
                .andExpect(jsonPath("$.data.orders.length()").value(2))
                .andExpect(jsonPath("$.data.statistics.totalOrders").value(4))
                .andExpect(jsonPath("$.data.orders[0].queryToken").doesNotExist())
                .andExpect(jsonPath("$.data.orders[0].posterUrl").doesNotExist());
    }

    @Test
    void r34UniformExceptionHandlingAndHttpStatusClassification() throws Exception {
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("SUPERADMIN", null);
        String adminToken = loginToken();

        // 1. Missing required query parameter -> HTTP 400 Bad Request
        mvc.perform(put("/api/v1/admin/merchants/1/status")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("缺少必需查询参数")));

        // 2. Unsupported HTTP method -> HTTP 405 Method Not Allowed
        mvc.perform(delete("/api/v1/public/stores/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持当前HTTP请求方法")));

        // 3. Unreadable JSON body -> HTTP 400 Bad Request
        mvc.perform(post("/api/v1/public/orders/checkout")
                        .header("Idempotency-Key", "idemp-unreadable-body-test-123456")
                        .contentType("application/json")
                        .content("{\"badJson\": [}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void r35BigIntIdStringContractAndBoundaryEnforcement() throws Exception {
        authUser("MERCHANT_ADMIN", 1L);
        String token = loginToken();

        // 1. Zero or negative ID in path variable -> BusinessException 400 "订单编号不正确"
        mvc.perform(get("/api/v1/merchant/orders/0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("订单编号不正确"));

        mvc.perform(get("/api/v1/merchant/orders/-1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("订单编号不正确"));

        // 2. Type mismatch / Overflow ID beyond Long.MAX_VALUE in path -> HTTP 400 "请求参数格式不正确"
        mvc.perform(get("/api/v1/merchant/orders/9223372036854775808")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求参数格式不正确"));

        mvc.perform(get("/api/v1/merchant/orders/abc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 3. JSON body with illegal ID (0, negative, leading-zero, or overflow) -> HTTP 400
        var badCheckout = java.util.Map.of(
                "merchantId", "0",
                "productId", 1L,
                "skuId", 1L,
                "quantity", 1,
                "buyerContact", "13800000000"
        );
        mvc.perform(post("/api/v1/public/orders/checkout")
                        .header("Idempotency-Key", "idemp-r35-bad-id-1234567890")
                        .contentType("application/json")
                        .content(json.writeValueAsString(badCheckout)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 4. JSON body with legal string ID parses properly
        var goodCheckout = java.util.Map.of(
                "merchantId", "1",
                "productId", "1",
                "skuId", "1",
                "quantity", 1,
                "buyerContact", "13800000000"
        );
        mvc.perform(post("/api/v1/public/orders/checkout")
                        .header("Idempotency-Key", "idemp-r35-good-id-1234567890")
                        .contentType("application/json")
                        .content(json.writeValueAsString(goodCheckout)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderSn").isNotEmpty());
    }

    @Test
    void r26SlowPosterRenderingDoesNotBlockConcurrentPaymentOrReservationRelease() throws Exception {
        // 1. 创建待付订单并原子持有预占
        TradeOrder order = intent("r26-concurrency");
        order.setPosterUrl(null);
        orders.save(order);
        var held = payments.preLockInventory("r26-concurrency");
        assertEquals("RESERVED_LOCK", held.getPeerStatus());

        // 2. 模拟耗时海报渲染（事务外运行，不持有行锁）
        // 在海报渲染或执行期间，并发进行支付回调
        CountDownLatch renderingStarted = new CountDownLatch(1);
        CountDownLatch paymentCompleted = new CountDownLatch(1);

        Future<String> posterFuture = Executors.newSingleThreadExecutor().submit(() -> {
            renderingStarted.countDown();
            try {
                // 等待支付完成以证明无锁渲染不阻塞支付，且后续更新不会覆盖支付状态
                paymentCompleted.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return payments.regeneratePoster("r26-concurrency");
        });

        assertTrue(renderingStarted.await(5, TimeUnit.SECONDS));

        // 3. 并发完成支付通知（必须立刻成功，不被海报查询或渲染阻塞）
        TradeOrder paidOrder = payments.notifyPaymentSuccess("r26-concurrency", "payer-concurrent", "trade-concurrency-999", "WECHAT");
        assertEquals("PAID", paidOrder.getPeerStatus());
        assertEquals("COMPLETED", paidOrder.getOrderStatus());
        paymentCompleted.countDown();

        String posterBase64 = posterFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(posterBase64);
        assertTrue(posterBase64.startsWith("data:image/png;base64,"));

        // 4. 关键验证：海报更新后，订单状态依然为 PAID 与 COMPLETED，绝未被旧内存快照覆盖成 UNPAID/RESERVED_LOCK
        TradeOrder afterOrder = orders.findById(order.getId()).orElseThrow();
        assertEquals("PAID", afterOrder.getPeerStatus());
        assertEquals("COMPLETED", afterOrder.getOrderStatus());
        assertEquals("trade-concurrency-999", afterOrder.getPayTradeNo());
        assertEquals(posterBase64, orders.findStoredPosterByToken("r26-concurrency"));
    }

    @Test
    void r26PosterIdempotencyDeduplicationAndZeroDbLockInDetail() throws Exception {
        // 1. 发起买家代付建单
        String key = "idemp-r26-dedup-12345678";
        String body = "{\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"13812345678\"}";
        String first = mvc.perform(post("/api/v1/peer-pay/buyer/create")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.posterBase64").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> firstMap = json.readValue(first, Map.class);
        Map<?, ?> firstData = (Map<?, ?>) firstMap.get("data");
        String token = (String) firstData.get("peerPayToken");
        String originalPoster = (String) firstData.get("posterBase64");
        assertNotNull(token);
        assertNotNull(originalPoster);

        // 2. 检查 order_idempotency 表存储：证明 Base64 大字段已被剥离减载
        String idempotencyJson = jdbc.queryForObject("SELECT response_json FROM order_idempotency WHERE response_json LIKE ?",
                String.class, "%" + token + "%");
        assertNotNull(idempotencyJson);
        assertFalse(idempotencyJson.contains("posterBase64"), "order_idempotency must not store large posterBase64 blob");
        assertTrue(idempotencyJson.length() < 2000, "Idempotency JSON must be compact without large poster");

        // 3. 幂等重放：证明重放时能从 trade_order 恢复海报 Base64，响应契约完全一致且不重复光栅化渲染
        String second = mvc.perform(post("/api/v1/peer-pay/buyer/create")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, second, "Idempotent replay must return identical response payload");

        // 4. 公开详情接口减载：GET /detail/{token} 不返回海报大字段，轻量快速
        mvc.perform(get("/api/v1/peer-pay/detail/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderSn").isNotEmpty())
                .andExpect(jsonPath("$.data.posterUrl").doesNotExist());

        // 5. 按需公开海报接口：GET /poster/{token} 返回已保存海报且不重新渲染
        mvc.perform(get("/api/v1/peer-pay/poster/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(originalPoster));

        // 6. 买家查单与常用查询投影减载：底层不加载 poster_url 大字段
        TradeOrder queried = orders.findByPeerPayToken(token).orElseThrow();
        assertNull(queried.getPosterUrl(), "findByPeerPayToken must project out posterUrl");
        TradeOrder queriedBySn = orders.findByOrderSn(queried.getOrderSn()).orElseThrow();
        assertNull(queriedBySn.getPosterUrl(), "findByOrderSn must project out posterUrl");
        TradeOrder queriedByContact = orders.findByOrderSnAndBuyerContact(queried.getOrderSn(), "13812345678").orElseThrow();
        assertNull(queriedByContact.getPosterUrl(), "findByOrderSnAndBuyerContact must project out posterUrl");
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
