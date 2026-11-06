/*
 * 文件：SchemaConstraintNameTest.java
 * 用途：schema.sql 的静态命名唯一性回归：约束名库内唯一、触发器名唯一、单表内索引/唯一键名不重复，并抽查关键表与列存在。
 * 维护注意：
 * 1. 只读取 schema.sql 文本，不启动 Spring、不连接数据库；真实 MySQL 建库仍须用户演练验证。
 * 2. MySQL 8.0.16 起外键约束名与 CHECK 约束名在库内唯一，重名会在第二条建表语句处报
 *    Duplicate foreign key constraint name 并中断脚本；本用例用于在空库建库前发现重名。
 * 3. 新增/改名约束、索引或触发器时若与既有名称冲突，本用例必须失败；不得用放宽断言或删除断言的方式绕过。
 * 4. 本用例不做 SQL 语法解析器级别的完整解析，仅按行匹配建表块与约束定义；复杂格式变化需同步调整正则。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaConstraintNameTest {

    private static final Path SCHEMA = Path.of("src", "main", "resources", "db", "schema.sql");

    private static final Pattern TABLE_BLOCK = Pattern.compile(
            "CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`([^`]+)`(.*?)\\n\\)\\s*ENGINE",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 去掉 -- 行注释，避免注释里出现的名称干扰计数。 */
    private static String schemaText() throws IOException {
        return Files.readString(SCHEMA, StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*--.*$", "");
    }

    /** 把匹配位置换算成 1 起始的行号，便于失败信息直接定位。 */
    private static int lineOf(String text, int index) {
        return (int) text.substring(0, index).chars().filter(ch -> ch == '\n').count() + 1;
    }

    private static Map<String, String> tableBlocks(String sql) {
        Map<String, String> blocks = new LinkedHashMap<>();
        Matcher matcher = TABLE_BLOCK.matcher(sql);
        while (matcher.find()) {
            blocks.put(matcher.group(1), matcher.group(2));
        }
        return blocks;
    }

    @Test
    void constraintNamesAreUniqueAcrossSchema() throws IOException {
        String sql = schemaText();
        // MySQL 8 中外键与 CHECK 约束名都要求库内唯一，因此一并统计。
        Matcher matcher = Pattern.compile(
                "CONSTRAINT\\s+`?([A-Za-z0-9_]+)`?\\s+(?:FOREIGN\\s+KEY|CHECK)",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        Map<String, List<Integer>> occurrences = new LinkedHashMap<>();
        while (matcher.find()) {
            occurrences.computeIfAbsent(matcher.group(1), key -> new ArrayList<>())
                    .add(lineOf(sql, matcher.start()));
        }
        assertTrue(occurrences.size() > 60, "未解析到足够的约束名，正则与文件格式可能已失配");
        occurrences.forEach((name, lines) -> assertEquals(1, lines.size(),
                "约束名在库内重复（MySQL 8.0.16+ 会在第二条语句处中断）：" + name + " 出现于 " + lines));
    }

    @Test
    void triggerNamesAreUnique() throws IOException {
        String sql = schemaText();
        Matcher matcher = Pattern.compile(
                "CREATE\\s+TRIGGER\\s+`?([A-Za-z0-9_]+)`?\\s+BEFORE",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        Set<String> seen = new LinkedHashSet<>();
        while (matcher.find()) {
            assertTrue(seen.add(matcher.group(1)),
                    "触发器名重复：" + matcher.group(1) + "，位置 L" + lineOf(sql, matcher.start()));
        }
        assertTrue(seen.size() >= 2, "未解析到账本 append-only 触发器，正则与文件格式可能已失配");
    }

    @Test
    void indexNamesAreUniqueWithinEachTable() throws IOException {
        Map<String, String> blocks = tableBlocks(schemaText());
        assertTrue(blocks.size() > 30, "建表块解析数量异常，正则与文件格式可能已失配");
        blocks.forEach((tableName, block) -> {
            Matcher index = Pattern.compile(
                    "(?:UNIQUE\\s+KEY|KEY|INDEX)\\s+`([^`]+)`",
                    Pattern.CASE_INSENSITIVE).matcher(block);
            Set<String> names = new LinkedHashSet<>();
            while (index.find()) {
                assertTrue(names.add(index.group(1)),
                        "表 " + tableName + " 内索引/唯一键名重复：" + index.group(1));
            }
        });
    }

    @Test
    void keyTablesAndColumnsExist() throws IOException {
        Map<String, String> blocks = tableBlocks(schemaText());
        assertTableHasColumns(blocks, "trade_order", "fee_status", "refunded_amount", "payment_channel");
        assertTableHasColumns(blocks, "payment_event", "merchant_id", "reconcile_attempts", "next_reconcile_at");
        assertTableHasColumns(blocks, "merchant_account_ledger", "merchant_id", "amount");
        assertTableHasColumns(blocks, "payment_channel_statement", "merchant_id");
        // 渠道流水的商户外键必须用独立名称，避免与 billing_statement 的 V6 同名外键冲突。
        assertTrue(blockOf(blocks, "payment_channel_statement")
                        .contains("CONSTRAINT fk_payment_channel_statement_merchant FOREIGN KEY"),
                "payment_channel_statement 的商户外键名必须为 fk_payment_channel_statement_merchant");
        // billing_statement 的 fk_statement_merchant 是 V6 明确命名的对象，改名会失去吸收对照，必须保留原名。
        assertTrue(blockOf(blocks, "billing_statement")
                        .contains("CONSTRAINT fk_statement_merchant FOREIGN KEY"),
                "billing_statement 的商户外键名必须保留 fk_statement_merchant");
    }

    private static String blockOf(Map<String, String> blocks, String tableName) {
        String block = blocks.get(tableName);
        assertTrue(block != null, "schema.sql 缺少关键表：" + tableName);
        return block;
    }

    private static void assertTableHasColumns(Map<String, String> blocks, String tableName, String... columns) {
        String block = blockOf(blocks, tableName);
        for (String column : columns) {
            assertTrue(block.contains("`" + column + "`"),
                    "表 " + tableName + " 缺少关键列：" + column);
        }
    }
}
