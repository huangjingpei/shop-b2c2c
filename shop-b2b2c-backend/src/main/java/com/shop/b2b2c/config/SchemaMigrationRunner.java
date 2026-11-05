/*
 * 文件：SchemaMigrationRunner.java
 * 用途：T12 存量数据库版本化迁移执行器；按 Vxxx 版本顺序执行 classpath:db/migrations 下的幂等脚本，并记录到 schema_migration。
 * 维护注意：
 *  1. 新库仍由 db/schema.sql 建结构；本执行器只面向保留数据的存量库，两者不得互相替代。
 *  2. 仅在 MySQL 上执行；H2 隔离测试夹具直接跳过并在日志中说明，不能用 H2 通过代替真实 MySQL 迁移验收。
 *  3. MySQL DDL 隐式提交，脚本必须使用 information_schema 存在性判断等幂等方式，失败重试不得留下半套结构。
 *  4. 任一脚本失败立即抛出并阻断启动，禁止 continue-on-error、禁止吞掉异常、禁止写入删库/TRUNCATE/DELETE 语句。
 *  5. 版本记录在脚本成功执行后写入；部分失败时该版本不记账，修复脚本后可按版本重试。
 */
package com.shop.b2b2c.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@Slf4j
public class SchemaMigrationRunner implements ApplicationRunner {

    private static final String MIGRATION_TABLE_DDL = "CREATE TABLE IF NOT EXISTS schema_migration ("
            + "version VARCHAR(64) NOT NULL PRIMARY KEY, "
            + "applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存量库版本化迁移记录'";

    private final DataSource dataSource;
    private final boolean enabled;
    private final String locations;

    public SchemaMigrationRunner(DataSource dataSource,
                                 @Value("${app.schema-migration.enabled:true}") boolean enabled,
                                 @Value("${app.schema-migration.locations:classpath:db/migrations}") String locations) {
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.locations = locations;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("存量库版本化迁移已关闭 (app.schema-migration.enabled=false)");
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!isMySql(connection)) {
                log.info("当前数据库不是 MySQL，跳过版本化迁移；H2 隔离测试不覆盖真实 MySQL 迁移");
                return;
            }
            ensureMigrationTable(connection);
            for (MigratingResource migration : loadMigrations()) {
                if (isApplied(connection, migration.version())) {
                    continue;
                }
                log.info("执行存量库迁移 {} ({})", migration.version(), migration.resource().getFilename());
                try {
                    ScriptUtils.executeSqlScript(connection, migration.resource());
                    recordApplied(connection, migration.version());
                } catch (RuntimeException e) {
                    throw new IllegalStateException("版本化迁移失败并阻断启动: " + migration.version()
                            + "，脚本必须保持幂等后重试", e);
                }
            }
            log.info("存量库版本化迁移检查完成");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("版本化迁移连接或执行失败，已阻断启动", e);
        }
    }

    private boolean isMySql(Connection connection) throws Exception {
        String product = connection.getMetaData().getDatabaseProductName();
        return product != null && product.toLowerCase().contains("mysql");
    }

    private void ensureMigrationTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(MIGRATION_TABLE_DDL);
        }
    }

    private boolean isApplied(Connection connection, String version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(1) FROM schema_migration WHERE version=?")) {
            statement.setString(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private void recordApplied(Connection connection, String version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_migration(version) VALUES(?)")) {
            statement.setString(1, version);
            statement.executeUpdate();
        }
    }

    private List<MigratingResource> loadMigrations() throws Exception {
        String pattern = locations.endsWith("/") ? locations + "V*.sql" : locations + "/V*.sql";
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
        List<MigratingResource> migrations = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null || !filename.startsWith("V") || !filename.endsWith(".sql")) {
                continue;
            }
            String version = filename.substring(0, filename.length() - ".sql".length());
            migrations.add(new MigratingResource(version, resource));
        }
        migrations.sort(Comparator.comparing(MigratingResource::version));
        return migrations;
    }

    /**
     * 仅供测试读取迁移清单，避免测试重复实现文件名解析。
     */
    public List<String> listMigrationVersionsForTest() throws Exception {
        return loadMigrations().stream().map(MigratingResource::version).toList();
    }

    private record MigratingResource(String version, Resource resource) {
    }
}
