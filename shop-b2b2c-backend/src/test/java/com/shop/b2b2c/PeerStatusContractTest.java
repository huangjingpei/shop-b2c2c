/*
 * 文件：PeerStatusContractTest.java
 * 用途：防止订单实体默认值、初始化结构和代付JSON编码再次分叉。
 * 维护注意：只读类路径资源及内存序列化，不连接数据库；结构文本检查不替代SQL执行。未知旧值保持原样，不自动授予预占或支付资格，业务拒绝另有隔离集成测试。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.dto.PeerPayDTOs;
import com.shop.b2b2c.entity.TradeOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class PeerStatusContractTest {
    @Test
    void entityAndBothSchemaDefaultsAreInitialAndNotLegacyIntent() throws Exception {
        var order = new TradeOrder();
        assertEquals("UNPAID", order.getOrderStatus());
        assertEquals("INIT", order.getPeerStatus());
        assertEquals("INIT", new ObjectMapper().valueToTree(order).get("peerStatus").textValue());
        for (String path : new String[] {"db/schema.sql", "sprint1-schema.sql"}) {
            String sql = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
            String column = sql.lines().filter(line -> line.stripLeading().startsWith("`peer_status`")).findFirst().orElseThrow();
            assertTrue(column.contains("DEFAULT 'INIT'"), path + " 的默认值必须与建单契约一致");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"INIT", "RESERVED_LOCK", "PAID", "EXPIRED", "CANCELLED", "INTENT", "LOCKED", "UNKNOWN"})
    void stringContractPreservesCanonicalAndUnknownHistoricalValues(String code) {
        var response = new PeerPayDTOs.DetailResponse(); response.setPeerStatus(code);
        var tree = new ObjectMapper().valueToTree(response);
        assertTrue(tree.get("peerStatus").isTextual());
        assertEquals(code, tree.get("peerStatus").textValue());
    }
}
