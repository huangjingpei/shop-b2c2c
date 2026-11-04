/*
 * 文件：StrictIdDeserializerTest.java
 * 用途：验证ID正整数反序列化契约（R35）：支持数字与十进制字符串、保持大整数（超2^53-1及Long.MAX_VALUE）精度，严格拒绝非正数、前导零、小数及溢出。
 */
package com.shop.b2b2c;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.shop.b2b2c.common.StrictIdDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class StrictIdDeserializerTest {

    record IdHolder(@JsonDeserialize(using = StrictIdDeserializer.class) Long id) {}

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void normalPositiveIntegerParsesCorrectly() throws Exception {
        IdHolder fromInt = json.readValue("{\"id\": 100}", IdHolder.class);
        assertEquals(100L, fromInt.id());

        IdHolder fromStr = json.readValue("{\"id\": \"100\"}", IdHolder.class);
        assertEquals(100L, fromStr.id());
    }

    @Test
    void largeSafeAndUnsafeJsIntegersPreserveFullPrecision() throws Exception {
        // 9007199254740993 exceeds JavaScript Number.MAX_SAFE_INTEGER (9007199254740991)
        String largeId = "9007199254740993";
        IdHolder holder = json.readValue("{\"id\": \"" + largeId + "\"}", IdHolder.class);
        assertEquals(9007199254740993L, holder.id());

        // Long.MAX_VALUE (9223372036854775807) preserves full precision
        String maxLong = String.valueOf(Long.MAX_VALUE);
        IdHolder maxHolder = json.readValue("{\"id\": \"" + maxLong + "\"}", IdHolder.class);
        assertEquals(Long.MAX_VALUE, maxHolder.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\": 0}",
            "{\"id\": -1}",
            "{\"id\": -999}",
            "{\"id\": 1.5}",
            "{\"id\": \"0\"}",
            "{\"id\": \"-1\"}",
            "{\"id\": \"01\"}",
            "{\"id\": \"1.5\"}",
            "{\"id\": \"1e3\"}",
            "{\"id\": \"abc\"}",
            "{\"id\": \"\"}",
            "{\"id\": \"   \"}",
            "{\"id\": \"1/other\"}",
            "{\"id\": \"9223372036854775808\"}" // Long.MAX_VALUE + 1 overflows 64-bit signed int
    })
    void illegalValuesAreStrictlyRejected(String jsonPayload) {
        assertThrows(Exception.class, () -> json.readValue(jsonPayload, IdHolder.class));
    }

    @Test
    void nullAndMissingValuesRemainNull() throws Exception {
        IdHolder nullVal = json.readValue("{\"id\": null}", IdHolder.class);
        assertNull(nullVal.id());

        IdHolder emptyObj = json.readValue("{}", IdHolder.class);
        assertNull(emptyObj.id());
    }
}
