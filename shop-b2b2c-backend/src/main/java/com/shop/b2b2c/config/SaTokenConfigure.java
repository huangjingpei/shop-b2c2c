/*
 * 文件：SaTokenConfigure.java
 * 用途：配置API角色拦截、公开存活/就绪及业务路径与CORS。
 * 维护注意：公开路由不得加入可信支付落账；受保护请求先核验数据库中的账号和授权状态；角色检查不替代对象归属校验。跨域仅允许配置的精确来源及必要请求头，Bearer授权不开放跨域Cookie。
 */
package com.shop.b2b2c.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 权限与多端路由拦截器及跨域配置 (Golden Architecture)
 */
@Configuration
@lombok.RequiredArgsConstructor
public class SaTokenConfigure implements WebMvcConfigurer {
    private final com.shop.b2b2c.service.AuthSessionService sessions;
    private final CorsOrigins corsOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            // 浏览器预检没有Bearer令牌；来源、方法和请求头仍由Spring CORS处理器校验。
            if ("OPTIONS".equals(cn.dev33.satoken.context.SaHolder.getRequest().getMethod())) return;
            // 超级管理员路由拦截
            SaRouter.match("/api/v1/admin/**", r -> { sessions.requireCurrentUser(); StpUtil.checkRole("SUPERADMIN"); });
            // 商家工作台路由拦截 (超管与商家员工均可访问)
            SaRouter.match("/api/v1/merchant/**", r -> { sessions.requireCurrentUser(); StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN", "MERCHANT_STAFF"); });
        })).addPathPatterns("/api/v1/**")
           .excludePathPatterns(
               "/api/v1/auth/login",
               "/api/v1/auth/health",
               "/api/v1/auth/ready",
               "/api/v1/public/**",
               "/api/v1/peer-pay/**",
               "/swagger-ui/**",
               "/v3/api-docs/**",
               "/swagger-ui.html"
           );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(corsOrigins.allowedOrigins())
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("Authorization", "Content-Type", "Idempotency-Key")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
