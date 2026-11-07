/*
 * 文件：OrderFeeStatusContractTest.java
 * 用途：锁定订单级服务费状态（trade_order.fee_status）的编码集合、实体默认值与 SQL 默认值/说明，防止与商户配置标记分叉。
 * 维护注意：
 *  1. 只读类路径 SQL 资源、注解文本与内存序列化，不连接数据库，不替代真实 MySQL 迁移验证。
 *  2. 本测试以 OrderFeeStatus 为唯一来源；新增/删除订单费状态必须先改常量与 schema.sql，再同步本测试。
 *  3. 允许出现在退款 SQL 的非费用状态（订单状态）在此显式列出，避免把 order_status 误判为费用编码。
 */
package com.shop.b2b2c;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.common.OrderFeeStatus;
import com.shop.b2b2c.common.ServiceFeeStatus;
import com.shop.b2b2c.entity.TradeOrder;
import com.shop.b2b2c.mapper.TradeRefundMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class OrderFeeStatusContractTest {

    private static final Set<String> ORDER_STATUS_TOKENS = Set.of("PAID", "SHIPPED", "COMPLETED");
    private static final Pattern QUOTED_UPPER = Pattern.compile("'([A-Z][A-Z_]*)'");

    @Test
    void entityDefaultAndJsonEncodingUsePending() {
        var order = new TradeOrder();
        assertEquals(OrderFeeStatus.PENDING, order.getFeeStatus());
        var tree = new ObjectMapper().valueToTree(order);
        assertTrue(tree.get("feeStatus").isTextual());
        assertEquals(OrderFeeStatus.PENDING, tree.get("feeStatus").textValue());
    }

    @Test
    void supportedSetIsExactlyFiveDeclaredCodes() {
        assertEquals(5, OrderFeeStatus.SUPPORTED.size());
        assertEquals(Set.of("PENDING", "ACCRUED", "ARREARS", "PARTIAL_REVERSED", "REVERSED"),
                OrderFeeStatus.SUPPORTED);
    }

    @Test
    void bothSchemasDefaultTradeOrderFeeStatusToPendingAndDocumentAllCodes() throws Exception {
        for (String path : new String[] {"db/schema.sql", "sprint1-schema.sql"}) {
            String sql = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
            String orderColumn = sql.lines()
                    .filter(line -> line.stripLeading().startsWith("`fee_status`"))
                    .filter(line -> line.contains("DEFAULT 'PENDING'"))
                    .findFirst().orElseThrow(() -> new AssertionError(path + " 缺少 trade_order.fee_status 默认值"));
            for (String code : OrderFeeStatus.SUPPORTED) {
                assertTrue(orderColumn.contains(code), path + " 的 trade_order.fee_status 说明缺少 " + code);
            }
        }
    }

    @Test
    void merchantStoreFeeStatusRemainsASeparateConfiguredSet() throws Exception {
        assertNotEquals(ServiceFeeStatus.SUPPORTED, OrderFeeStatus.SUPPORTED);
        for (String path : new String[] {"db/schema.sql", "sprint1-schema.sql"}) {
            String sql = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
            String storeColumn = sql.lines()
                    .filter(line -> line.stripLeading().startsWith("`fee_status`"))
                    .filter(line -> line.contains("DEFAULT 'NORMAL'"))
                    .findFirst().orElseThrow(() -> new AssertionError(path + " 缺少 merchant_store.fee_status 默认值"));
            for (String code : ServiceFeeStatus.SUPPORTED) {
                assertTrue(storeColumn.contains(code), path + " 的 merchant_store.fee_status 说明缺少 " + code);
            }
            for (String orderOnly : new String[] {"PENDING", "ACCRUED", "PARTIAL_REVERSED", "REVERSED"}) {
                assertFalse(storeColumn.contains(orderOnly), path + " 的商户配置标记不得混入订单费编码 " + orderOnly);
            }
        }
    }

    @Test
    void refundSqlOnlyWritesDeclaredOrderFeeStatusCodes() throws Exception {
        assertRefundSqlUsesDeclaredCodes("reserveRefund", Long.class, BigDecimal.class, BigDecimal.class);
        assertRefundSqlUsesDeclaredCodes("releaseRefundReservation", Long.class, BigDecimal.class, BigDecimal.class);
    }

    private static void assertRefundSqlUsesDeclaredCodes(String method, Class<?>... parameterTypes) throws Exception {
        String sql = String.join("\n",
                TradeRefundMapper.class.getMethod(method, parameterTypes).getAnnotation(Update.class).value());
        assertTrue(sql.contains("fee_status"), method + " 必须写入订单费状态");
        Matcher matcher = QUOTED_UPPER.matcher(sql);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (ORDER_STATUS_TOKENS.contains(token)) continue;
            assertTrue(OrderFeeStatus.SUPPORTED.contains(token),
                    method + " 写入了未声明的订单费状态 " + token + "，请先登记到 OrderFeeStatus 与 schema");
        }
    }
}
