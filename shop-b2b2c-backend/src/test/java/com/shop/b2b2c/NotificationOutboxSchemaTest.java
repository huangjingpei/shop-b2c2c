/*
 * 文件：NotificationOutboxSchemaTest.java
 * 用途：O8 结构静态断言：merchant_notification_outbox 表/幂等唯一键/渠道与状态语义注释/
 *       V14 迁移幂等/H2 夹具与 MySQL 结构同步。
 * 维护注意：列演进或新增配置键时必须同步本测试；H2 用 CLOB 兼容 MySQL JSON（类型差异属预期），列名保持一致。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationOutboxSchemaTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void runtimeSchemaDeclaresOutboxWithIdempotencyKey() throws Exception {
        String sql = read("src/main/resources/db/schema.sql");
        assertTrue(sql.contains("merchant_notification_outbox"), "schema.sql 必须包含通知发件箱表");
        assertTrue(sql.contains("uk_notification_event"), "发件箱必须声明 biz_type/biz_id/channel/template_code 唯一键");
        assertTrue(sql.contains("REVIEW_SLA_BREACH") && sql.contains("STORE_STATUS_CHANGED"),
                "业务类型注释需覆盖审核SLA与店铺状态变更");
        assertTrue(sql.contains("SKIPPED"), "状态语义需包含通道禁用 SKIPPED");
    }

    @Test
    void migrationIsIdempotentAndNonDestructive() throws Exception {
        String migration = read("src/main/resources/db/migrations/V14__o8_notification_outbox.sql");
        assertTrue(migration.contains("information_schema.tables"), "V14 必须用 information_schema 判断表存在");
        assertTrue(migration.contains("uk_notification_event"), "V14 建表必须携带幂等唯一键");
        // 文件头注释本身会提到禁止性语句，剔除注释行后再检查真实 DDL/DML。
        String statements = migration.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b)
                .toUpperCase();
        assertTrue(!statements.contains("DROP TABLE") && !statements.contains("TRUNCATE")
                        && !statements.contains("DELETE FROM"),
                "V14 不允许删表/清表/删数据");
    }

    @Test
    void h2FixtureMirrorsMysqlColumnsAndUniqueKey() throws Exception {
        String h2 = read("src/test/resources/sprint1-schema.sql");
        assertTrue(h2.contains("merchant_notification_outbox"), "H2 夹具必须同步通知发件箱表");
        assertTrue(h2.contains("uk_notification_event"), "H2 必须保留四元组唯一约束，保证幂等测试真实");
        for (String column : new String[]{"biz_type", "biz_id", "channel", "template_code", "recipient",
                "payload_json", "status", "attempts", "next_retry_at", "last_error", "created_at", "sent_at"}) {
            assertTrue(h2.contains(column), "H2 发件箱缺少列 " + column);
        }
    }

    @Test
    void slaJobUsesOutboxIdempotencyInsteadOfLocalState() throws Exception {
        String job = read("src/main/java/com/shop/b2b2c/config/ReviewSlaEscalationJob.java");
        assertTrue(job.contains("TEMPLATE_SLA_REMINDER") && job.contains("TEMPLATE_SLA_ESCALATION")
                        && job.contains("TEMPLATE_SLA_REVIEWER"), "SLA 三档模板必须齐全");
        assertTrue(job.contains("notificationService.notify"), "SLA 通知必须经发件箱服务（唯一键幂等）");
        assertTrue(!job.contains("Set<") && !job.contains("ConcurrentHashMap"),
                "SLA 不得用进程内集合做去重（重启即丢，必须靠 uk_notification_event）");
    }
}
