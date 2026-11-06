/*
 * 文件：QualificationExpirySchemaTest.java
 * 用途：O9 资质有效期结构变更静态守卫：schema.sql 两表四列、V15 迁移幂等（information_schema 判断 + 动态 DDL）、
 *       迁移只做加列不回填不破坏；H2 夹具列与 MySQL 基线同步。
 * 维护注意：本类只读文本、不连数据库；改 schema/migrations/sprint1-schema 后须同步本断言。
 *           新增结构断言时保持“列名 + 类型 + 归属表”三要素齐全。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationExpirySchemaTest {

    @Test
    void schemaContainsExpiryColumnsOnBothTables() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"));
        // 两表各两列；schema 是唯一运行时入口，列注释须说明快照语义。
        long occurrences = Pattern.compile("`id_card_expiry`\\s+DATE\\s+NULL").matcher(schema).results().count();
        assertTrue(occurrences == 2, "merchant_onboarding_application 与 merchant_store 各一列 id_card_expiry");
        long licenseOccurrences = Pattern.compile("`license_expiry`\\s+DATE\\s+NULL").matcher(schema).results().count();
        assertTrue(licenseOccurrences == 2, "merchant_onboarding_application 与 merchant_store 各一列 license_expiry");
        assertTrue(schema.contains("审核通过时从入驻申请快照"), "店铺侧列须注明快照来源");
    }

    @Test
    void migrationIsIdempotentPerColumn() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migrations/V15__o9_qualification_expiry.sql"));
        long guards = Pattern.compile("information_schema\\.columns").matcher(migration).results().count();
        assertTrue(guards >= 4, "四列各自独立幂等判断，允许重跑");
        long addColumns = Pattern.compile("ADD COLUMN").matcher(migration).results().count();
        assertTrue(addColumns == 4, "只做四次 ADD COLUMN，不改已有列");
        assertTrue(migration.contains("PREPARE stmt"), "DDL 必须经动态 PREPARE 以便幂等跳过");
    }

    @Test
    void migrationIsNonDestructive() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migrations/V15__o9_qualification_expiry.sql"));
        String[] forbidden = {"DROP TABLE", "DROP COLUMN", "TRUNCATE", "DELETE FROM", "DELETE\n"};
        for (String keyword : forbidden) {
            // 注释行可能引用词表，剥离后再查正文，避免头注释自描述误伤。
            String body = migration.lines()
                    .filter(line -> !line.trim().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b);
            assertFalse(body.contains(keyword), "迁移不得包含破坏性语句: " + keyword);
        }
        assertFalse(migration.contains("UPDATE merchant_store SET id_card_expiry"),
                "迁移不回填历史有效期（未采集为空，不参与提醒/冻结）");
    }

    @Test
    void h2FixtureColumnsStayInSync() throws Exception {
        String h2 = Files.readString(Path.of("src/test/resources/sprint1-schema.sql"));
        // merchant_store 列带反引号与注释，申请表列不带；反引号设为可选以覆盖两种写法。
        long idCard = Pattern.compile("id_card_expiry`?\\s+DATE\\s+NULL").matcher(h2).results().count();
        long license = Pattern.compile("license_expiry`?\\s+DATE\\s+NULL").matcher(h2).results().count();
        assertTrue(idCard == 2 && license == 2, "H2 夹具两表四列须与 MySQL schema 同步");
    }
}
