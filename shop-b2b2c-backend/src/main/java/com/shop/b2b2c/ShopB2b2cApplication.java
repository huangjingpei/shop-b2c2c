/*
 * 文件：ShopB2b2cApplication.java
 * 用途：Spring Boot后端启动入口。
 * 维护注意：启动会装配Mapper、会话、定时任务及SQL初始化；启动前确认环境和迁移，不能用生产数据做开发启动测试。
 */
package com.shop.b2b2c;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ShopB2b2cApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopB2b2cApplication.class, args);
    }
}
