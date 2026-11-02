/*
 * 文件：MybatisPlusConfig.java
 * 用途：注册MyBatis-Plus分页插件及Mapper扫描。
 * 维护注意：插件注册不代表所有查询已分页；新Mapper应处于扫描范围，SQL分页及MySQL兼容性仍须测试。
 */
package com.shop.b2b2c.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 企业级配置 (自动注入分页插件与 Mapper 扫描)
 */
@Configuration
@MapperScan("com.shop.b2b2c.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
