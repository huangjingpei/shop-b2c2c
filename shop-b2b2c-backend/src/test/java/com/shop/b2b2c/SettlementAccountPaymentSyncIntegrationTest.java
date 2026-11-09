/*
 * 文件：SettlementAccountPaymentSyncIntegrationTest.java
 * 用途：Sprint 56（问题 3 方案 A）H2 集成用例：结算账户核验通过且成为默认账户时，
 *      merchant_payment_account 台账真实落库（AUDITING/UNBOUND/sub_mchid 空串/脱敏账号），
 *      非默认账户核验通过不影响台账结算引用。
 * 维护注意：
 *  1. H2 独立内存库、Mock Redis；只验证本地结构与事务行为，不调用微信/支付宝，不代表真实 MySQL 行为。
 *  2. 本用例锁定“不伪造 PASSED/BOUND”：若未来同步逻辑改写渠道字段，本用例必须失败。
 *  3. 真实 MySQL 唯一索引（uk_merchant_channel/uk_settlement_default）并发行为仍需用户手动验证。
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:settlement_payment_sync;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.continue-on-error=false",
        "spring.sql.init.schema-locations=classpath:sprint1-schema.sql",
        "spring.sql.init.data-locations=",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "shop.poster.storage-dir=target/test-posters"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(SettlementAccountPaymentSyncIntegrationTest.SessionTestConfig.class)
class SettlementAccountPaymentSyncIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class SessionTestConfig {
        @org.springframework.context.annotation.Bean(destroyMethod = "destroy")
        @org.springframework.context.annotation.Primary
        cn.dev33.satoken.dao.SaTokenDao isolatedTokenDao() {
            return new cn.dev33.satoken.dao.SaTokenDaoDefaultImpl();
        }
    }

    private static final String AUTH_PASSWORD = "settlement-sync-test-password";
    private static final String AUTH_HASH = BCrypt.hashpw(AUTH_PASSWORD);
    private static final String PLAIN_ACCOUNT_NO = "6222021234567890";

    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockBean StringRedisTemplate redis;

    @BeforeEach
    void fixtures() {
        // AuthLoginLimiter 走 Redis 原子脚本：内存环境无真实 Redis，mock 放行限流窗口（与 Sprint1SecurityIntegrationTest 一致）。
        org.mockito.Mockito.when(redis.<Long>execute(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        for (String table : List.of("merchant_payment_account", "merchant_settlement_account_audit",
                "merchant_settlement_account", "merchant_onboarding_audit", "merchant_onboarding_application",
                "sys_user", "merchant_store")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO merchant_store(id,store_code,store_name,owner_name,id_card_number,contact_phone) "
                + "VALUES(1,'S1','store1','owner','redacted','12345678')");
        jdbc.update("INSERT INTO merchant_onboarding_application(id,merchant_id,subject_type,subject_name,"
                + "contact_phone,status) VALUES(1,1,'INDIVIDUAL','张三','12345678','APPROVED')");
        jdbc.update("INSERT INTO sys_user(id,username,password,role_code,merchant_id,is_active) "
                + "VALUES(200,'fixture-admin',?, 'SUPERADMIN',NULL,1)", AUTH_HASH);
    }

    @Test
    void verifiedDefaultAccountCreatesPaymentAccountWithRealValues() throws Exception {
        String token = loginToken();
        long accountId = registerAccount(token, PLAIN_ACCOUNT_NO);

        mvc.perform(post("/api/v1/admin/merchants/1/settlement-accounts/" + accountId + "/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("approved", true, "reason", "人工核对一致"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"));

        String where = " FROM merchant_payment_account WHERE merchant_id=1 AND channel='WECHAT'";
        Long settlementRef = jdbc.queryForObject("SELECT settlement_account_id" + where, Long.class);
        assertEquals(accountId, settlementRef);
        // 不伪造渠道结果：进件审核中、渠道侧未绑定、官方未分配二级商户号。
        assertEquals("AUDITING", jdbc.queryForObject("SELECT apply_status" + where, String.class));
        assertEquals("UNBOUND", jdbc.queryForObject("SELECT binding_status" + where, String.class));
        assertEquals("", jdbc.queryForObject("SELECT sub_mchid" + where, String.class));
        // 账号只存脱敏值，禁止明文落库。
        String masked = jdbc.queryForObject("SELECT bank_account_number" + where, String.class);
        assertEquals("****7890", masked);
        assertFalse(masked.contains(PLAIN_ACCOUNT_NO));
        assertEquals("张三", jdbc.queryForObject("SELECT bank_account_name" + where, String.class));
    }

    @Test
    void nonDefaultVerifiedAccountKeepsPaymentAccountReferenceUnchanged() throws Exception {
        String token = loginToken();
        long firstId = registerAccount(token, PLAIN_ACCOUNT_NO);
        verify(token, firstId);
        Long firstSettlementRef = jdbc.queryForObject(
                "SELECT settlement_account_id FROM merchant_payment_account WHERE merchant_id=1 AND channel='WECHAT'",
                Long.class);
        assertEquals(firstId, firstSettlementRef);

        // 第二账户核验通过但不是默认：台账结算引用必须仍指向原默认账户。
        long secondId = registerAccount(token, "6222021234560001");
        verify(token, secondId);

        Long afterRef = jdbc.queryForObject(
                "SELECT settlement_account_id FROM merchant_payment_account WHERE merchant_id=1 AND channel='WECHAT'",
                Long.class);
        assertEquals(firstId, afterRef);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM merchant_payment_account WHERE merchant_id=1", Integer.class));
    }

    private long registerAccount(String token, String accountNo) throws Exception {
        String response = mvc.perform(post("/api/v1/admin/merchants/1/settlement-accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "accountType", "BANK",
                                "holderName", "张三",
                                "bankName", "中国工商银行",
                                "bankBranch", "北京分行",
                                "accountNo", accountNo))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("id").asLong();
    }

    private void verify(String token, long accountId) throws Exception {
        mvc.perform(post("/api/v1/admin/merchants/1/settlement-accounts/" + accountId + "/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("approved", true, "reason", "人工核对一致"))))
                .andExpect(status().isOk());
    }

    private String loginToken() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("username", "fixture-admin", "password", AUTH_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("token").asText();
    }
}
