/*
 * 文件：StrictIdDeserializer.java
 * 用途：ID正整数严格解析器，支持正整数及十进制数字字符串，防范小数截断、非正数、前导零与超限溢出（R35）。
 * 维护注意：严格校验正整数 [1-9][0-9]*，Long.MAX_VALUE 边界严格防溢；拒绝 <= 0、小数、前导零与越界字符串；不回显非法原文以保护安全边界。
 */
package com.shop.b2b2c.common;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

public class StrictIdDeserializer extends StdDeserializer<Long> {
    public StrictIdDeserializer() { super(Long.class); }

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            long val = parser.getLongValue();
            if (val <= 0) {
                throw JsonMappingException.from(parser, "ID须为正整数");
            }
            return val;
        }
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            String value = parser.getText().trim();
            if (value.matches("[1-9][0-9]*")) {
                try {
                    return Long.valueOf(value);
                } catch (NumberFormatException ignored) {
                    // 超出 Long.MAX_VALUE (例如 9223372036854775808)，落入格式异常统一拦截
                }
            }
        }
        throw JsonMappingException.from(parser, "ID须为正整数或十进制数字字符串");
    }
}
