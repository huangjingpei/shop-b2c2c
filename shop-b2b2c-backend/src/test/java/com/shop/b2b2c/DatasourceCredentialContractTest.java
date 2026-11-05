/*
 * 文件：DatasourceCredentialContractTest.java
 * 用途：锁定生产数据源必须由环境变量显式注入，禁止回退root/硬编码口令；缺少占位值时启动应失败。
 * 维护注意：仅解析配置文本，不启动Spring上下文也不连接数据库；真实启动失败与生产连通性由用户在部署环境验证。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DatasourceCredentialContractTest {

    private static Properties load(String name) {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(name));
        Properties properties = yaml.getObject();
        assertNotNull(properties);
        return properties;
    }

    @Test
    void datasourceCredentialsComeFromEnvironmentWithoutDefaults() {
        Properties properties = load("application.yml");
        assertEquals("${SPRING_DATASOURCE_USERNAME}", properties.getProperty("spring.datasource.username"));
        assertEquals("${SPRING_DATASOURCE_PASSWORD}", properties.getProperty("spring.datasource.password"));
        assertFalse(properties.getProperty("spring.datasource.username", "").contains("root"));
        assertFalse(properties.getProperty("spring.datasource.password", "").contains("89dfu"));
    }

    @Test
    void productionProfileDoesNotReintroduceDefaultCredentials() {
        Properties properties = load("application-prod.yml");
        assertEquals("never", properties.getProperty("spring.sql.init.mode"));
        assertFalse(properties.getProperty("spring.datasource.username", "").contains("root"));
    }
}
