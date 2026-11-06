/*
 * 文件：SchemaMigrationContractTest.java
 * 用途：T12 版本化迁移的静态契约与非 MySQL 跳过行为测试；本轮追加 V6 存量库结构对齐的覆盖性断言（旧表列/外键/符号位/非空）与版本号自然排序断言。
 * 维护注意：不连接业务 MySQL；H2 只验证执行器不误跑 MySQL 脚本。真实迁移语法、索引存在性判断和重复执行必须由用户在独立 MySQL 环境手动验证，不能用本测试代替。
 * 静态断言只证明脚本里写了对应语句，不证明 MySQL 8 能执行；执行结果以用户演练记录为准。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.config.SchemaMigrationRunner;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SchemaMigrationContractTest {

    /** V1 引入前已存在的表；V6 只对这批表做列/外键对齐。 */
    private static final Set<String> LEGACY_TABLES = Set.of(
            "merchant_store", "merchant_payment_account", "sys_user", "category", "product",
            "product_sku", "product_virtual_card", "card_access_audit", "trade_order", "trade_order_item",
            "platform_ad_config", "billing_statement", "complaint", "inventory_reservation", "order_idempotency",
            "inventory_adjustment", "virtual_card_void", "card_import_batch", "card_import_item", "payment_event");

    /** V1 基线之后新增到旧表的列；V6 必须逐列补齐。 */
    private static final List<String> LEGACY_NEW_COLUMNS = List.of(
            "merchant_store.pay_channel",
            "merchant_payment_account.settlement_account_id", "merchant_payment_account.binding_status",
            "merchant_payment_account.channel_merchant_id", "merchant_payment_account.bound_at",
            "merchant_payment_account.effective_from", "merchant_payment_account.effective_to",
            "merchant_payment_account.raw_result",
            "trade_order.refunded_amount", "trade_order.fee_refunded_amount", "trade_order.fee_status",
            "trade_order.goods_amount", "trade_order.merchant_discount_amount", "trade_order.platform_discount_amount",
            "trade_order.shipping_amount", "trade_order.order_tax_amount", "trade_order.fee_policy_id",
            "trade_order.fee_rate_bps", "trade_order.fee_rate_type", "trade_order.fee_base_amount",
            "trade_order.fee_standard_amount", "trade_order.fee_amount", "trade_order.fee_waived_amount",
            "trade_order.fee_net_amount", "trade_order.fee_tax_amount", "trade_order.fee_tax_rate",
            "trade_order.collection_mode", "trade_order.pay_channel", "trade_order.platform_trade_no",
            "payment_event.collection_mode", "payment_event.channel_merchant_id", "payment_event.platform_trade_no");

    @Test
    void migrationIsVersionedIdempotentAndNonDestructive() throws Exception {
        var resource = new ClassPathResource("db/migrations/V1__s1_order_lifecycle.sql");
        assertTrue(resource.exists(), "T12 必须提供 V1 存量库迁移脚本");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("information_schema.statistics"), "索引变更必须做存在性判断以保证重试幂等");
        assertTrue(sql.contains("information_schema.columns"), "列变更必须做存在性判断以保证重试幂等");
        assertFalse(sql.contains("DATE_ADD(created_at, INTERVAL 15 MINUTE)"), "开发期基线不再回填存量订单截止时间");
        assertFalse(Pattern.compile("(?im)^\\s*UPDATE\\b").matcher(sql).find(),
                "迁移脚本不得回填历史业务数据");
        assertTrue(sql.contains("idx_order_created"));
        assertTrue(sql.contains("idx_merchant_created"));
        assertTrue(sql.contains("idx_order_expiry"));
        assertTrue(sql.contains("idx_reservation_expiry_v2 (status, expires_at, order_id)"),
                "必须先在临时名下建好同列索引");
        assertTrue(sql.contains("RENAME INDEX idx_reservation_expiry_v2 TO idx_reservation_expiry"),
                "换名后必须回到基线索引名");
        assertFalse(Pattern.compile("(?im)^\\s*ALTER TABLE inventory_reservation ADD INDEX idx_reservation_expiry ")
                .matcher(sql).find(), "索引已符合基线时不得无条件重建");
        assertTrue(sql.contains("GROUP_CONCAT(column_name ORDER BY seq_in_index)"),
                "重建前必须比对现有索引列定义，不能在每次执行时无脑重建");
        assertTrue(sql.contains("attempt_key"));
        assertTrue(sql.contains("reissue_count"));

        assertFalse(Pattern.compile("(?im)^\\s*(DROP\\s+TABLE|TRUNCATE|DELETE|USE)\\b")
                .matcher(sql).find(), "迁移脚本不得包含删表、清空、删数据或切库语句");
        assertFalse(Pattern.compile("(?im)^\\s*DROP\\s+DATABASE\\b").matcher(sql).find());
    }

    @Test
    void cardFingerprintStateMigrationOnlyAddsColumnAndDoesNotBackfill() throws Exception {
        var resource = new ClassPathResource("db/migrations/V2__s2_card_fingerprint_state.sql");
        assertTrue(resource.exists(), "T14 必须保留 V2 状态列增量脚本");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("information_schema.columns"), "新增列必须做存在性判断以保证重试幂等");
        assertTrue(sql.contains("fingerprint_unavailable"), "必须保留指纹不可用状态列");
        assertFalse(sql.contains("SHA2(TRIM(card_content), 256)"), "开发期基线不再回填历史明文指纹");
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*UPDATE\\b").matcher(sql).find(),
                "迁移脚本不得回填或改写历史卡密");
        assertFalse(sql.contains("ROW_NUMBER()"), "不再需要为历史回填去重选行");

        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*(DROP\\s+TABLE|TRUNCATE|DELETE|USE)\\b")
                .matcher(sql).find(), "迁移脚本不得包含删表、清空、删数据或切库语句");
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*DROP\\s+DATABASE\\b").matcher(sql).find());
    }

    @Test
    void cardAccessAuditMigrationIsIdempotentAndNonDestructive() throws Exception {
        var resource = new ClassPathResource("db/migrations/V3__s2_card_access_audit.sql");
        assertTrue(resource.exists(), "T15 必须提供 V3 卡密明文访问审计表脚本");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS"), "建表必须可重复执行");
        assertTrue(sql.contains("card_access_audit"));
        assertTrue(sql.contains("merchant_id") && sql.contains("actor_id") && sql.contains("sku_id")
                && sql.contains("card_count") && sql.contains("created_at"), "审计必须记录操作人、数量与时间");
        assertFalse(sql.contains("card_content"), "审计表不得保存卡密正文");

        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*(DROP\\s+TABLE|TRUNCATE|DELETE|USE)\\b")
                .matcher(sql).find(), "迁移脚本不得包含删表、清空、删数据或切库语句");
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*DROP\\s+DATABASE\\b").matcher(sql).find());
    }

    @Test
    void merchantSkuCodeMigrationScopesUniquenessToMerchant() throws Exception {
        var resource = new ClassPathResource("db/migrations/V4__s2_merchant_sku_code_unique.sql");
        assertTrue(resource.exists(), "T17 必须提供 V4 SKU 店内唯一索引脚本");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(sql.contains("uk_merchant_sku_code") && sql.contains("merchant_id") && sql.contains("sku_code"));
        assertTrue(sql.contains("information_schema.statistics"), "必须按索引存在性判断，保证幂等");
        assertTrue(sql.contains("DROP INDEX uk_sku_code"), "必须移除旧的全局唯一索引");
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*(TRUNCATE|DELETE|DROP\\s+TABLE|DROP\\s+DATABASE)\\b")
                .matcher(sql).find(), "迁移脚本不得包含清空/删表/删库语句");

        String schema = new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);
        assertTrue(schema.contains("uk_merchant_sku_code"), "新库基线必须直接落店内唯一索引");
        assertFalse(schema.contains("UNIQUE KEY `uk_sku_code`"), "新库基线不得保留全局 SKU 唯一键");
    }

    @Test
    void applicationConfigWiresEnabledVersionedMigration() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertNotNull(properties);
        assertEquals("${APP_SCHEMA_MIGRATION_ENABLED:true}",
                properties.getProperty("app.schema-migration.enabled"));
        assertEquals("classpath:db/migrations", properties.getProperty("app.schema-migration.locations"));
    }

    @Test
    void runnerSkipsNonMySqlAndDoesNotCreateMigrationTableInH2Tests() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:schema_migration_skip;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        SchemaMigrationRunner runner = new SchemaMigrationRunner(dataSource, true, "classpath:db/migrations");
        runner.run(null);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'SCHEMA_MIGRATION'")) {
            assertTrue(resultSet.next());
            assertEquals(0, resultSet.getInt(1), "H2 隔离测试不得执行 MySQL 迁移");
        }
    }

    @Test
    void v6ReconcilesLegacyTablesToCurrentBaseline() throws Exception {
        var resource = new ClassPathResource("db/migrations/V6__s1_legacy_structure_reconcile.sql");
        assertTrue(resource.exists(), "迁移与基线审计必须提供 V6 存量库结构对齐脚本");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);
        String schema = new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("information_schema.columns"), "补列必须做存在性判断以保证重试幂等");
        assertTrue(sql.contains("information_schema.table_constraints"), "补外键必须做存在性判断以保证重试幂等");
        assertFalse(Pattern.compile("(?im)^\\s*UPDATE\\b").matcher(sql).find(),
                "迁移脚本不得回填历史金额/订单/事件数据");
        assertFalse(Pattern.compile("(?im)^\\s*(DROP\\s+TABLE|TRUNCATE|DELETE|USE|DROP\\s+DATABASE)\\b")
                .matcher(sql).find(), "迁移脚本不得包含删表、清空、删数据、切库或删库语句");

        for (String column : LEGACY_NEW_COLUMNS) {
            String name = column.substring(column.indexOf('.') + 1);
            assertTrue(sql.contains("column_name = '" + name + "'"), "V6 必须补齐旧表列: " + column);
        }

        Set<String> expectedForeignKeys = legacyForeignKeys(schema);
        assertEquals(37, expectedForeignKeys.size(), "V1 基线后旧表应新增 37 个外键");
        for (String constraint : expectedForeignKeys) {
            assertTrue(sql.contains("ADD CONSTRAINT " + constraint + " FOREIGN KEY"),
                    "V6 必须补齐旧表外键: " + constraint);
        }

        assertTrue(sql.contains("MODIFY COLUMN merchant_id BIGINT UNSIGNED"),
                "payment_event.merchant_id 必须与 merchant_store.id 的符号位一致，否则外键无法建立");
        assertTrue(sql.contains("MODIFY COLUMN payment_channel VARCHAR(32) NOT NULL DEFAULT ''WECHAT''"),
                "trade_order.payment_channel 必须与基线一致为 NOT NULL");
    }

    /** 从 schema.sql 读取旧表当前外键；V1 基线已存在的 card_import_item 两条不计入增量。 */
    private static Set<String> legacyForeignKeys(String schema) {
        Set<String> names = new LinkedHashSet<>();
        String current = null;
        StringBuilder body = new StringBuilder();
        Pattern create = Pattern.compile("CREATE TABLE IF NOT EXISTS `?([a-z_]+)`? \\(");
        Pattern foreignKey = Pattern.compile("CONSTRAINT `?([a-z_]+)`? FOREIGN KEY");
        for (String raw : schema.split("\n")) {
            String line = raw.trim();
            var matcher = create.matcher(line);
            if (matcher.find()) {
                current = matcher.group(1);
                body.setLength(0);
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.startsWith(") ENGINE")) {
                if (LEGACY_TABLES.contains(current)) {
                    var fk = foreignKey.matcher(body);
                    while (fk.find()) {
                        names.add(fk.group(1));
                    }
                }
                current = null;
                continue;
            }
            body.append(line).append(' ');
        }
        names.remove("fk_card_import_item_batch");
        names.remove("fk_card_import_item_card");
        return names;
    }

    @Test
    void migrationVersionsAreOrderedByNumberNotLexicographically() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:schema_migration_list;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaMigrationRunner runner = new SchemaMigrationRunner(dataSource, true, "classpath:db/migrations");

        List<String> versions = runner.listMigrationVersionsForTest();
        assertFalse(versions.isEmpty());
        assertTrue(versions.get(0).startsWith("V1__"));
        assertTrue(versions.stream().anyMatch(version -> version.startsWith("V2__")), "T14 的 V2 必须被迁移执行器发现");
        assertTrue(versions.stream().anyMatch(version -> version.startsWith("V3__")), "T15 的 V3 必须被迁移执行器发现");
        assertTrue(versions.stream().anyMatch(version -> version.startsWith("V4__")), "T17 的 V4 必须被迁移执行器发现");
        assertTrue(versions.stream().anyMatch(version -> version.startsWith("V5__")), "G34 的 V5 必须被迁移执行器发现");
        assertTrue(versions.stream().anyMatch(version -> version.startsWith("V6__")), "V6 结构对齐脚本必须被迁移执行器发现");

        assertTrue(SchemaMigrationRunner.compareVersions("V9__a", "V10__b") < 0,
                "V10 必须排在 V9 之后，不能按字典序排到 V2 之前");
        assertTrue(SchemaMigrationRunner.compareVersions("V2__a", "V10__b") < 0);
        assertEquals(0, SchemaMigrationRunner.compareVersions("V3__x", "V3__x"));
    }
}
