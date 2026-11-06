/*
 * 文件：FinanceApprovalConfigValidatorTest.java
 * 用途：G36 生产启动守卫回归：prod profile 下双人复核开关为 false 必须阻断启动，dev profile 允许 false。
 * 维护注意：
 *  1. 使用 AnnotationConfigApplicationContext 仅装载 FinanceApprovalConfigValidator，不启动完整应用、不连接数据库。
 *  2. 断言异常发生在容器 refresh（bean 构造期），不是业务调用期；生产阻断必须早于端口监听。
 *  3. dev 放行不代表生产可用；宽松模式的 SAME_OPERATOR 与 WARN 由 FinanceApprovalGuardTest 覆盖。
 */
package com.shop.b2b2c;

import com.shop.b2b2c.config.FinanceApprovalConfigValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceApprovalConfigValidatorTest {

    private static final String SWITCH_KEY = "shop.finance.dual-approval-required";

    private AnnotationConfigApplicationContext bootContext(String profile, String switchValue) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("g36-test", Map.of(SWITCH_KEY, switchValue)));
        context.register(FinanceApprovalConfigValidator.class);
        return context;
    }

    @Test
    void prodProfileWithSwitchOffBlocksStartup() {
        try (AnnotationConfigApplicationContext context = bootContext("prod", "false")) {
            BeanCreationException failure = assertThrows(BeanCreationException.class, context::refresh);
            Throwable cause = failure.getMostSpecificCause();
            assertTrue(cause instanceof IllegalStateException, "应以配置校验异常阻断启动");
            assertTrue(cause.getMessage().contains("生产环境必须开启双人复核"),
                    "阻断原因必须明确指向双人复核开关");
        }
    }

    @Test
    void prodProfileWithSwitchOnStartsNormally() {
        try (AnnotationConfigApplicationContext context = bootContext("prod", "true")) {
            assertDoesNotThrow(context::refresh);
        }
    }

    @Test
    void devProfileStillAllowsSwitchOff() {
        try (AnnotationConfigApplicationContext context = bootContext("dev", "false")) {
            assertDoesNotThrow(context::refresh);
        }
    }
}
