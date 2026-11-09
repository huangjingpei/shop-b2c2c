/*
 * 文件：MerchantCollectionModeIntegrationTest.java
 * 用途：M1 收款双轨模式开关的 H2 持久化与权限集成用例：401/403、资质硬闸关闭 409、未绑定 409、
 *      切换落库、审计行内容、总闸开启后的生效模式、切回商家直收与非法模式 400。
 * 维护注意：
 *  1. H2 独立内存库、Mock Redis；只验证本地结构与事务行为，不调用微信/支付宝，不代表真实 MySQL 或渠道联调通过。
 *  2. H2 夹具 merchant_payment_account.apply_status 默认 PASSED 与 MySQL 默认 AUDITING 不同，本用例显式写状态，
 *     不依赖默认值证明进件流程正确。
 *  3. 并发只落一条审计由服务层 CAP 单元用例覆盖；真实 MySQL 唯一键/外键仍需用户手动验证。
 *  4. 资质硬闸 shop.settlement.platform-collect-enabled 在上下文级开启（true），
 *     “资质关闭拒绝切换”用例通过反射临时置 false，@BeforeEach 重置为 true 保证用例间隔离。
 */
package com.shop.b2b2c;

import cn.dev33.satoken.secure.BCrypt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real mapper SQL and Spring transactions; no external MySQL/Redis needed. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:collection_mode;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.continue-on-error=false",
        "spring.sql.init.schema-locations=classpath:sprint1-schema.sql",
        "spring.sql.init.data-locations=",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "shop.poster.storage-dir=target/test-posters",
        "shop.settlement.platform-collect-enabled=true"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(MerchantCollectionModeIntegrationTest.SessionTestConfig.class)
class MerchantCollectionModeIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class SessionTestConfig {
        @org.springframework.context.annotation.Bean(destroyMethod = "destroy")
        @org.springframework.context.annotation.Primary
        cn.dev33.satoken.dao.SaTokenDao isolatedTokenDao() {
            return new cn.dev33.satoken.dao.SaTokenDaoDefaultImpl();
        }
    }

    private static final String AUTH_PASSWORD = "collection-mode-test-password";
    private static final String AUTH_HASH = BCrypt.hashpw(AUTH_PASSWORD);

    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.shop.b2b2c.service.PlatformCollectionService platformCollectionService;
    @MockBean StringRedisTemplate redis;

    @BeforeEach
    void fixtures() {
        // 资质硬闸默认开启；个别用例反射置 false 后由此恢复，避免用例间污染。
        org.springframework.test.util.ReflectionTestUtils.setField(
                platformCollectionService, "platformCollectEnabled", true);
        // AuthLoginLimiter 走 Redis 原子脚本：内存环境无真实 Redis，mock 放行限流窗口（与 Sprint1SecurityIntegrationTest 一致）。
        org.mockito.Mockito.when(redis.<Long>execute(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        for (String table : List.of("merchant_collection_switch_audit", "merchant_payment_account",
                "sys_user", "merchant_store")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM platform_collection_config");
        jdbc.update("INSERT INTO platform_collection_config(id,enabled,version) VALUES(1,0,0)");
        for (int id = 1; id <= 2; id++) {
            jdbc.update("INSERT INTO merchant_store(id,store_code,store_name,owner_name,id_card_number,contact_phone) "
                    + "VALUES(?,?,?,?,?,?)", id, "S" + id, "store" + id, "owner", "redacted", "12345678");
        }
    }

    @Test
    void unauthenticatedAndMerchantRoleCannotOperateCollectionMode() throws Exception {
        mvc.perform(get("/api/v1/admin/settlement/platform-collection/config"))
                .andExpect(status().isUnauthorized());

        authUser("MERCHANT_ADMIN", 1L);
        String merchantToken = loginToken();
        mvc.perform(get("/api/v1/admin/settlement/platform-collection/config")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/settlement/merchants/1/collection-mode")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("mode", "PLATFORM_COLLECT"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformSwitchIsRejectedWhenQualificationChannelDisabled() throws Exception {
        authUser("SUPERADMIN", null);
        String token = loginToken();
        insertBoundAccount(1L, "MCH-1", 31L);
        // 反射关闭资质硬闸：即使绑定三要素齐全，也必须拒绝切换。
        org.springframework.test.util.ReflectionTestUtils.setField(
                platformCollectionService, "platformCollectEnabled", false);

        mvc.perform(put("/api/v1/admin/settlement/merchants/1/collection-mode")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("mode", "PLATFORM_COLLECT", "reason", "试点"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("分账通道未启用")));

        assertEquals("MERCHANT_DIRECT",
                jdbc.queryForObject("SELECT collection_mode FROM merchant_store WHERE id=1", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merchant_collection_switch_audit", Integer.class));
    }

    @Test
    void platformSwitchWithoutBoundAccountIsRejectedAndKeepsStoreUnchanged() throws Exception {
        authUser("SUPERADMIN", null);
        String token = loginToken();

        mvc.perform(put("/api/v1/admin/settlement/merchants/1/collection-mode")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("mode", "PLATFORM_COLLECT", "reason", "试点"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        assertEquals("MERCHANT_DIRECT",
                jdbc.queryForObject("SELECT collection_mode FROM merchant_store WHERE id=1", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merchant_collection_switch_audit", Integer.class));
    }

    @Test
    void boundMerchantCanSwitchThenGateControlsEffectiveModeAndSwitchBack() throws Exception {
        authUser("SUPERADMIN", null);
        String token = loginToken();
        insertBoundAccount(1L, "MCH-1", 31L);

        // 总闸关闭：允许预配置商家模式，但生效模式仍是商家直收。
        mvc.perform(put("/api/v1/admin/settlement/merchants/1/collection-mode")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("mode", "PLATFORM_COLLECT", "reason", "首批试点"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuredMode").value("PLATFORM_COLLECT"))
                .andExpect(jsonPath("$.data.effectiveMode").value("MERCHANT_DIRECT"))
                .andExpect(jsonPath("$.data.bindingReady").value(true))
                .andExpect(jsonPath("$.data.blockReason").isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM merchant_collection_switch_audit", Integer.class));

        // 打开总闸后按商家配置生效。
        mvc.perform(put("/api/v1/admin/settlement/platform-collection/config")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        mvc.perform(get("/api/v1/admin/settlement/merchants/1/collection-mode")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveMode").value("PLATFORM_COLLECT"));

        // 切回商家直收不要求绑定，允许回滚。
        jdbc.update("DELETE FROM merchant_payment_account WHERE merchant_id = 1");
        mvc.perform(put("/api/v1/admin/settlement/merchants/1/collection-mode")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("mode", "MERCHANT_DIRECT", "reason", "回滚"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuredMode").value("MERCHANT_DIRECT"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM merchant_collection_switch_audit", Integer.class));

        String from = jdbc.queryForObject("SELECT from_mode FROM merchant_collection_switch_audit "
                + "ORDER BY id DESC LIMIT 1", String.class);
        String to = jdbc.queryForObject("SELECT to_mode FROM merchant_collection_switch_audit "
                + "ORDER BY id DESC LIMIT 1", String.class);
        assertEquals("PLATFORM_COLLECT", from);
        assertEquals("MERCHANT_DIRECT", to);
    }

    @Test
    void invalidModeAndMissingEnabledAreBadRequest() throws Exception {
        authUser("SUPERADMIN", null);
        String token = loginToken();
        insertBoundAccount(1L, "MCH-1", 31L);

        mvc.perform(put("/api/v1/admin/settlement/merchants/1/collection-mode")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("mode", "ALIPAY_DIRECT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mvc.perform(put("/api/v1/admin/settlement/platform-collection/config")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private void insertBoundAccount(Long merchantId, String subMchid, Long settlementAccountId) {
        jdbc.update("INSERT INTO merchant_payment_account(merchant_id,channel,sub_mchid,bank_account_name,"
                        + "bank_account_number,bank_name,apply_status,binding_status,settlement_account_id) "
                        + "VALUES(?,'WECHAT',?,'fixture-name','redacted-card','fixture-bank','PASSED','BOUND',?)",
                merchantId, subMchid, settlementAccountId);
    }

    private void authUser(String role, Long merchantId) {
        jdbc.update("INSERT INTO sys_user(id,username,password,role_code,merchant_id,is_active) "
                + "VALUES(200,'fixture-user',?,?,?,1)", AUTH_HASH, role, merchantId);
    }

    private String loginToken() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("username", "fixture-user", "password", AUTH_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("token").asText();
    }
}
