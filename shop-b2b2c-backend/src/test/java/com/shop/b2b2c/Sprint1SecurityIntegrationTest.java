/*
 * 文件：Sprint1SecurityIntegrationTest.java
 * 用途：跨Sprint累计的建单幂等、商品/库存/卡密批次/卡密一致性核对与补发、状态统计、就绪探针、海报减载、店铺定向更新并发、支付回调审计持久化及租户安全集成回归。
 * 维护注意：名称保留Sprint1但覆盖后续修复；H2独立内存库、Mock Redis，Sa-Token使用仅测试注入的内存DAO验证真实令牌生命周期；认证限流计数模拟不等于真实Redis验收。并发测试不能加测试级外层事务掩盖真实提交；调整模型时同步隔离结构，不连接业务数据库。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PosterUtils;
import com.shop.b2b2c.common.SecurityUtils;
import com.shop.b2b2c.controller.OrderController;
import com.shop.b2b2c.controller.ProductController;
import com.shop.b2b2c.dto.PeerPayDTOs;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.entity.TradeOrderItem;
import com.shop.b2b2c.mapper.*;
import com.shop.b2b2c.service.PeerPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        "spring.sql.init.data-locations=", // H2夹具自带种子，显式关闭MySQL演示种子避免语法/重复数据干扰
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "payment.mock=true", "inventory.expiry-delay-ms=3600000",
        "app.security.cors-allowed-origins=https://admin.example.test",
        "app.security.login-account-limit=3", "app.security.login-ip-limit=5",
        // D38：海报落盘到构建目录，避免测试写入仓库数据目录；同名文件按凭证摘要覆盖。
        "shop.poster.storage-dir=target/test-posters"
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
    @SpyBean MerchantStoreMapper stores;
    @Autowired com.shop.b2b2c.controller.MerchantController merchantController;
    @Autowired com.shop.b2b2c.service.PaymentEventService paymentEventService;
    @Autowired com.shop.b2b2c.mapper.PaymentEventMapper paymentEvents;
    @Autowired com.shop.b2b2c.service.payment.PaymentEventReconcileService paymentEventReconcile;
    @Autowired com.shop.b2b2c.service.payment.PaymentCallbackAuditService callbackAudits;
    @Autowired com.shop.b2b2c.service.payment.PaymentCloseReceiptService closeReceipts;
    @MockBean com.shop.b2b2c.service.payment.PaymentGatewayService paymentGateway;
    @MockBean com.shop.b2b2c.service.payment.PaymentChannelRegistry channelRegistry;
    @Autowired com.shop.b2b2c.service.MerchantNotificationService notificationService;
    @Autowired com.shop.b2b2c.mapper.MerchantNotificationOutboxMapper notificationOutboxMapper;
    @Autowired com.shop.b2b2c.service.OrderReceiptService receiptService;
    @Autowired com.shop.b2b2c.config.OrderAutoCompleteJob autoCompleteJob;

    @BeforeEach
    void fixtures() {
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(1L);
        for (String table : List.of("payment_close_receipt", "payment_callback_audit", "complaint_event", "complaint", "refund_request", "billing_statement", "merchant_notification_outbox", "payment_event", "card_reissue", "card_import_item", "card_import_batch", "virtual_card_void", "inventory_adjustment", "sys_user", "order_idempotency", "inventory_reservation", "trade_refund", "trade_order_item", "trade_order", "product_virtual_card", "product_sku", "product", "product_violation_audit", "merchant_fee_audit", "merchant_fee_policy", "merchant_payment_account", "merchant_store")) {
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

    @Test
    void staleStoreReadsCannotOverwriteOtherOperationsAcrossAllStorePaths() throws Exception {
        var fee = new com.shop.b2b2c.controller.MerchantController.ServiceFeeConfigDTO();
        fee.setMonthlyFee(new java.math.BigDecimal("29.00"));
        var channel = new com.shop.b2b2c.controller.MerchantController.PaymentChannelConfigDTO();
        channel.setPaymentChannelType("B");
        var onlyStatus = new com.shop.b2b2c.controller.MerchantController.ServiceFeeConfigDTO();
        onlyStatus.setFeeStatus("TRIAL");
        // T39：佣金已迁移到独立费率政策表，不再用旧实体回写 merchant_store；本用例只覆盖仍写店铺列的四条路径。
        // O6：状态写入收口到状态机（打烊 1->2 走 updateStatusCas），旧直置冻结(3)入口已移除；
        // 过期写被并发提交抢先后 CAS 影响 0 行返回 409，正是本用例要验证的防覆盖结果。
        Runnable pauseStore = () -> {
            try (var security = mockStatic(SecurityUtils.class)) {
                security.when(SecurityUtils::getCurrentUserId).thenReturn(2L);
                merchantController.updateStatus(1L, 2);
            } catch (BusinessException conflict) {
                assertEquals(409, conflict.getCode(), "过期的状态写入只能因 CAS 冲突失败");
            }
        };
        List<Runnable> stale = List.of(
                pauseStore,
                () -> merchantController.updateServiceFee(1L, fee),
                () -> merchantController.updatePaymentChannel(1L, channel),
                () -> {
                    try (var security = mockStatic(SecurityUtils.class)) {
                        security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
                        // Q13-A 后资料更新走 AuthSessionService 角色校验，必须同时提供管理角色，
                        // 否则会在读取快照前被 403 拒绝，旧实现的 NPE 同样会掩盖真实交错结果。
                        security.when(SecurityUtils::getCurrentRoleCode).thenReturn("MERCHANT_ADMIN");
                        var profile = new MerchantStore(); profile.setNotice("new notice"); profile.setId(2L);
                        merchantController.updateMerchantProfile(profile);
                    }
                },
                () -> merchantController.updateServiceFee(1L, fee));
        List<Runnable> committed = List.of(
                pauseStore,
                () -> merchantController.updatePaymentChannel(1L, channel),
                () -> merchantController.updateServiceFee(1L, fee),
                pauseStore,
                () -> merchantController.updateServiceFee(1L, onlyStatus));
        for (int i = 0; i < stale.size(); i++) {
            jdbc.update("UPDATE merchant_store SET status=1,commission_rate=3,monthly_fee=0,fee_status='NORMAL',payment_channel_type='C',notice=NULL WHERE id=1");
            interleaveStoreWrites(stale.get(i), committed.get(i));
            var result = stores.findById(1L).orElseThrow();
            if (i == 0) {
                assertEquals(2, result.getStatus());
                // 佣金展示镜像独立于其它配置更新，不能被状态写入带回旧值。
                assertEquals(0, new java.math.BigDecimal("3.00").compareTo(result.getCommissionRate()));
            } else if (i == 3) {
                assertEquals(2, result.getStatus()); assertEquals("new notice", result.getNotice());
                assertNull(stores.findById(2L).orElseThrow().getNotice());
            } else {
                assertEquals(0, new java.math.BigDecimal("29.00").compareTo(result.getMonthlyFee()));
                if (i == 4) assertEquals("TRIAL", result.getFeeStatus());
                else assertEquals("B", result.getPaymentChannelType());
            }
        }
    }

    // O8：发件箱四元组唯一键在真实 H2 库上的去重、SMS 默认禁用 SKIPPED 与密码红线。
    @Test
    void notificationOutboxDeduplicatesByUniqueKeyAndSmsStaysSkipped() {
        Map<String, Object> payload = Map.of("applicationId", 9001, "storeName", "store1");
        assertTrue(notificationService.notify("ONBOARDING_SUBMITTED", 9001L,
                "INAPP", "ONBOARDING_SUBMITTED", "13800000000", payload));
        // 重试/重扫重复事件：同四元组返回 false 且不再插行。
        assertFalse(notificationService.notify("ONBOARDING_SUBMITTED", 9001L,
                "INAPP", "ONBOARDING_SUBMITTED", "13800000000", payload));
        assertTrue(notificationService.notify("ONBOARDING_SUBMITTED", 9001L,
                "SMS", "ONBOARDING_SUBMITTED", "13800000000", payload));
        // 不同业务事件（新审计行ID）不受唯一键影响，各通知一次。
        assertTrue(notificationService.notify("ONBOARDING_SUPPLEMENT", 9002L,
                "INAPP", "ONBOARDING_SUPPLEMENT", "13800000000", payload));

        var rows = jdbc.queryForList("SELECT channel,status,sent_at FROM merchant_notification_outbox WHERE biz_id IN (9001,9002) ORDER BY id");
        assertEquals(3, rows.size());
        var inapp = rows.get(0);
        assertEquals("INAPP", inapp.get("CHANNEL"));
        assertEquals("SENT", inapp.get("STATUS"));
        assertNotNull(inapp.get("SENT_AT"));
        var sms = rows.get(1);
        assertEquals("SMS", sms.get("CHANNEL"));
        assertEquals("SKIPPED", sms.get("STATUS"), "SMS 通道默认禁用必须落 SKIPPED，绝不发送");
        assertNull(sms.get("SENT_AT"));

        // 密码红线：含 password 键的负载 fail-fast 且不写库。
        var ex = assertThrows(BusinessException.class,
                () -> notificationService.notify("ONBOARDING_OPENED", 9003L,
                        "INAPP", "ONBOARDING_OPENED", "MERCHANT:1",
                        Map.of("username", "merchant1", "initialPassword", "abc123")));
        assertEquals(500, ex.getCode());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM merchant_notification_outbox WHERE biz_id IN (9001,9002,9003)", Integer.class));
    }

    // 在真实Spring事务中暂停旧读取，另一路提交后才允许其UPDATE；不用sleep猜测交错。
    private void interleaveStoreWrites(Runnable staleWrite, Runnable committedWrite) throws Exception {
        CountDownLatch snapshotRead = new CountDownLatch(1), resume = new CountDownLatch(1);
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            Object snapshot = invocation.callRealMethod();
            if (Thread.currentThread() == worker.get() && once.compareAndSet(false, true)) {
                snapshotRead.countDown();
                if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("store write barrier timed out");
            }
            return snapshot;
        }).when(stores).findById(1L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> pending = executor.submit(() -> { worker.set(Thread.currentThread()); staleWrite.run(); });
        try {
            assertTrue(snapshotRead.await(10, TimeUnit.SECONDS), "old snapshot must be read before concurrent commit");
            committedWrite.run();
            resume.countDown(); pending.get(10, TimeUnit.SECONDS);
        } finally {
            resume.countDown(); pending.cancel(true); executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void feePatchPreservesNullsAndExplicitClearDoesNotTouchOtherConfiguration() {
        var date = java.time.LocalDate.of(2028, 2, 29);
        jdbc.update("UPDATE merchant_store SET status=3,fee_status='HISTORICAL',fee_free_until=?,payment_channel_type='B',custom_pay_qrcode='old-qr' WHERE id=1", date.atStartOfDay());
        var fee = new com.shop.b2b2c.controller.MerchantController.ServiceFeeConfigDTO();
        fee.setMonthlyFee(java.math.BigDecimal.ZERO);
        merchantController.updateServiceFee(1L, fee);
        var retained = stores.findById(1L).orElseThrow();
        assertEquals(date.atStartOfDay(), retained.getFeeFreeUntil()); assertEquals("HISTORICAL", retained.getFeeStatus());
        fee.setClearFeeFreeUntil(true); fee.setFeeFreeUntil(date.plusDays(1));
        merchantController.updateServiceFee(1L, fee);
        var cleared = stores.findById(1L).orElseThrow();
        assertNull(cleared.getFeeFreeUntil()); assertEquals(3, cleared.getStatus());
        assertEquals("B", cleared.getPaymentChannelType()); assertEquals("old-qr", cleared.getCustomPayQrcode());
        fee.setClearFeeFreeUntil(false); fee.setFeeFreeUntil(date);
        merchantController.updateServiceFee(1L, fee);
        assertEquals(date.atStartOfDay(), stores.findById(1L).orElseThrow().getFeeFreeUntil());
        var channel = new com.shop.b2b2c.controller.MerchantController.PaymentChannelConfigDTO();
        channel.setCustomPayQrcode(""); merchantController.updatePaymentChannel(1L, channel);
        assertEquals("B", stores.findById(1L).orElseThrow().getPaymentChannelType());
        assertEquals("", stores.findById(1L).orElseThrow().getCustomPayQrcode());
    }

    private com.shop.b2b2c.dto.ProductEditingDTOs.Update productEdit(String revision, String title, String price) {
        jdbc.update("MERGE INTO category(id,parent_id,name,sort_order) KEY(id) VALUES(1,0,'standard',0)");
        return new com.shop.b2b2c.dto.ProductEditingDTOs.Update(revision, 1L, title, null, "updated-image", null, null,
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
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.orders[0].order.posterUrl").isEmpty())
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
    void detailLockOwnershipOnlyVisibleToOwner() throws Exception {
        intent("owner-view");
        var held = payments.preLockInventory("owner-view");
        mvc.perform(get("/api/v1/peer-pay/detail/owner-view")
                        .header("X-Reservation-Token", held.getOwnerToken()))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.lockOwnedByCaller").value(true))
                .andExpect(jsonPath("$.data.ownerToken").doesNotExist());
        mvc.perform(get("/api/v1/peer-pay/detail/owner-view"))
                .andExpect(jsonPath("$.data.lockOwnedByCaller").value(false));
        mvc.perform(get("/api/v1/peer-pay/detail/owner-view")
                        .header("X-Reservation-Token", "not-the-owner"))
                .andExpect(jsonPath("$.data.lockOwnedByCaller").value(false));
        payments.cancelPreLock("owner-view", held.getOwnerToken());
        mvc.perform(get("/api/v1/peer-pay/detail/owner-view")
                        .header("X-Reservation-Token", held.getOwnerToken()))
                .andExpect(jsonPath("$.data.lockOwnedByCaller").doesNotExist());
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
        assertEquals(List.of(1L, 2L, 3L, 4L, 7L), products.listPublicProducts(null, null, null, null, null).getData().stream()
                .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertEquals(List.of(3L, 4L), products.listPublicProducts(2L, null, null, null, null).getData().stream()
                .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertEquals(List.of(1L, 3L, 7L), products.listPublicProducts(null, 1L, null, null, null).getData().stream()
                .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertEquals(List.of(3L), products.listPublicProducts(2L, 1L, null, null, null).getData().stream()
                .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertTrue(products.listPublicProducts(999L, 1L, null, null, null).getData().isEmpty());
        assertTrue(products.listPublicProducts(null, 999L, null, null, null).getData().isEmpty());
        jdbc.update("UPDATE product SET sort_order=-1 WHERE id=3");
        mvc.perform(get("/api/v1/public/products").param("merchantId", "1"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data[0].id").value("3"))
                .andExpect(jsonPath("$.data[0].minPrice").value(12.30))
                .andExpect(jsonPath("$.data[0].storeName").value("store1"))
                .andExpect(jsonPath("$.data[0].totalStock").value("0"))
                .andExpect(jsonPath("$.data[0].subTitle").isEmpty())
                .andExpect(jsonPath("$.data[0].serviceDesc").doesNotExist())
                .andExpect(jsonPath("$.data[0].ownerName").doesNotExist());
        mvc.perform(get("/api/v1/public/products").param("categoryId", "0"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void publicCatalogSearchesTitleCaseInsensitivelyAndFiltersGoodsType() throws Exception {
        publicCatalogFixture();
        jdbc.update("UPDATE product SET goods_type=2 WHERE id=4");
        assertEquals(List.of(4L), products.listPublicProducts(null, null, "CATALOG-4", null, null).getData().stream()
                .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertTrue(products.listPublicProducts(null, null, "catalog", 1, null).getData().stream()
                .map(com.shop.b2b2c.dto.PublicProductView::getId).noneMatch(id -> id == 4L));
        assertEquals(List.of(4L), products.listPublicProducts(null, null, "catalog", 2, null).getData().stream()
                .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertTrue(products.listPublicProducts(null, null, "not-exists", null, null).getData().isEmpty());
        // 下架/非营业店铺的商品即使关键词命中也不得返回
        assertTrue(products.listPublicProducts(null, null, "catalog-5", null, null).getData().isEmpty());
        mvc.perform(get("/api/v1/public/products").param("goodsType", "4"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/v1/public/products").param("q", "x".repeat(51)))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void publicCatalogStockUsesOnlyOwnedSkusAndDoesNotOverflowInt() {
        publicCatalogFixture();
        jdbc.update("INSERT INTO product_sku(id,merchant_id,product_id,sku_code,spec_data,price,stock_quantity) VALUES(10,1,1,'LARGE','{}',10,2147483647)");
        jdbc.update("INSERT INTO product_sku(id,merchant_id,product_id,sku_code,spec_data,price,stock_quantity) VALUES(11,2,1,'BAD-OWNER','{}',10,500)");
        var rows = products.listPublicProducts(1L, 1L, null, null, null).getData();
        assertEquals(List.of(1L, 7L), rows.stream().map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        assertEquals(Long.valueOf(2147483648L), rows.get(0).getTotalStock());
        assertEquals(Long.valueOf(0L), rows.get(1).getTotalStock());
    }

    @Test
    void publicCatalogOmitsAllNonOperatingStoreStates() {
        for (int status : new int[] {0, 2, 3, 99}) {
            jdbc.update("UPDATE merchant_store SET status=? WHERE id=1", status);
            assertTrue(products.listPublicProducts(null, 1L, null, null, null).getData().isEmpty());
            assertEquals(List.of(2L), products.listPublicProducts(null, null, null, null, null).getData().stream()
                    .map(com.shop.b2b2c.dto.PublicProductView::getId).toList());
        }
        jdbc.update("UPDATE merchant_store SET status=1 WHERE id=1");
        assertEquals(1, products.listPublicProducts(1L, 1L, null, null, null).getData().size());
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
        var full = new com.shop.b2b2c.dto.ProductEditingDTOs.Update(revision, 1L, "complete", null, "image", null, null,
                List.of(new com.shop.b2b2c.dto.ProductEditingDTOs.Price(1L, new java.math.BigDecimal("9.00")),
                        new com.shop.b2b2c.dto.ProductEditingDTOs.Price(3L, new java.math.BigDecimal("7.00"))));
        var result = editing.update(1L, 1L, full);
        assertEquals(0, result.product().getMinPrice().compareTo(new java.math.BigDecimal("7.00")));
        assertEquals(5, skus.selectById(3).getStockQuantity());
        assertEquals(0, skus.selectById(2).getPrice().compareTo(java.math.BigDecimal.TEN));
    }

    @Test
    void skuAddRemovePersistsWithDerivedMinimumAndHistoryProtection() {
        var created = editing.addSku(1L, 1L, new com.shop.b2b2c.dto.ProductEditingDTOs.SkuDraft(
                editing.get(1L, 1L).revision(), "SKU1-PLUS", "{\"规格\":\"年卡\"}", new java.math.BigDecimal("5.00"), 0));
        assertEquals(2, created.skus().size());
        assertEquals(0, created.product().getMinPrice().compareTo(new java.math.BigDecimal("5.00")));
        Long newSkuId = jdbc.queryForObject("SELECT id FROM product_sku WHERE sku_code='SKU1-PLUS'", Long.class);
        assertEquals(0, jdbc.queryForObject("SELECT stock_quantity FROM product_sku WHERE id=" + newSkuId, Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT min_price FROM product WHERE id=1", java.math.BigDecimal.class)
                .compareTo(new java.math.BigDecimal("5.00")));
        // 同店重复编码409、自动卡密初始库存必须为0、越店404（revision合法但商品不属于该店）
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, new com.shop.b2b2c.dto.ProductEditingDTOs.SkuDraft(
                editing.get(1L, 1L).revision(), "SKU1-PLUS", "{}", java.math.BigDecimal.ONE, 0))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> editing.addSku(1L, 1L, new com.shop.b2b2c.dto.ProductEditingDTOs.SkuDraft(
                editing.get(1L, 1L).revision(), "SKU1-STOCK", "{}", java.math.BigDecimal.ONE, 1))).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> editing.addSku(2L, 1L, new com.shop.b2b2c.dto.ProductEditingDTOs.SkuDraft(
                "a".repeat(64), "SKU-X", "{}", java.math.BigDecimal.ONE, 0))).getCode());
        // 有库存的SKU拒绝删除；清零后仍有卡密引用同样拒绝（fixtures卡1引用sku1）
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.removeSku(1L, 1L, 1L,
                new com.shop.b2b2c.dto.ProductEditingDTOs.SkuRemoval(editing.get(1L, 1L).revision()))).getCode());
        jdbc.update("UPDATE product_sku SET stock_quantity=0 WHERE id=1");
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.removeSku(1L, 1L, 1L,
                new com.shop.b2b2c.dto.ProductEditingDTOs.SkuRemoval(editing.get(1L, 1L).revision()))).getCode());
        // 订单明细引用的干净SKU同样拒绝删除
        jdbc.update("INSERT INTO product_sku(id,merchant_id,product_id,sku_code,spec_data,price,stock_quantity) VALUES(5,1,1,'SKU5','{}',8,0)");
        var order = unpaid("sku-ref", 1);
        jdbc.update("UPDATE trade_order_item SET sku_id=5 WHERE order_sn=?", order.getOrderSn());
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.removeSku(1L, 1L, 5L,
                new com.shop.b2b2c.dto.ProductEditingDTOs.SkuRemoval(editing.get(1L, 1L).revision()))).getCode());
        // 仅剩一个SKU的商品拒绝删除
        assertEquals(409, assertThrows(BusinessException.class, () -> editing.removeSku(2L, 2L, 2L,
                new com.shop.b2b2c.dto.ProductEditingDTOs.SkuRemoval(editing.get(2L, 2L).revision()))).getCode());
        // 无库存无引用的干净SKU可删除；删除最低价(5.00)后按剩余SKU(10/8)重算为8.00
        var after = editing.removeSku(1L, 1L, newSkuId,
                new com.shop.b2b2c.dto.ProductEditingDTOs.SkuRemoval(editing.get(1L, 1L).revision()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product_sku WHERE id=" + newSkuId, Long.class));
        assertEquals(0, after.product().getMinPrice().compareTo(new java.math.BigDecimal("8.00")));
        assertEquals(0, jdbc.queryForObject("SELECT min_price FROM product WHERE id=1", java.math.BigDecimal.class)
                .compareTo(new java.math.BigDecimal("8.00")));
    }

    @Test
    void skuMutationHttpRequiresManagerOpenStoreAndValidQualification() throws Exception {
        var anonymous = java.util.Map.of("revision", "x", "skuCode", "SKU-HTTP", "specData", "{}", "price", 6.00, "stockQuantity", 0);
        mvc.perform(post("/api/v1/merchant/products/1/skus").contentType("application/json")
                .content(json.writeValueAsString(anonymous))).andExpect(status().isUnauthorized());
        authUser("MERCHANT_STAFF", 1L); String staffToken = loginToken();
        mvc.perform(post("/api/v1/merchant/products/1/skus").header("Authorization", "Bearer " + staffToken)
                .contentType("application/json").content(json.writeValueAsString(anonymous))).andExpect(status().isForbidden());
        // D22：角色变化撤销旧令牌，管理员操作使用重新登录的令牌
        jdbc.update("UPDATE sys_user SET role_code='MERCHANT_ADMIN' WHERE id=200");
        String adminToken = loginToken();
        var create = java.util.Map.of("revision", editing.get(1L, 1L).revision(), "skuCode", "SKU-HTTP",
                "specData", "{\"规格\":\"次卡\"}", "price", 6.00, "stockQuantity", 0);
        mvc.perform(post("/api/v1/merchant/products/1/skus").header("Authorization", "Bearer " + adminToken)
                .contentType("application/json").content(json.writeValueAsString(create)))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.revision").isNotEmpty());
        Long httpSku = jdbc.queryForObject("SELECT id FROM product_sku WHERE sku_code='SKU-HTTP'", Long.class);
        // 最新版本号+重复编码仍409
        var duplicate = java.util.Map.of("revision", editing.get(1L, 1L).revision(), "skuCode", "SKU-HTTP",
                "specData", "{}", "price", 7.00, "stockQuantity", 0);
        mvc.perform(post("/api/v1/merchant/products/1/skus").header("Authorization", "Bearer " + adminToken)
                .contentType("application/json").content(json.writeValueAsString(duplicate)))
                .andExpect(jsonPath("$.code").value(409));
        // O4：打烊店铺拒绝写
        jdbc.update("UPDATE merchant_store SET status=2 WHERE id=1");
        mvc.perform(post("/api/v1/merchant/products/1/skus").header("Authorization", "Bearer " + adminToken)
                .contentType("application/json").content(json.writeValueAsString(duplicate)))
                .andExpect(jsonPath("$.code").value(409));
        jdbc.update("UPDATE merchant_store SET status=1 WHERE id=1");
        // O9细化：资质到期拦新增SKU（视同上新产生新可售单元），但不拦减损的删除
        jdbc.update("UPDATE merchant_store SET id_card_expiry=? WHERE id=1", java.time.LocalDate.now().minusDays(1));
        mvc.perform(post("/api/v1/merchant/products/1/skus").header("Authorization", "Bearer " + adminToken)
                .contentType("application/json").content(json.writeValueAsString(duplicate)))
                .andExpect(jsonPath("$.code").value(409));
        mvc.perform(delete("/api/v1/merchant/products/1/skus/" + httpSku).header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("revision", editing.get(1L, 1L).revision()))))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product_sku WHERE id=" + httpSku, Long.class));
        // 越店删除他店SKU按404处理，不泄露归属
        mvc.perform(delete("/api/v1/merchant/products/2/skus/2").header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("revision", editing.get(2L, 2L).revision()))))
                .andExpect(jsonPath("$.code").value(404));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM product_sku WHERE id=2", Long.class));
    }

    @Test
    void specUpdatePersistsWithoutTouchingOrderSnapshots() {
        // 建单时已快照spec_info（测试辅助方法不填，此处用SQL造哨兵值），之后修改规格不改写历史订单
        var order = unpaid("spec-hist", 1);
        jdbc.update("UPDATE trade_order_item SET spec_info='{\"规格\":\"下单时快照\"}' WHERE order_sn=?", order.getOrderSn());
        String before = editing.get(1L, 1L).revision();
        var first = editing.updateSpec(1L, 1L, 1L, new com.shop.b2b2c.dto.ProductEditingDTOs.SpecUpdate(before, "{\"规格\":\"季卡\"}"));
        assertNotEquals(before, first.revision()); // 规格纳入revision指纹
        assertEquals("{\"规格\":\"季卡\"}", skus.selectById(1).getSpecData());
        assertEquals("{\"规格\":\"下单时快照\"}", items.findByOrderSn(order.getOrderSn()).get(0).getSpecInfo());
        // 未变化提交不报错且版本不变
        var again = editing.updateSpec(1L, 1L, 1L, new com.shop.b2b2c.dto.ProductEditingDTOs.SpecUpdate(
                editing.get(1L, 1L).revision(), "{\"规格\":\"季卡\"}"));
        assertEquals(first.revision(), again.revision());
        // 越店按404处理
        assertEquals(404, assertThrows(BusinessException.class, () -> editing.updateSpec(2L, 1L, 1L,
                new com.shop.b2b2c.dto.ProductEditingDTOs.SpecUpdate("a".repeat(64), "{}"))).getCode());
    }

    @Test
    void specUpdateHttpRequiresManagerAndStaysTenantScoped() throws Exception {
        var body = java.util.Map.of("revision", editing.get(1L, 1L).revision(), "specData", "{\"规格\":\"月卡\"}");
        mvc.perform(patch("/api/v1/merchant/products/1/skus/1/spec").contentType("application/json")
                .content(json.writeValueAsString(body))).andExpect(status().isUnauthorized());
        authUser("MERCHANT_STAFF", 1L); String staffToken = loginToken();
        mvc.perform(patch("/api/v1/merchant/products/1/skus/1/spec").header("Authorization", "Bearer " + staffToken)
                .contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        // D22：角色变化撤销旧令牌，管理员操作使用重新登录的令牌
        jdbc.update("UPDATE sys_user SET role_code='MERCHANT_ADMIN' WHERE id=200");
        String adminToken = loginToken();
        mvc.perform(patch("/api/v1/merchant/products/1/skus/1/spec").header("Authorization", "Bearer " + adminToken)
                .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.revision").isNotEmpty());
        assertEquals("{\"规格\":\"月卡\"}", jdbc.queryForObject("SELECT spec_data FROM product_sku WHERE id=1", String.class));
        assertEquals("{\"规格\":\"月卡\"}", skus.selectById(1).getSpecData());
        // 越店（本店管理员操作他店商品）404且目标规格不变
        var foreign = java.util.Map.of("revision", editing.get(2L, 2L).revision(), "specData", "{\"规格\":\"篡改\"}");
        mvc.perform(patch("/api/v1/merchant/products/2/skus/2/spec").header("Authorization", "Bearer " + adminToken)
                .contentType("application/json").content(json.writeValueAsString(foreign)))
                .andExpect(jsonPath("$.code").value(404));
        assertEquals("{}", skus.selectById(2).getSpecData());
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
        doReturn(0).when(editingMapper).updateMetadata(any(), any(), any(), any(), any());
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
    /**
     * HTTP 用例需显式构造 JSON：T37 后测试侧 ObjectMapper 会把包装 Long 序列化成字符串，
     * 库存 DTO 现按前端契约同时接受 JSON 整数与十进制数字字符串；小数仍必须在参数解析阶段返回 400。
     */
    private com.fasterxml.jackson.databind.node.ObjectNode inventoryBody(int delta, int expected) {
        var body = json.createObjectNode();
        body.put("productId", 1); body.put("skuId", 1);
        body.put("delta", delta); body.put("expectedStock", expected); body.put("reason", "stock-taking");
        return body;
    }

    @Test
    void inventoryAcceptsStringifiedLongIdsFromAdminUi() throws Exception {
        physicalProduct(); authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        var body = inventoryBody(2, 1);
        body.put("productId", "1"); body.put("skuId", "1");
        mvc.perform(post("/api/v1/merchant/inventory-adjustments").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "stock-string-id-key-1").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.afterStock").value(3));
        assertEquals(3, skus.selectById(1).getStockQuantity());
    }
    private void physicalProduct() { jdbc.update("UPDATE product SET goods_type=1 WHERE id=1"); }

    @Test
    void inventoryHttpUsesSessionActorAndRequiresIdempotencyKey() throws Exception {
        physicalProduct(); authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        var body = inventoryBody(2, 1);
        body.put("actorId", 999); body.put("merchantId", 2);
        mvc.perform(post("/api/v1/merchant/inventory-adjustments").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/merchant/inventory-adjustments").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "stock-http-key-1234").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.actorId").value("200"))
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
                .header("Idempotency-Key", "stock-staff-key-1234").contentType("application/json").content(json.writeValueAsString(inventoryBody(1, 1))))
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
    void encryptedHistoricalCardWithoutFingerprintIsDeduplicatedWithoutBackfill() {
        var cipher = new com.shop.b2b2c.service.CardCipherService();
        String legacyPlain = "legacy-encrypted-fixture";
        jdbc.update("UPDATE product_virtual_card SET card_content=?, card_fingerprint=NULL WHERE id=1", cipher.encrypt(legacyPlain));

        assertEquals(1, cardAssets.importCards(1L, 200L, 1L, 1L, List.of("fresh-other-fixture")).get("importedCount"));
        // 基线清理已删除历史指纹回填：无指纹行只在内存解密比对，不得写回 card_fingerprint。
        assertNull(jdbc.queryForObject("SELECT card_fingerprint FROM product_virtual_card WHERE id=1", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT fingerprint_unavailable FROM product_virtual_card WHERE id=1", Integer.class));
        // 去重能力仍必须覆盖：同一明文再次导入应被内存比对拦下。
        assertEquals(409, assertThrows(BusinessException.class,
                () -> cardAssets.importCards(1L, 200L, 1L, 1L, List.of(legacyPlain))).getCode());
    }

    @Test
    void unreadableHistoricalCardIsMarkedUnavailableAndDoesNotBlockImport() {
        jdbc.update("UPDATE product_virtual_card SET card_content='ENC:v1:not-base64!', card_fingerprint=NULL WHERE id=1");
        assertEquals(1, cardAssets.importCards(1L, 200L, 1L, 1L, List.of("fresh-after-unreadable-history")).get("importedCount"));
        assertEquals(1, jdbc.queryForObject("SELECT fingerprint_unavailable FROM product_virtual_card WHERE id=1", Integer.class));
        assertNull(jdbc.queryForObject("SELECT card_fingerprint FROM product_virtual_card WHERE id=1", String.class));
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
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.actorId").value("200"))
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
    void skuCodeIsUniqueWithinMerchantButReusableAcrossMerchants() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(1L);
            int before = jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class);

            ProductController.CreateProductDTO first = productWithSku("T17-FIRST", "SHARED-SKU-CODE");
            Long firstId = products.createProduct(first).getData().getId();
            assertEquals("SHARED-SKU-CODE", jdbc.queryForObject(
                    "SELECT sku_code FROM product_sku WHERE product_id=?", String.class, firstId));

            ProductController.CreateProductDTO duplicate = productWithSku("T17-DUPLICATE", "SHARED-SKU-CODE");
            assertEquals(409, assertThrows(BusinessException.class, () -> products.createProduct(duplicate)).getCode());
            // 同店冲突必须整单回滚：不新增商品、不新增 SKU
            assertEquals(before + 1, jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM product_sku WHERE merchant_id=1 AND sku_code='SHARED-SKU-CODE'", Integer.class));

            // 跨店同码允许：唯一域是 (merchant_id, sku_code)
            security.when(SecurityUtils::requireCurrentMerchantId).thenReturn(2L);
            Long secondMerchantProduct = products.createProduct(productWithSku("T17-OTHER-MERCHANT", "SHARED-SKU-CODE")).getData().getId();
            assertEquals(2L, jdbc.queryForObject("SELECT merchant_id FROM product_sku WHERE product_id=?",
                    Long.class, secondMerchantProduct));
        }
    }

    private ProductController.CreateProductDTO productWithSku(String title, String skuCode) {
        var dto = new ProductController.CreateProductDTO();
        dto.setTitle(title); dto.setMainImage("https://example.test/img.png");
        dto.setCategoryId(1L); dto.setGoodsType(1); dto.setMinPrice(java.math.BigDecimal.TEN);
        var sku = new ProductController.SkuDTO();
        sku.setSkuCode(skuCode); sku.setSpecData("{}"); sku.setPrice(java.math.BigDecimal.TEN); sku.setStockQuantity(10);
        dto.setSkus(List.of(sku));
        return dto;
    }

    @Test
    void importBatchHttpUsesSessionActorAndHidesCardContent() throws Exception {
        mvc.perform(get("/api/v1/merchant/card-import-batches")).andExpect(status().isUnauthorized());
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        String response = mvc.perform(post("/api/v1/merchant/cards/import").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"productId\":1,\"skuId\":1,\"actorId\":999,\"cardContents\":[\"batch-private-fixture\"]}"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.batchId").isString())
                .andReturn().getResponse().getContentAsString();
        long batch = json.readTree(response).path("data").path("batchId").asLong();
        mvc.perform(get("/api/v1/merchant/card-import-batches").header("Authorization", "Bearer " + token))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.items[0].actorId").value("200"))
                .andExpect(jsonPath("$.data.items[0].importedCount").value(1));
        String members = mvc.perform(get("/api/v1/merchant/card-import-batches/" + batch + "/cards").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].cardContent").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(members.contains("batch-private-fixture"));
    }

    @Test
    void cardConsistencyReportsDriftAndUnavailableWithoutAutoRepair() {
        var initial = cardAssets.consistencyCheck(1L);
        @SuppressWarnings("unchecked") var items = (List<com.shop.b2b2c.dto.CardConsistencyRow>) initial.get("items");
        assertEquals(1, items.size());
        assertEquals(1L, items.get(0).getSkuId());
        assertEquals(1L, items.get(0).getStockQuantity());
        assertEquals(1L, items.get(0).getAvailableCards());
        assertTrue(items.get(0).getConsistent());
        assertEquals(0, initial.get("abnormalCount"));

        // merchant2的SKU账面1却无卡，天然漂移；核对按商户隔离，看不到merchant1的SKU
        var foreign = cardAssets.consistencyCheck(2L);
        @SuppressWarnings("unchecked") var foreignItems = (List<com.shop.b2b2c.dto.CardConsistencyRow>) foreign.get("items");
        assertEquals(1, foreignItems.size());
        assertEquals(2L, foreignItems.get(0).getSkuId());
        assertFalse(foreignItems.get(0).getConsistent());
        assertEquals(1, foreign.get("abnormalCount"));

        // 漂移与异常指纹都被如实报告，核对本身不修正任何数据
        jdbc.update("UPDATE product_sku SET stock_quantity=2 WHERE id=1");
        jdbc.update("UPDATE product_virtual_card SET fingerprint_unavailable=1 WHERE id=1");
        var drift = cardAssets.consistencyCheck(1L);
        @SuppressWarnings("unchecked") var driftItems = (List<com.shop.b2b2c.dto.CardConsistencyRow>) drift.get("items");
        assertFalse(driftItems.get(0).getConsistent());
        assertEquals(1L, driftItems.get(0).getFingerprintUnavailable());
        assertEquals(1, drift.get("abnormalCount"));
        assertEquals(1L, drift.get("fingerprintUnavailableTotal"));
        assertEquals(2, skus.selectById(1).getStockQuantity());
        assertEquals(0, cards.selectById(1L).getStatus());
    }

    @Test
    void cardConsistencyAfterVoidStaysConsistentAndKeepsVoidedCount() {
        cardAssets.voidCard(1L, 200L, 1L, "expired");
        var result = cardAssets.consistencyCheck(1L);
        @SuppressWarnings("unchecked") var items = (List<com.shop.b2b2c.dto.CardConsistencyRow>) result.get("items");
        assertTrue(items.get(0).getConsistent());
        assertEquals(0L, items.get(0).getStockQuantity());
        assertEquals(0L, items.get(0).getAvailableCards());
        assertEquals(1L, items.get(0).getVoidedCards());
        assertEquals(0, result.get("abnormalCount"));
    }

    @Test
    void cardConsistencyHttpIsMerchantScopedReadOnlyAndNoStore() throws Exception {
        mvc.perform(get("/api/v1/merchant/cards/consistency")).andExpect(status().isUnauthorized());
        authUser("MERCHANT_STAFF", 1L); String token = loginToken();
        mvc.perform(get("/api/v1/merchant/cards/consistency").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.abnormalCount").value(0))
                .andExpect(jsonPath("$.data.items[0].skuId").value("1"))
                .andExpect(jsonPath("$.data.items[0].consistent").value(true));
    }

    @Test
    void cardReissueBindsNewCardToSameOrderAndStaysIdempotent() {
        // 造一张已售卡（不占账面库存）；卡1保持未售，账面1==在库1
        jdbc.update("INSERT INTO product_virtual_card(product_id,sku_id,merchant_id,card_content,status,order_sn) VALUES(1,1,1,'reissue-sold-fixture',1,'ORD-REISSUE-1')");
        long soldId = jdbc.queryForObject("SELECT id FROM product_virtual_card WHERE order_sn='ORD-REISSUE-1'", Long.class);
        var result = cardAssets.reissueCard(1L, 200L, soldId, "买家未收到");
        var record = (com.shop.b2b2c.entity.CardReissue) result.get("record");
        assertEquals(soldId, record.getSourceCardId());
        assertEquals(1L, record.getNewCardId());
        assertEquals("ORD-REISSUE-1", record.getOrderSn());
        assertEquals(200L, record.getActorId());
        assertEquals(1, record.getBeforeStock()); assertEquals(0, record.getAfterStock());
        // 新卡绑定原订单且转已售，库存扣1；源卡保持已售，交付事实不回滚
        assertEquals(1, cards.selectById(1L).getStatus());
        assertEquals("ORD-REISSUE-1", cards.selectById(1L).getOrderSn());
        assertEquals(0, skus.selectById(1).getStockQuantity());
        assertEquals(1, cards.selectById(soldId).getStatus());
        assertEquals("secret", ((com.shop.b2b2c.dto.MerchantCardView) result.get("newCard")).getCardContent());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM card_access_audit WHERE action='REISSUE_PLAINTEXT' AND merchant_id=1", Integer.class));
        // 同原因重放：库存不重复扣、记录仍一条、审计按外带事件追加
        var replay = cardAssets.reissueCard(1L, 200L, soldId, "买家未收到");
        assertEquals(record.getNewCardId(), ((com.shop.b2b2c.entity.CardReissue) replay.get("record")).getNewCardId());
        assertEquals("secret", ((com.shop.b2b2c.dto.MerchantCardView) replay.get("newCard")).getCardContent());
        assertEquals(0, skus.selectById(1).getStockQuantity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM card_reissue", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM card_access_audit WHERE action='REISSUE_PLAINTEXT' AND merchant_id=1", Integer.class));
        // 一张已售卡只能补发一次，换原因拒绝
        BusinessException changed = assertThrows(BusinessException.class, () -> cardAssets.reissueCard(1L, 200L, soldId, "换个原因"));
        assertEquals(409, changed.getCode());
        assertEquals(0, skus.selectById(1).getStockQuantity());
        // 记录查询（本店只读）
        var read = cardAssets.readReissueRecord(1L, soldId);
        assertEquals(1L, read.getNewCardId());
        assertThrows(BusinessException.class, () -> cardAssets.readReissueRecord(2L, soldId));
    }

    @Test
    void cardReissueHttpRequiresManagerAuditsPlaintextAndStaysTenantScoped() throws Exception {
        jdbc.update("INSERT INTO product_virtual_card(product_id,sku_id,merchant_id,card_content,status,order_sn) VALUES(1,1,1,'reissue-sold-http',1,'ORD-REISSUE-HTTP')");
        long soldId = jdbc.queryForObject("SELECT id FROM product_virtual_card WHERE order_sn='ORD-REISSUE-HTTP'", Long.class);
        jdbc.update("INSERT INTO product_virtual_card(product_id,sku_id,merchant_id,card_content,status,order_sn) VALUES(2,2,2,'foreign-sold-http',1,'ORD-FOREIGN-HTTP')");
        long foreignId = jdbc.queryForObject("SELECT id FROM product_virtual_card WHERE order_sn='ORD-FOREIGN-HTTP'", Long.class);
        String body = "{\"reason\":\"买家未收到\"}";
        mvc.perform(post("/api/v1/merchant/cards/{id}/reissue", soldId).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        authUser("MERCHANT_STAFF", 1L); String staffToken = loginToken();
        mvc.perform(post("/api/v1/merchant/cards/{id}/reissue", soldId).header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        assertEquals(1, skus.selectById(1).getStockQuantity());
        // 员工（本店后台）只读补发记录：未补发时404
        mvc.perform(get("/api/v1/merchant/cards/{id}/reissue-record", soldId).header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isNotFound());
        // 角色变化会撤销旧令牌（D22），升店长后重新登录
        jdbc.update("UPDATE sys_user SET role_code='MERCHANT_ADMIN' WHERE id=200");
        String adminToken = loginToken();
        mvc.perform(post("/api/v1/merchant/cards/{id}/reissue", soldId).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.record.sourceCardId").value(String.valueOf(soldId)))
                .andExpect(jsonPath("$.data.record.orderSn").value("ORD-REISSUE-HTTP"))
                .andExpect(jsonPath("$.data.newCard.cardContent").value("secret"))
                .andExpect(jsonPath("$.data.newCard.orderSn").value("ORD-REISSUE-HTTP"));
        assertEquals(0, skus.selectById(1).getStockQuantity());
        // 本店后台只读补发记录，不含卡密内容
        mvc.perform(get("/api/v1/merchant/cards/{id}/reissue-record", soldId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderSn").value("ORD-REISSUE-HTTP"))
                .andExpect(jsonPath("$.data.newCardId").value("1"))
                .andExpect(jsonPath("$.data.cardContent").doesNotExist());
        // 越店：本店店长不能补发他店卡
        mvc.perform(post("/api/v1/merchant/cards/{id}/reissue", foreignId).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isNotFound());
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
                // D25：无退款账本记录时指标为已知 0，不再返回未知。
                .andExpect(jsonPath("$.data.actualRefundAmount").value("0.00")).andExpect(jsonPath("$.data.netTurnover").value("12.00"))
                .andExpect(jsonPath("$.data.settledCommission").value("0.30")).andExpect(jsonPath("$.data.financialLedgerAvailable").value(true))
                .andExpect(jsonPath("$.data.orders").doesNotExist()).andExpect(jsonPath("$.data.queryToken").doesNotExist());
        // T25：时间范围非法或跨度超过 366 天直接 400，不能静默全表扫描。
        mvc.perform(get("/api/v1/merchant/orders/statistics").header("Authorization", "Bearer " + token)
                        .param("from", "2030-01-02T00:00:00").param("to", "2030-01-01T00:00:00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/v1/merchant/orders/statistics").header("Authorization", "Bearer " + token)
                        .param("from", "2024-01-01T00:00:00").param("to", "2026-01-01T00:00:00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void platformStatisticsKeepsCompatibleKeysButExcludesIntentAndIncludesRefundLedger() throws Exception {
        statisticsOrder("intent", 1L, "UNPAID", "INIT", "1000", "30");
        statisticsOrder("paid", 1L, "PAID", "PAID", "10", "0.30");
        statisticsOrder("refund", 1L, "REFUNDED", "PAID", "2", "0.06");
        statisticsOrder("foreign", 2L, "COMPLETED", "PAID", "9000", "270");
        authUser("SUPERADMIN", null); String token = loginToken();
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.page.total").value(4)).andExpect(jsonPath("$.data.orders.length()").value(4))
                .andExpect(jsonPath("$.data.totalTurnover").doesNotExist()).andExpect(jsonPath("$.data.totalCommission").doesNotExist())
                .andExpect(jsonPath("$.data.statistics.paidOrders").value(3)).andExpect(jsonPath("$.data.statistics.paidTurnover").value("9012.00"))
                .andExpect(jsonPath("$.data.statistics.estimatedCommission").value("270.30"))
                // D25：退款账本空时净额等于支付 GMV、扣冲正佣金等于预计佣金，且标记账本可用。
                .andExpect(jsonPath("$.data.statistics.actualRefundAmount").value("0.00"))
                .andExpect(jsonPath("$.data.statistics.settledCommission").value("270.30")).andExpect(jsonPath("$.data.statistics.netTurnover").value("9012.00"))
                .andExpect(jsonPath("$.data.statistics.financialLedgerAvailable").value(true));
    }

    @Test
    void merchantStatisticsIncludesSuccessfulRefundLedgerInSameWindow() throws Exception {
        // D25：仅统计窗口内 status=SUCCESS 且本店的退款；PROCESSING/窗口外/他店退款不得计入。
        statisticsOrder("ledger-paid", 1L, "PAID", "PAID", "10", "0.30");
        insertRefund("LEDGER-IN", 1L, "SUCCESS", "2.00", "0.06", java.time.LocalDateTime.now().minusDays(1));
        insertRefund("LEDGER-PROCESSING", 1L, "PROCESSING", "5.00", "0.15", null);
        insertRefund("LEDGER-OUT-OF-WINDOW", 1L, "SUCCESS", "7.00", "0.21", java.time.LocalDateTime.now().minusDays(120));
        insertRefund("LEDGER-FOREIGN", 2L, "SUCCESS", "13.00", "0.39", java.time.LocalDateTime.now().minusDays(1));
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        mvc.perform(get("/api/v1/merchant/orders/statistics").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.paidTurnover").value("10.00")).andExpect(jsonPath("$.data.estimatedCommission").value("0.30"))
                .andExpect(jsonPath("$.data.actualRefundAmount").value("2.00")).andExpect(jsonPath("$.data.netTurnover").value("8.00"))
                .andExpect(jsonPath("$.data.settledCommission").value("0.24")).andExpect(jsonPath("$.data.financialLedgerAvailable").value(true));
    }

    private void insertRefund(String sn, long merchantId, String status, String refundAmount, String feeRefund, java.time.LocalDateTime successAt) {
        jdbc.update("INSERT INTO trade_refund (refund_no, order_id, order_sn, merchant_id, channel, idempotency_key, refund_amount, fee_refund_amount, status, success_at) "
                        + "VALUES (?, 0, ?, ?, 'MOCK', ?, ?, ?, ?, ?)",
                "RF-" + sn, sn, merchantId, "IK-" + sn, new java.math.BigDecimal(refundAmount), new java.math.BigDecimal(feeRefund), status, successAt);
    }

    @Test
    void emptyMerchantStatisticsReturnsKnownZeroRatherThanReadingOtherStore() throws Exception {
        statisticsOrder("foreign-only", 2L, "PAID", "PAID", "500", "15");
        authUser("MERCHANT_STAFF", 1L); String token = loginToken();
        mvc.perform(get("/api/v1/merchant/orders/statistics").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.totalOrders").value(0)).andExpect(jsonPath("$.data.paidTurnover").value("0.00"))
                // D25：空店铺退款指标为已知 0，且不回读他店账本。
                .andExpect(jsonPath("$.data.actualRefundAmount").value("0.00")).andExpect(jsonPath("$.data.settledCommission").value("0.00"))
                .andExpect(jsonPath("$.data.financialLedgerAvailable").value(true));
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
            var request = post("/api/v1/auth/login").contentType("application/json")
                    .header("X-Forwarded-For", "192.0.2." + attempt).header("Forwarded", "for=192.0.2." + attempt)
                    .content(json.writeValueAsString(java.util.Map.of("username", "missing-" + attempt, "password", AUTH_PASSWORD)))
                    // MockMvc 默认 remoteAddr=127.0.0.1 属于可信回环，必须显式模拟直连公网客户端才能验证忽略 XFF。
                    .with(builder -> { builder.setRemoteAddr("198.51.100.10"); return builder; });
            mvc.perform(request)
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
                .andExpect(status().is(scenario.equals("unknownRole") ? 403 : 500))
                .andExpect(jsonPath("$.code").value(scenario.equals("unknownRole") ? 403 : 500))
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
                .andExpect(jsonPath("$.data.merchantId").value("2"));
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
        // T06：测试适配器必须显式传入渠道实收金额/币种/商户号，不再由服务层从订单反填。
        TradeOrder order = orders.findByOrderSn("ORD-" + token).orElse(null);
        java.math.BigDecimal amount = order != null ? order.getPayAmount() : java.math.BigDecimal.TEN;
        Long merchantId = order != null ? order.getMerchantId() : 1L;
        return payments.notifyPaymentSuccess(token, "verified-payer", "trade-" + token, "WECHAT",
                amount, "CNY", merchantId);
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
                        .content("{\"merchantId\":1,\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"buyer@example.com\","
                                + "\"receiverName\":\"张三\",\"receiverPhone\":\"13800138000\",\"receiverRegion\":\"广东省 深圳市 南山区\",\"receiverAddress\":\"科技园南路 88 号 3 栋 501\"}"))
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
    @DisplayName("T10/Q17: ownerToken 由服务端生成，幂等键不能恢复预占，同一预占最多重发 3 次")
    void t10ServerOwnerTokenIsSeparatedFromAttemptKeyAndReissueIsCapped() {
        intent("t10-recovery");
        assertEquals(1, skus.selectById(1).getStockQuantity());

        // 1. 首次预占：尝试键只留痕，ownerToken 必须是服务端生成的高熵值
        var firstLock = payments.preLockInventory("t10-recovery", "attempt-key-alpha");
        assertNotNull(firstLock.getOwnerToken());
        assertNotEquals("attempt-key-alpha", firstLock.getOwnerToken());
        assertEquals(32, firstLock.getOwnerToken().length());
        assertEquals("RESERVED_LOCK", firstLock.getPeerStatus());
        assertEquals(0, skus.selectById(1).getStockQuantity());

        // 2. 只带相同幂等键、不带 ownerToken：不得恢复，防止猜测幂等键窃取预占
        var exAttemptOnly = assertThrows(BusinessException.class,
                () -> payments.preLockInventory("t10-recovery", "attempt-key-alpha"));
        assertTrue(exAttemptOnly.getMessage().contains("已被其他付款人预占中"));

        // 3. 提交服务端签发的 ownerToken：可恢复且不重复扣库存
        var retryLock = payments.preLockInventory("t10-recovery", "attempt-key-alpha", firstLock.getOwnerToken());
        assertEquals(firstLock.getOwnerToken(), retryLock.getOwnerToken());
        assertTrue(retryLock.getSuccess());
        assertEquals(0, skus.selectById(1).getStockQuantity());

        // 4. 重发上限 3 次：第 2、3 次恢复成功，第 4 次被拒
        assertEquals(firstLock.getOwnerToken(),
                payments.preLockInventory("t10-recovery", null, firstLock.getOwnerToken()).getOwnerToken());
        assertEquals(firstLock.getOwnerToken(),
                payments.preLockInventory("t10-recovery", null, firstLock.getOwnerToken()).getOwnerToken());
        var exCap = assertThrows(BusinessException.class, () -> payments.preLockInventory(
                "t10-recovery", null, firstLock.getOwnerToken()));
        assertEquals(409, exCap.getCode());
        assertTrue(exCap.getMessage().contains("重发次数已达上限"));

        // 5. 错误/缺失凭据仍被拒
        var exWrong = assertThrows(BusinessException.class,
                () -> payments.preLockInventory("t10-recovery", null, "wrong-owner-token"));
        assertTrue(exWrong.getMessage().contains("已被其他付款人预占中"));
        assertEquals(0, skus.selectById(1).getStockQuantity());

        // 6. 原持有者释放后，新的尝试才能重新预占且获得新的服务端凭据
        payments.cancelPreLock("t10-recovery", firstLock.getOwnerToken());
        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals("INIT", orders.lockByPeerPayToken("t10-recovery").getPeerStatus());
        var nextLock = payments.preLockInventory("t10-recovery", "attempt-key-beta");
        assertNotEquals("attempt-key-beta", nextLock.getOwnerToken());
        assertEquals(0, skus.selectById(1).getStockQuantity());

        // 7. 超长尝试键仍被 400 拦截
        intent("t10-long-key");
        var exExcessive = assertThrows(BusinessException.class,
                () -> payments.preLockInventory("t10-long-key", "k".repeat(65)));
        assertEquals(400, exExcessive.getCode());
    }

    @Test
    void t10HttpRecoveryRequiresServerIssuedReservationTokenAndCapsReissue() throws Exception {
        intent("t10-http");

        // 首次预占：响应返回服务端 ownerToken，客户端必须保存
        var first = mvc.perform(post("/api/v1/peer-pay/pre-lock/t10-http")
                        .header("Idempotency-Key", "http-client-attempt-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ownerToken").isNotEmpty())
                .andExpect(jsonPath("$.data.peerStatus").value("RESERVED_LOCK"))
                .andReturn();
        String ownerToken = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.data.ownerToken");
        assertNotEquals("http-client-attempt-1", ownerToken);

        // 只重发幂等键不提交 ownerToken：被拒
        mvc.perform(post("/api/v1/peer-pay/pre-lock/t10-http")
                        .header("Idempotency-Key", "http-client-attempt-1"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("订单已被其他付款人预占中，请稍候再试"));

        // 提交服务端 ownerToken：恢复成功
        mvc.perform(post("/api/v1/peer-pay/pre-lock/t10-http")
                        .header("Idempotency-Key", "http-client-attempt-1")
                        .header("X-Reservation-Token", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ownerToken").value(ownerToken))
                .andExpect(jsonPath("$.data.message").value("已恢复现有库存预占状态"));
    }

    @Test
    void r12UnpaidDirectCheckoutOrdersAreAutomaticallyClosedAfterFifteenMinutes() {
        TradeOrder directOrder = unpaid("r12-direct", 1);
        directOrder.setPeerStatus("INIT");
        orders.save(directOrder);

        // 设置创建时间在 20 分钟前（截止时间按 createdAt+15 计算），无预占记录
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(20);
        jdbc.update("UPDATE trade_order SET created_at=?, lock_expire_time=? WHERE id=?",
                createdAt, createdAt.plusMinutes(15), directOrder.getId());

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
        orders.save(freshUnpaid);
        LocalDateTime freshCreatedAt = LocalDateTime.now().minusMinutes(5);
        jdbc.update("UPDATE trade_order SET created_at=?, lock_expire_time=? WHERE id=?",
                freshCreatedAt, freshCreatedAt.plusMinutes(15), freshUnpaid.getId());

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

        // 2. 超管将店铺封禁 (status=3) 并调高费率至 5.00%（调高次日生效，展示镜像今日仍为 3.00%）
        // O6：冻结改走 /store-status 携带原因码（全审计），旧 PUT /status 直置冻结已移除。
        authUser("SUPERADMIN", null);
        String adminToken = loginToken();
        mvc.perform(post("/api/v1/admin/merchants/1/store-status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"action\":\"FREEZE\",\"reasonCode\":\"VIOLATION\",\"reason\":\"测试封禁\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/merchants/1/commission?rate=5.00")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 登出超管并清理会话与用户记录，准备切换为商家身份
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");

        // 3. O4 开通闸门：封禁(3)等非营业状态下商家写接口一律 409，公告/电话绝不能被部分写入
        authUser("MERCHANT_ADMIN", 1L);
        String merchantToken = loginToken();
        mvc.perform(put("/api/v1/merchant/profile")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType("application/json")
                        .content("{\"notice\":\"new notice\",\"contactPhone\":\"13900001111\"}"))
                .andExpect(status().is(409));
        var banned = stores.findById(1L).orElseThrow();
        assertEquals("old notice", banned.getNotice(), "O4: 非营业状态拒绝写资料，原有公告不得被改动");
        assertEquals(3, banned.getStatus(), "O4: 拒绝路径不得触碰店铺状态");

        // 4. 超管解封恢复营业 (status=1) 后，商家可正常更新展示公告与电话
        // O6：解封走 /store-status 携带原因码（默认宽松模式 SAME_OPERATOR 放行；严格模式需 confirmedBy 双人复核）。
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("SUPERADMIN", null);
        String reopenToken = loginToken();
        mvc.perform(post("/api/v1/admin/merchants/1/store-status")
                        .header("Authorization", "Bearer " + reopenToken)
                        .contentType("application/json")
                        .content("{\"action\":\"UNFREEZE\",\"reasonCode\":\"MERCHANT_REQUEST\",\"reason\":\"整改完成\"}"))
                .andExpect(status().isOk());
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("MERCHANT_ADMIN", 1L);
        String reopenedToken = loginToken();
        mvc.perform(put("/api/v1/merchant/profile")
                        .header("Authorization", "Bearer " + reopenedToken)
                        .contentType("application/json")
                        .content("{\"notice\":\"new notice\",\"contactPhone\":\"13900001111\"}"))
                .andExpect(status().isOk());

        // 5. 验证：公告与电话被成功更新，但营业状态 (1) 和待生效费率政策 (500bps) 绝未被冲刷回滚！
        var store = stores.findById(1L).orElseThrow();
        assertEquals("new notice", store.getNotice());
        assertEquals("13900001111", store.getContactPhone());
        assertEquals(1, store.getStatus(), "R09: 商家资料更新绝不能覆盖平台设置的店铺状态");
        assertEquals(500, jdbc.queryForObject("SELECT rate_bps FROM merchant_fee_policy WHERE merchant_id=1 "
                + "ORDER BY effective_from DESC, id DESC LIMIT 1", Integer.class).intValue(),
                "R09: 商家资料更新绝不能覆盖超管设置的平台抽成政策");
        assertEquals(new java.math.BigDecimal("3.00"), store.getCommissionRate(),
                "R09: 调高次日生效，展示镜像不应提前变更");
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
        // 金额不符消息携带两侧金额，断言关键语义而不是固定拼接顺序。
        assertTrue(ex.getMessage().contains("实付金额") && ex.getMessage().contains("订单应付金额")
                && ex.getMessage().contains("不符"));

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

        TradeOrder paidOrder = payments.notifyPaymentSuccess("r13-peer-adapter", "verified_peer_payer",
                "TX_PEER_ADAPTER_123", "WECHAT", java.math.BigDecimal.TEN, "CNY", 1L);
        assertEquals("COMPLETED", paidOrder.getOrderStatus());
        assertEquals("TX_PEER_ADAPTER_123", paidOrder.getPayTradeNo());

        var pe = paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_PEER_ADAPTER_123");
        assertNotNull(pe);
        assertEquals("SUCCESS", pe.getEventStatus());
        assertEquals("verified_peer_payer", pe.getPayerId());
    }

    @Test
    @DisplayName("T05: 履约失败回滚订单与库存后，事件必须落 FULFILLMENT_FAILED 而不是 SUCCESS")
    void t05FulfillmentFailureLeavesEventInFailedStateNotSuccess() {
        TradeOrder order = unpaid("t05-fail-state", 1);
        doReturn(0).when(cards).claimCard(anyLong(), anyLong(), anyLong(), anyString());

        assertThrows(BusinessException.class, () -> pay("t05-fail-state"));

        var pe = paymentEvents.findByChannelAndTradeNo("WECHAT", "trade-t05-fail-state");
        assertNotNull(pe);
        assertEquals("FULFILLMENT_FAILED", pe.getEventStatus());
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        assertEquals(1, skus.selectById(1).getStockQuantity());
    }

    @Test
    @DisplayName("T05: 同一流水号在订单已提交履约后的重放只做幂等返回，不再次发卡/扣库存")
    void t05CommittedEventReplayIsIdempotent() {
        TradeOrder order = unpaid("t05-replay", 1);
        assertEquals("COMPLETED", pay("t05-replay").getOrderStatus());
        assertNotNull(paymentEvents.findByChannelAndTradeNo("WECHAT", "trade-t05-replay"));

        assertEquals("COMPLETED", pay("t05-replay").getOrderStatus());
        assertEquals(0, skus.selectById(1).getStockQuantity());
        verify(cards, times(1)).claimCard(anyLong(), anyLong(), anyLong(), anyString());
        assertEquals(1, paymentEvents.findByOrderSn(order.getOrderSn()).size());
    }

    @Test
    @DisplayName("T05: 事件 SUCCESS 但订单仍 UNPAID 时，补偿扫描用同一事件重新驱动履约")
    void t05CompensationReDrivesSuccessEventWithUnpaidOrder() {
        TradeOrder order = unpaid("t05-compensate", 1);
        var orphan = new com.shop.b2b2c.entity.PaymentEvent();
        orphan.setChannel("WECHAT");
        orphan.setPayTradeNo("TX_T05_COMPENSATE");
        orphan.setOrderSn(order.getOrderSn());
        orphan.setMerchantId(1L);
        orphan.setPayAmount(java.math.BigDecimal.TEN);
        orphan.setCurrency("CNY");
        orphan.setPayerId("compensate-payer");
        orphan.setEventStatus("SUCCESS");
        paymentEvents.insert(orphan);

        int processed = paymentEventService.reconcileSuccessfulWithoutFulfillment(10);

        assertTrue(processed >= 1);
        assertEquals("COMPLETED", orders.selectById(order.getId()).getOrderStatus());
        assertEquals("SUCCESS", paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_T05_COMPENSATE").getEventStatus());
        assertEquals(0, skus.selectById(1).getStockQuantity());
    }

    @Test
    @DisplayName("G34: 超过阈值的 PROCESSING 事件经渠道确认成功后补履约，同一轮不二次发卡")
    void g34StaleProcessingEventFulfilledAfterChannelConfirmation() {
        TradeOrder order = unpaid("g34-stale", 1);
        insertProcessingEvent(order, "TX_G34_STALE", LocalDateTime.now().minusMinutes(10));
        when(paymentGateway.queryChannelPayment(eq(1L), eq("WECHAT"), eq(order.getOrderSn())))
                .thenReturn(channelQuerySuccess(order.getOrderSn(), "TX_G34_STALE", "g34-payer"));

        int processed = paymentEventReconcile.reconcile(10, Duration.ofMinutes(5), 3, Duration.ofSeconds(60));

        assertTrue(processed >= 1);
        assertEquals("COMPLETED", orders.selectById(order.getId()).getOrderStatus());
        assertEquals("SUCCESS", paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_G34_STALE").getEventStatus());
        // 渠道确认成功即资金已到账：funds_received 必须为 1。
        assertEquals(1, paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_G34_STALE")
                .getFundsReceived().intValue());
        assertEquals(0, skus.selectById(1).getStockQuantity());
        verify(cards, times(1)).claimCard(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("G34: 渠道无法确认支付成功的陈旧 PROCESSING 落 FULFILLMENT_FAILED，不交付资产")
    void g34StaleProcessingWithoutChannelConfirmationBecomesFailed() {
        TradeOrder order = unpaid("g34-nopay", 1);
        insertProcessingEvent(order, "TX_G34_NOPAY", LocalDateTime.now().minusMinutes(10));
        when(paymentGateway.queryChannelPayment(eq(1L), eq("WECHAT"), eq(order.getOrderSn())))
                .thenReturn(com.shop.b2b2c.dto.PaymentChannelDTOs.ChannelQueryResult.builder()
                        .channel("WECHAT").orderSn(order.getOrderSn()).payTradeNo("TX_G34_NOPAY")
                        .tradeState("PENDING").currency("CNY").build());

        paymentEventReconcile.reconcile(10, Duration.ofMinutes(5), 3, Duration.ofSeconds(60));

        assertEquals("FULFILLMENT_FAILED",
                paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_G34_NOPAY").getEventStatus());
        // 渠道明确未支付必须显式标注 funds_received=0，T+1 不得据此判渠道未导入。
        assertEquals(0, paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_G34_NOPAY")
                .getFundsReceived().intValue());
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        verify(cards, never()).claimCard(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("G34: 渠道已收款后已退款按 funds_received=1 处理，不得写成未收到资金")
    void g34RefundedKeepsFundsReceivedAndFailsFulfillment() {
        TradeOrder order = unpaid("g34-refunded", 1);
        insertProcessingEvent(order, "TX_G34_REFUNDED", LocalDateTime.now().minusMinutes(10));
        when(paymentGateway.queryChannelPayment(eq(1L), eq("WECHAT"), eq(order.getOrderSn())))
                .thenReturn(com.shop.b2b2c.dto.PaymentChannelDTOs.ChannelQueryResult.builder()
                        .channel("WECHAT").orderSn(order.getOrderSn()).payTradeNo("TX_G34_REFUNDED")
                        .tradeState("REFUNDED").currency("CNY").build());

        paymentEventReconcile.reconcile(10, Duration.ofMinutes(5), 3, Duration.ofSeconds(60));

        com.shop.b2b2c.entity.PaymentEvent stored =
                paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_G34_REFUNDED");
        assertEquals("FULFILLMENT_FAILED", stored.getEventStatus());
        // 已收款后已退款：渠道侧发生过资金往来，funds_received 必须为 1，进入 T+1 预期流水差集。
        assertEquals(1, stored.getFundsReceived().intValue());
        assertTrue(stored.getErrorMessage().contains("渠道已收款后已退款"));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        verify(cards, never()).claimCard(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("G34: 占位 funds_received=1（渠道侧存在资金往来）不被明确未支付答复降级")
    void g34DefinitiveNotPaidKeepsFundsOnClaimedEvent() {
        TradeOrder order = unpaid("g34-preserve", 1);
        // 与 claimEvent 占位行同形：新事件建出即为 1，语义是“渠道侧存在该笔资金往来、应有对应流水”，
        // 不是“此刻仍持有资金”。这里不是人工“确认到账”，而是正常占位数据。
        var seeded = insertProcessingEvent(order, "TX_G34_PRESERVE", LocalDateTime.now().minusMinutes(10));
        seeded.setFundsReceived(1);
        paymentEvents.updateById(seeded);
        when(paymentGateway.queryChannelPayment(eq(1L), eq("WECHAT"), eq(order.getOrderSn())))
                .thenReturn(com.shop.b2b2c.dto.PaymentChannelDTOs.ChannelQueryResult.builder()
                        .channel("WECHAT").orderSn(order.getOrderSn()).payTradeNo("TX_G34_PRESERVE")
                        .tradeState("CLOSED").currency("CNY").build());

        paymentEventReconcile.reconcile(10, Duration.ofMinutes(5), 3, Duration.ofSeconds(60));

        com.shop.b2b2c.entity.PaymentEvent stored =
                paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_G34_PRESERVE");
        // 资金往来有记录（1）与渠道答复（明确未支付）冲突：保留 1，由 T+1 生成冲突类差异要求人工核对。
        assertEquals("FULFILLMENT_FAILED", stored.getEventStatus());
        assertEquals(1, stored.getFundsReceived().intValue());
        assertTrue(stored.getErrorMessage().startsWith("渠道明确未支付"));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    @DisplayName("G34: 支付宝 TRADE_CLOSED 无退款金额证据时按冲突处理，不写“明确未支付=0”")
    void g34AlipayClosedWithoutRefundEvidenceKeepsFunds() {
        TradeOrder order = unpaid("g34-alipay-closed", 1);
        // 支付宝适配器：TRADE_CLOSED 且响应未携带 refund_fee/refund_amount 时仍输出 CLOSED。
        var seeded = insertProcessingEvent(order, "TX_G34_ALIPAY_CLOSED", LocalDateTime.now().minusMinutes(10));
        seeded.setChannel("ALIPAY");
        seeded.setFundsReceived(1);
        paymentEvents.updateById(seeded);
        when(paymentGateway.queryChannelPayment(eq(1L), eq("ALIPAY"), eq(order.getOrderSn())))
                .thenReturn(com.shop.b2b2c.dto.PaymentChannelDTOs.ChannelQueryResult.builder()
                        .channel("ALIPAY").orderSn(order.getOrderSn()).payTradeNo("TX_G34_ALIPAY_CLOSED")
                        .tradeState("CLOSED").currency("CNY").build());

        paymentEventReconcile.reconcile(10, Duration.ofMinutes(5), 3, Duration.ofSeconds(60));

        com.shop.b2b2c.entity.PaymentEvent stored =
                paymentEvents.findByChannelAndTradeNo("ALIPAY", "TX_G34_ALIPAY_CLOSED");
        assertEquals("FULFILLMENT_FAILED", stored.getEventStatus());
        // 无法判定是否全额退款：资金往来记录保留 1；错误信息必须提示“可能为全额退款”并核对退款流水，
        // 不得写成“渠道明确未支付”，T+1 按 REASON_CHANNEL_CLOSED_UNDETERMINED 归入冲突类。
        assertEquals(1, stored.getFundsReceived().intValue());
        assertTrue(stored.getErrorMessage().startsWith("渠道已关闭，可能为全额退款，需核对退款流水"));
        assertFalse(stored.getErrorMessage().contains("渠道明确未支付"));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    @DisplayName("G34: 仅未标注行/历史或异常行在渠道查无此单时写 0（新事件不可达）")
    void g34DefinitiveNotPaidMarksZeroWhenFundsUnmarked() {
        TradeOrder order = unpaid("g34-notexist", 1);
        insertProcessingEvent(order, "TX_G34_NOTEXIST", LocalDateTime.now().minusMinutes(10));
        when(paymentGateway.queryChannelPayment(eq(1L), eq("WECHAT"), eq(order.getOrderSn())))
                .thenReturn(com.shop.b2b2c.dto.PaymentChannelDTOs.ChannelQueryResult.builder()
                        .channel("WECHAT").orderSn(order.getOrderSn()).payTradeNo("TX_G34_NOTEXIST")
                        .tradeState("NOT_EXIST").currency("CNY").build());

        paymentEventReconcile.reconcile(10, Duration.ofMinutes(5), 3, Duration.ofSeconds(60));

        com.shop.b2b2c.entity.PaymentEvent stored =
                paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_G34_NOTEXIST");
        assertEquals("FULFILLMENT_FAILED", stored.getEventStatus());
        assertEquals(0, stored.getFundsReceived().intValue());
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    @DisplayName("G34: 未达到阈值的 PROCESSING 事件不进入补偿扫描")
    void g34FreshProcessingEventBelowThresholdIsNotScanned() {
        TradeOrder order = unpaid("g34-fresh", 1);
        insertProcessingEvent(order, "TX_G34_FRESH", LocalDateTime.now());

        int processed = paymentEventReconcile.reconcile(10, Duration.ofMinutes(5), 3, Duration.ofSeconds(60));

        assertEquals(0, processed);
        assertEquals("PROCESSING",
                paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_G34_FRESH").getEventStatus());
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        verify(paymentGateway, never()).queryChannelPayment(any(), any(), any());
    }

    @Test
    @DisplayName("G34: 主事务已正常完成履约的订单，其残留 PROCESSING 不被误处理")
    void g34ProcessingEventOnFulfilledOrderIsNotTouched() {
        TradeOrder order = unpaid("g34-done", 1);
        assertEquals("COMPLETED", pay("g34-done").getOrderStatus());
        insertProcessingEvent(order, "TX_G34_ORPHAN", LocalDateTime.now().minusMinutes(10));

        int processed = paymentEventReconcile.reconcile(10, Duration.ofMinutes(5), 3, Duration.ofSeconds(60));

        assertEquals(0, processed);
        assertEquals("PROCESSING",
                paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_G34_ORPHAN").getEventStatus());
        verify(paymentGateway, never()).queryChannelPayment(any(), any(), any());
    }

    @Test
    @DisplayName("G34: 渠道查询异常不当终态，保留可重试 PROCESSING 并按退避时间安排下次回查")
    void g34QueryExceptionKeepsEventRetryable() {
        TradeOrder order = unpaid("g34-retry", 1);
        insertProcessingEvent(order, "TX_G34_RETRY", LocalDateTime.now().minusMinutes(10));
        when(paymentGateway.queryChannelPayment(eq(1L), eq("WECHAT"), eq(order.getOrderSn())))
                .thenThrow(new com.shop.b2b2c.common.BusinessException(502, "渠道查单超时"));

        paymentEventReconcile.reconcile(10, Duration.ofMinutes(5), 3, Duration.ofSeconds(60));

        com.shop.b2b2c.entity.PaymentEvent event =
                paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_G34_RETRY");
        assertEquals("PROCESSING", event.getEventStatus());
        assertEquals(1, event.getReconcileAttempts().intValue());
        assertNotNull(event.getNextReconcileAt());
        assertTrue(event.getNextReconcileAt().isAfter(LocalDateTime.now()));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());

        // 未到 next_reconcile_at 时不得重复查单，保留状态等待退避到期
        paymentEventReconcile.reconcile(10, Duration.ofMinutes(5), 3, Duration.ofSeconds(60));
        verify(paymentGateway, times(1))
                .queryChannelPayment(eq(1L), eq("WECHAT"), eq(order.getOrderSn()));
    }

    @Test
    @DisplayName("G34: 超过重试上限转 MANUAL_REVIEW 人工队列，不静默失败")
    void g34ExceedingRetryLimitMovesToManualReview() {
        TradeOrder order = unpaid("g34-manual", 1);
        // 起点刻意设为 1（已到账）：只有 SQL 显式 SET funds_received=NULL 才会变成 NULL，
        // 起点本为 NULL 时即使不写该列也会“通过”，证明不了清空逻辑生效。
        var seeded = insertPaymentEvent(order, "TX_G34_MANUAL",
                LocalDateTime.now().minusMinutes(10), "PROCESSING", 2);
        seeded.setFundsReceived(1);
        paymentEvents.updateById(seeded);
        when(paymentGateway.queryChannelPayment(eq(1L), eq("WECHAT"), eq(order.getOrderSn())))
                .thenThrow(new com.shop.b2b2c.common.BusinessException(503, "渠道持续不可用"));

        paymentEventReconcile.reconcile(10, Duration.ofMinutes(5), 3, Duration.ofSeconds(60));

        com.shop.b2b2c.entity.PaymentEvent stored =
                paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_G34_MANUAL");
        assertEquals("MANUAL_REVIEW", stored.getEventStatus());
        // 人工队列资金状态未知，funds_received 必须回写 NULL，由 T+1 REVIEW 差异兜住。
        assertNull(stored.getFundsReceived());
        assertEquals(3, stored.getReconcileAttempts().intValue());
        assertNull(stored.getNextReconcileAt());
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        verify(cards, never()).claimCard(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("G34: 事件已落 FULFILLMENT_FAILED 后收到真实 SUCCESS 通知仍能幂等履约")
    void g34FailedEventIsFulfilledByLaterRealSuccessNotification() {
        TradeOrder order = unpaid("g34-replay", 1);
        insertPaymentEvent(order, "trade-g34-replay", LocalDateTime.now(), "FULFILLMENT_FAILED", null);

        assertEquals("COMPLETED", pay("g34-replay").getOrderStatus());
        assertEquals("SUCCESS",
                paymentEvents.findByChannelAndTradeNo("WECHAT", "trade-g34-replay").getEventStatus());
        verify(cards, times(1)).claimCard(anyLong(), anyLong(), anyLong(), anyString());
    }

    private com.shop.b2b2c.entity.PaymentEvent insertProcessingEvent(TradeOrder order, String tradeNo,
                                                                     LocalDateTime createdAt) {
        return insertPaymentEvent(order, tradeNo, createdAt, "PROCESSING", null);
    }

    private com.shop.b2b2c.entity.PaymentEvent insertPaymentEvent(TradeOrder order, String tradeNo,
                                                                  LocalDateTime createdAt,
                                                                  String status, Integer attempts) {
        var event = new com.shop.b2b2c.entity.PaymentEvent();
        event.setChannel("WECHAT");
        event.setPayTradeNo(tradeNo);
        event.setOrderSn(order.getOrderSn());
        event.setMerchantId(order.getMerchantId());
        event.setPayAmount(order.getPayAmount());
        event.setCurrency("CNY");
        event.setPayerId("g34-payer");
        event.setEventStatus(status);
        event.setReconcileAttempts(attempts);
        event.setCreatedAt(createdAt);
        event.setUpdatedAt(createdAt);
        paymentEvents.insert(event);
        return event;
    }

    private com.shop.b2b2c.dto.PaymentChannelDTOs.ChannelQueryResult channelQuerySuccess(
            String orderSn, String tradeNo, String payerId) {
        return com.shop.b2b2c.dto.PaymentChannelDTOs.ChannelQueryResult.builder()
                .channel("WECHAT").orderSn(orderSn).payTradeNo(tradeNo).tradeState("SUCCESS")
                .channelMerchantId("mch-g34").payAmount(new java.math.BigDecimal("10.00"))
                .currency("CNY").payerId(payerId).rawPayload("{}").build();
    }

    @Test
    @DisplayName("T06: 渠道适配器缺失/错误实收金额被拒绝，事件金额使用渠道值而非订单反填")
    void t06AdapterReceiptAmountIsNotBackfilledFromOrder() {
        TradeOrder order = unpaid("t06-amount", 1);

        var missing = assertThrows(BusinessException.class, () -> payments.notifyPaymentSuccess(
                "t06-amount", "payer", "TX_T06_MISSING", "WECHAT", null, "CNY", 1L));
        assertTrue(missing.getMessage().contains("缺少渠道实收金额"));
        assertNull(paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_T06_MISSING"));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());

        var mismatch = assertThrows(BusinessException.class, () -> payments.notifyPaymentSuccess(
                "t06-amount", "payer", "TX_T06_MISMATCH", "WECHAT",
                new java.math.BigDecimal("9.99"), "CNY", 1L));
        assertTrue(mismatch.getMessage().contains("实付金额"));
        var pe = paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_T06_MISMATCH");
        assertNotNull(pe);
        assertEquals("AMOUNT_MISMATCH", pe.getEventStatus());
        assertEquals(0, new java.math.BigDecimal("9.99").compareTo(pe.getPayAmount()));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    @DisplayName("T06: 币种/渠道商户号不符必须拒绝且不降级为订单默认 CNY/商户")
    void t06AdapterRejectsCurrencyAndMerchantMismatch() {
        TradeOrder order = unpaid("t06-currency", 1);
        var wrongCurrency = assertThrows(BusinessException.class, () -> payments.notifyPaymentSuccess(
                "t06-currency", "payer", "TX_T06_USD", "WECHAT",
                java.math.BigDecimal.TEN, "USD", 1L));
        assertTrue(wrongCurrency.getMessage().contains("交易币种不符"));
        assertEquals("CURRENCY_MISMATCH",
                paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_T06_USD").getEventStatus());

        var wrongMerchant = assertThrows(BusinessException.class, () -> payments.notifyPaymentSuccess(
                "t06-currency", "payer", "TX_T06_MERCHANT", "WECHAT",
                java.math.BigDecimal.TEN, "CNY", 2L));
        assertTrue(wrongMerchant.getMessage().contains("支付事件商户与订单商户不一致"));
        assertEquals("MERCHANT_MISMATCH",
                paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_T06_MERCHANT").getEventStatus());
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    @DisplayName("T07: 已支付订单收到不同流水号必须落 DUPLICATE_PAYMENT，不静默成功也不重复发卡")
    void t07SecondTradeNoOnPaidOrderBecomesDuplicatePayment() {
        TradeOrder order = intent("t07-dup");
        payments.preLockInventory("t07-dup");
        assertEquals("COMPLETED", payments.notifyPaymentSuccess("t07-dup", "payer-a", "TX_T07_FIRST",
                "WECHAT", java.math.BigDecimal.TEN, "CNY", 1L).getOrderStatus());

        TradeOrder second = payments.notifyPaymentSuccess("t07-dup", "payer-b", "TX_T07_SECOND",
                "WECHAT", java.math.BigDecimal.TEN, "CNY", 1L);
        assertEquals("COMPLETED", second.getOrderStatus());

        var duplicate = paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_T07_SECOND");
        assertNotNull(duplicate);
        assertEquals("DUPLICATE_PAYMENT", duplicate.getEventStatus());
        assertEquals("SUCCESS", paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_T07_FIRST").getEventStatus());
        verify(cards, times(1)).claimCard(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("T07: 已支付订单仍先完成参数校验，缺失付款人不得静默返回")
    void t07PaidOrderStillValidatesIncomingParameters() {
        TradeOrder order = intent("t07-validate");
        payments.preLockInventory("t07-validate");
        payments.notifyPaymentSuccess("t07-validate", "payer-a", "TX_T07_VALIDATE_FIRST",
                "WECHAT", java.math.BigDecimal.TEN, "CNY", 1L);

        var ex = assertThrows(BusinessException.class, () -> payments.notifyPaymentSuccess(
                "t07-validate", " ", "TX_T07_VALIDATE_BAD", "WECHAT",
                java.math.BigDecimal.TEN, "CNY", 1L));
        assertTrue(ex.getMessage().contains("缺少可信支付结果"));
        assertNull(paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_T07_VALIDATE_BAD"));
        assertEquals("COMPLETED", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    @DisplayName("T07: 两个付款人并发用不同流水号付款时两笔事件均落库，仅一笔履约")
    void t07ConcurrentDifferentTradeNosOnlyOneFulfills() throws Exception {
        TradeOrder order = intent("t07-race");
        payments.preLockInventory("t07-race");
        race(2, i -> payments.notifyPaymentSuccess("t07-race", "payer-" + i, "TX_T07_RACE_" + i,
                "WECHAT", java.math.BigDecimal.TEN, "CNY", 1L));

        assertEquals("COMPLETED", orders.selectById(order.getId()).getOrderStatus());
        List<String> statuses = jdbc.queryForList(
                "SELECT event_status FROM payment_event WHERE pay_trade_no LIKE 'TX_T07_RACE_%'", String.class);
        assertEquals(2, statuses.size());
        assertTrue(statuses.contains("SUCCESS"));
        assertTrue(statuses.contains("DUPLICATE_PAYMENT"));
        verify(cards, times(1)).claimCard(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void checkoutRejectsCrossProductSkuWithoutSideEffects() throws Exception {
        mvc.perform(post("/api/v1/public/orders/checkout").header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType("application/json")
                        .content("{\"productId\":1,\"skuId\":2,\"quantity\":1,\"buyerContact\":\"buyer\","
                                + "\"receiverName\":\"张三\",\"receiverPhone\":\"13800138000\",\"receiverRegion\":\"广东省 深圳市 南山区\",\"receiverAddress\":\"科技园南路 88 号 3 栋 501\"}"))
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
        // G34：并发同单只允许一条支付事件；race() 会在任一线程抛出 UnexpectedRollbackException 时失败。
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_event WHERE channel='WECHAT' AND pay_trade_no='trade-replay'",
                Integer.class).intValue());
        assertEquals("SUCCESS", jdbc.queryForObject(
                "SELECT event_status FROM payment_event WHERE channel='WECHAT' AND pay_trade_no='trade-replay'", String.class));
    }

    @Test
    void callbackAuditPersistsOutcomeWithoutStoringPayloadOrOversizedReason() {
        com.shop.b2b2c.entity.PaymentCallbackAudit accepted = new com.shop.b2b2c.entity.PaymentCallbackAudit();
        accepted.setChannel(" wechat ");
        accepted.setOrderSn("SN-AUDIT-1");
        accepted.setPayTradeNo("TX-AUDIT-1");
        accepted.setChannelMerchantId("MCH-1");
        accepted.setOutcome(com.shop.b2b2c.service.payment.PaymentCallbackAuditService.OUTCOME_ACCEPTED);
        accepted.setProcessingResult("SUCCESS");
        accepted.setPayloadSha256("f".repeat(64));
        accepted.setDurationMs(12);
        callbackAudits.record(accepted);

        com.shop.b2b2c.entity.PaymentCallbackAudit rejected = new com.shop.b2b2c.entity.PaymentCallbackAudit();
        rejected.setChannel("ALIPAY");
        rejected.setOrderSn("SN-AUDIT-1");
        rejected.setOutcome(com.shop.b2b2c.service.payment.PaymentCallbackAuditService.OUTCOME_REJECTED);
        rejected.setReason("reject-" + "x".repeat(400));
        rejected.setPayloadSha256("e".repeat(64));
        callbackAudits.record(rejected);

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_callback_audit WHERE order_sn='SN-AUDIT-1'", Integer.class).intValue());
        assertEquals("WECHAT", jdbc.queryForObject(
                "SELECT channel FROM payment_callback_audit WHERE payload_sha256=?", String.class, "f".repeat(64)));
        assertEquals("ACCEPTED", jdbc.queryForObject(
                "SELECT outcome FROM payment_callback_audit WHERE payload_sha256=?", String.class, "f".repeat(64)));
        assertEquals("SUCCESS", jdbc.queryForObject(
                "SELECT processing_result FROM payment_callback_audit WHERE payload_sha256=?", String.class, "f".repeat(64)));
        assertEquals(12, jdbc.queryForObject(
                "SELECT duration_ms FROM payment_callback_audit WHERE payload_sha256=?", Integer.class, "f".repeat(64)).intValue());
        assertNotNull(jdbc.queryForObject(
                "SELECT created_at FROM payment_callback_audit WHERE payload_sha256=?", java.sql.Timestamp.class, "f".repeat(64)));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT outcome FROM payment_callback_audit WHERE payload_sha256=?", String.class, "e".repeat(64)));
        assertEquals(200, jdbc.queryForObject(
                "SELECT CHAR_LENGTH(reason) FROM payment_callback_audit WHERE payload_sha256=?", Integer.class, "e".repeat(64)).intValue(),
                "原因摘要必须截断到 200 字符，不得因超长导致整条审计失败");
        assertNull(jdbc.queryForObject(
                "SELECT processing_result FROM payment_callback_audit WHERE payload_sha256=?", String.class, "e".repeat(64)));
    }

    @Test
    void closeReceiptRetryCandidatesOnlyLatestFailedWithinWindow() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        insertCloseReceipt("SN-CLOSE-1", 1, "FAILED", now.minusMinutes(20));
        insertCloseReceipt("SN-CLOSE-1", 2, "SKIPPED", now.minusMinutes(5));
        insertCloseReceipt("SN-CLOSE-2", 5, "FAILED", now.minusMinutes(30));
        insertCloseReceipt("SN-CLOSE-3", 1, "FAILED", now.minusMinutes(1));
        insertCloseReceipt("SN-CLOSE-4", 1, "FAILED", now.minusHours(100));
        insertCloseReceipt("SN-CLOSE-5", 1, "FAILED", now.minusMinutes(25));
        insertCloseReceipt("SN-CLOSE-5", 2, "FAILED", now.minusMinutes(15));
        insertCloseReceipt("SN-CLOSE-6", 1, "SUCCESS", now.minusMinutes(20));

        List<String> candidates = closeReceipts.findRetryableOrderSn(now.minusHours(72), now.minusMinutes(10), 5, 100);

        assertTrue(candidates.contains("SN-CLOSE-5"), "最新一次 FAILED 且已过退避间隔的订单应进入重试");
        assertFalse(candidates.contains("SN-CLOSE-1"), "最新回执为 SKIPPED 时不得自动重试");
        assertFalse(candidates.contains("SN-CLOSE-2"), "已达尝试上限的订单不得继续重试");
        assertFalse(candidates.contains("SN-CLOSE-3"), "未过退避间隔的订单不得提前重试");
        assertFalse(candidates.contains("SN-CLOSE-4"), "超出扫描窗口的历史回执不再重试");
        assertFalse(candidates.contains("SN-CLOSE-6"), "SUCCESS 回执不进入重试");
    }

    private void insertCloseReceipt(String orderSn, int attemptNo, String result, java.time.LocalDateTime attemptedAt) {
        jdbc.update("INSERT INTO payment_close_receipt "
                        + "(order_sn, merchant_id, channel, attempt_no, result, reason, attempted_at, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                orderSn, 1L, "WECHAT", attemptNo, result, "fixture", attemptedAt, attemptedAt);
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
            assertThrows(BusinessException.class, () -> orderController.listMerchantOrders(null, null, null, null));
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
            security.when(SecurityUtils::getCurrentRoleCode).thenReturn("MERCHANT_ADMIN");
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
            security.when(SecurityUtils::getCurrentRoleCode).thenReturn("MERCHANT_ADMIN");
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
            security.when(SecurityUtils::getCurrentRoleCode).thenReturn("MERCHANT_ADMIN");
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
            security.when(SecurityUtils::getCurrentRoleCode).thenReturn("MERCHANT_ADMIN");
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
        assertTrue(products.listPublicProducts(null, 1L, null, null, null).getData().isEmpty());
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
        dto.setReceiverName("张三"); dto.setReceiverPhone("13800138000");
        dto.setReceiverRegion("广东省 深圳市 南山区"); dto.setReceiverAddress("科技园南路 88 号 3 栋 501");
        String first = (String) orderController.checkout(dto).getData().get("orderSn");
        String second = (String) orderController.checkout(dto).getData().get("orderSn");
        assertTrue(first.matches("ORD[0-9a-f]{32}")); assertNotEquals(first, second);
        PeerPayDTOs.PushPayCreateRequest push = new PeerPayDTOs.PushPayCreateRequest();
        push.setProductId(1L); push.setSkuId(1L); push.setQuantity(1); push.setNegotiatedPrice(new java.math.BigDecimal("-1"));
        assertThrows(BusinessException.class, () -> payments.createPushPayOrder(push, 1L));
        assertEquals(2, orders.selectCount(null));
    }

    @Test
    @DisplayName("T08/Q15: 直购、买家代付、卖家推单建单时均写入约 15 分钟数据库支付截止时间")
    void t08EveryNewOrderWritesDatabaseDeadline() {
        String checkoutSn = (String) orderController.checkout(checkoutRequest()).getData().get("orderSn");
        assertFifteenMinuteDeadline(orders.findByOrderSn(checkoutSn).orElseThrow());

        PeerPayDTOs.BuyerCreateRequest buyer = new PeerPayDTOs.BuyerCreateRequest();
        buyer.setProductId(1L); buyer.setSkuId(1L); buyer.setQuantity(1); buyer.setBuyerContact("buyer");
        buyer.setReceiverName("张三"); buyer.setReceiverPhone("13800138000");
        buyer.setReceiverRegion("广东省 深圳市 南山区"); buyer.setReceiverAddress("科技园南路 88 号 3 栋 501");
        String buyerSn = (String) payments.createBuyerPeerPay(buyer).get("orderSn");
        assertFifteenMinuteDeadline(orders.findByOrderSn(buyerSn).orElseThrow());

        PeerPayDTOs.PushPayCreateRequest push = new PeerPayDTOs.PushPayCreateRequest();
        push.setProductId(1L); push.setSkuId(1L); push.setQuantity(1);
        String pushSn = (String) payments.createPushPayOrder(push, 1L).get("orderSn");
        assertFifteenMinuteDeadline(orders.findByOrderSn(pushSn).orElseThrow());
    }

    private void assertFifteenMinuteDeadline(TradeOrder order) {
        assertNotNull(order.getLockExpireTime());
        long minutes = java.time.Duration.between(LocalDateTime.now(), order.getLockExpireTime()).toMinutes();
        assertTrue(minutes >= 14 && minutes <= 15, "截止时间应约为 15 分钟，实际剩余分钟=" + minutes);
    }

    @Test
    @DisplayName("T08: 普通订单超过数据库截止时间或截止时间为 NULL 时拒绝落账并记录 LATE_PAYMENT")
    void t08ExpiredOrNullDeadlineOrderIsRejected() {
        TradeOrder expired = unpaid("t08-expired", 1);
        expired.setPeerPayToken(null);
        expired.setPeerStatus("INIT");
        expired.setLockExpireTime(LocalDateTime.now().minusSeconds(1));
        orders.save(expired);
        var expiredEvent = com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel("WECHAT").payTradeNo("TX_T08_EXPIRED").orderSn(expired.getOrderSn())
                .merchantId(1L).payAmount(java.math.BigDecimal.TEN).currency("CNY").payerId("payer").build();
        var ex = assertThrows(BusinessException.class, () -> paymentEventService.processPaymentEvent(expiredEvent));
        assertTrue(ex.getMessage().contains("超过 15 分钟"));
        assertEquals("LATE_PAYMENT", paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_T08_EXPIRED").getEventStatus());
        assertEquals("UNPAID", orders.selectById(expired.getId()).getOrderStatus());

        TradeOrder legacy = unpaid("t08-null", 1);
        legacy.setPeerPayToken(null);
        legacy.setPeerStatus("INIT");
        legacy.setLockExpireTime(null);
        orders.save(legacy);
        var legacyEvent = com.shop.b2b2c.dto.PaymentEventDTOs.IncomingPaymentEvent.builder()
                .channel("WECHAT").payTradeNo("TX_T08_NULL").orderSn(legacy.getOrderSn())
                .merchantId(1L).payAmount(java.math.BigDecimal.TEN).currency("CNY").payerId("payer").build();
        assertThrows(BusinessException.class, () -> paymentEventService.processPaymentEvent(legacyEvent));
        assertEquals("LATE_PAYMENT", paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_T08_NULL").getEventStatus());
        assertEquals("UNPAID", orders.selectById(legacy.getId()).getOrderStatus());
        assertEquals(1, skus.selectById(1).getStockQuantity());
    }

    @Test
    @DisplayName("Q15 补充: 预占成功不得延长支付截止，仍按订单创建时间 + 15 分钟")
    void preLockNeverExtendsOrderPaymentDeadline() {
        TradeOrder order = intent("deadline-no-extend");
        // 下单已过 10 分钟，剩余支付窗口约 5 分钟
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(10).withNano(0);
        jdbc.update("UPDATE trade_order SET created_at=?, lock_expire_time=? WHERE id=?",
                createdAt, createdAt.plusMinutes(15), order.getId());

        var lock = payments.preLockInventory("deadline-no-extend");

        LocalDateTime deadline = orders.selectById(order.getId()).getLockExpireTime();
        assertNotNull(deadline);
        assertEquals(900, java.time.Duration.between(createdAt, deadline).getSeconds(),
                "预占后截止时间必须仍为下单时间 + 15 分钟，不得用当前时刻重算");
        assertTrue(lock.getLockSeconds() <= 300 && lock.getLockSeconds() >= 290,
                "剩余秒数应按原始截止计算，实际=" + lock.getLockSeconds());
        assertEquals(deadline, lock.getExpireTime());
        LocalDateTime reservationDeadline = jdbc.queryForObject(
                "SELECT expires_at FROM inventory_reservation WHERE order_id=?", LocalDateTime.class, order.getId());
        assertEquals(900, java.time.Duration.between(createdAt, reservationDeadline).getSeconds(),
                "预占到期时间也必须沿用下单截止时间");
    }

    @Test
    @DisplayName("Q15 补充: 下单 15 分钟到期即释放预占，超期付款落 LATE_PAYMENT")
    void expiredCreationDeadlineReleasesHoldAndLatePaymentIsRecorded() {
        TradeOrder order = intent("deadline-expired");
        payments.preLockInventory("deadline-expired");
        assertEquals(0, skus.selectById(1).getStockQuantity());

        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(20).withNano(0);
        jdbc.update("UPDATE trade_order SET created_at=?, lock_expire_time=? WHERE id=?",
                createdAt, createdAt.plusMinutes(15), order.getId());
        jdbc.update("UPDATE inventory_reservation SET expires_at=? WHERE order_id=?",
                createdAt.plusMinutes(15), order.getId());

        expiryJob.expire();

        assertEquals(1, skus.selectById(1).getStockQuantity());
        assertEquals("CLOSED", orders.selectById(order.getId()).getOrderStatus());
        assertEquals("EXPIRED", orders.selectById(order.getId()).getPeerStatus());

        assertThrows(BusinessException.class, () -> pay("deadline-expired"));
        assertEquals("LATE_PAYMENT",
                paymentEvents.findByChannelAndTradeNo("WECHAT", "trade-deadline-expired").getEventStatus());
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT order_status FROM trade_order WHERE id=?", String.class, order.getId()));
    }

    @Test
    @DisplayName("基线清理: 取消预占回到意向态但保留下单截止时间")
    void cancelPreLockKeepsOrderPaymentDeadline() {
        TradeOrder order = intent("cancel-keeps-deadline");
        var lock = payments.preLockInventory("cancel-keeps-deadline");
        LocalDateTime deadline = orders.selectById(order.getId()).getLockExpireTime();
        assertNotNull(deadline);

        payments.cancelPreLock("cancel-keeps-deadline", lock.getOwnerToken());

        TradeOrder released = orders.selectById(order.getId());
        assertEquals("INIT", released.getPeerStatus());
        assertEquals(deadline, released.getLockExpireTime(), "取消预占不得抹掉下单时确定的支付截止");
    }

    @Test
    @DisplayName("T09/Q16: HELD 预占阻止下架；已下架商品不得先 CAS 为 PAID 再校验可购性")
    void t09HeldReservationBlocksOffShelfAndPaymentRejectsUnpurchasable() {
        TradeOrder order = intent("t09-held");
        payments.preLockInventory("t09-held");
        String revision = editing.get(1L, 1L).revision();

        var offShelf = assertThrows(BusinessException.class, () -> editing.changeStatus(1L, 1L,
                new com.shop.b2b2c.dto.ProductEditingDTOs.Status(revision, 0)));
        assertEquals(409, offShelf.getCode());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM product WHERE id=1", Integer.class));

        // 绕过商家接口直接制造“已下架”状态，验证支付侧不会先落 PAID。
        jdbc.update("UPDATE product SET status=0 WHERE id=1");
        var payment = assertThrows(BusinessException.class, () -> payments.notifyPaymentSuccess(
                "t09-held", "payer", "TX_T09_OFFLINE", "WECHAT",
                java.math.BigDecimal.TEN, "CNY", 1L));
        assertTrue(payment.getMessage().contains("下架"));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        assertEquals("FULFILLMENT_FAILED",
                paymentEvents.findByChannelAndTradeNo("WECHAT", "TX_T09_OFFLINE").getEventStatus());
    }

    @Test
    @DisplayName("T11: 同一秒创建的订单分页使用 created_at + id 稳定排序，不重复不漏行")
    void t11PaginationUsesIdAsTieBreaker() {
        LocalDateTime sameSecond = LocalDateTime.of(2026, 11, 6, 10, 0, 0);
        TradeOrder first = unpaid("t11-page-a", 1);
        first.setCreatedAt(sameSecond);
        orders.save(first);
        TradeOrder second = unpaid("t11-page-b", 1);
        second.setCreatedAt(sameSecond);
        orders.save(second);

        List<TradeOrder> page1 = orders.findMerchantKeyset(1L, null, null, 1);
        assertEquals(1, page1.size());
        TradeOrder last = page1.get(0);
        com.shop.b2b2c.common.OrderCursor cursor =
                com.shop.b2b2c.common.OrderCursor.decode(
                        com.shop.b2b2c.common.OrderCursor.encode(last.getCreatedAt(), last.getId()));
        List<TradeOrder> page2 = orders.findMerchantKeyset(1L, null, cursor, 1);
        assertEquals(1, page2.size());
        assertNotEquals(page1.get(0).getId(), page2.get(0).getId());
        assertTrue(page1.get(0).getId() > page2.get(0).getId(), "id 降序必须作为 created_at 相同的 tiebreaker");
    }

    @Test
    void publicProductAndQuickSearchNeverExposePrivateStoreFields() throws Exception {
        unpaid("privacy", 1);
        for (var request : List.of(get("/api/v1/public/products/1"),
                post("/api/v1/public/orders/quick-search").contentType("application/json").content("{\"orderSn\":\"ORD-privacy\",\"contact\":\"buyer@example.com\",\"queryToken\":\"query-privacy\"}"))) {
            mvc.perform(request).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.store.id").value("1"))
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
        // F07：实物订单必须携带完整收货地址。
        dto.setReceiverName("张三"); dto.setReceiverPhone("13800138000");
        dto.setReceiverRegion("广东省 深圳市 南山区"); dto.setReceiverAddress("科技园南路 88 号 3 栋 501");
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
        String body = "{\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"buyer\","
                + "\"receiverName\":\"张三\",\"receiverPhone\":\"13800138000\",\"receiverRegion\":\"广东省 深圳市 南山区\",\"receiverAddress\":\"科技园南路 88 号 3 栋 501\"}";
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
        String body = "{\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"buyer\","
                + "\"receiverName\":\"张三\",\"receiverPhone\":\"13800138000\",\"receiverRegion\":\"广东省 深圳市 南山区\",\"receiverAddress\":\"科技园南路 88 号 3 栋 501\"}";
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
            security.when(SecurityUtils::getCurrentRoleCode).thenReturn("MERCHANT_ADMIN");
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
        request.setReceiverName("张三"); request.setReceiverPhone("13800138000");
        request.setReceiverRegion("广东省 深圳市 南山区"); request.setReceiverAddress("科技园南路 88 号 3 栋 501");
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
        mvc.perform(receiptLookup("forwarded-rate", order.getQueryToken()).header("X-Forwarded-For", "192.0.2.1")
                        // 直连公网客户端：remoteAddr 不在可信代理列表，所有 XFF 都必须被忽略。
                        .with(builder -> { builder.setRemoteAddr("198.51.100.9"); return builder; }))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(receiptLookup("forwarded-rate", order.getQueryToken()).header("X-Forwarded-For", "198.51.100.2")
                        .with(builder -> { builder.setRemoteAddr("198.51.100.9"); return builder; }))
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
                .andExpect(jsonPath("$.data.orders[0].order.queryToken").doesNotExist())
                .andExpect(jsonPath("$.data.orders[0].order.posterUrl").doesNotExist())
                .andExpect(jsonPath("$.data.orders[0].order.serviceVoucher").value("系统卡密已自动交付"))
                .andExpect(jsonPath("$.data.orders[0].items[0].cardDetails").value("已自动发放 (共2张)"))
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
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].maskedContent").isString())
                .andExpect(jsonPath("$.data.items[0].cardContent").doesNotExist());

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
    void t15CardRevealIsSkuScopedAndLimitedWhileExportRequiresConfirmationAndAudit() throws Exception {
        jdbc.update("DELETE FROM product_virtual_card WHERE merchant_id=1");
        jdbc.update("DELETE FROM card_access_audit");
        jdbc.update("INSERT INTO product_virtual_card(id, product_id, sku_id, merchant_id, card_content, status) " +
                "VALUES(1003, 1, 1, 1, 'RAW-CARD-1003-PASS', 0)");
        jdbc.update("INSERT INTO product_virtual_card(id, product_id, sku_id, merchant_id, card_content, status) " +
                "VALUES(1004, 1, 1, 1, 'RAW-CARD-1004-PASS', 0)");

        authUser("MERCHANT_ADMIN", 1L);
        String adminToken = loginToken();

        // 列表只给脱敏标识+指纹，绝不解密正文
        mvc.perform(get("/api/v1/merchant/cards").param("skuId", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].maskedContent").isString())
                .andExpect(jsonPath("$.data.items[0].cardContent").doesNotExist());

        // 按 SKU 限量查看：limit=1 只返回一条明文
        mvc.perform(get("/api/v1/merchant/cards/reveal").param("skuId", "1").param("limit", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].cardContent").isString());

        // 超上限直接拒绝，不静默截断
        mvc.perform(get("/api/v1/merchant/cards/reveal").param("skuId", "1").param("limit", "51")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(400));

        // 未二次确认不得导出，也不写审计
        mvc.perform(post("/api/v1/merchant/cards/export")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"skuId\":1,\"confirm\":false,\"onlyAvailable\":false,\"limit\":2}"))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM card_access_audit", Integer.class));

        // 显式确认后导出明文并写审计（操作人/数量/时间）
        mvc.perform(post("/api/v1/merchant/cards/export")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"skuId\":1,\"confirm\":true,\"onlyAvailable\":false,\"limit\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.items[0].cardContent").isString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM card_access_audit", Integer.class));
        assertEquals(200L, jdbc.queryForObject("SELECT actor_id FROM card_access_audit", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT card_count FROM card_access_audit", Integer.class));
        assertEquals("EXPORT_PLAINTEXT", jdbc.queryForObject("SELECT action FROM card_access_audit", String.class));

        // 员工在接口层被拒绝，不能靠前端隐藏
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("MERCHANT_STAFF", 1L);
        String staffToken = loginToken();

        mvc.perform(get("/api/v1/merchant/cards/reveal").param("skuId", "1")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/v1/merchant/cards/export")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content("{\"skuId\":1,\"confirm\":true,\"onlyAvailable\":false,\"limit\":2}"))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void q13StaffProfileAndPiiAreMinimizedWhileManagerSeesMaskedValues() throws Exception {
        jdbc.update("UPDATE merchant_store SET id_card_number='110101199001011234' WHERE id=1");
        jdbc.update("INSERT INTO merchant_payment_account(id,merchant_id,channel,sub_mchid,bank_account_name,bank_account_number,bank_name) " +
                "VALUES(1,1,'WECHAT','sub-1','张三','6222021234567890123','招商银行')");

        authUser("MERCHANT_STAFF", 1L);
        String staffToken = loginToken();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.store.storeName").value("store1"))
                .andExpect(jsonPath("$.data.store.storeCode").value("S1"))
                .andExpect(jsonPath("$.data.store.idCardNumber").doesNotExist())
                .andExpect(jsonPath("$.data.store.ownerName").doesNotExist())
                .andExpect(jsonPath("$.data.store.commissionRate").doesNotExist());
        String staffProfile = mvc.perform(get("/api/v1/merchant/profile").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.store.storeName").value("store1"))
                .andExpect(jsonPath("$.data.store.idCardNumber").doesNotExist())
                .andExpect(jsonPath("$.data.paymentAccount").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertFalse(staffProfile.contains("110101199001011234"));
        assertFalse(staffProfile.contains("6222021234567890123"));

        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("MERCHANT_ADMIN", 1L);
        String managerToken = loginToken();
        String managerProfile = mvc.perform(get("/api/v1/merchant/profile").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.store.ownerName").value("owner"))
                .andExpect(jsonPath("$.data.store.idCardNumber").value("1101**********1234"))
                .andExpect(jsonPath("$.data.paymentAccount.bankAccountNumber").value("****0123"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(managerProfile.contains("110101199001011234"));
        assertFalse(managerProfile.contains("6222021234567890123"));
    }

    @Test
    void q13StaffCannotPushOrFulfillButCanDeliverPhysicalOrderWithoutCrossTenantEffect() throws Exception {
        physicalProduct();
        authUser("MERCHANT_STAFF", 1L);
        String staffToken = loginToken();

        PeerPayDTOs.PushPayCreateRequest push = new PeerPayDTOs.PushPayCreateRequest();
        push.setProductId(1L); push.setSkuId(1L); push.setQuantity(1);
        mvc.perform(post("/api/v1/merchant/orders/push-pay")
                        .header("Authorization", "Bearer " + staffToken)
                        .header("Idempotency-Key", "staff-push-1")
                        .contentType("application/json")
                        .content(json.writeValueAsString(push)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE peer_pay_type=2", Integer.class));

        TradeOrder physical = unpaid("staff-physical", 1);
        physical.setGoodsType(1); physical.setOrderStatus("PAID"); orders.save(physical);
        OrderController.DeliverDTO deliver = new OrderController.DeliverDTO();
        deliver.setLogisticsCompany("carrier"); deliver.setTrackingNumber("tracking-staff");
        // 2026-11-06 口径澄清：员工可执行实物订单发货，但只能发本店订单。
        mvc.perform(post("/api/v1/merchant/orders/" + physical.getId() + "/deliver")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(deliver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("SHIPPED", orders.selectById(physical.getId()).getOrderStatus());

        // T31：重复发货必须被服务端拒绝，不能把已发货订单再次改写运单。
        mvc.perform(post("/api/v1/merchant/orders/" + physical.getId() + "/deliver")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(deliver)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        assertEquals("SHIPPED", orders.selectById(physical.getId()).getOrderStatus());

        // 越权副作用检查：员工发其他店铺的实物订单必须被拒且订单状态不变。
        TradeOrder otherStore = unpaid("staff-other-store", 1);
        otherStore.setMerchantId(2L); otherStore.setGoodsType(1); otherStore.setOrderStatus("PAID");
        orders.save(otherStore);
        mvc.perform(post("/api/v1/merchant/orders/" + otherStore.getId() + "/deliver")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(deliver)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        assertEquals("PAID", orders.selectById(otherStore.getId()).getOrderStatus());
        assertNull(orders.selectById(otherStore.getId()).getTrackingNumber());

        // 口径澄清只放开“实物订单发货”；虚拟履约仍按原实现仅店长可调用。
        TradeOrder virtual = unpaid("staff-virtual", 1);
        virtual.setGoodsType(3); virtual.setOrderStatus("PAID"); orders.save(virtual);
        mvc.perform(post("/api/v1/merchant/orders/" + virtual.getId() + "/fulfill-virtual")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("serviceVoucher", "staff-voucher"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        assertNull(orders.selectById(virtual.getId()).getServiceVoucher());
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
                .andExpect(status().isConflict())
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
                .andExpect(status().isBadRequest())
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

        // 1. Merchant 1: keyset with pageSize=2 returns first page plus total/hasMore/nextCursor
        authUser("MERCHANT_ADMIN", 1L);
        String tokenM1 = loginToken();

        String firstPage = mvc.perform(get("/api/v1/merchant/orders")
                        .header("Authorization", "Bearer " + tokenM1)
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orders.length()").value(2))
                .andExpect(jsonPath("$.data.page.total").value(3))
                .andExpect(jsonPath("$.data.page.hasMore").value(true))
                .andExpect(jsonPath("$.data.orders[0].items").isArray())
                .andExpect(jsonPath("$.data.orders[0].order.queryToken").doesNotExist())
                .andExpect(jsonPath("$.data.orders[0].order.posterUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String nextCursor = com.jayway.jsonpath.JsonPath.read(firstPage, "$.data.page.nextCursor");

        // Merchant 1: 游标第二页返回剩余 1 单且 hasMore=false，不重复第一页
        String secondPage = mvc.perform(get("/api/v1/merchant/orders")
                        .header("Authorization", "Bearer " + tokenM1)
                        .param("pageSize", "2")
                        .param("cursor", nextCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orders.length()").value(1))
                .andExpect(jsonPath("$.data.page.total").value(3))
                .andExpect(jsonPath("$.data.page.hasMore").value(false))
                .andReturn().getResponse().getContentAsString();
        Object firstOrderId = com.jayway.jsonpath.JsonPath.read(firstPage, "$.data.orders[0].order.id");
        Object secondOrderId = com.jayway.jsonpath.JsonPath.read(secondPage, "$.data.orders[0].order.id");
        assertNotEquals(firstOrderId, secondOrderId);

        // 状态筛选与非法参数：非法 pageSize/cursor/status 一律 400
        mvc.perform(get("/api/v1/merchant/orders").header("Authorization", "Bearer " + tokenM1)
                        .param("status", "PAID"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page.total").value(0));
        mvc.perform(get("/api/v1/merchant/orders").header("Authorization", "Bearer " + tokenM1)
                        .param("pageSize", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/v1/merchant/orders").header("Authorization", "Bearer " + tokenM1)
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/v1/merchant/orders").header("Authorization", "Bearer " + tokenM1)
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));

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
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.page.total").value(4))
                .andExpect(jsonPath("$.data.page.hasMore").value(true))
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("订单编号不正确"));

        mvc.perform(get("/api/v1/merchant/orders/-1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
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
                "buyerContact", "13800000000",
                "receiverName", "张三", "receiverPhone", "13800138000",
                "receiverRegion", "广东省 深圳市 南山区", "receiverAddress", "科技园南路 88 号 3 栋 501"
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
                "buyerContact", "13800000000",
                "receiverName", "张三", "receiverPhone", "13800138000",
                "receiverRegion", "广东省 深圳市 南山区", "receiverAddress", "科技园南路 88 号 3 栋 501"
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
        TradeOrder paidOrder = payments.notifyPaymentSuccess("r26-concurrency", "payer-concurrent",
                "trade-concurrency-999", "WECHAT", java.math.BigDecimal.TEN, "CNY", 1L);
        assertEquals("PAID", paidOrder.getPeerStatus());
        assertEquals("COMPLETED", paidOrder.getOrderStatus());
        paymentCompleted.countDown();

        String posterPath = posterFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(posterPath);
        assertTrue(posterPath.startsWith("/api/v1/peer-pay/poster/image/"), posterPath);
        assertTrue(posterPath.endsWith(".png"), posterPath);

        // 4. 关键验证：海报更新后，订单状态依然为 PAID 与 COMPLETED，绝未被旧内存快照覆盖成 UNPAID/RESERVED_LOCK
        TradeOrder afterOrder = orders.findById(order.getId()).orElseThrow();
        assertEquals("PAID", afterOrder.getPeerStatus());
        assertEquals("COMPLETED", afterOrder.getOrderStatus());
        assertEquals("trade-concurrency-999", afterOrder.getPayTradeNo());
        assertEquals(posterPath, orders.findStoredPosterByToken("r26-concurrency"));
        // 5. 落盘地址可直接读取为 PNG 字节，且带长缓存与 nosniff，不返回 JSON 包装
        mvc.perform(get(posterPath))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age")));
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

        // 5. 按需公开海报接口：GET /poster/{token} 返回已保存的落盘路径且不重新渲染
        mvc.perform(get("/api/v1/peer-pay/poster/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(originalPoster));
        // 5.1 落盘路径可直接读取为图片；非法文件名与不存在文件返回404，不回显路径
        mvc.perform(get(originalPoster))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/v1/peer-pay/poster/image/not-a-poster.png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        // 6. 买家查单与常用查询投影减载：底层不加载 poster_url 大字段
        TradeOrder queried = orders.findByPeerPayToken(token).orElseThrow();
        assertNull(queried.getPosterUrl(), "findByPeerPayToken must project out posterUrl");
        TradeOrder queriedBySn = orders.findByOrderSn(queried.getOrderSn()).orElseThrow();
        assertNull(queriedBySn.getPosterUrl(), "findByOrderSn must project out posterUrl");
        TradeOrder queriedByContact = orders.findByOrderSnAndBuyerContact(queried.getOrderSn(), "13812345678").orElseThrow();
        assertNull(queriedByContact.getPosterUrl(), "findByOrderSnAndBuyerContact must project out posterUrl");
    }

    @Test
    void t26PosterRenderingIsDeferredAndRetryableWithoutAffectingCommit() throws Exception {
        var buyer = new PeerPayDTOs.BuyerCreateRequest();
        buyer.setProductId(1L);
        buyer.setSkuId(1L);
        buyer.setQuantity(1);
        buyer.setBuyerContact("13812345678");
        buyer.setReceiverName("张三"); buyer.setReceiverPhone("13800138000");
        buyer.setReceiverRegion("广东省 深圳市 南山区"); buyer.setReceiverAddress("科技园南路 88 号 3 栋 501");

        // 1. 建单事务只落库：服务层不渲染海报，订单与明细已提交
        Map<String, Object> created = payments.createBuyerPeerPay(buyer);
        String token = (String) created.get("peerPayToken");
        assertFalse(created.containsKey("posterBase64"));
        assertNull(orders.findStoredPosterByToken(token));

        TradeOrder committed = orders.findByPeerPayToken(token).orElseThrow();
        assertEquals("UNPAID", committed.getOrderStatus());
        assertEquals(1, items.findByOrderSn((String) created.get("orderSn")).size());

        // 2. 补海报失败：只把字段置 null，订单不回滚、仍可重试
        try (var poster = mockStatic(PosterUtils.class)) {
            poster.when(() -> PosterUtils.generatePeerPayPosterBytes(anyString(), anyString(), anyString(),
                    any(), anyString(), anyString())).thenReturn(null);
            Map<String, Object> attached = payments.attachPoster(created);
            assertTrue(attached.containsKey("posterBase64"));
            assertNull(attached.get("posterBase64"));
        }
        assertEquals("UNPAID", orders.findByPeerPayToken(token).orElseThrow().getOrderStatus());
        assertNull(orders.findStoredPosterByToken(token));

        // 3. 重试成功：PNG 落盘并单字段写入公开路径，不影响已提交订单
        String storedPath;
        try (var poster = mockStatic(PosterUtils.class)) {
            poster.when(() -> PosterUtils.generatePeerPayPosterBytes(anyString(), anyString(), anyString(),
                    any(), anyString(), anyString())).thenReturn(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
            Object attached = payments.attachPoster(created).get("posterBase64");
            assertNotNull(attached);
            storedPath = attached.toString();
            assertTrue(storedPath.startsWith("/api/v1/peer-pay/poster/image/"), storedPath);
            assertTrue(storedPath.endsWith(".png"), storedPath);
        }
        assertEquals(storedPath, orders.findStoredPosterByToken(token));
        assertEquals("UNPAID", orders.findByPeerPayToken(token).orElseThrow().getOrderStatus());
    }

    @Test
    void t27IdempotencySnapshotRedactsBuyerCredentialsButReplayRestores() throws Exception {
        String key = "t27-redact-" + UUID.randomUUID().toString().replace("-", "");
        String body = "{\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"13812345678\"}";
        String first = mvc.perform(post("/api/v1/peer-pay/buyer/create")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> data = (Map<?, ?>) json.readValue(first, Map.class).get("data");
        String token = (String) data.get("peerPayToken");
        String orderSn = (String) data.get("orderSn");
        String queryToken = (String) data.get("queryToken");
        assertNotNull(queryToken);

        // T27/Q18-B：数据库只保留键→订单号及非敏感展示字段，脱敏 queryToken/buyerContact，剥离海报大字段
        String stored = jdbc.queryForObject("SELECT response_json FROM order_idempotency WHERE response_json LIKE ?",
                String.class, "%" + token + "%");
        assertNotNull(stored);
        assertTrue(stored.contains(orderSn), "键→订单号映射必须保留");
        assertFalse(stored.contains(queryToken), "queryToken 不得落库");
        assertFalse(stored.contains("13812345678"), "buyerContact 不得落库");
        assertFalse(stored.contains("posterBase64"), "海报大字段不得落库");

        // 重放从 trade_order 回填领取凭证与联系方式，响应与首次完全一致
        String second = mvc.perform(post("/api/v1/peer-pay/buyer/create")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
    }

    @Test
    void r07OrderCreationCostBudgetingAndFloodingDefense() throws Exception {
        // 1. 正常建单成功并写入数据库
        String normalKey = "r07-normal-key-" + UUID.randomUUID();
        String validBody = "{\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"13800000001\"}";
        mvc.perform(post("/api/v1/peer-pay/buyer/create")
                        .header("Idempotency-Key", normalKey)
                        .contentType("application/json")
                        .content(validBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderSn").isNotEmpty());

        int orderCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class);
        int idempCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class);
        int skuStockBefore = jdbc.queryForObject("SELECT stock_quantity FROM product_sku WHERE id = 1", Integer.class);

        // 2. 模拟新键洪泛攻击：当成本限流器触发 (Redis 返回 0L 超限) 时，建单立即被拒绝 (429)
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(0L);

        try {
            // A. 代付建单超限：返回 429
            mvc.perform(post("/api/v1/peer-pay/buyer/create")
                            .header("Idempotency-Key", "flood-peer-" + UUID.randomUUID())
                            .contentType("application/json")
                            .content(validBody))
                    .andExpect(jsonPath("$.code").value(429))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("建单过于频繁")));

            // B. 普通公开 checkout 建单超限：返回 429
            String checkoutBody = "{\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"13800000001\",\"merchantId\":1,"
                    + "\"receiverName\":\"张三\",\"receiverPhone\":\"13800138000\",\"receiverRegion\":\"广东省 深圳市 南山区\",\"receiverAddress\":\"科技园南路 88 号 3 栋 501\"}";
            mvc.perform(post("/api/v1/public/orders/checkout")
                            .header("Idempotency-Key", "flood-checkout-" + UUID.randomUUID())
                            .contentType("application/json")
                            .content(checkoutBody))
                    .andExpect(jsonPath("$.code").value(429))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("建单过于频繁")));

            // C. 核心安全守恒断言：超限不写单、不写入幂等表、不扣库存
            int orderCountAfter = jdbc.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class);
            int idempCountAfter = jdbc.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class);
            int skuStockAfter = jdbc.queryForObject("SELECT stock_quantity FROM product_sku WHERE id = 1", Integer.class);

            assertEquals(orderCountBefore, orderCountAfter, "Rate-limited requests must NEVER write records to trade_order");
            assertEquals(idempCountBefore, idempCountAfter, "Rate-limited requests must NEVER write records to order_idempotency");
            assertEquals(skuStockBefore, skuStockAfter, "Rate-limited requests must NEVER deduct inventory");
        } finally {
            when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(1L);
        }
    }

    @Test
    void r07PreLockAndPosterCostBudgetingDefense() throws Exception {
        // 1. 创建待预占订单
        TradeOrder order = intent("r07-prelock-budget");
        int stockBefore = jdbc.queryForObject("SELECT stock_quantity FROM product_sku WHERE id = 1", Integer.class);
        int resBefore = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE status = 'HELD'", Integer.class);

        // 2. 模拟高频重复预占超限 (Redis 返回 0L)
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(0L);

        try {
            // A. 预占接口受限：返回 429
            mvc.perform(post("/api/v1/peer-pay/pre-lock/" + order.getPeerPayToken()))
                    .andExpect(jsonPath("$.code").value(429))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("预占尝试过于频繁")));

            // B. 预占超限后数据严格守恒：不扣减库存、不插入预占表记录
            int stockAfter = jdbc.queryForObject("SELECT stock_quantity FROM product_sku WHERE id = 1", Integer.class);
            int resAfter = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE status = 'HELD'", Integer.class);
            assertEquals(stockBefore, stockAfter, "Pre-lock throttling must NEVER deduct stock_quantity");
            assertEquals(resBefore, resAfter, "Pre-lock throttling must NEVER create inventory_reservation rows");

            // C. 海报生成接口受限：返回 429
            mvc.perform(get("/api/v1/peer-pay/poster/" + order.getPeerPayToken()))
                    .andExpect(jsonPath("$.code").value(429))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("海报获取过于频繁")));
        } finally {
            when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(1L);
        }

        // 3. 超长字段参数防御 (防内存/网络膨胀)：超长手机号/留言直接 400 阻断
        String longContact = "x".repeat(65);
        String longMessage = "m".repeat(201);
        String longContactBody = "{\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"" + longContact + "\"}";
        mvc.perform(post("/api/v1/peer-pay/buyer/create")
                        .header("Idempotency-Key", "overflow-contact-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(longContactBody))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("64字符")));

        String longMessageBody = "{\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"13812345678\",\"peerMessage\":\"" + longMessage + "\"}";
        mvc.perform(post("/api/v1/peer-pay/buyer/create")
                        .header("Idempotency-Key", "overflow-msg-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(longMessageBody))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("200字符")));
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

    @Test
    void t18BusinessErrorCodesMapToHttpStatusAndBodyCode() throws Exception {
        authUser("MERCHANT_ADMIN", 1L);
        String token = loginToken();

        mvc.perform(get("/api/v1/merchant/cards/0").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/v1/merchant/cards/999999").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("MERCHANT_STAFF", 1L);
        String staffToken = loginToken();
        mvc.perform(get("/api/v1/merchant/cards").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    // F11/Sprint 28：平台违规处置全链路 —— 下架后对买家不可见、商家不能自行恢复、审计留痕、恢复回到处置前状态。
    @Test
    void violationTakedownHidesProductFromPublicAndMerchantUntilRestore() throws Exception {
        // 商品1在售，公开可见
        mvc.perform(get("/api/v1/public/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/v1/public/products/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        authUser("SUPERADMIN", null); String adminToken = loginToken();

        // 违规下架：商品 1（在售 → 2）
        mvc.perform(post("/api/v1/admin/products/1/violation-takedown")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"reason\":\"  涉嫌售假  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.violationReason").value("涉嫌售假"))
                .andExpect(jsonPath("$.data.violatedAt").isNotEmpty());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM product WHERE id=1", Integer.class));
        assertEquals("涉嫌售假", jdbc.queryForObject("SELECT violation_reason FROM product WHERE id=1", String.class));

        // 公开列表与详情不再可见
        mvc.perform(get("/api/v1/public/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("2"));
        mvc.perform(get("/api/v1/public/products/1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));

        // 商家不能自行恢复或改状态（403），也不能再违规下架（409）
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("MERCHANT_ADMIN", 1L); String merchantToken = loginToken();
        String revision = editing.get(1L, 1L).revision();
        mvc.perform(patch("/api/v1/merchant/products/1/status")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(new com.shop.b2b2c.dto.ProductEditingDTOs.Status(revision, 1))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        assertEquals(2, jdbc.queryForObject("SELECT status FROM product WHERE id=1", Integer.class));

        // 审计留痕一行 TAKEDOWN
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM product_violation_audit WHERE product_id=1", Integer.class));
        assertEquals("TAKEDOWN", jdbc.queryForObject(
                "SELECT action FROM product_violation_audit WHERE product_id=1", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT from_status FROM product_violation_audit WHERE product_id=1", Integer.class));

        // 超管恢复：回到处置前状态（1），清空处置快照
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("SUPERADMIN", null); String reopenToken = loginToken();
        mvc.perform(post("/api/v1/admin/products/1/restore")
                        .header("Authorization", "Bearer " + reopenToken)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.violationReason").isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM product WHERE id=1", Integer.class));
        assertNull(jdbc.queryForObject("SELECT violation_reason FROM product WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT violated_at FROM product WHERE id=1", java.time.LocalDateTime.class));

        // 恢复后公开可见，审计两行（TAKEDOWN + RESTORE）
        mvc.perform(get("/api/v1/public/products/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM product_violation_audit WHERE product_id=1", Integer.class));
        assertEquals("RESTORE", jdbc.queryForObject(
                "SELECT action FROM product_violation_audit WHERE product_id=1 ORDER BY id DESC LIMIT 1", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT to_status FROM product_violation_audit WHERE product_id=1 AND action='RESTORE'", Integer.class));

        // 处置记录接口返回两条，倒序
        mvc.perform(get("/api/v1/admin/products/1/violation-audits")
                        .header("Authorization", "Bearer " + reopenToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].action").value("RESTORE"))
                .andExpect(jsonPath("$.data[1].action").value("TAKEDOWN"));
    }

    // F11/Sprint 28：违规处置接口的角色边界与非法操作拒绝。
    @Test
    void violationModerationEnforcesRoleAndRejectsIllegalOperations() throws Exception {
        // 匿名一律 401
        mvc.perform(get("/api/v1/admin/products")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/products/1/violation-takedown")
                        .contentType("application/json").content("{\"reason\":\"违规\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/products/1/restore").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/products/1/violation-audits")).andExpect(status().isUnauthorized());

        // 商家角色一律 403
        authUser("MERCHANT_ADMIN", 1L); String merchantToken = loginToken();
        mvc.perform(get("/api/v1/admin/products").header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/products/1/violation-takedown")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType("application/json").content("{\"reason\":\"违规\"}"))
                .andExpect(status().isForbidden());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM product WHERE id=1", Integer.class));

        // 超管正常操作
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("SUPERADMIN", null); String adminToken = loginToken();
        mvc.perform(get("/api/v1/admin/products").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].storeName").isNotEmpty());
        // 列表筛选：状态与关键词
        mvc.perform(get("/api/v1/admin/products").header("Authorization", "Bearer " + adminToken)
                        .param("status", "1").param("merchantId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value("2"));

        // 非法入参：空原因 400、超长原因 400、不存在商品 404
        mvc.perform(post("/api/v1/admin/products/1/violation-takedown")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/v1/admin/products/1/violation-takedown")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"reason\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/v1/admin/products/999/violation-takedown")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"reason\":\"违规\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product_violation_audit", Integer.class));

        // 重复下架 409
        mvc.perform(post("/api/v1/admin/products/1/violation-takedown")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"reason\":\"违规\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/products/1/violation-takedown")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"reason\":\"再次\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));

        // 非违规商品恢复 409、不存在商品恢复 404
        mvc.perform(post("/api/v1/admin/products/2/restore")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        mvc.perform(post("/api/v1/admin/products/999/restore")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM product_violation_audit", Integer.class));
    }

    // F11/Sprint 29：详情图文维护全链路 —— 编辑落库规范JSON、公开详情透出、清空与非法内容拒绝。
    @Test
    void detailImagesPersistClearAndRejectInvalid() throws Exception {
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();

        // 合法图文：image + text，trim 后落规范JSON
        String revision = editing.get(1L, 1L).revision();
        var items = List.of(
                new com.shop.b2b2c.dto.ProductEditingDTOs.DetailItem("image", "  https://cdn.example.com/d1.jpg ", null),
                new com.shop.b2b2c.dto.ProductEditingDTOs.DetailItem("text", null, "  产地直发，支持验货  "));
        var update = new com.shop.b2b2c.dto.ProductEditingDTOs.Update(revision, 1L, "custom dog tag", null, "image", null, items,
                List.of(new com.shop.b2b2c.dto.ProductEditingDTOs.Price(1L, new java.math.BigDecimal("10.00"))));
        mvc.perform(put("/api/v1/merchant/products/1").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(json.writeValueAsString(update)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        String canonical = "[{\"type\":\"image\",\"url\":\"https://cdn.example.com/d1.jpg\"}," +
                "{\"type\":\"text\",\"content\":\"产地直发，支持验货\"}]";
        assertEquals(canonical, jdbc.queryForObject("SELECT detail_images FROM product WHERE id=1", String.class));

        // 商家编辑快照与公开详情同步透出
        assertEquals(canonical, editing.get(1L, 1L).product().getDetailImages());
        mvc.perform(get("/api/v1/public/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product.detailImages").value(canonical));

        // 非法内容一律 400 且不落库：坏协议、超长、超条数、未知类型
        String rev2 = editing.get(1L, 1L).revision();
        for (var bad : List.of(
                List.of(new com.shop.b2b2c.dto.ProductEditingDTOs.DetailItem("image", "javascript:alert(1)", null)),
                List.of(new com.shop.b2b2c.dto.ProductEditingDTOs.DetailItem("video", "/v.mp4", null)),
                List.of(new com.shop.b2b2c.dto.ProductEditingDTOs.DetailItem("text", null, "y".repeat(2001))),
                java.util.Collections.nCopies(21, new com.shop.b2b2c.dto.ProductEditingDTOs.DetailItem("text", null, "段落")))) {
            var badUpdate = new com.shop.b2b2c.dto.ProductEditingDTOs.Update(rev2, 1L, "custom dog tag", null, "image", null, bad,
                    List.of(new com.shop.b2b2c.dto.ProductEditingDTOs.Price(1L, new java.math.BigDecimal("10.00"))));
            mvc.perform(put("/api/v1/merchant/products/1").header("Authorization", "Bearer " + token)
                            .contentType("application/json").content(json.writeValueAsString(badUpdate)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        assertEquals(canonical, jdbc.queryForObject("SELECT detail_images FROM product WHERE id=1", String.class));

        // 空数组清空：落库 NULL，公开详情透出 null
        String rev3 = editing.get(1L, 1L).revision();
        var clearing = new com.shop.b2b2c.dto.ProductEditingDTOs.Update(rev3, 1L, "custom dog tag", null, "image", null,
                List.of(), List.of(new com.shop.b2b2c.dto.ProductEditingDTOs.Price(1L, new java.math.BigDecimal("10.00"))));
        mvc.perform(put("/api/v1/merchant/products/1").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(json.writeValueAsString(clearing)))
                .andExpect(status().isOk());
        assertNull(jdbc.queryForObject("SELECT detail_images FROM product WHERE id=1", String.class));
        mvc.perform(get("/api/v1/public/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product.detailImages").isEmpty());
    }

    // ==================== F05 买家退款申请集成回归 ====================

    private void paidOrderForRefund(String sn, long merchantId, String contact, String token, String pay) {
        jdbc.update("INSERT INTO trade_order(order_sn,merchant_id,buyer_contact,query_token,goods_type,total_amount,pay_amount,fee_amount,commission_amount,collection_mode,pay_channel,order_status,peer_pay_type,created_at) "
                        + "VALUES(?,?,?,?,1,?,?,?,?,'MERCHANT_DIRECT','WECHAT','PAID',0,?)",
                sn, merchantId, contact, token, new java.math.BigDecimal(pay), new java.math.BigDecimal(pay),
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.time.LocalDateTime.of(2026, 10, 15, 10, 0));
    }

    @Test
    void buyerApplyRefundCreatesPendingRequest() throws Exception {
        paidOrderForRefund("RF-O1", 1L, "13900000001", "tk-001", "100.00");

        mvc.perform(post("/api/v1/public/orders/refund-requests").contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of(
                                "orderSn", "RF-O1", "contact", "13900000001",
                                "queryToken", "tk-001", "refundAmount", 30.00, "reason", "不想要了"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.refundAmount").value(30.0));

        // 重复申请被拒
        mvc.perform(post("/api/v1/public/orders/refund-requests").contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of(
                                "orderSn", "RF-O1", "contact", "13900000001",
                                "queryToken", "tk-001", "refundAmount", 10.00, "reason", "x"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
    }

    private void merchantPaymentAccount(long merchantId, String channel) {
        jdbc.update("INSERT INTO merchant_payment_account(merchant_id,channel,sub_mchid,bank_account_name,bank_account_number,bank_name,apply_status) "
                        + "VALUES(?,?,'SUB-TEST','测试户名','6222000000000001','测试支行','PASSED')",
                merchantId, channel);
    }

    @Test
    void merchantApproveThenRejectFlow() throws Exception {
        paidOrderForRefund("RF-O2", 1L, "13900000002", "tk-002", "100.00");
        merchantPaymentAccount(1L, "WECHAT");
        // 申请
        String applyRes = mvc.perform(post("/api/v1/public/orders/refund-requests").contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of(
                                "orderSn", "RF-O2", "contact", "13900000002",
                                "queryToken", "tk-002", "refundAmount", 100.00, "reason", "全额退"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long requestId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(applyRes).get("data").get("id").asLong();

        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        // 退款通道用 mock 适配器返回成功，避免依赖真实微信配置。
        com.shop.b2b2c.service.payment.PaymentChannelAdapter mockAdapter =
                mock(com.shop.b2b2c.service.payment.PaymentChannelAdapter.class);
        com.shop.b2b2c.dto.PaymentChannelDTOs.RefundResult refundOk =
                new com.shop.b2b2c.dto.PaymentChannelDTOs.RefundResult();
        refundOk.setStatus("SUCCESS");
        refundOk.setChannelRefundNo("MOCK-REFUND-" + requestId);
        when(channelRegistry.require(anyString())).thenReturn(mockAdapter);
        when(mockAdapter.refundPayment(any())).thenReturn(refundOk);
        // 同意退款
        mvc.perform(post("/api/v1/merchant/refund-requests/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));
        // 重复审批被拒
        mvc.perform(post("/api/v1/merchant/refund-requests/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade_refund WHERE order_sn='RF-O2'", Integer.class));
    }

    @Test
    void merchantRejectAndBuyerCancel() throws Exception {
        paidOrderForRefund("RF-O3", 1L, "13900000003", "tk-003", "50.00");
        String applyRes = mvc.perform(post("/api/v1/public/orders/refund-requests").contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of(
                                "orderSn", "RF-O3", "contact", "13900000003",
                                "queryToken", "tk-003", "refundAmount", 50.00, "reason", "退"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long requestId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(applyRes).get("data").get("id").asLong();

        // 商家拒绝
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        mvc.perform(post("/api/v1/merchant/refund-requests/" + requestId + "/reject")
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("rejectReason", "已发货"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"));

        // 已拒绝不能撤回
        mvc.perform(post("/api/v1/public/orders/refund-requests/" + requestId + "/cancel")
                        .contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of(
                                "orderSn", "RF-O3", "contact", "13900000003", "queryToken", "tk-003"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void refundRequestMerchantEndpointRejectsAnonymousAndCrossShop() throws Exception {
        paidOrderForRefund("RF-O4", 1L, "13900000004", "tk-004", "50.00");
        // 匿名访问商家接口 401
        mvc.perform(get("/api/v1/merchant/refund-requests")).andExpect(status().isUnauthorized());

        String applyRes = mvc.perform(post("/api/v1/public/orders/refund-requests").contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of(
                                "orderSn", "RF-O4", "contact", "13900000004",
                                "queryToken", "tk-004", "refundAmount", 10.00, "reason", "x"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long requestId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(applyRes).get("data").get("id").asLong();

        // 商家2无权处理商家1的申请
        authUser("MERCHANT_ADMIN", 2L); String token2 = loginToken();
        mvc.perform(post("/api/v1/merchant/refund-requests/" + requestId + "/approve")
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
    }

    // ==================== F06 投诉协商与平台仲裁集成回归 ====================

    private long submitComplaint(String sn, String contact, String token) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "orderSn", sn, "contact", contact, "queryToken", token,
                "complaintType", "QUALITY", "content", "收到的商品与描述严重不符",
                "evidenceImages", java.util.List.of("https://cdn.example.com/evidence.jpg")));
        String res = mvc.perform(post("/api/v1/public/orders/complaints").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(res).get("data").get("id").asLong();
    }

    @Test
    void complaintSubmitReplyEscalateAndAdminArbitrateRefund() throws Exception {
        paidOrderForRefund("CP-O1", 1L, "13900000011", "cp-tk-1", "100.00");
        merchantPaymentAccount(1L, "WECHAT");
        long id = submitComplaint("CP-O1", "13900000011", "cp-tk-1");

        // 同一订单重复提交被拒
        String duplicateBody = json.writeValueAsString(java.util.Map.of(
                "orderSn", "CP-O1", "contact", "13900000011", "queryToken", "cp-tk-1",
                "complaintType", "OTHER", "content", "重复投诉重复投诉"));
        mvc.perform(post("/api/v1/public/orders/complaints").contentType("application/json").content(duplicateBody))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));

        // 凭证错误不能申请介入
        String badEscalateBody = json.writeValueAsString(java.util.Map.of(
                "orderSn", "CP-O1", "contact", "13900000011", "queryToken", "wrong"));
        mvc.perform(post("/api/v1/public/orders/complaints/" + id + "/escalate")
                        .contentType("application/json").content(badEscalateBody))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));

        // 商家答复
        authUser("MERCHANT_ADMIN", 1L); String token = loginToken();
        String replyBody = json.writeValueAsString(java.util.Map.of("reply", "已与买家沟通，可补偿 20 元"));
        mvc.perform(post("/api/v1/merchant/complaints/" + id + "/reply")
                        .header("Authorization", "Bearer " + token).contentType("application/json").content(replyBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("MERCHANT_REPLIED"));
        // 商家详情能看到两次追加记录
        mvc.perform(get("/api/v1/merchant/complaints/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.events.length()").value(2))
                .andExpect(jsonPath("$.data.complaint.buyerId").value(org.hamcrest.Matchers.nullValue()));

        // 买家申请平台介入
        String escalateBody = json.writeValueAsString(java.util.Map.of(
                "orderSn", "CP-O1", "contact", "13900000011", "queryToken", "cp-tk-1",
                "note", "补偿方案未兑现"));
        mvc.perform(post("/api/v1/public/orders/complaints/" + id + "/escalate")
                        .contentType("application/json").content(escalateBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ARBITRATING"));

        // 超管裁决并原路退款（渠道用 mock 适配器）
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("SUPERADMIN", null); String adminToken = loginToken();
        com.shop.b2b2c.service.payment.PaymentChannelAdapter mockAdapter =
                mock(com.shop.b2b2c.service.payment.PaymentChannelAdapter.class);
        com.shop.b2b2c.dto.PaymentChannelDTOs.RefundResult refundOk =
                new com.shop.b2b2c.dto.PaymentChannelDTOs.RefundResult();
        refundOk.setStatus("SUCCESS");
        refundOk.setChannelRefundNo("MOCK-COMPLAINT-" + id);
        when(channelRegistry.require(anyString())).thenReturn(mockAdapter);
        when(mockAdapter.refundPayment(any())).thenReturn(refundOk);
        String arbitrateBody = json.writeValueAsString(java.util.Map.of(
                "decision", "RESOLVE", "arbitration", "举证成立，全额退款", "refundAmount", 100.00));
        mvc.perform(post("/api/v1/admin/complaints/" + id + "/arbitrate")
                        .header("Authorization", "Bearer " + adminToken).contentType("application/json")
                        .content(arbitrateBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.refundDecision").value("FULL_REFUND"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade_refund WHERE order_sn='CP-O1'", Integer.class));
        // 追加记录：提交、答复、介入、裁决，共 4 条且不可覆盖
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM complaint_event WHERE complaint_id=?", Integer.class, id));
        // 已终结的工单不能再申请介入
        String finalEscalateBody = json.writeValueAsString(java.util.Map.of(
                "orderSn", "CP-O1", "contact", "13900000011", "queryToken", "cp-tk-1"));
        mvc.perform(post("/api/v1/public/orders/complaints/" + id + "/escalate")
                        .contentType("application/json").content(finalEscalateBody))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void complaintMerchantEndpointsRejectAnonymousAndCrossShop() throws Exception {
        paidOrderForRefund("CP-O2", 1L, "13900000012", "cp-tk-2", "50.00");
        long id = submitComplaint("CP-O2", "13900000012", "cp-tk-2");

        mvc.perform(get("/api/v1/merchant/complaints")).andExpect(status().isUnauthorized());
        authUser("MERCHANT_ADMIN", 2L); String token2 = loginToken();
        String crossReplyBody = json.writeValueAsString(java.util.Map.of("reply", "越权答复"));
        mvc.perform(post("/api/v1/merchant/complaints/" + id + "/reply")
                        .header("Authorization", "Bearer " + token2).contentType("application/json")
                        .content(crossReplyBody))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/api/v1/admin/complaints/" + id).header("Authorization", "Bearer " + token2))
                .andExpect(status().isForbidden());
    }

    // ==================== F09 商户账单生成集成回归 ====================

    private void billingOrder(String sn, long merchantId, String status, String pay, String fee,
                              int peerPayType, java.time.LocalDateTime createdAt) {
        jdbc.update("INSERT INTO trade_order(order_sn,merchant_id,buyer_contact,query_token,goods_type,total_amount,pay_amount,commission_amount,fee_amount,order_status,peer_pay_type,created_at) "
                        + "VALUES(?,?,?,?,1,?,?,?,?,?,?,?)",
                sn, merchantId, "fixture-contact", "q-" + sn, new java.math.BigDecimal(pay), new java.math.BigDecimal(pay),
                new java.math.BigDecimal(fee), new java.math.BigDecimal(fee), status, peerPayType, createdAt);
    }

    private void billingRefund(String sn, long merchantId, String amount, String feeRefund, String source, java.time.LocalDateTime successAt) {
        jdbc.update("INSERT INTO trade_refund(refund_no,order_id,order_sn,merchant_id,channel,source,idempotency_key,refund_amount,fee_refund_amount,status,success_at) "
                        + "VALUES(?,0,?,?,'MOCK',?,?,?,?,?,?)",
                "RF-" + sn, sn, merchantId, source, "IK-" + sn, new java.math.BigDecimal(amount),
                new java.math.BigDecimal(feeRefund), "SUCCESS", successAt);
    }

    @Test
    void adminRegenerateAggregatesOrdersAndRefundsForDailyPeriod() throws Exception {
        java.time.LocalDateTime day = java.time.LocalDateTime.of(2026, 10, 15, 10, 0);
        billingOrder("B-DIRECT", 1L, "PAID", "100.00", "3.00", 0, day);
        billingOrder("B-PEER", 1L, "COMPLETED", "50.00", "1.50", 1, day.plusHours(1));
        billingRefund("B-REFUND", 1L, "10.00", "0.30", "ORDER", day.plusHours(2));
        authUser("SUPERADMIN", null); String token = loginToken();

        mvc.perform(post("/api/v1/admin/billing/statements/regenerate").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("shopId", 1, "periodDate", "2026-10-15", "periodType", "DAILY"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.shopId").value(1))
                .andExpect(jsonPath("$.data.gmv").value(150.0))
                .andExpect(jsonPath("$.data.peerPayGmv").value(50.0))
                .andExpect(jsonPath("$.data.directPayGmv").value(100.0))
                .andExpect(jsonPath("$.data.ordersCnt").value(2))
                .andExpect(jsonPath("$.data.refundAmt").value(10.0))
                .andExpect(jsonPath("$.data.feeAmt").value(4.2))
                .andExpect(jsonPath("$.data.status").value("SETTLED"));
    }

    @Test
    void duplicateRegenerateKeepsSingleRowAndSameAmounts() throws Exception {
        billingOrder("B-ONCE", 1L, "PAID", "88.00", "2.64", 0,
                java.time.LocalDateTime.of(2026, 10, 20, 9, 0));
        authUser("SUPERADMIN", null); String token = loginToken();
        String body = json.writeValueAsString(java.util.Map.of("shopId", 1, "periodDate", "2026-10-20", "periodType", "DAILY"));

        mvc.perform(post("/api/v1/admin/billing/statements/regenerate").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/billing/statements/regenerate").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gmv").value(88.0));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM billing_statement WHERE shop_id=1 AND period_date='2026-10-20'", Integer.class));
    }

    @Test
    void merchantBillingOnlyReturnsOwnShopStatements() throws Exception {
        billingOrder("B-M1", 1L, "PAID", "10.00", "0.30", 0,
                java.time.LocalDateTime.of(2026, 10, 22, 9, 0));
        billingOrder("B-M2", 2L, "PAID", "20.00", "0.60", 0,
                java.time.LocalDateTime.of(2026, 10, 22, 9, 0));
        authUser("SUPERADMIN", null); String adminToken = loginToken();
        for (long shop : new long[]{1L, 2L}) {
            mvc.perform(post("/api/v1/admin/billing/statements/regenerate").header("Authorization", "Bearer " + adminToken)
                    .contentType("application/json")
                    .content(json.writeValueAsString(java.util.Map.of("shopId", shop, "periodDate", "2026-10-22", "periodType", "DAILY"))))
                    .andExpect(status().isOk());
        }

        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("MERCHANT_ADMIN", 1L); String merchantToken = loginToken();
        mvc.perform(get("/api/v1/merchant/billing/statements").header("Authorization", "Bearer " + merchantToken)
                        .param("periodType", "DAILY").param("from", "2026-10-22").param("to", "2026-10-22"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].shopId").value(1))
                .andExpect(jsonPath("$.data.list[0].gmv").value(10.0));
    }

    @Test
    void billingEndpointsEnforceAuthenticationAndRole() throws Exception {
        // 匿名 401
        mvc.perform(get("/api/v1/merchant/billing/statements").param("periodType", "DAILY")
                        .param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/billing/statements")).andExpect(status().isUnauthorized());

        // 商家无权调用超管重算
        authUser("MERCHANT_ADMIN", 1L); String merchantToken = loginToken();
        mvc.perform(post("/api/v1/admin/billing/statements/regenerate").header("Authorization", "Bearer " + merchantToken)
                .contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("shopId", 1, "periodDate", "2026-10-15", "periodType", "DAILY"))))
                .andExpect(status().isForbidden());

        // 超管非法请求：类型、不存在店铺
        cn.dev33.satoken.stp.StpUtil.logout(200L);
        jdbc.update("DELETE FROM sys_user WHERE id=200");
        authUser("SUPERADMIN", null); String adminToken = loginToken();
        mvc.perform(post("/api/v1/admin/billing/statements/regenerate").header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("shopId", 1, "periodDate", "2026-10-15", "periodType", "WEEKLY"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/v1/admin/billing/statements/regenerate").header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("shopId", 999, "periodDate", "2026-10-15", "periodType", "DAILY"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void monthlyBillingNormalizesToFirstDayAndAggregatesFullMonth() throws Exception {
        billingOrder("B-MO-1", 1L, "PAID", "30.00", "0.90", 0,
                java.time.LocalDateTime.of(2026, 9, 5, 9, 0));
        billingOrder("B-MO-2", 1L, "PAID", "70.00", "2.10", 0,
                java.time.LocalDateTime.of(2026, 9, 28, 9, 0));
        authUser("SUPERADMIN", null); String token = loginToken();

        mvc.perform(post("/api/v1/admin/billing/statements/regenerate").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("shopId", 1, "periodDate", "2026-09-20", "periodType", "MONTHLY"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.periodDate").value("2026-09-01"))
                .andExpect(jsonPath("$.data.gmv").value(100.0)).andExpect(jsonPath("$.data.ordersCnt").value(2))
                .andExpect(jsonPath("$.data.feeAmt").value(3.0));
    }

    @Test
    void adminBillingCsvExportContainsHeaderAndEscapedRows() throws Exception {
        billingOrder("B-CSV", 1L, "PAID", "100.00", "3.00", 0,
                java.time.LocalDateTime.of(2026, 10, 25, 9, 0));
        authUser("SUPERADMIN", null); String token = loginToken();
        mvc.perform(post("/api/v1/admin/billing/statements/regenerate").header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("shopId", 1, "periodDate", "2026-10-25", "periodType", "DAILY"))))
                .andExpect(status().isOk());

        String csv = mvc.perform(get("/api/v1/admin/billing/statements/export").header("Authorization", "Bearer " + token)
                        .param("periodType", "DAILY").param("from", "2026-10-25").param("to", "2026-10-25"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn().getResponse().getContentAsString();
        String[] lines = csv.split("\n");
        assertTrue(lines[0].startsWith("period_date,period_type,shop_id"));
        assertTrue(lines[1].contains("store1") && lines[1].contains("100.00"));
    }

    // ==================== F07 实物收货地址、运费、确认收货与自动完成 ====================

    private String physicalCheckoutBody(String contact, boolean withAddress) {
        String base = "{\"merchantId\":1,\"productId\":1,\"skuId\":1,\"quantity\":1,\"buyerContact\":\"" + contact + "\"";
        if (!withAddress) {
            return base + "}";
        }
        return base + ",\"receiverName\":\"张三\",\"receiverPhone\":\"13800138000\","
                + "\"receiverRegion\":\"广东省 深圳市 南山区\",\"receiverAddress\":\"科技园南路 88 号 3 栋 501\"}";
    }

    @Test
    void f07PhysicalCheckoutRequiresAddressAndSnapshotsShippingWithoutFeeBase() throws Exception {
        physicalProduct();
        jdbc.update("UPDATE merchant_store SET shipping_fee=8.00 WHERE id=1");

        // 1. 实物订单缺地址拒绝，且不产生订单/明细副作用
        mvc.perform(post("/api/v1/public/orders/checkout")
                        .header("Idempotency-Key", "f07-no-address-key-0001")
                        .contentType("application/json")
                        .content(physicalCheckoutBody("13900000021", false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertEquals(0, orders.selectCount(null));

        // 2. 合法地址建单：运费只计入实付，服务费基数仍为商品金额
        String res = mvc.perform(post("/api/v1/public/orders/checkout")
                        .header("Idempotency-Key", "f07-with-address-key-0001")
                        .contentType("application/json")
                        .content(physicalCheckoutBody("13900000022", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.goodsAmount").value(10.0))
                .andExpect(jsonPath("$.data.shippingAmount").value(8.0))
                .andExpect(jsonPath("$.data.payAmount").value(18.0))
                .andReturn().getResponse().getContentAsString();
        String orderSn = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(res).get("data").get("orderSn").asText();
        TradeOrder saved = orders.findByOrderSn(orderSn).orElseThrow();
        assertEquals("张三", saved.getReceiverName());
        assertEquals("13800138000", saved.getReceiverPhone());
        assertEquals("广东省 深圳市 南山区", saved.getReceiverRegion());
        assertEquals("科技园南路 88 号 3 栋 501", saved.getReceiverAddress());
        assertEquals(0, new java.math.BigDecimal("8.00").compareTo(saved.getShippingAmount()));
        assertEquals(0, new java.math.BigDecimal("10.00").compareTo(saved.getFeeBaseAmount()));
        assertEquals(0, new java.math.BigDecimal("18.00").compareTo(saved.getPayAmount()));

        // 3. 数字商品（夹具默认 goods_type=2）不要求地址，也不收运费
        jdbc.update("UPDATE merchant_store SET shipping_fee=8.00 WHERE id=2");
        jdbc.update("UPDATE product SET goods_type=2 WHERE id=2");
        String digital = mvc.perform(post("/api/v1/public/orders/checkout")
                        .header("Idempotency-Key", "f07-digital-key-000001")
                        .contentType("application/json")
                        .content("{\"merchantId\":2,\"productId\":2,\"skuId\":2,\"quantity\":1,\"buyerContact\":\"13900000023\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippingAmount").value(0.0))
                .andExpect(jsonPath("$.data.payAmount").value(10.0))
                .andReturn().getResponse().getContentAsString();
        assertNotNull(digital);
    }

    @Test
    void f07BuyerCanConfirmReceiptIdempotentlyAndOnlyFromShipped() throws Exception {
        physicalProduct();
        String res = mvc.perform(post("/api/v1/public/orders/checkout")
                        .header("Idempotency-Key", "f07-receipt-create-0001")
                        .contentType("application/json")
                        .content(physicalCheckoutBody("13900000031", true)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode data = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build().readTree(res).get("data");
        String orderSn = data.get("orderSn").asText();
        String queryToken = data.get("queryToken").asText();

        // UNPAID 不可确认收货
        String body = json.writeValueAsString(java.util.Map.of(
                "orderSn", orderSn, "contact", "13900000031", "queryToken", queryToken));
        mvc.perform(post("/api/v1/public/orders/confirm-receipt").contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));

        // 发货后确认收货，写完成时间并返回售后截止
        TradeOrder order = orders.findByOrderSn(orderSn).orElseThrow();
        order.setOrderStatus("SHIPPED");
        order.setDeliveryTime(LocalDateTime.now().minusHours(2));
        orders.save(order);
        mvc.perform(post("/api/v1/public/orders/confirm-receipt").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.afterSaleDeadline").isNotEmpty());
        TradeOrder completed = orders.selectById(order.getId());
        assertNotNull(completed.getFinishTime());

        // 重复提交幂等：不重复写完成时间，也不再报错
        LocalDateTime firstFinish = completed.getFinishTime();
        mvc.perform(post("/api/v1/public/orders/confirm-receipt").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("COMPLETED"));
        assertEquals(firstFinish, orders.selectById(order.getId()).getFinishTime());

        // 凭证错误不改状态
        String bad = json.writeValueAsString(java.util.Map.of(
                "orderSn", orderSn, "contact", "13900000031", "queryToken", "wrong-token"));
        mvc.perform(post("/api/v1/public/orders/confirm-receipt").contentType("application/json").content(bad))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertEquals("COMPLETED", orders.selectById(order.getId()).getOrderStatus());
    }

    @Test
    void f07ShippedOrdersAutoCompleteAfterThresholdAndSkipRecentOnes() {
        physicalProduct();
        TradeOrder expired = unpaid("f07-auto-old", 1);
        expired.setGoodsType(1);
        expired.setOrderStatus("SHIPPED");
        expired.setDeliveryTime(LocalDateTime.now().minusDays(com.shop.b2b2c.common.OrderLifecycle.AUTO_COMPLETE_DAYS + 1));
        orders.save(expired);

        TradeOrder recent = unpaid("f07-auto-new", 1);
        recent.setGoodsType(1);
        recent.setOrderStatus("SHIPPED");
        recent.setDeliveryTime(LocalDateTime.now().minusDays(1));
        orders.save(recent);

        TradeOrder digital = unpaid("f07-auto-digital", 1);
        digital.setGoodsType(2);
        digital.setOrderStatus("SHIPPED");
        digital.setDeliveryTime(LocalDateTime.now().minusDays(30));
        orders.save(digital);

        int completed = autoCompleteJob.complete(LocalDateTime.now());
        assertEquals(1, completed);
        assertEquals("COMPLETED", orders.selectById(expired.getId()).getOrderStatus());
        assertNotNull(orders.selectById(expired.getId()).getFinishTime());
        assertEquals("SHIPPED", orders.selectById(recent.getId()).getOrderStatus());
        assertEquals("SHIPPED", orders.selectById(digital.getId()).getOrderStatus());
    }

    @Test
    void f07CompletedOrderRefundIsRejectedAfterAfterSaleWindow() throws Exception {
        jdbc.update("INSERT INTO trade_order(order_sn,merchant_id,buyer_contact,query_token,goods_type,total_amount,pay_amount,"
                        + "fee_amount,commission_amount,collection_mode,pay_channel,order_status,peer_pay_type,finish_time,created_at) "
                        + "VALUES('F07-OLD',1,'13900000041','f07-tk-old',1,100.00,100.00,0,0,'MERCHANT_DIRECT','WECHAT','COMPLETED',0,?,?)",
                LocalDateTime.now().minusDays(com.shop.b2b2c.common.OrderLifecycle.AFTER_SALE_DAYS + 2),
                LocalDateTime.now().minusDays(30));
        jdbc.update("INSERT INTO trade_order(order_sn,merchant_id,buyer_contact,query_token,goods_type,total_amount,pay_amount,"
                        + "fee_amount,commission_amount,collection_mode,pay_channel,order_status,peer_pay_type,finish_time,created_at) "
                        + "VALUES('F07-NEW',1,'13900000042','f07-tk-new',1,100.00,100.00,0,0,'MERCHANT_DIRECT','WECHAT','COMPLETED',0,?,?)",
                LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(10));

        mvc.perform(post("/api/v1/public/orders/refund-requests").contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("orderSn", "F07-OLD", "contact", "13900000041",
                                "queryToken", "f07-tk-old", "refundAmount", 30.00, "reason", "超期申请"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("售后受理期")));
        mvc.perform(post("/api/v1/public/orders/refund-requests").contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("orderSn", "F07-NEW", "contact", "13900000042",
                                "queryToken", "f07-tk-new", "refundAmount", 30.00, "reason", "窗口内申请"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    // ==================== F08 取消未支付订单与支付异常队列 ====================

    private String buyerCancelBody(String orderSn, String contact, String queryToken) throws Exception {
        return json.writeValueAsString(java.util.Map.of(
                "orderSn", orderSn, "contact", contact, "queryToken", queryToken));
    }

    @Test
    void f08BuyerCancelReleasesReservationClosesChannelAndIsIdempotent() throws Exception {
        TradeOrder order = intent("f08-cancel");
        payments.preLockInventory("f08-cancel");
        assertEquals(0, skus.selectById(1L).getStockQuantity());
        String body = buyerCancelBody(order.getOrderSn(), "buyer@example.com", "query-f08-cancel");

        // 凭证错误：统一 400，订单与库存都不变
        mvc.perform(post("/api/v1/public/orders/cancel").contentType("application/json")
                        .content(buyerCancelBody(order.getOrderSn(), "buyer@example.com", "wrong-token")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertEquals("UNPAID", orders.selectById(order.getId()).getOrderStatus());
        assertEquals(0, skus.selectById(1L).getStockQuantity());

        // 合法取消：CLOSED + 预占 RELEASED + 库存回补 + 事务提交后尽力关单
        mvc.perform(post("/api/v1/public/orders/cancel").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.orderStatus").value("CLOSED"));
        TradeOrder closed = orders.selectById(order.getId());
        assertEquals("CLOSED", closed.getOrderStatus());
        assertEquals("CANCELLED", closed.getPeerStatus());
        assertNull(closed.getLockExpireTime());
        assertEquals(1, skus.selectById(1L).getStockQuantity());
        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT status FROM inventory_reservation WHERE order_id=?", String.class, order.getId()));
        verify(paymentGateway, times(1)).closePaymentBestEffort(any(TradeOrder.class));

        // 重复提交：幂等成功，不重复回补库存
        mvc.perform(post("/api/v1/public/orders/cancel").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("CLOSED"));
        assertEquals(1, skus.selectById(1L).getStockQuantity());
    }

    @Test
    void f08CannotCancelPaidOrderAndRejectsForeignBuyerCredential() throws Exception {
        TradeOrder paid = unpaid("f08-paid", 1);
        paid.setOrderStatus("PAID");
        paid.setPeerStatus("PAID");
        orders.save(paid);

        // 已支付不得用取消接口绕过退款流程
        mvc.perform(post("/api/v1/public/orders/cancel").contentType("application/json")
                        .content(buyerCancelBody(paid.getOrderSn(), "buyer@example.com", "query-f08-paid")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        assertEquals("PAID", orders.selectById(paid.getId()).getOrderStatus());

        // 订单号与联系方式不匹配时与错误凭证同口径，不泄露订单是否存在
        mvc.perform(post("/api/v1/public/orders/cancel").contentType("application/json")
                        .content(buyerCancelBody(paid.getOrderSn(), "other@example.com", "query-f08-paid")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));

        // 已进入终态的订单重复取消按幂等成功返回，不改状态
        paid.setOrderStatus("REFUNDED");
        orders.save(paid);
        mvc.perform(post("/api/v1/public/orders/cancel").contentType("application/json")
                        .content(buyerCancelBody(paid.getOrderSn(), "buyer@example.com", "query-f08-paid")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("REFUNDED"));
    }

    @Test
    void f08ExpiredUnpaidOrderClosesChannelAfterLocalClose() {
        TradeOrder order = intent("f08-timeout");
        payments.preLockInventory("f08-timeout");
        assertEquals(0, skus.selectById(1L).getStockQuantity());
        jdbc.update("UPDATE trade_order SET lock_expire_time=? WHERE id=?",
                LocalDateTime.now().minusMinutes(1), order.getId());

        payments.expireReservation(order.getId());

        assertEquals("CLOSED", orders.selectById(order.getId()).getOrderStatus());
        assertEquals("EXPIRED", orders.selectById(order.getId()).getPeerStatus());
        assertEquals(1, skus.selectById(1L).getStockQuantity());
        // 本地关单提交后尽力关闭渠道交易（测试中渠道网关为 Mock）
        verify(paymentGateway, times(1)).closePaymentBestEffort(any(TradeOrder.class));
    }

    @Test
    void f08PaymentExceptionQueueIsSuperadminOnly() throws Exception {
        mvc.perform(get("/api/v1/admin/settlement/payment-exceptions"))
                .andExpect(status().isUnauthorized());
        authUser("MERCHANT_ADMIN", 1L);
        String token = loginToken();
        mvc.perform(get("/api/v1/admin/settlement/payment-exceptions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void f08PaymentExceptionQueueReturnsWhitelistedFieldsOnly() throws Exception {
        jdbc.update("INSERT INTO payment_event(channel,pay_trade_no,order_sn,merchant_id,pay_amount,currency,"
                        + "payer_id,event_status,error_message,raw_payload,funds_received) "
                        + "VALUES('WECHAT','F08-LATE-1','F08-ORD-1',1,10.00,'CNY','payer-secret','LATE_PAYMENT',"
                        + "'订单已关闭后的迟到支付','{\"secret\":\"raw-body\"}',1)");
        jdbc.update("INSERT INTO payment_event(channel,pay_trade_no,order_sn,merchant_id,pay_amount,currency,"
                        + "payer_id,event_status,error_message,raw_payload,funds_received) "
                        + "VALUES('ALIPAY','F08-DUP-1','F08-ORD-2',1,20.00,'CNY','payer-secret-2','DUPLICATE_PAYMENT',"
                        + "'重复付款','{\"secret\":\"raw-body-2\"}',NULL)");
        jdbc.update("INSERT INTO payment_event(channel,pay_trade_no,order_sn,merchant_id,pay_amount,currency,"
                        + "payer_id,event_status,error_message,raw_payload,funds_received) "
                        + "VALUES('WECHAT','F08-OK-1','F08-ORD-3',1,30.00,'CNY','payer-secret-3','SUCCESS',NULL,NULL,1)");

        authUser("SUPERADMIN", null);
        String token = loginToken();
        String response = mvc.perform(get("/api/v1/admin/settlement/payment-exceptions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].eventStatus").value("DUPLICATE_PAYMENT"))
                .andExpect(jsonPath("$.data.items[1].eventStatus").value("LATE_PAYMENT"))
                .andReturn().getResponse().getContentAsString();
        // 只读白名单：不回传原始报文、付款人标识与渠道商户号
        assertFalse(response.contains("raw-body"));
        assertFalse(response.contains("payer-secret"));
        assertFalse(response.contains("rawPayload"));
        assertFalse(response.contains("payerId"));

        // 原因过滤只命中对应异常，仍不改变资金状态
        mvc.perform(get("/api/v1/admin/settlement/payment-exceptions")
                        .header("Authorization", "Bearer " + token)
                        .param("reason", "重复"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].orderSn").value("F08-ORD-2"));
    }
}
