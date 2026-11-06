/*
 * 文件：StoreStatusSchemaTest.java
 * 用途：O6 店铺状态机的结构静态断言：merchant_store_status_audit 审计表（动作/状态/复核列）、
 *      merchant_store.status 取值注释含 4-已注销、V13 迁移与 H2 夹具同步。
 * 维护注意：
 *  1. 静态读取 db/schema.sql、V13 迁移与 sprint1-schema.sql 文本，不连接数据库；
 *     真实 MySQL 的外键与并发行为由用户手动验证。
 *  2. 状态语义（0-待开通/1-营业/2-打烊/3-冻结/4-已注销）变更时同步 schema 注释、服务与本断言。
 *  3. 本断言只保证结构存在，状态机流转正确性依赖 MerchantStoreStatusServiceTest。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreStatusSchemaTest {

    @Test
    void storeStatusAuditTableExistsWithReviewColumns() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS `merchant_store_status_audit`"),
                "必须创建店铺状态流转审计表");
        assertTrue(schema.contains("`action` VARCHAR(16) NOT NULL COMMENT 'OPEN/PAUSE/RESUME/FREEZE/UNFREEZE/CLOSE/CANCEL'"),
                "审计动作字典必须与开发文档附录 A.5 一致");
        assertTrue(schema.contains("`reason_code` VARCHAR(32) NOT NULL"),
                "冻结/解封/关店/注销必须强制原因码");
        assertTrue(schema.contains("`confirmed_by` BIGINT NULL"),
                "审计必须记录双人复核人");
        assertTrue(schema.contains("`approval_mode` VARCHAR(16) NULL"),
                "审计必须记录 SAME_OPERATOR/DUAL 复核方式");
        assertTrue(schema.contains("CONSTRAINT fk_store_status_audit_store FOREIGN KEY (`merchant_id`) REFERENCES `merchant_store` (`id`)"),
                "审计外键必须指向店铺主表");
    }

    @Test
    void storeStatusCommentIncludesCancelledTerminal() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"));
        assertTrue(schema.contains("0-待开通(已建店未完成协议/验证), 1-正常营业, 2-打烊, 3-已封禁, 4-已注销(终态)"),
                "merchant_store.status 注释必须覆盖 4-已注销终态");
    }

    @Test
    void migrationV13KeepsSameNamesAsSchema() throws Exception {
        String migration = Files.readString(
                Path.of("src/main/resources/db/migrations/V13__o6_store_status_audit.sql"));
        assertTrue(migration.contains("merchant_store_status_audit"), "V13 必须创建店铺状态审计表");
        assertTrue(migration.contains("confirmed_by"), "V13 必须包含复核人列");
        assertTrue(migration.contains("approval_mode"), "V13 必须包含复核方式列");
        assertTrue(migration.contains("information_schema"), "V13 建表必须幂等判断");
    }

    @Test
    void h2FixtureKeepsAuditTableInSync() throws Exception {
        String fixture = Files.readString(Path.of("src/test/resources/sprint1-schema.sql"));
        assertTrue(fixture.contains("merchant_store_status_audit"), "H2 夹具必须同步店铺状态审计表");
        assertTrue(fixture.contains("approval_mode"), "H2 夹具必须同步复核方式列");
    }
}
