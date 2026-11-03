/*
 * 文件：StrictIdDeserializer.java
 * 用途：卡密导入ID的整数解析，兼容旧前端十进制数字字符串但拒绝小数截断。
 * 维护注意：不回显输入原文；正数与业务归属由服务层再校验，JSON溢出应返回格式错误。仅显式标注字段使用，不改变其他接口的全局Jackson行为。
 */
package com.shop.b2b2c.common;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
public class StrictIdDeserializer extends StdDeserializer<Long> {
    public StrictIdDeserializer() { super(Long.class); }
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) return parser.getLongValue();
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            String value = parser.getText().trim();
            if (value.matches("[0-9]+")) {
                try { return Long.valueOf(value); } catch (NumberFormatException ignored) { /* 统一格式错误，不记录原文。 */ }
            }
        }
        throw JsonMappingException.from(parser, "ID须为整数或十进制数字字符串");
    }
}
