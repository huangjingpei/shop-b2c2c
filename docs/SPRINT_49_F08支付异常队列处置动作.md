<!--
文件：SPRINT_49_F08支付异常队列处置动作.md
用途：记录主台账 F08 中“支付异常队列人工处置动作”的表结构、接口契约、只审计不改资金边界、可重跑命令与未验证项。
维护注意：本文只描述代码代理已完成的实现与已执行命令的结果，不代表真实退款联调或生产 MySQL 迁移已验证；处置记录不是资金动作。
-->
# Sprint 49：F08 支付异常队列处置动作

- 日期：2026-11-08
- 主台账：F08（P1，`部分修复（待人工验证）`）
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：代理已执行后端全量测试与管理端生产构建；全量 1124 项、0 失败、1 项跳过，新增用例全部通过；仍待人工验收

## 1. 背景与缺口

Sprint 35 交付了超管只读队列 `GET /api/v1/admin/settlement/payment-exceptions`，Sprint 48 补了关单回执重试。遗留缺口是：人工核对完迟到支付、重复付款、金额/商户不符等异常后，系统里没有结论留痕，队列也无法区分“未核对”和“已核对”。

本轮只补“人工处置结论的记录与展示”，不做自动退款、不做核销账本联动：

1. 新增 `payment_exception_disposition` 表，`event_id` 唯一，只增不改；
2. 新增超管处置接口，白名单结论 + 必填说明；
3. 队列投影 LEFT JOIN 处置记录，已处置事件仍保留并展示处置信息。

同时按已确认的方案 B 调整 Sprint 48 关单重试默认参数：最多 `6` 次、扫描窗口 `24` 小时，间隔仍 `10` 分钟、单轮仍 `100` 条。

## 2. 本轮实现

### 2.1 处置表

新增 `payment_exception_disposition`（`schema.sql` 基线第 40 表 + `V22__f08_payment_exception_disposition.sql` 幂等迁移 + H2 夹具同步）：

| 列 | 说明 |
| --- | --- |
| `event_id` | `payment_event.id`；唯一约束，同一事件只允许一条处置记录 |
| `order_sn` | 订单号快照，事件归档后仍可定位；不设外键 |
| `action` | `REFUND_CONFIRMED` / `DUPLICATE_RESOLVED` / `CHANNEL_VERIFIED` / `NO_ACTION_REQUIRED` |
| `note` | 人工核对依据与结论，单行化后 2-200 字，不含凭据与报文正文 |
| `operator_id` | 处置操作的平台账号 ID 快照 |
| `created_at` | 处置时间 |

索引：`uk_payment_disposition_event(event_id)`、`idx_payment_disposition_action(action, created_at)`。只增不改：Mapper 不提供更新/删除业务方法。

### 2.2 处置接口

`POST /api/v1/admin/settlement/payment-exceptions/{eventId}/disposition`（`SUPERADMIN`）：

请求体：

```json
{ "action": "REFUND_CONFIRMED", "note": "已走渠道退款流程，渠道流水 XXX 已核对" }
```

校验与结果：

- `eventId <= 0` → 400；
- 事件不存在 → 404；
- 事件状态不在异常队列白名单（`LATE_PAYMENT`、`DUPLICATE_PAYMENT`、`AMOUNT_MISMATCH`、`CURRENCY_MISMATCH`、`MERCHANT_MISMATCH`、`MANUAL_REVIEW`、`FULFILLMENT_FAILED`）→ 400；
- `action` 不在白名单 → 400；大小写与首尾空格会被规范化；
- `note` 单行化后不足 2 字或超过 200 字 → 400；
- 已有处置记录 → 409；并发重复提交由唯一键兜底，同样返回 409；
- 成功返回 200 与处置记录投影。

### 2.3 只审计不改资金

处置流程只执行 `selectById(payment_event)` 与 `insert(payment_exception_disposition)`：

- 不改写 `payment_event.event_status`、`funds_received`、`raw_payload`；
- 不改订单状态、退款账本、分账或商户余额；
- 不调用渠道退款/关单接口；
- 日志只记 `eventId` / `action` / `operatorId`，不记录说明全文与报文。

退款/补偿仍须走渠道或既有退款流程；处置接口不得被当作资金操作。

### 2.4 队列投影

`PaymentEventMapper.findPaymentExceptions` 改为 `payment_event e LEFT JOIN payment_exception_disposition d ON d.event_id = e.id`，在原有白名单字段后追加：

- `dispositionId`、`dispositionAction`、`dispositionNote`、`disposedBy`、`disposedAt`。

已处置事件仍保留在队列中（不因处置而隐藏）；仍禁止回传 `raw_payload`、`payer_id`、渠道商户号。

### 2.5 管理端页面

`shop-admin-vue/src/views/admin/Finance.vue` 新增“支付异常队列”卡片与“记录支付异常处置”弹窗：

- 列表展示订单号、店铺、渠道、金额、事件状态、资金往来、异常说明；
- 已处置行展示结论标签、说明、操作人与时间；未处置行提供“记录处置”；
- 弹窗固定提示“只记录结论，不改资金/订单/退款账本”；结论必选、说明 2-200 字；
- 加载失败保留上次数据，不把旧队列清空成“已无异常”。

### 2.6 方案 B 参数同步

`PaymentCloseRetryJob` 默认值改为 `payment.close-retry.max-attempts=6`、`payment.close-retry.window-hours=24`；`delay-ms=600000`、`interval-minutes=10`、单轮 `100` 条不变。`SPRINT_48` 参数表同步更新。

## 3. 用例

- 新增 `PaymentExceptionDispositionServiceTest` 8 项：事件不存在 404、非异常状态 400、缺请求体/缺结论 400、结论越界 400、说明长度/空白 400、重复处置 409、并发唯一键 409、成功落库并规范化说明且不调用事件更新。
- `Sprint1SecurityIntegrationTest` 新增 2 项：
  - 未登录 401、商家账号 403，且不落处置记录；
  - 超管非法结论/短说明/非异常事件均 400 且不落库；合法处置 200 并落 `action/note/operator_id`；重复 409 不产生第二行；`event_status`、`funds_received`、`raw_payload` 保持不变；GET 队列含处置字段且仍不含 `raw-body`/`payer-secret`。
- fixtures 清理加入 `payment_exception_disposition`。

## 4. 可重跑命令

```bash
mvn -f shop-b2b2c-backend/pom.xml test
# 定向
mvn -f shop-b2b2c-backend/pom.xml test -Dtest="PaymentExceptionDispositionServiceTest,PaymentCloseRetryServiceTest,Sprint1SecurityIntegrationTest"
# 管理端生产构建（涉及 Finance.vue）
npm --prefix shop-admin-vue run build
```

预期：后端全量通过；H2 用例证明唯一键、状态与资金标记不变、队列 LEFT JOIN 投影生效；管理端构建通过。`-DforkCount=0` 会让既有静态扫描用例的相对路径解析失败，手动验证请勿带该参数。

本机执行结果（2026-11-08）：

- `mvn -f shop-b2b2c-backend/pom.xml test`：最终 1124 项、0 失败、1 项跳过，BUILD SUCCESS；新增 8 项单元与 2 项 H2 用例均通过。
- 首轮出现 2 项既有 poster 断言失败（`r26SlowPosterRenderingDoesNotBlockConcurrentPaymentOrReservationRelease`、`r26PosterIdempotencyDeduplicationAndZeroDbLockInDetail`）：断言严格匹配 `image/png`，而全局 `server.servlet.encoding.force=true` 使 MockMvc 返回 `image/png;charset=UTF-8`。已改为 `contentTypeCompatibleWith(MediaType.IMAGE_PNG)`，未改生产代码，定向复跑与全量复跑均通过。
- `npm --prefix shop-admin-vue run build`：通过（21.68s，仅有 chunk 体积提示）。
- 首次测试因新增集成用例少一个右括号编译失败，已补齐并重跑；该修复随后续提交入库。

## 5. 未验证边界

- 前端仅完成生产构建，未做浏览器联调；弹窗交互、长说明换行、队列分页（页面当前只取默认前 50 条）未验证。
- 真实 MySQL 8 上 `V22` 迁移、唯一键与 LEFT JOIN 分页未演练；H2 通过不代表 MySQL 等价。
- 处置后不触发退款/核销、不支持撤销或追加备注；如需多轮备注需改表结构与唯一约束。
- 迟到支付自动退款、SKIPPED 补关单（方案 B 已确认不做）、多实例跨实例抢占仍未实现。

## 6. 回滚

- 回滚代码：删除处置实体/Mapper/Service/DTO 与控制器 POST，恢复 `findPaymentExceptions` 原查询，移除 Finance.vue 卡片与弹窗及相关用例。
- 数据库：`payment_exception_disposition` 为独立新表，无外键引用；回滚应用后可保留只读留存或由运维按策略删除，不影响既有业务表与 `payment_event`。
