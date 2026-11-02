# Spring Boot 3 + Vue 3 敏捷工程化与架构决策指南 (Scaffolding & Decision Matrix)

> **文档性质**：全栈团队敏捷研发脚手架基线与场景决策矩阵（Single Source of Truth）  
> **适用范围**：MVP 快速概念验证、B2B2C 商业闭环构建、多端协同企业级平台开发  
> **版本**：v1.0.0 (生产实践沉淀版)

---

## 一、 架构决策总览表 (The Golden Stack)

在 MVP 验证与中早期产品开发中，**开发速度、易调优性、调试确定性与免外部依赖自闭环**具有最高优先级。以下为经过多轮项目磨合确立的技术选型黄金标准：

| 架构维度 | 默认推荐标准 (Default) | 场景替代方案 (Alternative) | 核心裁决理由 (Why) |
| :--- | :--- | :--- | :--- |
| **数据库治理** | `src/main/resources/db/schema.sql` | `db/migration/*.sql` (仅在多环境生产灰度时) | 单一真实事实源，必须自带建库切库逻辑，支持开发期一键删库重启秒级自愈。 |
| **认证与权限** | **Sa-Token + Redis** | Spring Security / OAuth2 (仅海外标准生态) | 零冗余 Filter 链，原生完美支持超管/商户/买家多账号体系统治，开发提效 80%。 |
| **ORM 持久层** | **MyBatis-Plus** | Spring Data JPA (仅极度轻量且无复杂 SQL 时) | 继承 `BaseMapper` 实现极速 CRUD，同时保留 XML 编写任意复杂统计报表与调优能力。 |
| **主存储与缓存** | **MySQL 8.0 + Redis 7** | PostgreSQL + Redis (涉 GIS/向量/IoT 时) | 国内云基建与运维工具链最成熟；Redis 对两段式锁库存、防并发重付、会话管理必不可少。 |
| **前端呈现层** | **Vue 3 + Vite 双端物理分离** | Nuxt 3 (仅强 SEO 内容门户) | 管理端用 Element Plus，移动/买家端用 Tailwind CSS，工程隔离，避免依赖冲突。 |
| **外部生态解耦** | **Mock 沙箱驱动桩模式** | 真实第三方 SDK (正式上线时无缝切换) | 支付/短信/核身开发期无正式执照，必须自带 Mock 桩，保证全业务链路 100% 离线可测。 |
| **接口契约治理** | 统一 `R<T>` + 全局业务异常捕获 | 裸实体 / 默认 Spring 500 异常响应 | 杜绝原生 500 堆栈外露，前后端契约规范稳定，异常带业务语义错误码。 |

---

## 二、 遇到不同业务场景的“条件决策卡” (Scenario Decision Matrix)

在面对新需求立项时，请对照以下决策卡选择最贴合场景的组合，**严禁盲目引入未经论证的重型框架**：

```
                                  [新业务立项需求]
                                         │
                 ┌───────────────────────┴───────────────────────┐
                 ▼                                               ▼
         【核心是交易、订单、多端】                       【核心涉空间GIS、AI向量、传感器】
                 │                                               │
     ┌───────────┴───────────┐                       ┌───────────┴───────────┐
     ▼                       ▼                       ▼                       ▼
【B2B2C / SaaS / 撮合电商】 【极简活动页 / 单商户】   【高精地图 / 骑手围栏】 【AI 知识库 / RAG 物联网】
 组合：MySQL + Redis          组合：MySQL (可不用Redis) 组合：PostgreSQL + PostGIS 组合：PostgreSQL + pgvector
 鉴权：Sa-Token 多端体系      鉴权：Sa-Token 单端       缓存：Redis                 存储：TimescaleDB
 ORM ：MyBatis-Plus          ORM ：MyBatis-Plus      ORM ：MyBatis-Plus          ORM ：MyBatis-Plus / JPA
```

### 场景 1：标准 B2B2C 电商 / 多商户平台 / 社交裂变（如当前“找人代付”项目）
- **选型组合**：`Spring Boot 3 + Sa-Token + MyBatis-Plus + MySQL 8.0 + Redis 7 + Vue 3`
- **关键设计点**：
  - 账号体系利用 Sa-Token 的多账号机制分为三组：`adminId`（超管）、`merchantId`（卖家）、`buyerId`（前台消费者）；
  - 库存预扣采用 Redis 原子两段式锁（`RESERVED_LOCK` 15分钟超时），支付回调落盘转真实扣减；
  - 数据库使用 MyBatis-Plus 的物理分页与 XML 编写平台端三方对账聚合大盘。

### 场景 2：AI 智能体 / 向量搜索 / 企业 RAG 混合系统
- **选型组合**：`Spring Boot 3 + PostgreSQL (pgvector 扩展) + Redis + Vue 3`
- **裁决理由**：业务数据（用户、文档记录）与文本 Embedding 向量存入同一个 PGSQL 实例中，无需额外维护 Milvus、Pinecone 等专用向量库，直接用 SQL 执行混合相似度检索。

### 场景 3：空间地理围栏 / 骑手实时调度 / 智慧物流
- **选型组合**：`Spring Boot 3 + PostgreSQL (PostGIS) + Redis GEO + Vue 3`
- **裁决理由**：涉及多边形电子围栏判定、两点球面测距、高频空间拓扑分析，PostGIS 是数据库领域的工业级天花板。

---

## 三、 五大核心技术选型深度剖析

### 1. 数据库治理规范：单一事实源与启动自愈

#### 核心痛点
开发期反复 `ALTER TABLE` 容易导致团队成员本地环境不同步；脚本位置散乱导致删库重建时缺失 DDL；缺少建库切库语句导致客户端导入直接报错。

#### 黄金规则
1. **统一路径**：所有数据库初始化脚本一律存放在：
   ```text
   src/main/resources/db/
   ├── schema.sql    # 唯一的全量建库、建表、索引与基础枚举脚本 (Single Source of Truth)
   └── data.sql      # 演示用基础种子数据 (账号、初始分类、示例商品)
   ```
2. **强制头部语句**：`schema.sql` 最顶端必须显式包含：
   ```sql
   CREATE DATABASE IF NOT EXISTS `your_db_name` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   USE `your_db_name`;
   SET FOREIGN_KEY_CHECKS = 0;
   ```
3. **开发期无 Migration 原则**：在开发验证与 MVP 阶段，**坚决不写零碎的增量 alter 脚本**。字段改动直接在 `schema.sql` 原位修改并提交 Git。当数据库需要重置时，执行 `DROP DATABASE <name>;`，Spring Boot 启动或一键终端命令即可自愈重建。

---

### 2. 认证与安全规范：全面确立 Sa-Token

#### 为什么弃用 Spring Security + 原生 JWT？
- Spring Security 在 Spring Boot 3 下对 FilterChain、CORS、401/403 处理器配置极度繁琐；
- 原生 JWT 无法直接满足“管理员封禁商户立即下线”、“商户修改密码踢掉子账号”等实时会话控制需求，仍需在 Redis 中引入黑名单机制，失去了所谓的无状态优势；
- 多端角色冲突：一个系统中超管（PC）、商户（PC）、买家（H5）是三套完全独立的登录态，用 Spring Security 实现三套隔离极易踩坑。

#### Sa-Token 落地标准
```java
// 1. 超管登录
StpAdminUtil.login(adminUser.getId());
// 2. 商家登录
StpMerchantUtil.login(merchantUser.getId());
// 3. 买家免密/验证码登录
StpUserUtil.login(buyerUser.getId());

// 4. 注解式权限保护 (干净利落)
@SaCheckRole("SUPER_ADMIN")
@GetMapping("/api/v1/admin/merchants")
public R<List<MerchantVO>> listMerchants() { ... }
```

---

### 3. ORM 持久层规范：确立 MyBatis-Plus

#### 为什么淘汰 JPA (Hibernate)？
- **透明度低**：Hibernate 生成的 SQL 难以把控，极易产生隐蔽的 `N+1` 次查询；
- **异常高发**：`LazyInitializationException`（懒加载切断会话）在前后端数据返回中频发；
- **复杂查询笨重**：多表动态联查、财务账单聚合、报表统计用 JPA `Specification` 极其晦涩。

#### MyBatis-Plus 落地标准
1. **简单 CRUD**：直接继承 `BaseMapper<T>`，零 XML 配置：
   ```java
   // Lambda 强类型防拼写错误
   List<Product> list = productMapper.selectList(
       new LambdaQueryWrapper<Product>()
           .eq(Product::getStatus, 1)
           .like(Product::getTitle, keyword)
           .orderByDesc(Product::getCreateTime)
   );
   ```
2. **复杂 SQL**：显式写入 `src/main/resources/mapper/*Mapper.xml`，方便 EXPLAIN 性能分析和索引优化；
3. **企业级内置能力**：开启内置分页插件（`MybatisPlusInterceptor`）与自动填充插件（创建时间、修改时间自动填入）。

---

### 4. 存储层规范：MySQL 8.0 + Redis 7

- **MySQL 8.0**：行级锁、JSON 字段支持、强一致性 ACID 事务，承载核心交易流水与状态机变更。
- **Redis 7**：承载三项核心高频诉求：
  1. **分布式原子锁与两段式库存预占**（Lua 脚本 / `setIfAbsent` 带 15 分钟 TTL）；
  2. **会话与 Token 存储**（集成 Sa-Token 自动管理会话超时与踢人）；
  3. **防重防并发拦截**（买家代付并发互斥锁 `lock:peerpay:{orderNo}`）。

---

### 5. 前端工程化规范：双端独立与统一拦截

#### 目录拓扑与技术选型
```text
├── shop-admin-vue/           # 管理运营中台
│   ├── 技术栈：Vue 3 + Vite + Element Plus + Pinia + Vue Router
│   └── 场景：密集表格、筛选表单、数据看板、审批流、商品进销存
│
└── shop-storefront-vue/      # 买家前台 & 移动代付端
    ├── 技术栈：Vue 3 + Vite + Tailwind CSS / Vant + Pinia
    └── 场景：卡片流商品展示、微信/支付宝移动端自适应收银台、极简查单
```

#### 前端通信统一标准 (`request.ts`)
- 所有请求统一包装全局 BaseURL 与超时时间（默认 10000ms）；
- 请求拦截器：自动从 Pinia/LocalStorage 读取对应端的 Token 并注入 `Header: Authorization`；
- 响应拦截器：
  - HTTP 200 且业务 `code == 200`：直接解包返回 `response.data.data`；
  - HTTP 401：自动清空本地 Token，友好 Toast 提示并重定向至对应端登录页；
  - 业务非 200：全局 Element Plus / Toast 弹出后端返回的友好错误 `message`，避免静默失败。

---

## 四、 敏捷工程“三大质量红线”

### 红线 1：第三方接口必须具备【Mock 沙箱驱动桩】
- **规定**：任何依赖第三方商业资质的外部服务（微信支付、支付宝当面付、电子发票、快递 100 等），在服务端均需设计为面向接口架构：
  ```java
  public interface PaymentGateway {
      PayResult createPayOrder(PayOrderDTO dto);
      boolean verifyCallback(Map<String, String> params);
  }
  ```
- **落地**：开发阶段默认激活 `@Profile("dev")` 下的 `MockSandboxPaymentService`，前端点击“立即支付”无需真实扫码直接拉起沙箱扣款或异步回调，保障整套业务逻辑在零商业执照前提下 100% 完整跑通测试。

### 红线 2：全平台统一结果契约 `R<T>` 与业务异常体系
- 控制层严禁返回裸数据或直接向上抛出原生异常：
  ```java
  @Data
  public class R<T> {
      private Integer code;    // 200: 成功, 其他: 具体业务错误码
      private String message;  // 业务提示语
      private T data;          // 泛型载荷
      private Long timestamp;  // 时间戳
  }
  ```
- 业务逻辑校验失败一律抛出 `BusinessException(ErrorCodeEnum)`，统一由全局 `@RestControllerAdvice` 拦截并平滑转为前端友好的 JSON 响应。

### 红线 3：自愈闭环一键启动脚本
- 项目根目录下必须保留 `start_all.bat`（Windows）或 `start_all.sh`（Linux/Mac），新成员或自动化环境一键即可同时唤醒前后端全部开发服务。

---

## 五、 后续新工程快速初始化 Check-List

在新项目启动的第 1 小时内，核对以下清单：
- [ ] 数据库脚本是否放入 `src/main/resources/db/schema.sql`，顶部是否有 `CREATE DATABASE` 与 `USE`？
- [ ] 是否引入 `sa-token-spring-boot3-starter` 与 `sa-token-redis-jackson` 并完成跨域配置？
- [ ] 是否引入 `mybatis-plus-spring-boot3-starter` 并配置好分页拦截器？
- [ ] 配置文件 `application.yml` 是否启用 `spring.sql.init.mode = always` 保证自愈？
- [ ] 外部支付/通讯接口是否已搭建 Mock 沙箱驱动桩？
- [ ] 前端是否完成管理端与买家端物理分仓，并封装全局 Axios 拦截器？
