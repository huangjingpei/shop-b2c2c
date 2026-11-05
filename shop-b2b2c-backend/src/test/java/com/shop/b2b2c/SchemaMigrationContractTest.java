/*
 * 文件：SchemaMigrationContractTest.java
 * 用途：T12 版本化迁移的静态契约与非 MySQL 跳过行为测试。
 * 维护注意：不连接业务 MySQL；H2 只验证执行器不误跑 MySQL 脚本。真实迁移语法、索引存在性判断和重复执行必须由用户在独立 MySQL 环境手动验证，不能用本测试代替。
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaMigrationContractTest {

    @Test
    void migrationIsVersionedIdempotentAndNonDestructive() throws Exception {
        var resource = new ClassPathResource("db/migrations/V1__s1_order_lifecycle.sql");
        assertTrue(resource.exists(), "T12 必须提供 V1 存量库迁移脚本");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("information_schema.statistics"), "索引变更必须做存在性判断以保证重试幂等");
        assertTrue(sql.contains("information_schema.columns"), "列变更必须做存在性判断以保证重试幂等");
        assertTrue(sql.contains("DATE_ADD(created_at, INTERVAL 15 MINUTE)"), "存量 UNPAID 必须按 created_at + 15 分钟回填");
        assertTrue(sql.contains("idx_order_created"));
        assertTrue(sql.contains("idx_merchant_created"));
        assertTrue(sql.contains("idx_order_expiry"));
        assertTrue(sql.contains("idx_reservation_expiry (status, expires_at, order_id)"));
        assertTrue(sql.contains("attempt_key"));
        assertTrue(sql.contains("reissue_count"));

        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*(DROP\\s+TABLE|TRUNCATE|DELETE|USE)\\b")
                .matcher(sql).find(), "迁移脚本不得包含删表、清空、删数据或切库语句");
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*DROP\\s+DATABASE\\b").matcher(sql).find());
    }

    @Test
    void cardFingerprintBackfillMigrationIsIdempotentAndNonDestructive() throws Exception {
        var resource = new ClassPathResource("db/migrations/V2__s2_card_fingerprint_backfill.sql");
        assertTrue(resource.exists(), "T14 必须提供 V2 存量库指纹补算脚本");
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("information_schema.columns"), "新增列必须做存在性判断以保证重试幂等");
        assertTrue(sql.contains("fingerprint_unavailable"), "必须显式标记无法补算的历史行");
        assertTrue(sql.contains("SHA2(TRIM(card_content), 256)"), "明文历史行必须回填 SHA-256 指纹");
        assertTrue(sql.contains("ENC:v1:") && sql.contains("ENC:v2:"), "加密行不得在 SQL 中当作明文取指纹");
        assertTrue(sql.contains("ROW_NUMBER()"), "同店铺重复明文必须选定唯一回填行以避开唯一键");
        assertTrue(sql.contains("card_fingerprint IS NULL"), "补算只处理仍无指纹的历史行");

        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*(DROP\\s+TABLE|TRUNCATE|DELETE|USE)\\b")
                .matcher(sql).find(), "迁移脚本不得包含删表、清空、删数据或切库语句");
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*DROP\\s+DATABASE\\b").matcher(sql).find());
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
    void migrationVersionsAreLoadedInLexicographicOrder() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:schema_migration_list;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        SchemaMigrationRunner runner = new SchemaMigrationRunner(dataSource, true, "classpath:db/migrations");

        List<String> versions = runner.listMigrationVersionsForTest();
        assertFalse(versions.isEmpty());
        assertTrue(versions.get(0).startsWith("V1__"));
        assertTrue(versions.stream().anyMatch(version -> version.startsWith("V2__")), "T14 的 V2 必须被迁移执行器发现");
        assertEquals(versions.stream().sorted().toList(), versions);
    }
}
