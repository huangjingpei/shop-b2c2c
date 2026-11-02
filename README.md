# Shop B2B2C 电商平台 (Spring Boot 3 + Vue 3)

> **技术栈**：Spring Boot 3.2.5 (JDK 17) + Vue 3 (Vite + Element Plus + Tailwind CSS) + MySQL 8.0 + Redis 7  
> **商业模型**：对标 16688 模式（数字权益自动秒发 / 实体电商 / 小微自然人免执照开店 / 微信电商收付通与支付宝直付通二级分账零二清 / 免登录极速查单）

---

## 工程目录结构

```
E:\shop-b2b2c\
├── start_all.bat                # 一键启动全套三个前后端服务
│
├── shop-b2b2c-backend/          # 【后端服务】Spring Boot 3.2 + JDK 17 (端口: 8082)
│   ├── src/main/java/com/shop/b2b2c/
│   │   ├── config/              # Security, JWT, Redis, CORS
│   │   ├── controller/          # Auth, Merchant, Category, Product, Order
│   │   ├── entity/              # JPA 领域模型 (商户、商品、SKU、两段式代付订单等)
│   │   └── repository/          # Spring Data JPA 仓储接口
│   └── src/main/resources/
│       ├── application.yml      # Spring Boot 配置 (含 spring.sql.init 自动初始化)
│       └── schema.sql           # 【单一权威建表与全量种子数据】(应用启动自动自愈)
│
├── shop-admin-vue/              # 【管理中台】Vue 3 + Element Plus (端口: 3001)
│   ├── src/views/layout/        # 平台超管 & 商家工作台双重视角切换框架
│   ├── src/views/admin/         # 平台超管视角 (交易大盘、商户审批进件、统一类目、订单监察)
│   └── src/views/merchant/      # 商家工作台视角 (经营看板、店铺资料与结算卡、商品SKU、16688虚拟卡密库、发货)
│
└── shop-storefront-vue/         # 【买家商城】Vue 3 + Tailwind CSS (端口: 3002)
    └── src/views/
        ├── Home.vue             # 16688 高密度货源大厅 (卡片流、现货秒发)
        ├── Store.vue            # 商户独立店铺主页 (公告、全品陈列)
        ├── ProductDetail.vue    # 详情与单店轻量收银台 (立即支付秒发卡密)
        └── QuickQuery.vue       # 16688 招牌功能：免登录凭订单号/手机极速查单
```

---

## 快速启动方式

### 方式 1：双击一键启动脚本
直接双击运行 `E:\shop-b2b2c\start_all.bat`。

### 方式 2：手动分步启动
1. **启动后端**：
   ```bash
   cd E:\shop-b2b2c\shop-b2b2c-backend
   mvn spring-boot:run
   ```
2. **启动管理中台 (Element Plus)**：
   ```bash
   cd E:\shop-b2b2c\shop-admin-vue
   npm run dev
   # 访问地址: http://localhost:3001
   ```
3. **启动买家商城 (16688 风格)**：
   ```bash
   cd E:\shop-b2b2c\shop-storefront-vue
   npm run dev
   # 访问地址: http://localhost:3002
   ```

---

## 预设体验测试账号

| 角色类型 | 登录账号 | 登录密码 | 默认视角与权限 |
| :--- | :--- | :--- | :--- |
| **平台超级管理员** | `superadmin` | `admin123` | 全局治理视角，支持一键在【平台视角】与【商家视角】自由切换 |
| **演示商家店长** | `seller01` | `admin123` | 极速数卡旗舰店，专属商家工作台（商品、卡密库、发货） |
| **普通终端买家** | 无需登录 | 任意测试手机号 | 前台自选商品直购，16688 免登录 3 秒查单提取卡密 |
