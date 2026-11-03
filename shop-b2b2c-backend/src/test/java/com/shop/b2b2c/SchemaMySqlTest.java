/*
 * 文件：SchemaMySqlTest.java
 * 用途：在独立临时MySQL8实例上执行完整schema.sql，验证空库初始化、索引、种子及重复执行保留数据。
 * 维护注意：仅显式提供schema.mysql.bin时运行；--no-defaults、随机空闲端口和target下独立数据目录隔离现有服务，校验@@datadir后才写入。不能改为连接开发/生产服务，测试实例停止后保留日志和临时数据供检查。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="schema.mysql.bin", matches=".+")
class SchemaMySqlTest {
    @Test
    void fullBaselineExecutesTwiceAgainstAnIsolatedEmptyMySqlDatabase() throws Exception {
        Path executable = Path.of(System.getProperty("schema.mysql.bin"), "mysqld.exe").toAbsolutePath();
        assertTrue(Files.isRegularFile(executable), "需要本机MySQL8程序目录");
        Path build = Path.of("target").toAbsolutePath().normalize(); Files.createDirectories(build);
        Path instance = Files.createTempDirectory(build, "schema-mysql-").toRealPath();
        assertTrue(instance.startsWith(build.toRealPath()));
        Path data = Files.createDirectory(instance.resolve("data"));
        Process init = new ProcessBuilder(executable.toString(), "--no-defaults", "--initialize-insecure",
                "--datadir=" + data, "--console").redirectErrorStream(true)
                .redirectOutput(instance.resolve("initialize.log").toFile()).start();
        if (!init.waitFor(60, TimeUnit.SECONDS)) { init.destroyForcibly(); fail("临时MySQL初始化超时：" + instance); }
        assertEquals(0, init.exitValue(), "查看隔离实例初始化日志：" + instance);
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        assertNotEquals(3306, port);
        Process server = new ProcessBuilder(executable.toString(), "--no-defaults", "--datadir=" + data,
                "--port=" + port, "--bind-address=127.0.0.1", "--mysqlx=OFF", "--skip-log-bin",
                "--innodb-buffer-pool-size=32M", "--local-infile=0", "--secure-file-priv=NULL", "--console")
                .redirectErrorStream(true).redirectOutput(instance.resolve("server.log").toFile()).start();
        String url = "jdbc:mysql://127.0.0.1:" + port + "/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectTimeout=1000&socketTimeout=5000&serverTimezone=Asia/Shanghai";
        boolean verified = false;
        try {
            Connection ready = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && server.isAlive()) {
                try { ready = DriverManager.getConnection(url, "root", ""); break; }
                catch (SQLException retry) { Thread.sleep(100); }
            }
            assertNotNull(ready, "临时MySQL未就绪，查看：" + instance);
            try (Connection connection = ready) {
                try (Statement bootstrap = connection.createStatement()) {
                try (ResultSet identity = bootstrap.executeQuery("SELECT @@datadir")) {
                    assertTrue(identity.next());
                    assertEquals(data.toRealPath(), Path.of(identity.getString(1)).toRealPath(), "拒绝向非隔离实例写入");
                }
                verified = true;
                bootstrap.execute("CREATE DATABASE bootstrap_contract_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                }
                connection.setCatalog("bootstrap_contract_test");
                // Connector/J statements capture their catalog at creation; create validators after selecting it.
                try (Statement statement = connection.createStatement()) {
                var resource = new EncodedResource(new ClassPathResource("db/schema.sql"), StandardCharsets.UTF_8);
                ScriptUtils.executeSqlScript(connection, resource);
                List<String> tables = new ArrayList<>();
                try (ResultSet result = statement.executeQuery("SHOW TABLES")) {
                    while (result.next()) tables.add(result.getString(1));
                }
                assertEquals(14, tables.size());
                try (ResultSet indexes = statement.executeQuery("SHOW INDEX FROM inventory_reservation WHERE Key_name='idx_reservation_expiry'")) {
                    List<String> columns = new ArrayList<>();
                    while (indexes.next()) columns.add(indexes.getString("Column_name"));
                    assertEquals(List.of("status", "expires_at"), columns);
                }
                try (ResultSet seed = statement.executeQuery("SELECT goods_type FROM product WHERE id=6")) {
                    assertTrue(seed.next()); assertEquals(3, seed.getInt(1));
                }
                try (ResultSet target = statement.executeQuery("SELECT DATABASE(), @@foreign_key_checks")) {
                    assertTrue(target.next()); assertEquals("bootstrap_contract_test", target.getString(1)); assertEquals(1, target.getInt(2));
                }
                statement.executeUpdate("UPDATE product_sku SET stock_quantity=777 WHERE id=1");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO order_idempotency(request_key,request_hash,response_json) VALUES(?,?,'{}')")) {
                    insert.setString(1, "a".repeat(64)); insert.setString(2, "b".repeat(64)); insert.executeUpdate();
                }
                Map<String,Long> counts = counts(connection, tables);
                ScriptUtils.executeSqlScript(connection, resource);
                assertEquals(counts, counts(connection, tables), "重复初始化不能增加演示数据或清空已有记录");
                try (ResultSet stock = statement.executeQuery("SELECT stock_quantity FROM product_sku WHERE id=1")) {
                    assertTrue(stock.next()); assertEquals(777, stock.getInt(1));
                }
                try (ResultSet otherDatabase = statement.executeQuery("SHOW DATABASES LIKE 'shop_b2b2c'")) {
                    assertFalse(otherDatabase.next(), "schema不得隐式创建固定名称数据库");
                }
                }
            }
        } finally {
            if (verified && server.isAlive()) {
                try (Connection connection = DriverManager.getConnection(url, "root", ""); Statement stop = connection.createStatement()) {
                    stop.execute("SHUTDOWN");
                } catch (SQLException shuttingDown) { /* Server may close the socket while shutting down. */ }
            }
            if (!server.waitFor(10, TimeUnit.SECONDS)) { server.destroy(); if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly(); }
        }
    }

    private Map<String,Long> counts(Connection connection, List<String> tables) throws SQLException {
        Map<String,Long> counts = new TreeMap<>();
        for (String table : tables) {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                assertTrue(result.next()); counts.put(table, result.getLong(1));
            }
        }
        return counts;
    }
}
