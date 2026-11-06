/*
 * 文件：LogbackConfigTest.java
 * 用途：日志分环境策略静态守卫——logback-spring.xml 必须同时存在本地（!prod）与生产（prod）两段 profile；
 *      本地控制台 DEBUG 全量、生产文件滚动保留与 ERROR 单独文件；MyBatis SQL 默认本地可见、生产硬关闭；
 *      测试环境 logback-test.xml 不产文件、不开 DEBUG，保证测试输出安静。
 * 维护注意：本类只读文本、不启动日志框架；改 logback-spring.xml / application*.yml / logback-test.xml 时同步本断言。
 */
package com.shop.b2b2c;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogbackConfigTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void logbackHasBothLocalAndProdProfiles() throws Exception {
        String xml = read("src/main/resources/logback-spring.xml");
        assertTrue(xml.contains("<springProfile name=\"!prod\">"), "本地档（非prod）必须存在");
        assertTrue(xml.contains("<springProfile name=\"prod\">"), "生产档必须存在");
        // 本地：控制台 + 本工程 DEBUG，不产文件。
        assertTrue(xml.contains("<logger name=\"com.shop.b2b2c\" level=\"DEBUG\"/>"), "本地本工程须 DEBUG");
        assertFalse(xml.contains("mapper\" level=\"DEBUG\""), "不开 mapper DEBUG：与 SQL 隐私红线保持一致");
        String localSection = xml.substring(xml.indexOf("<springProfile name=\"!prod\">"),
                xml.indexOf("<springProfile name=\"prod\">"));
        assertFalse(localSection.contains("RollingFileAppender"), "本地档不得产日志文件");
    }

    @Test
    void prodKeepsOnlyKeyLogsWithRollingFiles() throws Exception {
        String xml = read("src/main/resources/logback-spring.xml");
        String prodSection = xml.substring(xml.indexOf("<springProfile name=\"prod\">"));
        assertTrue(prodSection.contains("<root level=\"INFO\">"), "生产 root 只收 INFO+（关键日志）");
        assertFalse(prodSection.contains("level=\"DEBUG\""), "生产档不得开 DEBUG");
        assertTrue(prodSection.contains("RollingFileAppender"), "生产必须文件持久化");
        assertTrue(prodSection.contains("${LOG_PATH}/shop-b2b2c.log"), "主日志文件路径须可经 LOG_PATH 覆盖");
        assertTrue(prodSection.contains("<maxHistory>30</maxHistory>"), "主日志保留 30 天");
        assertTrue(prodSection.contains("<totalSizeCap>2GB</totalSizeCap>"), "主日志总量封顶防磁盘打满");
        assertTrue(prodSection.contains("ThresholdFilter"), "ERROR 级须有阈值过滤");
        assertTrue(prodSection.contains("shop-b2b2c-error.log"), "ERROR 单独文件便于值班定位");
    }

    @Test
    void mybatisSqlLoggingStaysOffInEveryProfile() throws Exception {
        // 隐私红线（与 SchemaInitializationTest 同口径）：SQL 参数含买家联系方式/领取凭证/卡密，全环境关闭。
        String app = read("src/main/resources/application.yml");
        assertTrue(app.contains("org.apache.ibatis.logging.nologging.NoLoggingImpl"),
                "任何环境不得把 SQL 参数打印到日志/stdout");
        // 配置行（剥离 YAML 注释后）不得出现 Slf4jImpl；注释里提及"本地可临时切换"属说明文字。
        String configLines = app.lines().filter(line -> !line.trim().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
        assertFalse(configLines.contains("Slf4jImpl"), "log-impl 配置值不得切到 Slf4jImpl（本地临时排查可自改但不提交）");
    }

    @Test
    void testLoggingStaysQuietAndFileless() throws Exception {
        String testXml = read("src/test/resources/logback-test.xml");
        assertFalse(testXml.contains("RollingFileAppender"), "测试不得产日志文件");
        assertFalse(testXml.contains("level=\"DEBUG\""), "测试不得开 DEBUG（SQL 会刷屏）");
        assertTrue(testXml.contains("<root level=\"INFO\">"), "测试保持 INFO 控制台输出");
    }
}
