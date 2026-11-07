<!-- 文件：SPRINT_28_商品违规处置.md -->
<!-- 用途：Sprint 28（F11 子项：平台商品违规下架/审核恢复）交付、验证与边界说明。 -->
<!-- 维护注意：验收由用户管理；本文件不替代台账状态，真实 MySQL 与浏览器交互未验证。 -->

# Sprint 28：F11 平台商品违规处置（违规下架/审核恢复）

日期：2026-11-07　状态：已实现·待人工验证

## 1. 范围与归属

- 主台账：**F11（部分修复→仍部分修复）**。原清单"违规审核……等仍待实现"中的"违规下架/审核"，本轮回补。
- 台账 F11 剩余：**详情图文维护与售罄规则**仍未实现，F11 保持部分修复。
- 边界：平台处置（违规下架/审核恢复）与商家自助上下架完全分离；不触碰订单履约、库存、价格；已付款订单照常履约。

## 2. 数据模型

- `product` 新增两列（唯一 schema 入口 + V17 幂等迁移，既有列不变，无需删库）：
  - `violation_reason VARCHAR(255)`：最近一次违规处置原因（审核恢复后清空）。
  - `violated_at DATETIME`：最近一次违规下架时间（审核恢复后清空）。
- 新表 `product_violation_audit`（append-only）：`id, product_id, merchant_id(冗余), action(TAKEDOWN/RESTORE), from_status, to_status, reason(NOT NULL), operator_id(超管 sys_user.id，无外键), created_at`；索引 `product_id+created_at`、`merchant_id+created_at`。
- V17 迁移（`db/migrations/V17__f11_product_violation_moderation.sql`）用 information_schema 判断幂等，保留数据的环境可重复执行；**真实 MySQL 未执行，待人工验证**。

## 3. 接口与语义

| 接口 | 角色 | 说明 |
| --- | --- | --- |
| `GET /api/v1/admin/products` | SUPERADMIN | 全平台商品分页（merchantId/status/keyword/page≤100 size），join 店铺名 |
| `POST /api/v1/admin/products/{id}/violation-takedown` | SUPERADMIN | 违规下架：0/1 → 2，写原因与时间，同事务落审计 |
| `POST /api/v1/admin/products/{id}/restore` | SUPERADMIN | 审核恢复：2 → 处置前状态，清空快照，同事务落审计 |
| `GET /api/v1/admin/products/{id}/violation-audits` | SUPERADMIN | 处置记录倒序（limit 1..200） |

- 违规下架 `reason` 必填 trim 后 1..255（400）；恢复 `note` 可选 ≤255，空值写默认"平台审核通过，恢复商品"。
- 状态机约束：已 2 → 409；恢复非 2 → 409；商品不存在 → 404。
- 状态更新走**带条件 CAS**（`WHERE status=#{fromStatus}` / `WHERE status=2`），0 行 → 409；审计与状态更新同一事务。
- **恢复目标 = 最近一次 TAKEDOWN 的 `from_status`**（撤销处置回到原状态），无历史记录兜底 0（已下架）。
- 既有约束联动（Sprint 9 已建）：商家 `PATCH /{id}/status` 对 status=2 返回 403；公开列表按 status=1 过滤、公开详情 `requirePurchasable` 拒绝 —— 本轮以集成测试固化联动，未改动这些既有代码路径。

## 4. 代理自行细化（待业务确认，非用户已确认规则）

1. 违规下架允许从 status **0(已下架) 或 1(在售)** 发起（对 0 发起可防止商家自行重新上架）。
2. 平台处置**不套用商家下架的 HELD 预占闸门**：违规处置须即时生效，进行中预占到期/核销自然结束，已付款订单照常履约。
3. 商家在 status=2 期间**仍可编辑资料**以便整改（编辑接口不禁 status=2），平台审核后才可恢复。
4. RESTORE 恢复目标取最近一次 TAKEDOWN 的 `from_status`，无记录兜底 0。

## 5. 前端（shop-admin-vue）

- 新页面 `views/admin/ProductModeration.vue`（路由 `/admin/product-moderation`，meta SUPERADMIN；AdminLayout 超管菜单在"统一商品类目树"后）：店铺ID/状态/标题关键词筛选、分页表格、违规下架对话框（必填原因）、恢复对话框（可选备注）、处置记录抽屉（时间线）。
- 新规则模块 `src/utils/productModeration.mjs`：takedownPayload/restorePayload（trim、长度口径与后端一致）、canTakedown(0|1)/canRestore(2)、动作文案映射。
- 商家商品列表（merchant/Products.vue）status=2 行用 tooltip 展示处置原因与时间（MerchantProductView 直接序列化 Product，自动带出两列）。

## 6. 测试

| 层 | 用例 | 覆盖 |
| --- | --- | --- |
| ProductModerationServiceTest（新增，13 方法） | 下架 0/1 成功+审计内容断言、2/未知状态 409、reason 空/超长 400、不存在 404、CAS 0行 409；恢复回 from_status/无记录兜底 0/非2 409/CAS 409/note 默认与超长；audits 404+limit 边界；list 非法筛选 400 与关键词 trim | 全 mock 契约 |
| Sprint1SecurityIntegrationTest（新增 2 用例，共 222 项） | 全链路：下架→落库 status=2+原因→公开列表消失/详情 400→商家 PATCH 403→审计 1 行→恢复回 1+清空快照→审计 2 行+记录接口倒序；角色边界：匿名 401/商家 403/超管 200、列表筛选、空与超长原因 400、404、重复下架 409、非违规恢复 409 | 真实 SQL+H2 |
| shared/productModeration.test.mjs（新增，8 用例） | payload trim/必填/255 边界与超长抛错、空 note→{}、canTakedown/canRestore 状态映射、动作文案兜底 | 纯 Node |

## 7. 验证（代理执行）

- `mvn -f shop-b2b2c-backend/pom.xml test -Dtest="ProductModerationServiceTest,Sprint1SecurityIntegrationTest"`：13 + 222 项通过，BUILD SUCCESS
- 全量 `mvn -f shop-b2b2c-backend/pom.xml test`：**1020 项通过（1 项既有跳过），BUILD SUCCESS**
- `node --test shared/productModeration.test.mjs shared/productEditing.test.mjs`：28 项通过
- `npm --prefix shop-admin-vue run build`：通过（✓ built in 8.71s，含 ProductModeration chunk 7.99 kB）

**未验证项**：真实 MySQL（V17 迁移、外键、REPEATABLE READ 并发 CAS）、浏览器交互（筛选/对话框/抽屉）。不标验收通过。

## 8. 人工验证步骤

1. 后端：`mvn -f shop-b2b2c-backend/pom.xml test`，预期全部通过（含既有 1 项跳过）。
2. 前端规则：`node --test shared/productModeration.test.mjs`，预期 8 项通过。
3. 前端构建：`npm --prefix shop-admin-vue run build`，预期成功。
4. 真实 MySQL：执行 V17 迁移（或重建 schema），确认 `product` 两列与 `product_violation_audit` 表及两索引存在。
5. 浏览器：超管"商品治理"页——按店铺/状态/关键词筛选；对在售商品违规下架（填原因）后商城列表/详情不可见，商家列表显示处置原因 tooltip；商家尝试上架被拒；超管恢复后商品回到原状态，处置记录时间线两行。
