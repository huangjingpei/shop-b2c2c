/*
 * 文件：SettlementVerificationSchemaTest.java
 * 用途：O5 结算账户验证与变更的结构静态断言：verify_method/replaces_account_id 列、
 *      merchant_payout_verification 打款验证表（金额/次数 CHECK、尝试唯一键）、V12 迁移与 H2 夹具同步。
 * 维护注意：
 *  1. 静态读取 db/schema.sql、V12 迁移与 sprint1-schema.sql 文本，不连接数据库；
 *     真实 MySQL 的 CHECK/唯一索引/外键行为由用户手动验证。
 *  2. 若调整列名、索引名或 CHECK 边界，必须同步本断言、迁移脚本与 H2 夹具，避免双轨漂移。
 *  3. 本断言只保证结构存在，不证明验证流程行为；流程正确性依赖服务层单元测试与行锁。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementVerificationSchemaTest {

    private static String schema() throws Exception {
        return Files.readString(Path.of("src/main/resources/db/schema.sql"));
    }

    @Test
    void settlementAccountHasVerifyMethodAndReplacesColumn() throws Exception {
        String schema = schema();
        assertTrue(schema.contains("`verify_method` VARCHAR(16) NOT NULL DEFAULT 'MANUAL'"),
                "收款账户必须有核验方式列，默认 MANUAL");
        assertTrue(schema.contains("`replaces_account_id` BIGINT UNSIGNED NULL"),
                "收款账户必须有变更指向旧账户列");
        assertTrue(schema.contains("KEY `idx_settlement_replaces` (`replaces_account_id`)"),
                "变更指向列必须有索引");
        assertTrue(schema.contains("CHANGE_REQUESTED/CHANGE_VERIFIED/CHANGE_SWITCHED"),
                "账户审计 action 注释必须包含 O5 变更动作");
    }

    @Test
    void payoutVerificationTableHasAttemptUniqueKeyAndChecks() throws Exception {
        String schema = schema();
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS `merchant_payout_verification`"),
                "必须创建打款验证表");
        assertTrue(schema.contains("UNIQUE KEY `uk_payout_account_attempt` (`account_id`, `attempt_no`)"),
                "打款验证必须有 (account_id, attempt_no) 唯一键防并发重复尝试");
        assertTrue(schema.contains("CONSTRAINT chk_payout_amount CHECK (`amount` > 0.00 AND `amount` <= 1.00)"),
                "打款金额必须受 CHECK 约束限制在小额范围");
        assertTrue(schema.contains("CONSTRAINT chk_payout_attempt CHECK (`attempt_no` BETWEEN 1 AND 3)"),
                "尝试次数必须受 CHECK 约束限制在 1-3");
        assertTrue(schema.contains("KEY `idx_payout_pending_expire` (`status`, `expires_at`)"),
                "过期扫描必须有 (status, expires_at) 索引");
    }

    @Test
    void migrationV12KeepsSameNamesAsSchema() throws Exception {
        String migration = Files.readString(
                Path.of("src/main/resources/db/migrations/V12__o5_settlement_account_verification.sql"));
        assertTrue(migration.contains("verify_method"), "V12 必须创建核验方式列");
        assertTrue(migration.contains("replaces_account_id"), "V12 必须创建变更指向列");
        assertTrue(migration.contains("merchant_payout_verification"), "V12 必须创建打款验证表");
        assertTrue(migration.contains("uk_payout_account_attempt"), "V12 必须创建尝试唯一键");
        assertTrue(migration.contains("chk_payout_amount"), "V12 必须创建金额 CHECK");
        assertTrue(migration.contains("chk_payout_attempt"), "V12 必须创建次数 CHECK");
        assertTrue(migration.contains("information_schema"), "V12 全部变更必须幂等判断");
    }

    @Test
    void h2FixtureKeepsColumnsAndTableInSync() throws Exception {
        String fixture = Files.readString(Path.of("src/test/resources/sprint1-schema.sql"));
        assertTrue(fixture.contains("verify_method"), "H2 夹具必须同步 verify_method 列");
        assertTrue(fixture.contains("replaces_account_id"), "H2 夹具必须同步 replaces_account_id 列");
        assertTrue(fixture.contains("merchant_payout_verification"), "H2 夹具必须同步打款验证表");
        assertTrue(fixture.contains("uk_payout_account_attempt"), "H2 夹具必须同步尝试唯一键");
    }
}
