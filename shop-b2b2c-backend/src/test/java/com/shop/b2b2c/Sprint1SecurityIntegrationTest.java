/*
 * 文件：Sprint1SecurityIntegrationTest.java
 * 用途：跨Sprint累计的建单幂等、商品/库存/卡密维护、Spring事务、SQL、公开响应及租户安全集成回归。
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @MockBean StringRedisTemplate redis;

    @BeforeEach
    void fixtures() {
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(1L);
        for (String table : List.of("virtual_card_void", "inventory_adjustment", "sys_user", "order_idempotency", "inventory_reservation", "trade_order_item", "trade_order", "product_virtual_card", "product_sku", "product", "merchant_store")) {
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

    @org.junit.jupiter.api.AfterEach
    void clearAuthSessions() {
        cn.dev33.satoken.stp.StpUtil.logout(200L);
    }

    private com.shop.b2b2c.dto.ProductEditingDTOs.Update productEdit(String revision, String title, String price) {
        jdbc.update("MERGE INTO category(id,parent_id,name,sort_order) KEY(id) VALUES(1,0,'standard',0)");
        return new com.shop.b2b2c.dto.ProductEditingDTOs.Update(revision, 1L, title, null, "updated-image", null,
                List.of(new com.shop.b2b2c.dto.ProductEditingDTOs.Price(1L, new java.math.BigDecimal(price))));
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
                () -> cardAssets.importCards(1L, 1L, 1L, List.of("fresh-fixture", " secret "))).getCode());
        assertEquals(1, cards.selectCount(null)); assertEquals(1, skus.selectById(1).getStockQuantity());
    }

    @Test
    void concurrentCardImportsAcrossOwnProductsHaveOneWinner() throws Exception {
        jdbc.update("INSERT INTO product(id,merchant_id,category_id,title,goods_type,main_image,min_price) VALUES(3,1,1,'goods3',2,'image',10)");
        jdbc.update("INSERT INTO product_sku(id,merchant_id,product_id,sku_code,spec_data,price,stock_quantity) VALUES(3,1,3,'SKU3','{}',10,0)");
        AtomicInteger success = new AtomicInteger(), duplicates = new AtomicInteger();
        race(2, i -> {
            try { cardAssets.importCards(1L, i == 0 ? 1L : 3L, i == 0 ? 1L : 3L, List.of("shared-new-fixture")); success.incrementAndGet(); }
            catch (BusinessException e) { assertEquals(409, e.getCode()); duplicates.incrementAndGet(); }
        });
        assertEquals(1, success.get()); assertEquals(1, duplicates.get());
        assertEquals(2, cards.selectCount(null)); assertEquals(2, skus.selectById(1).getStockQuantity() + skus.selectById(3).getStockQuantity());
    }

    @Test
    void cardImportScopesHistoryToMerchantAndRollsBackStockFailure() {
        assertEquals(1, cardAssets.importCards(2L, 2L, 2L, List.of("secret")).get("importedCount"));
        doReturn(0).when(skus).addStock(1L, 1L, 2);
        assertThrows(IllegalStateException.class, () -> cardAssets.importCards(1L, 1L, 1L, List.of("new-a", "new-b")));
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
        assertEquals(409, assertThrows(BusinessException.class, () -> cardAssets.importCards(1L, 1L, 1L, List.of("secret"))).getCode());
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
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> cardAssets.importCards(1L, 1L, 1L, List.of("fixture"))));
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
        cardAssets.importCards(1L, productId, skuId, List.of("new-product-card-a", "new-product-card-b"));
        assertEquals(2, skus.selectById(skuId).getStockQuantity());
        var imported = cards.findAvailableForMerchant(skuId, 1L);
        cardAssets.voidCard(1L, 200L, imported.get(0).getId(), "expired");
        assertEquals(1, skus.selectById(skuId).getStockQuantity());
        assertEquals(1, cards.findAvailableForMerchant(skuId, 1L).size());
    }

    private void authUser(String role, Long merchantId) {
        jdbc.update("INSERT INTO sys_user(id,username,password,role_code,merchant_id,is_active) VALUES(200,'auth-fixture',?,?,?,1)", AUTH_HASH, role, merchantId);
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
