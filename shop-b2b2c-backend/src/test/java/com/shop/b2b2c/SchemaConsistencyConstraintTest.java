/*
 * 文件：SchemaConsistencyConstraintTest.java
 * 用途：schema.sql 的数据一致性静态回归：外键两端类型一致、资金列精度、状态列非空、
 *      资金幂等唯一键基线、金额不变量 CHECK 与财务表审计时间戳。
 * 维护注意：
 * 1. 只读取 schema.sql 文本，不启动 Spring、不连接数据库；真实 MySQL 的建表、外键与 CHECK 强制行为仍须用户演练验证。
 * 2. CHECK 约束自 MySQL 8.0.16 起才真正强制，本用例只断言 DDL 中存在该约束，不能证明运行实例已执行。
 * 3. 新增/调整资金唯一键、外键或 CHECK 时，必须同步更新本用例的基线；不得删除断言来绕过失败。
 * 4. 列解析按建表块逐行匹配，不构成 SQL 语法解析器；格式大幅变化时需同步调整正则。
 * 5. from_status/to_status 属于状态迁移审计的前后值，首次迁移没有前值，允许为空，不纳入“状态列非空”断言。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaConsistencyConstraintTest {

    private static final Path SCHEMA = Path.of("src", "main", "resources", "db", "schema.sql");

    private static final Pattern TABLE_BLOCK = Pattern.compile(
            "CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`?([A-Za-z0-9_]+)`?(.*?)\\n\\)\\s*ENGINE",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 列定义解析到行尾：NOT NULL 位于类型之后，若只匹配类型前缀会漏判，导致“状态列必须非空”失真。
     */
    private static final Pattern COLUMN = Pattern.compile(
            "^\\s*`?([A-Za-z0-9_]+)`?\\s+([A-Za-z]+(?:\\([^)]*\\))?)(\\s+UNSIGNED)?(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern FOREIGN_KEY = Pattern.compile(
            "CONSTRAINT\\s+`?([A-Za-z0-9_]+)`?\\s+FOREIGN\\s+KEY\\s+\\(`?([A-Za-z0-9_]+)`?\\)"
                    + "\\s+REFERENCES\\s+`?([A-Za-z0-9_]+)`?\\s*\\(`?([A-Za-z0-9_]+)`?\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UNIQUE_KEY = Pattern.compile(
            "UNIQUE\\s+KEY\\s+`?([A-Za-z0-9_]+)`?\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    /** 建表块中以关键字开头、并非列定义的行。 */
    private static final Set<String> NON_COLUMN_KEYWORDS = Set.of(
            "PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "CHECK");

    /** 状态迁移审计的前后值允许为空，不参与“状态列必须非空”断言。 */
    private static final Set<String> NULLABLE_STATUS_COLUMNS = Set.of("from_status", "to_status");

    private record ColumnDef(String type, boolean notNull) {
    }

    private static String schemaText() throws IOException {
        return Files.readString(SCHEMA, StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*--.*$", "");
    }

    /** 折叠空白，便于对跨行 CHECK 表达式做子串断言。 */
    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    private static Map<String, String> tableBlocks(String sql) {
        Map<String, String> blocks = new LinkedHashMap<>();
        Matcher matcher = TABLE_BLOCK.matcher(sql);
        while (matcher.find()) {
            blocks.put(matcher.group(1), matcher.group(2));
        }
        assertTrue(blocks.size() > 30, "建表块解析数量异常，正则与文件格式可能已失配");
        return blocks;
    }

    private static Map<String, Map<String, ColumnDef>> tableColumns(Map<String, String> blocks) {
        Map<String, Map<String, ColumnDef>> columns = new LinkedHashMap<>();
        blocks.forEach((table, block) -> {
            Map<String, ColumnDef> tableColumnMap = new LinkedHashMap<>();
            Matcher matcher = COLUMN.matcher(block);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (NON_COLUMN_KEYWORDS.contains(name.toUpperCase())) {
                    continue;
                }
                String type = matcher.group(2).toUpperCase();
                if (matcher.group(3) != null) {
                    type = type + " UNSIGNED";
                }
                String line = matcher.group();
                boolean notNull = Pattern.compile("\\bNOT\\s+NULL\\b", Pattern.CASE_INSENSITIVE)
                        .matcher(line).find();
                tableColumnMap.put(name, new ColumnDef(type, notNull));
            }
            columns.put(table, tableColumnMap);
        });
        return columns;
    }

    @Test
    void foreignKeyChildAndParentColumnTypesMatch() throws IOException {
        Map<String, String> blocks = tableBlocks(schemaText());
        Map<String, Map<String, ColumnDef>> columns = tableColumns(blocks);
        int count = 0;
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            String childTable = entry.getKey();
            Matcher matcher = FOREIGN_KEY.matcher(entry.getValue());
            while (matcher.find()) {
                count++;
                String constraint = matcher.group(1);
                String childColumn = matcher.group(2);
                String parentTable = matcher.group(3);
                String parentColumn = matcher.group(4);
                ColumnDef child = columns.containsKey(childTable)
                        ? columns.get(childTable).get(childColumn) : null;
                ColumnDef parent = columns.containsKey(parentTable)
                        ? columns.get(parentTable).get(parentColumn) : null;
                if (child == null || parent == null) {
                    problems.add(constraint + "：子列/父列未解析到（" + childTable + "." + childColumn
                            + " -> " + parentTable + "." + parentColumn + "）");
                    continue;
                }
                if (!child.type().equals(parent.type())) {
                    problems.add(constraint + "：子列 " + childTable + "." + childColumn + "=" + child.type()
                            + "，父列 " + parentTable + "." + parentColumn + "=" + parent.type());
                }
            }
        }
        assertTrue(problems.isEmpty(), "外键两端类型或父对象不一致：" + problems);
        assertTrue(count >= 66, "外键解析数量异常，正则与文件格式可能已失配");
    }

    @Test
    void moneyColumnsAreDecimalWithTwoScale() throws IOException {
        Map<String, Map<String, ColumnDef>> columns = tableColumns(tableBlocks(schemaText()));
        Pattern decimal = Pattern.compile("DECIMAL\\((\\d+),(\\d+)\\)", Pattern.CASE_INSENSITIVE);
        List<String> problems = new ArrayList<>();
        columns.forEach((table, tableColumns) -> tableColumns.forEach((column, def) -> {
            String type = def.type();
            assertFalse(type.startsWith("FLOAT") || type.startsWith("DOUBLE") || type.startsWith("REAL"),
                    "资金列禁止浮点类型：" + table + "." + column + "=" + type);
            Matcher matcher = decimal.matcher(type);
            if (!matcher.matches()) {
                return;
            }
            int scale = Integer.parseInt(matcher.group(2));
            // fee_tax_rate 是税率比例快照，不是金额，允许 4 位小数；其余金额/费率比例标度统一为 2。
            if (scale != 2 && !"fee_tax_rate".equals(column)) {
                problems.add(table + "." + column + "=" + type);
            }
        }));
        assertTrue(problems.isEmpty(), "金额列标度必须为 2（税率比例除外）：" + problems);
    }

    @Test
    void statusColumnsAreNotNull() throws IOException {
        Map<String, Map<String, ColumnDef>> columns = tableColumns(tableBlocks(schemaText()));
        List<String> problems = new ArrayList<>();
        columns.forEach((table, tableColumns) -> tableColumns.forEach((column, def) -> {
            String lower = column.toLowerCase();
            boolean statusLike = lower.endsWith("status") || lower.equals("is_active")
                    || lower.equals("is_enabled") || lower.equals("is_default");
            if (statusLike && !NULLABLE_STATUS_COLUMNS.contains(lower) && !def.notNull()) {
                problems.add(table + "." + column);
            }
        }));
        assertTrue(problems.isEmpty(), "状态/开关列必须 NOT NULL：" + problems);
    }

    @Test
    void financialIdempotencyUniqueKeysMatchBaseline() throws IOException {
        String sql = schemaText();
        Map<String, String> uniqueKeys = new LinkedHashMap<>();
        Matcher matcher = UNIQUE_KEY.matcher(sql);
        while (matcher.find()) {
            uniqueKeys.put(matcher.group(1), matcher.group(2).replace("`", "").replaceAll("\\s+", ""));
        }
        Map<String, String> expected = Map.ofEntries(
                Map.entry("uk_store_code", "store_code"),
                Map.entry("uk_username", "username"),
                Map.entry("uk_merchant_channel", "merchant_id,channel"),
                Map.entry("uk_merchant_sku_code", "merchant_id,sku_code"),
                Map.entry("uk_merchant_fingerprint", "merchant_id,card_fingerprint"),
                Map.entry("uk_order_sn", "order_sn"),
                Map.entry("uk_peer_pay_token", "peer_pay_token"),
                Map.entry("uk_query_token", "query_token"),
                Map.entry("uk_refund_no", "refund_no"),
                Map.entry("uk_refund_order_key", "order_id,idempotency_key"),
                Map.entry("uk_refund_source_event", "source_event_id"),
                Map.entry("uk_shop_period", "shop_id,period_date,period_type"),
                Map.entry("uk_inventory_adjustment_request", "request_key"),
                Map.entry("uk_channel_trade_no", "channel,pay_trade_no"),
                Map.entry("uk_account_merchant", "merchant_id"),
                Map.entry("uk_ledger_idempotency", "idempotency_key"),
                Map.entry("uk_recharge_no", "recharge_no"),
                Map.entry("uk_recharge_voucher", "voucher_no"),
                Map.entry("uk_settlement_period", "merchant_id,period_start,period_end,settlement_type"),
                Map.entry("uk_settlement_item", "batch_id,item_type,order_id,refund_id"),
                Map.entry("uk_statement_trade", "channel,channel_trade_no"),
                Map.entry("uk_difference_key", "difference_key"),
                Map.entry("uk_reconciliation_run", "business_date,merchant_id"),
                Map.entry("uk_settlement_account_fingerprint", "merchant_id,account_fingerprint"),
                Map.entry("uk_platform_settlement_order", "order_id"),
                Map.entry("uk_split_order_receiver", "order_id,payment_account_id"),
                Map.entry("uk_split_reverse_refund", "split_detail_id,refund_id"));
        expected.forEach((name, cols) -> {
            assertNotNull(uniqueKeys.get(name), "缺少资金幂等唯一键：" + name);
            assertEquals(cols, uniqueKeys.get(name),
                    "唯一键列顺序与基线不一致：" + name + "，实际=" + uniqueKeys.get(name));
        });
        // query_token 已是唯一键，不允许再保留重复的普通索引。
        assertFalse(normalized(sql).contains("KEY `idx_query_token`"),
                "query_token 已由 uk_query_token 覆盖，不应保留普通索引 idx_query_token");
    }

    @Test
    void queryTokenIsNotNullAndUnique() throws IOException {
        Map<String, Map<String, ColumnDef>> columns = tableColumns(tableBlocks(schemaText()));
        ColumnDef token = columns.get("trade_order") == null ? null : columns.get("trade_order").get("query_token");
        assertNotNull(token, "trade_order.query_token 必须存在");
        assertEquals("VARCHAR(64)", token.type(), "query_token 类型基线已变化");
        assertTrue(token.notNull(), "query_token 必须 NOT NULL；若改为可空，唯一键会放过空值，"
                + "与“买家免密查单凭证必须存在”的语义不符");
        assertTrue(normalized(schemaText()).contains("UNIQUE KEY `uk_query_token` (`query_token`)"),
                "query_token 必须由唯一键覆盖");
    }

    @Test
    void moneyInvariantChecksArePresent() throws IOException {
        String sql = normalized(schemaText());
        assertTrue(sql.contains("CONSTRAINT `chk_order_refund_within_paid`"),
                "缺少订单退款上限 CHECK");
        assertTrue(sql.contains("`refunded_amount` <= `pay_amount`"),
                "订单累计退款必须受实付金额限制");
        assertTrue(sql.contains("`fee_refunded_amount` <= `commission_amount`"),
                "服务费冲正必须受订单快照限制");

        assertTrue(sql.contains("CONSTRAINT `chk_ledger_balance_nonnegative`"),
                "缺少账本余额/欠费非负 CHECK");
        assertTrue(sql.contains("`balance_after` >= 0.00 AND `arrears_after` >= 0.00"),
                "账本入账后余额与欠费必须非负");

        assertTrue(sql.contains("CONSTRAINT `chk_fee_policy_rate`"),
                "缺少费率区间 CHECK");
        assertTrue(sql.contains("`rate_bps` BETWEEN 100 AND 1000"),
                "正常费率必须限制在 100–1000 基点");
        assertTrue(sql.contains("`rate_type` = 'PROMOTION' AND `rate_bps` = 0"),
                "推广费率必须固定为 0 基点");

        assertTrue(sql.contains("`refunded_amount` <= `merchant_payable_amount`"),
                "平台代收累计退分不得超过商家应结净额");
    }

    @Test
    void financialTablesCarryAuditTimestamps() throws IOException {
        Map<String, Map<String, ColumnDef>> columns = tableColumns(tableBlocks(schemaText()));
        assertAuditColumn(columns, "trade_order", "updated_at");
        assertAuditColumn(columns, "merchant_fee_policy", "updated_at");
        List<String> financialTables = List.of(
                "trade_order", "trade_refund", "payment_event", "merchant_account", "merchant_account_ledger",
                "merchant_recharge_order", "merchant_settlement_batch", "merchant_settlement_item",
                "payment_channel_statement", "reconciliation_difference", "billing_statement",
                "merchant_fee_policy", "merchant_fee_audit", "merchant_collection_status_audit",
                "merchant_settlement_account", "merchant_settlement_account_audit",
                "merchant_onboarding_application", "merchant_onboarding_audit",
                "platform_collection_settlement", "split_detail", "split_reverse",
                "reconciliation_batch_run", "reconciliation_difference_audit",
                "platform_collection_config", "merchant_collection_switch_audit");
        financialTables.forEach(table -> assertAuditColumn(columns, table, "created_at"));
    }

    @Test
    void t1ReconciliationColumnsAndRunUniquenessArePresent() throws IOException {
        Map<String, Map<String, ColumnDef>> columns = tableColumns(tableBlocks(schemaText()));
        Map<String, ColumnDef> difference = columns.get("reconciliation_difference");
        assertNotNull(difference, "schema.sql 缺少 reconciliation_difference");
        assertNotNull(difference.get("diff_category"), "差异缺少 T+1 分类列 diff_category");
        assertNotNull(difference.get("business_date"), "差异缺少业务日归属列 business_date");
        assertNotNull(difference.get("last_retry_date"), "差异缺少同日重试防重复列 last_retry_date");

        Map<String, ColumnDef> run = columns.get("reconciliation_batch_run");
        assertNotNull(run, "schema.sql 缺少 reconciliation_batch_run");
        assertNotNull(run.get("business_date"), "跑批记录缺少业务日");
        assertNotNull(run.get("merchant_id"), "跑批记录缺少店铺");
        assertNotNull(run.get("attempts"), "跑批记录缺少尝试次数");
        assertNotNull(run.get("last_error"), "跑批记录缺少失败/部分完成原因");
        assertNotNull(run.get("retry_failed"),
                "跑批记录缺少 retry_failed；自动重试异常必须落库可见，不能只打日志");

        Map<String, ColumnDef> refund = columns.get("trade_refund");
        assertNotNull(refund, "schema.sql 缺少 trade_refund");
        assertNotNull(refund.get("success_at"),
                "退款缺少不可变成功时点 success_at；T+1 切日不能用会被 ON UPDATE 改写的 updated_at");
        assertTrue(normalized(schemaText()).contains("KEY `idx_refund_success` (`merchant_id`, `success_at`)"),
                "退款切日必须由 (merchant_id, success_at) 索引支撑，否则跨日窗口会全表扫描");

        Map<String, ColumnDef> audit = columns.get("reconciliation_difference_audit");
        assertNotNull(audit, "schema.sql 缺少 reconciliation_difference_audit");
        assertNotNull(audit.get("action"), "差异审计缺少动作");
        assertNotNull(audit.get("from_status"), "差异审计缺少变更前状态");
        assertNotNull(audit.get("to_status"), "差异审计缺少变更后状态");
        assertNotNull(audit.get("operator_id"), "差异审计缺少操作人");
        assertTrue(normalized(schemaText()).contains("UNIQUE KEY `uk_reconciliation_run` (`business_date`, `merchant_id`)"),
                "跑批必须由 (business_date, merchant_id) 唯一键保证同日同店不重复");
    }

    @Test
    void paymentAccountApplyStatusDefaultsToAuditing() throws IOException {
        String sql = normalized(schemaText());
        assertTrue(sql.contains("`apply_status` VARCHAR(32) NOT NULL DEFAULT 'AUDITING'"),
                "进件状态默认值必须是 AUDITING，不能默认 PASSED");
        assertFalse(sql.contains("`apply_status` VARCHAR(32) NOT NULL DEFAULT 'PASSED'"),
                "进件状态不得默认为已开通");
    }

    private static void assertAuditColumn(Map<String, Map<String, ColumnDef>> columns,
                                          String table, String column) {
        Map<String, ColumnDef> tableColumns = columns.get(table);
        assertNotNull(tableColumns, "schema.sql 缺少财务表：" + table);
        assertNotNull(tableColumns.get(column), "财务表 " + table + " 缺少审计列：" + column);
    }
}
