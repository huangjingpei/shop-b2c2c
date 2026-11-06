/*
 * 文件：OnboardingUniquenessSchemaTest.java
 * 用途：O2 唯一索引与并发防重的结构静态断言：主体防重生成列、费率同店同生效时点唯一、默认收款账户唯一。
 * 维护注意：
 *  1. 静态读取 db/schema.sql 文本，不连接数据库；真实 MySQL 的生成列/唯一索引行为由用户手动验证（SchemaMySqlTest 可选运行）。
 *  2. 若调整生成列表达式、唯一索引名称或唯一列组合，必须同步本断言并在迁移脚本中保持一致。
 *  3. 本断言只保证结构存在，不证明并发行为；并发创建/审核只成功一次仍依赖服务层行锁 + 唯一索引翻译 409。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingUniquenessSchemaTest {

    private static String schema() throws Exception {
        return Files.readString(Path.of("src/main/resources/db/schema.sql"));
    }

    @Test
    void onboardingApplicationHasActiveFingerprintGeneratedColumnAndUniqueKey() throws Exception {
        String schema = schema();
        assertTrue(schema.contains("`active_fingerprint` VARCHAR(128) GENERATED ALWAYS AS"),
                "入驻申请必须有进行中状态主体指纹生成列");
        assertTrue(schema.contains("'DRAFT','SUBMITTED','REVIEWING','SUPPLEMENT_REQUIRED','APPROVED','OPENED'"),
                "生成列进行中状态集合必须包含 OPENED，且与 Mapper 防重查询一致");
        assertTrue(schema.contains("UNIQUE KEY `uk_onboarding_active_subject` (`subject_type`, `active_fingerprint`)"),
                "主体防重唯一索引必须按 (subject_type, active_fingerprint) 建立");
    }

    @Test
    void settlementAccountHasDefaultMerchantGeneratedColumnAndUniqueKey() throws Exception {
        String schema = schema();
        assertTrue(schema.contains("`default_merchant_id` BIGINT UNSIGNED GENERATED ALWAYS AS (IF(`is_default` = 1, `merchant_id`, NULL)) STORED"),
                "收款账户必须有默认账户占位生成列");
        assertTrue(schema.contains("UNIQUE KEY `uk_settlement_default` (`default_merchant_id`)"),
                "默认账户唯一索引必须建立在生成列上");
    }

    @Test
    void feePolicyHasMerchantEffectiveUniqueKey() throws Exception {
        String schema = schema();
        assertTrue(schema.contains("UNIQUE KEY `uk_fee_policy_merchant_effective` (`merchant_id`, `effective_from`)"),
                "费率政策必须有同店同生效时点唯一索引");
    }

    @Test
    void migrationV9KeepsSameNamesAsSchema() throws Exception {
        // 保留数据库走 V9 迁移：索引/列名必须与 schema.sql 完全一致，避免双轨漂移。
        String migration = Files.readString(Path.of("src/main/resources/db/migrations/V9__o2_onboarding_uniqueness.sql"));
        assertTrue(migration.contains("uk_onboarding_active_subject"), "V9 必须创建主体防重唯一索引");
        assertTrue(migration.contains("uk_settlement_default"), "V9 必须创建默认账户唯一索引");
        assertTrue(migration.contains("uk_fee_policy_merchant_effective"), "V9 必须创建费率唯一索引");
        assertTrue(migration.contains("active_fingerprint"), "V9 必须创建主体指纹生成列");
        assertTrue(migration.contains("default_merchant_id"), "V9 必须创建默认账户生成列");
    }
}
