/*
 * 文件：JacksonConfig.java
 * 用途：T37 全局把包装类型 Long 序列化为十进制字符串，避免超过 JavaScript 安全整数（2^53-1）的 ID 在浏览器端被静默截断。
 * 维护注意：只注册 Long.class，不覆盖 long 基本类型，订单统计计数等数值字段仍按 JSON number 输出；
 * 请求侧 Long 反序列化不在此处改变，StrictIdDeserializer 继续同时接受数字与十进制字符串。新增 Long 字段须评估前端是否按字符串处理。
 */
package com.shop.b2b2c.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longAsStringCustomizer() {
        return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance);
    }
}
