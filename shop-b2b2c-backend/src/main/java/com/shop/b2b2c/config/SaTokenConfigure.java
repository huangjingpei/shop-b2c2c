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
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            // 超级管理员路由拦截
            SaRouter.match("/api/v1/admin/**", r -> StpUtil.checkRole("SUPERADMIN"));
            // 商家工作台路由拦截 (超管与商家员工均可访问)
            SaRouter.match("/api/v1/merchant/**", r -> StpUtil.checkRoleOr("SUPERADMIN", "MERCHANT_ADMIN", "MERCHANT_STAFF"));
        })).addPathPatterns("/api/v1/**")
           .excludePathPatterns(
               "/api/v1/auth/login",
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
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
