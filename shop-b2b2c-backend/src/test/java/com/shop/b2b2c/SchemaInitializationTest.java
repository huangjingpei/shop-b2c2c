/*
 * 文件：SchemaInitializationTest.java
 * 用途：验证开发期初始化的单一入口、失败阻断、非破坏性SQL及参数日志隐私边界。
 * 维护注意：只读取类路径资源，不连接数据库；实际MySQL执行与重复启动由SchemaMySqlTest覆盖。不能把静态契约检查当成SQL语法验收。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SchemaInitializationTest {
    @Test
    void startupLoadsOneBaselineAndDoesNotSwallowSqlErrors() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertNotNull(properties);
        assertEquals("always", properties.getProperty("spring.sql.init.mode"));
        assertEquals("classpath:db/schema.sql", properties.getProperty("spring.sql.init.schema-locations"));
        assertEquals("${APP_DEMO_SEED_LOCATIONS:classpath:db/demo-seed.sql}", properties.getProperty("spring.sql.init.data-locations"),
                "演示种子只能通过显式data-locations开关加载");
        assertEquals("false", properties.getProperty("spring.sql.init.continue-on-error"));
        assertEquals("org.apache.ibatis.logging.nologging.NoLoggingImpl", properties.getProperty("mybatis-plus.configuration.log-impl"),
                "运行配置不得将买家联系方式、领取凭证和卡密参数打印到stdout");
        assertEquals("", properties.getProperty("app.bootstrap-admin.username"), "管理员初始化默认关闭");
        assertEquals("", properties.getProperty("app.bootstrap-admin.password"), "管理员初始化默认关闭");
        assertFalse(new ClassPathResource("db/migrations/002_inventory_reservation.sql").exists());
        assertFalse(new ClassPathResource("db/migrations/003_order_idempotency.sql").exists());
    }

    @Test
    void productionProfileDisablesAllAutomaticSqlInitialization() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        var properties = yaml.getObject();
        assertNotNull(properties);
        assertEquals("prod", properties.getProperty("spring.config.activate.on-profile"));
        assertEquals("never", properties.getProperty("spring.sql.init.mode"));
    }

    @Test
    void baselineHasNoDemoCredentialsOrVirtualCardSeed() throws Exception {
        String sql = new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);
        assertFalse(sql.contains("admin123"), "结构基线不得包含演示默认口令");
        assertFalse(sql.contains("superadmin"), "结构基线不得预置固定超管账号");
        assertFalse(sql.contains("INSERT INTO `sys_user`"));
        assertFalse(sql.contains("INSERT INTO `product_virtual_card`"), "结构基线不得包含明文卡密种子");
        assertTrue(sql.contains("INSERT IGNORE INTO `category`"), "平台分类参考数据仍应由结构基线初始化");

        String demo = new ClassPathResource("db/demo-seed.sql").getContentAsString(StandardCharsets.UTF_8);
        assertTrue(demo.contains("禁止在生产库执行"));
        assertTrue(demo.contains("INSERT IGNORE INTO `sys_user`"));
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*(DROP|TRUNCATE|DELETE|USE)\\b").matcher(demo).find());
    }

    @Test
    void baselineHasStablePaginationAndExpiryIndexes() throws Exception {
        String sql = new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);
        assertTrue(sql.contains("idx_order_created"), "T11 缺少 created_at + id 的分页索引");
        assertTrue(sql.contains("idx_merchant_created"), "T11 缺少商户维度 created_at + id 分页索引");
        assertTrue(sql.contains("idx_order_expiry"), "T11 缺少 order_status + lock_expire_time 的到期扫描索引");
        assertTrue(sql.contains("idx_reservation_expiry (status, expires_at, order_id)"),
                "T11 预占到期索引必须带 order_id 稳定游标");
    }

    @Test
    void baselineDeclaresCompleteReferentialConstraints() throws Exception {
        String sql = new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String constraint : new String[]{
                "fk_payment_account_merchant", "fk_user_merchant", "fk_product_merchant", "fk_product_category",
                "fk_sku_product", "fk_sku_merchant", "fk_card_product", "fk_card_sku", "fk_card_merchant",
                "fk_audit_merchant", "fk_audit_sku", "fk_audit_actor", "fk_order_merchant", "fk_item_order",
                "fk_item_product", "fk_item_sku", "fk_statement_merchant", "fk_complaint_order", "fk_complaint_merchant",
                "fk_reservation_order", "fk_reservation_sku", "fk_reservation_product", "fk_reservation_merchant",
                "fk_adjustment_merchant", "fk_adjustment_product", "fk_adjustment_sku", "fk_adjustment_actor",
                "fk_card_void_card", "fk_card_void_merchant", "fk_card_void_product", "fk_card_void_sku", "fk_card_void_actor",
                "fk_card_batch_merchant", "fk_card_batch_product", "fk_card_batch_sku", "fk_card_batch_actor",
                "fk_card_import_item_batch", "fk_card_import_item_card", "fk_payment_event_merchant"}) {
            assertTrue(sql.contains("CONSTRAINT " + constraint + " FOREIGN KEY"),
                    "开发期基线缺少外键约束: " + constraint);
        }
        assertTrue(sql.contains("`payment_channel` VARCHAR(32) NOT NULL DEFAULT 'WECHAT'"),
                "订单支付渠道应非空并带默认值");
    }

    @Test
    void baselineNeverDeletesDataOrChangesTheConfiguredDatabase() throws Exception {
        String sql = new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);
        String commands = sql.replaceAll("(?m)^\\s*--.*$", "");
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*(DROP|TRUNCATE|DELETE|USE)\\b").matcher(commands).find());
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*CREATE\\s+DATABASE\\b").matcher(commands).find());
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*CREATE\\s+INDEX\\b").matcher(commands).find(),
                "重复启动不能重复执行独立CREATE INDEX");
    }
}
