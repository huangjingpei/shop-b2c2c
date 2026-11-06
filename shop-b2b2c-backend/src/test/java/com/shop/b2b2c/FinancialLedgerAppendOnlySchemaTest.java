/*
 * 文件：FinancialLedgerAppendOnlySchemaTest.java
 * 用途：财务基线 7 的静态结构用例：确认 MySQL 基线为服务费账本提供 UPDATE/DELETE 触发器兜底。
 * 维护注意：
 * 1. 只读取 schema.sql 文本，不启动 Spring、不连接数据库；触发器真实生效仍须用户在 MySQL 手工验证。
 * 2. H2 测试夹具不复制触发器，因此本用例不替代数据库行为测试。
 * 3. 若移除或改名 trg_ledger_no_update/trg_ledger_no_delete，本用例必须失败。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FinancialLedgerAppendOnlySchemaTest {

    private static final Path SCHEMA = Path.of("src", "main", "resources", "db", "schema.sql");

    @Test
    void merchantLedgerHasUpdateAndDeleteGuardTriggers() throws IOException {
        String schema = Files.readString(SCHEMA, StandardCharsets.UTF_8);

        assertTrue(schema.contains("CREATE TRIGGER `trg_ledger_no_update` BEFORE UPDATE ON `merchant_account_ledger`"),
                "服务费账本必须有 UPDATE 触发器兜底");
        assertTrue(schema.contains("CREATE TRIGGER `trg_ledger_no_delete` BEFORE DELETE ON `merchant_account_ledger`"),
                "服务费账本必须有 DELETE 触发器兜底");
        assertTrue(schema.contains("merchant_account_ledger is append-only"),
                "触发器必须明确拒绝账本更新/删除");
    }
}
