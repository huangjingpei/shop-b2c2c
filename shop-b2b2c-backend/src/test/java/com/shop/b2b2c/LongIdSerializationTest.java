/*
 * 文件：LongIdSerializationTest.java
 * 用途：T37 序列化契约单元测试，验证包装 Long 一律输出十进制字符串、基本 long 与 Integer 仍为 JSON number，并验证字符串 ID 可反序列化。
 * 维护注意：不启动Spring上下文，直接应用 JacksonConfig 的定制器；MVC 全局生效另由 Sprint1 集成用例覆盖。新增 Long 字段时须同步评估该契约。
 */
package com.shop.b2b2c;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.b2b2c.config.JacksonConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongIdSerializationTest {
    public static class Sample {
        public Long id = 9007199254740993L;
        public Long smallId = 1L;
        public long count = 3L;
        public Integer code = 7;
    }

    @Test
    void wrapperLongIsTextWhilePrimitiveAndIntegerStayNumbers() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().longAsStringCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        JsonNode node = mapper.readTree(mapper.writeValueAsString(new Sample()));
        assertTrue(node.path("id").isTextual());
        assertEquals("9007199254740993", node.path("id").asText());
        assertTrue(node.path("smallId").isTextual());
        assertEquals("1", node.path("smallId").asText());
        assertTrue(node.path("count").isNumber());
        assertTrue(node.path("code").isNumber());
    }

    @Test
    void decimalStringIdRoundTripsWithoutPrecisionLoss() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().longAsStringCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        Sample sample = mapper.readValue("{\"id\":\"9007199254740993\",\"smallId\":\"1\",\"count\":3,\"code\":7}", Sample.class);
        assertEquals(9007199254740993L, sample.id);
        assertEquals(1L, sample.smallId);
    }
}
