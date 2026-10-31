@echo off
chcp 65001 >nul
echo ===================================================
echo   B2B2C 电商平台一键服务启动器 (Spring Boot 3 + Vue 3)
echo ===================================================

echo [1/3] 正在后台启动 Spring Boot 3 服务端 (端口: 8082)...
start "B2B2C 后端服务 (8082)" cmd /k "cd /d E:\shop-b2b2c\shop-b2b2c-backend && mvn spring-boot:run"

timeout /t 5 >nul

echo [2/3] 正在启动 Element Plus 管理中台 (端口: 3001)...
start "B2B2C 管理端 (3001)" cmd /k "cd /d E:\shop-b2b2c\shop-admin-vue && npm run dev"

echo [3/3] 正在启动 16688 风格买家端商城 (端口: 3002)...
start "16688 买家商城 (3002)" cmd /k "cd /d E:\shop-b2b2c\shop-storefront-vue && npm run dev"

echo ===================================================
echo   所有服务已成功启动！
echo   - 平台超管 & 商家工作台: http://localhost:3001
echo   - 16688 买家前台商城:   http://localhost:3002
echo   - 后端 OpenAPI/Swagger: http://localhost:8082/swagger-ui.html
echo ===================================================
pause
