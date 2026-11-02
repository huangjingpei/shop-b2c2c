<!--
文件：B2B2C_MVP_PEER_PAY_DELIVERY_REPORT.md
用途：找人代付MVP的历史交付与演示验收记录。
维护注意：此处描述包含规划或历史验收记录，不代表当前代码已全部实现；现状以问题台账及最新Sprint记录为准，旧截图/链接不能替代可重复测试。
-->
# B2B2C 找人代付与两段式库存锁控电商平台 · 全闭环交付报告

> **项目名称**：Shop B2B2C Multi-Tenant E-Commerce Platform (找人代付裂变版)  
> **核心引擎**：找人代付 (Peer Pay) + 卖家代客推单 (Push Pay) + 两段式原子预占库存锁 (Two-Stage Reservation)  
> **商业合规支柱**：商户 SaaS 月度技术服务费 (前期 0 元扶持) + 实物与线上服务双轨履约  
> **代码仓库路径**：`E:\shop-b2b2c`  
> **交付状态**：**Step 1 ~ Step 5 全部 100% 落地并通过自动化端到端验证**

---

## 一、系统整体演进与核心业务闭环

平台已完整建立起**平台超管 (Superadmin)、入驻商户 (Merchant)、前台买家 (Buyer) 与 代付人 (Guest Payer)** 四位一体的闭环体系：

```mermaid
flowchart TD
    subgraph S1["前台买家 / 店铺导购 (Storefront & H5)"]
        A1["选购商品 / 挑选规格变体"] --> A2{"支付决策通道"}
        A2 -->|"通道 1: 自主支付"| A3["立即下单支付"]
        A2 -->|"通道 2: ★ 找人代付"| B1["输入个性求助留言<br/>(快捷热词气泡)"]
        B1 --> B2["Stage 1: 意向生成<br/>(防刷! 不扣减物理库存)"]
        B2 --> B3["生成微信短链 + 9:16 防封高清海报"]
    end

    subgraph S2["代付人极简免密收银台 (/peer-pay/:token)"]
        B3 -->|"微信扫码 / 打开短链"| C1["极简免密收银台<br/>(隐私脱敏 138****5678)"]
        C1 --> C2["点击【立即替TA买单】"]
        C2 --> C3["Stage 2: Redis 原子预占库存<br/>(独占倒计时 15 分钟)"]
        C3 -->|"并发拦截"| C3_BLOCK["后续付款人被互斥阻断"]
        C3 --> C4["沙箱 / 微信 / 支付宝支付结算"]
        C4 --> C5["真实扣减物理库存<br/>释放预占锁"]
        C5 --> C6["支付成功展示交付凭证<br/>+【我也想开店】引流卡片"]
    end

    subgraph S3["商户工作台 (Merchant Console)"]
        M1["★【代客推单】"] --> M2["自主协商改价 + 定制推单账单"] --> B3
        C5 --> M3{"商品类型判定"}
        M3 -->|"实物商品"| M4["录入快递运单打单发货"]
        M3 -->|"虚拟卡密"| M5["系统自动秒发卡密交付"]
        M3 -->|"线上定制服务"| M6["卖家录入交付凭据<br/>(锁定不可极速退款)"]
    end

    subgraph S4["平台超管治理 (Super Admin)"]
        ADM1["入驻商户治理与进件"] --> ADM2["SaaS 月度技术服务费配置<br/>(月租、免收截止日、豁免标记)"]
        ADM3["全平台对账大盘"] --> ADM4["穿透审计: <卖家, 买家, 付款人>"]
    end
```

---

## 二、端到端真实交互走查与运行实录

全部界面均已通过无头浏览器与真实测试验证，实录截图存档如下：

### 1. 买家端与代付人收银台闭环

````carousel
![1. 商品详情与【找人代付】按钮](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/storefront-product-detail.png)
<!-- slide -->
![2. 找人代付求助弹窗与个性热词](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/storefront-peerpay-modal.png)
<!-- slide -->
![3. 生成专属短链与 9:16 防封买单海报](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/storefront-peerpay-generated.png)
<!-- slide -->
![4. 代付人免密免登录极简收银台](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/peerpay-cashier-init.png)
<!-- slide -->
![5. 调起支付触发两段式 15 分钟独占倒计时](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/peerpay-cashier-locked.png)
<!-- slide -->
![6. 代付成功交付与【我也想开店】引流位](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/peerpay-cashier-success.png)
````

---

### 2. 商家工作台与超级管理端闭环

````carousel
![1. 商家订单履约与三方角色对账台账](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/merchant-orders-ledger.png)
<!-- slide -->
![2. 商家【★ 代客推单】协议改价弹窗](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/merchant-pushpay-dialog.png)
<!-- slide -->
![3. 商家推单生成成功与带参二维码海报](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/merchant-pushpay-result.png)
<!-- slide -->
![4. 超管商户治理与 SaaS 月服务费列表](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/admin-merchants-service-fee.png)
<!-- slide -->
![5. 超管配置商户月租服务费与免收期](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/admin-service-fee-dialog.png)
<!-- slide -->
![6. 全平台交易穿透监管与分账大盘](C:/Users/Administrator/.gemini/antigravity/brain/78913bdb-9ae4-4fed-8316-5c9f3d703035/admin-orders-audit-board.png)
````

---

## 三、核心技术实现与工程架构

### 1. 行业推荐两段式预占库存引擎 (Two-Stage Reservation)
- **阶段一 (Token 生成)**：
  - 代码位置：[`PeerPayServiceImpl.java`](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L40-L100)
  - 仅校验物理库存充盈度，`peerStatus = 'INIT'`，不占真实库存。
- **阶段二 (点击买单原子预占锁)**：
  - 代码位置：[`PeerPayServiceImpl.java`](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L230-L280)
  - 通过 Redis `setIfAbsent(peer:lock:ORD..., LOCKED, 15, TimeUnit.MINUTES)` 实现原子互斥。
  - `peerStatus = 'RESERVED_LOCK'`，锁定 15 分钟，其他并发付款人调起时被友好拦截。
- **阶段三 (结算真实扣减与超时自愈)**：
  - 支付成功调用 `notifyPaymentSuccess`，执行 `sku.stockQuantity -= quantity`。
  - 若付款人超时未支付或主动关闭页面，调用 `cancelPreLock` 或由详情接口自愈释放。

### 2. 9:16 微信裂变防封买单海报生成器
- 代码位置：[`PosterUtils.java`](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/PosterUtils.java)
- 基于 `ZXing` 高质量二维码编码 + Java AWT 高阶抗锯齿渲染。
- 输出标准 750×1334 像素 9:16 高清电商质感海报，包含：店铺名称徽标、商品主图与标题、代付实付金额、买家好友留言气泡、带参收银台二维码及合规安全背书。

### 3. 三方不可篡改对账台账
- 数据表：[`trade_order`](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/schema.sql#L125-L160)
- 每一笔交易同时固化：
  - `merchant_id`: 收款商户
  - `buyer_contact`: 下单买家联系方式 (前台对外脱敏展示为 `138****5678`)
  - `peer_payer_id`: 实际代付付款人标识 (OpenID/流水号)
  - `peer_pay_type`: `0-自主支付` / `1-买家求助代付` / `2-卖家代客推单`
  - `service_voucher`: 线上服务交付凭据

---

## 四、核心 API 接口清单

| 模块 | 方法 | 路径 | 权限 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| **代付核心** | `POST` | `/api/v1/peer-pay/buyer/create` | 公开 | 买家发起求助代付 (生成短链与海报) |
| **代付核心** | `GET` | `/api/v1/peer-pay/detail/{token}` | 公开 | 免密查询代付详情 (含自愈到期检查与隐私脱敏) |
| **两段式锁** | `POST` | `/api/v1/peer-pay/pre-lock/{token}` | 公开 | 调起收银台原子预占库存 (锁 15 分钟) |
| **两段式锁** | `POST` | `/api/v1/peer-pay/cancel-lock/{token}` | 公开 | 付款人主动离开收银台释放预占库存 |
| **Mock沙箱**| `POST` | `/api/v1/peer-pay/mock-pay/{token}` | 公开 | 沙箱模拟支付回调 (真实扣库存、三方对账) |
| **逆向引流** | `GET` | `/api/v1/peer-pay/success-ad` | 公开 | 获取支付成功页【我也想开店】引流广告位 |
| **商家工作台**| `POST` | `/api/v1/merchant/orders/push-pay` | 商家 | ★ 卖家代客推单 (自主协议改价与专属海报) |
| **双轨履约** | `POST` | `/api/v1/merchant/orders/{id}/fulfill-virtual` | 商家 | 录入线上服务交付凭据 (锁定不可极速退款) |
| **超管治理** | `PUT` | `/api/v1/admin/merchants/{id}/service-fee` | 超管 | 配置商户月度技术服务费、免收截止日与豁免 |
| **超管治理** | `PUT` | `/api/v1/admin/merchants/{id}/payment-channel` | 超管 | 调配商户结算模式 (A-收付通 / B-直收码 / C-沙箱) |

---

## 五、服务快速启动与维护指南

### 1. 端口分布说明
- **8082 端口**：Spring Boot 3 后端服务 ([`shop-b2b2c-backend`](file:///E:/shop-b2b2c/shop-b2b2c-backend))
- **3001 端口**：Vue 3 综合运营中台 ([`shop-admin-vue`](file:///E:/shop-b2b2c/shop-admin-vue))
- **3002 端口**：Vue 3 买家商城与代付收银台 ([`shop-storefront-vue`](file:///E:/shop-b2b2c/shop-storefront-vue))
- **3306 端口**：MySQL 8.0 数据库 (`shop_b2b2c`)
- **6379 端口**：Redis 7 缓存与分布式预占锁

### 2. 数据库单一权威源运维原则 (Single Source of Truth)
按照指令约定，开发期无需维护繁琐的 `ALTER TABLE` 增量脚本，全量结构与多商户全品类真实演示数据统一收敛在：
- [`shop-b2b2c-backend/src/main/resources/schema.sql`](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/schema.sql)

如需重置数据，只需在 MySQL 执行：
```sql
DROP DATABASE shop_b2b2c;
```
重启 Spring Boot 即可在 15 秒内自动自愈创建全套数据库、表结构与演示数据。
