/*
 * 文件：SchemaMySqlTest.java
 * 用途：在临时MySQL8验证schema/记录保留、商品/库存/卡密批次与作废SQL、约束和回滚。
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
                assertEquals(18, tables.size());
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
                try (PreparedStatement audit = connection.prepareStatement("INSERT INTO inventory_adjustment(merchant_id,product_id,sku_id,actor_id,request_key,delta,before_stock,after_stock,reason) VALUES(1,1,1,1,?,1,776,777,'retention-fixture')")) {
                    audit.setString(1, "c".repeat(64)); audit.executeUpdate();
                }
                statement.executeUpdate("INSERT INTO virtual_card_void(card_id,merchant_id,product_id,sku_id,actor_id,reason,before_stock,after_stock) VALUES(999999999,1,1,1,1,'retention-fixture',1,0)");
                statement.executeUpdate("INSERT INTO product_virtual_card(id,merchant_id,product_id,sku_id,card_content,status) VALUES(900000000,1,1,1,'batch-retention-fixture',2)");
                statement.executeUpdate("INSERT INTO card_import_batch(id,merchant_id,product_id,sku_id,actor_id,imported_count,before_stock,after_stock) VALUES(900000000,1,1,1,1,1,0,1)");
                statement.executeUpdate("INSERT INTO card_import_item(card_id,batch_id) VALUES(900000000,900000000)");
                Map<String,Long> counts = counts(connection, tables);
                ScriptUtils.executeSqlScript(connection, resource);
                assertEquals(counts, counts(connection, tables), "重复初始化不能增加演示数据或清空已有记录");
                try (ResultSet stock = statement.executeQuery("SELECT stock_quantity FROM product_sku WHERE id=1")) {
                    assertTrue(stock.next()); assertEquals(777, stock.getInt(1));
                }
                try (ResultSet otherDatabase = statement.executeQuery("SHOW DATABASES LIKE 'shop_b2b2c'")) {
                    assertFalse(otherDatabase.next(), "schema不得隐式创建固定名称数据库");
                }
                verifyProductEditingSql(url);
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

    private void verifyProductEditingSql(String baseUrl) {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("isolated-editing",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                new org.apache.ibatis.datasource.unpooled.UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                        baseUrl.replace("/?", "/bootstrap_contract_test?") + "&useAffectedRows=true", "root", "")));
        configuration.addMapper(com.shop.b2b2c.mapper.ProductEditingMapper.class);
        configuration.addMapper(com.shop.b2b2c.mapper.InventoryAdjustmentMapper.class);
        configuration.addMapper(com.shop.b2b2c.mapper.IdempotencyMapper.class);
        configuration.addMapper(com.shop.b2b2c.mapper.CardAssetMapper.class);
        configuration.addMapper(com.shop.b2b2c.mapper.CardImportBatchMapper.class);
        var factory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession(false)) {
            try { session.getConnection().setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); }
            catch (SQLException e) { throw new IllegalStateException(e); }
            var cardAssets = session.getMapper(com.shop.b2b2c.mapper.CardAssetMapper.class);
            assertEquals(1L, cardAssets.lockMerchant(1L));
            var mapper = session.getMapper(com.shop.b2b2c.mapper.ProductEditingMapper.class);
            var original = mapper.lockProduct(1L, 1L);
            var sku = mapper.lockSkus(1L, 1L).get(0);
            int stock = sku.getStockQuantity();
            var edit = new com.shop.b2b2c.dto.ProductEditingDTOs.Update("unused-at-mapper", 1L, "mysql-edited", null, "image", null,
                    List.of(new com.shop.b2b2c.dto.ProductEditingDTOs.Price(sku.getId(), new java.math.BigDecimal("3.50"))));
            assertEquals(1, mapper.updatePrice(1L, 1L, sku.getId(), new java.math.BigDecimal("3.50")));
            assertEquals(1, mapper.updateMetadata(1L, 1L, edit, new java.math.BigDecimal("3.50")));
            assertEquals(1, mapper.updateStatus(1L, 1L, 0));
            assertEquals(stock, mapper.lockSkus(1L, 1L).get(0).getStockQuantity());
            assertEquals("mysql-edited", mapper.lockProduct(1L, 1L).getTitle());
            assertNull(mapper.lockProduct(1L, 2L));
            assertEquals(0, mapper.updatePrice(1L, 2L, sku.getId(), java.math.BigDecimal.ONE));
            var inventory = session.getMapper(com.shop.b2b2c.mapper.InventoryAdjustmentMapper.class);
            assertNotNull(inventory.lockSku(1L, 1L, sku.getId()));
            assertNull(inventory.lockSku(2L, 1L, sku.getId()));
            assertEquals(0L, inventory.heldQuantity(1L, sku.getId()));
            assertEquals(1, inventory.adjust(1L, 1L, sku.getId(), stock, 1));
            assertEquals(0, inventory.adjust(1L, 1L, sku.getId(), stock, 1));
            var audit = new com.shop.b2b2c.entity.InventoryAdjustment();
            audit.setMerchantId(1L); audit.setProductId(1L); audit.setSkuId(sku.getId()); audit.setActorId(1L);
            audit.setRequestKey("d".repeat(64)); audit.setDelta(1); audit.setBeforeStock(stock); audit.setAfterStock(stock + 1); audit.setReason("native-sql");
            assertEquals(1, inventory.insert(audit)); assertNotNull(audit.getId());
            assertEquals(stock + 1, inventory.find(1L, audit.getId()).getAfterStock());
            assertNull(inventory.find(2L, audit.getId()));
            assertEquals(audit.getId(), inventory.list(1L, sku.getId(), null, 1).get(0).getId());
            assertEquals(1, inventory.list(1L, sku.getId(), audit.getId(), 10).size());
            assertTrue(inventory.list(2L, sku.getId(), null, 10).isEmpty());
            var idempotency = session.getMapper(com.shop.b2b2c.mapper.IdempotencyMapper.class);
            idempotency.claim("e".repeat(64), "f".repeat(64));
            assertEquals(1, idempotency.complete("e".repeat(64), "{}"));
            assertEquals("{}", idempotency.lock("e".repeat(64)).getResponseJson());
            long cardId;
            try (var insert = session.getConnection().prepareStatement("INSERT INTO product_virtual_card(product_id,sku_id,merchant_id,card_content,status) VALUES(1,?,1,'native-card-fixture',0)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, sku.getId()); insert.executeUpdate();
                try (var generated = insert.getGeneratedKeys()) { assertTrue(generated.next()); cardId = generated.getLong(1); }
            } catch (SQLException e) { throw new IllegalStateException(e); }
            assertNotNull(cardAssets.findCard(1L, cardId)); assertNull(cardAssets.findCard(2L, cardId));
            assertNotNull(cardAssets.lockCard(1L, cardId));
            assertTrue(cardAssets.historyContents(1L).contains("native-card-fixture"));
            assertTrue(cardAssets.availableCount(1L, 1L, sku.getId()) >= 1);
            assertEquals(1, cardAssets.markVoid(1L, cardId)); assertEquals(0, cardAssets.markVoid(1L, cardId));
            assertEquals(1, inventory.adjust(1L, 1L, sku.getId(), stock + 1, -1));
            var voidRecord = new com.shop.b2b2c.entity.CardVoidRecord();
            voidRecord.setCardId(cardId); voidRecord.setMerchantId(1L); voidRecord.setProductId(1L); voidRecord.setSkuId(sku.getId());
            voidRecord.setActorId(1L); voidRecord.setReason("native-void"); voidRecord.setBeforeStock(stock + 1); voidRecord.setAfterStock(stock);
            assertEquals(1, cardAssets.insertVoid(voidRecord));
            assertEquals("native-void", cardAssets.voidRecord(1L, cardId).getReason());
            assertNull(cardAssets.voidRecord(2L, cardId));
            var batches = session.getMapper(com.shop.b2b2c.mapper.CardImportBatchMapper.class);
            var batch = new com.shop.b2b2c.entity.CardImportBatch();
            batch.setMerchantId(1L); batch.setProductId(1L); batch.setSkuId(sku.getId()); batch.setActorId(1L);
            batch.setImportedCount(1); batch.setBeforeStock(stock); batch.setAfterStock(stock + 1);
            assertEquals(1, batches.insert(batch)); assertNotNull(batch.getId());
            assertEquals(1, batches.link(batch.getId(), cardId));
            assertEquals(1, batches.find(1L, batch.getId()).getImportedCount()); assertNull(batches.find(2L, batch.getId()));
            assertEquals(batch.getId(), batches.list(1L, sku.getId(), null, 1).get(0).getId());
            assertEquals(1, batches.list(1L, sku.getId(), batch.getId(), 10).size());
            var members = batches.members(1L, batch.getId(), null, 2);
            assertEquals(1, members.size()); assertEquals(cardId, members.get(0).cardId()); assertEquals(2, members.get(0).status());
            assertTrue(batches.members(1L, batch.getId(), cardId, 2).isEmpty());
            assertTrue(batches.members(2L, batch.getId(), null, 2).isEmpty());
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class, () -> batches.link(900000000L, cardId));
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class, () -> batches.link(batch.getId(), Long.MAX_VALUE));
            session.rollback();
            assertEquals(original.getTitle(), mapper.lockProduct(1L, 1L).getTitle());
            assertEquals(0, sku.getPrice().compareTo(mapper.lockSkus(1L, 1L).get(0).getPrice()));
            assertEquals(original.getStatus(), mapper.lockProduct(1L, 1L).getStatus());
            assertEquals(stock, inventory.lockSku(1L, 1L, sku.getId()).getStockQuantity());
            assertNull(inventory.find(1L, audit.getId()));
            assertNull(cardAssets.findCard(1L, cardId)); assertNull(cardAssets.voidRecord(1L, cardId));
            assertNull(batches.find(1L, batch.getId())); assertTrue(batches.members(1L, batch.getId(), null, 2).isEmpty());
            session.rollback();
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
