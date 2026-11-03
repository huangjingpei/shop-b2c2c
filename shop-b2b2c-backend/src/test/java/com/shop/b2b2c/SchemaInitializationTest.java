/*
 * 文件：SchemaInitializationTest.java
 * 用途：验证开发期初始化的单一入口、失败阻断、非破坏性SQL及参数日志隐私边界。
 * 维护注意：只读取类路径资源，不连接数据库；实际MySQL执行与重复启动由SchemaMySqlTest覆盖。不能把静态契约检查当成SQL语法验收。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SchemaInitializationTest {
    @Test
    void startupLoadsOneBaselineAndDoesNotSwallowSqlErrors() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertNotNull(properties);
        assertEquals("always", properties.getProperty("spring.sql.init.mode"));
        assertEquals("classpath:db/schema.sql", properties.getProperty("spring.sql.init.schema-locations"));
        assertEquals("false", properties.getProperty("spring.sql.init.continue-on-error"));
        assertEquals("org.apache.ibatis.logging.nologging.NoLoggingImpl", properties.getProperty("mybatis-plus.configuration.log-impl"),
                "运行配置不得将买家联系方式、领取凭证和卡密参数打印到stdout");
        assertFalse(new ClassPathResource("db/migrations/002_inventory_reservation.sql").exists());
        assertFalse(new ClassPathResource("db/migrations/003_order_idempotency.sql").exists());
    }

    @Test
    void baselineNeverDeletesDataOrChangesTheConfiguredDatabase() throws Exception {
        String sql = new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);
        String commands = sql.replaceAll("(?m)^\\s*--.*$", "");
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*(DROP|TRUNCATE|DELETE|USE)\\b").matcher(commands).find());
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*CREATE\\s+DATABASE\\b").matcher(commands).find());
        assertFalse(java.util.regex.Pattern.compile("(?im)^\\s*CREATE\\s+INDEX\\b").matcher(commands).find(),
                "重复启动不能重复执行独立CREATE INDEX");
    }
}
