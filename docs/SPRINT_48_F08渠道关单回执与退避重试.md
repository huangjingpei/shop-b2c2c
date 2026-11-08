<!--
文件：SPRINT_48_F08渠道关单回执与退避重试.md
用途：记录主台账 F08 中“渠道关单结果回执与重试”缺口的表结构、重试口径、默认参数、可重跑命令与未验证边界。
维护注意：本文只描述代码代理已完成的实现与待用户执行的命令，不代表真实渠道关单联调、生产 MySQL 迁移或多实例部署已验证。
-->
# Sprint 48：F08 渠道关单回执与退避重试

- 日期：2026-11-08
- 主台账：F08（P1，`部分修复（待人工验证）`）
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：待人工验证（本轮未执行编译、测试或生产构建）

## 1. 背景与缺口

Sprint 35 已实现取消/超时后的 `closePaymentBestEffort`：渠道未配置、账户未审核或调用失败时只记 WARN，不阻断本地关单。残留缺口是“关单没有回执、失败后不会重试”，只能靠人工看日志核对渠道侧交易。

本轮补两点：

1. 每次关单尝试落 `payment_close_receipt` 回执，区分 `SUCCESS` / `FAILED` / `SKIPPED`；
2. `PaymentCloseRetryJob` 定期扫描最新回执为 `FAILED` 且已关闭的订单，按次数、退避与窗口限制重试。

## 2. 本轮实现

### 2.1 回执表

新增 `payment_close_receipt`（`schema.sql` 基线第 39 表 + `V21__f08_payment_close_receipt.sql` 幂等迁移 + H2 夹具同步）：

| 列 | 说明 |
| --- | --- |
| `order_sn` / `merchant_id` | 订单号与商家 ID 快照；不设外键，订单归档后回执仍保留 |
| `channel` | 关单渠道，`WECHAT`/`ALIPAY` |
| `attempt_no` | 同订单+渠道的尝试序号，插入时按当前最大值递增 |
| `result` | `SUCCESS` 调用成功 / `FAILED` 调用失败可重试 / `SKIPPED` 账户未就绪或适配器未启用 |
| `reason` | 失败/跳过摘要，单行化并截断 200 字，不含凭据与报文正文 |
| `attempted_at` / `created_at` | 尝试时间与写入时间 |

索引：`(order_sn, channel, attempt_no)`、`(result, attempted_at)`。只增不改：`PaymentCloseReceiptMapper` 不提供 update/delete，重试结果以新行表达。

### 2.2 关单回执写入

`PaymentGatewayService` 的两条关单路径都写回执：

- `closePaymentBestEffort`（订单关闭后自动调用）：账户未登记/未审核 → `SKIPPED`；适配器未启用 → `SKIPPED`；调用异常 → `FAILED`；成功 → `SUCCESS`。仍不阻断本地关单。
- `closePayment(merchantId, orderSn)`（商家主动关单，仅 UNPAID）：成功写 `SUCCESS`，异常先写 `FAILED` 再原样抛出。

回执写入失败只记 WARN，不影响关单结果与订单状态。

### 2.3 重试口径

`PaymentCloseRetryService.retryPending(maxAttempts, minInterval, window, limit)`：

- 候选来自 `PaymentCloseReceiptMapper.findRetryableOrderSn`：最新一条回执为 `FAILED`（同订单+渠道按 `attempted_at`、`id` 取最新）、`attempt_no < maxAttempts`、`attempted_at` 在 `[now-window, now-minInterval]` 内；
- 逐条二次校验订单存在且 `order_status` 为 `CLOSED`/`CANCELLED`，否则跳过；
- 单条异常只记 WARN，不阻断后续候选；
- 重试仍走 `closePaymentBestEffort`，由新回执决定是否继续重试；不改订单状态、不做退款。

`PaymentCloseRetryJob` 默认参数（代理细化，待业务确认）：

| 配置键 | 默认 | 说明 |
| --- | --- | --- |
| `payment.close-retry.enabled` | `true` | 关闭后只停止重试，不影响关单与回执 |
| `payment.close-retry.delay-ms` | `600000` | 固定间隔 10 分钟 |
| `payment.close-retry.interval-minutes` | `10` | 失败后至少间隔 10 分钟再重试 |
| `payment.close-retry.max-attempts` | `6` | 同一订单最多 6 次关单尝试（含首次），方案 B 已确认 |
| `payment.close-retry.window-hours` | `24` | 只扫描 24 小时内的回执，方案 B 已确认 |
| 批量 | `100` | 单轮最多 100 条（代码常量） |

`SKIPPED` 不自动重试：账户审核通过或适配器启用后不会自动补调，仍需商家主动关单或人工核对。

## 3. 用例

- `PaymentCloseRetryServiceTest` 3 项：只重试 CLOSED 订单、缺失/UNPAID 跳过、单条异常继续、非法参数提前返回不查库。
- `PaymentChannelSecurityTest` 新增 4 项：账户缺失写 `SKIPPED` 且不调用适配器、成功写 `SUCCESS`、渠道异常写 `FAILED` 且返回 false、回执写入失败不影响关单成功。
- `Sprint1SecurityIntegrationTest` 新增 1 项 H2 候选扫描用例：最新回执 SKIPPED/SUCCESS、达到次数上限、未过退避、超出窗口均被排除，最新 FAILED 且过间隔的订单入选；fixtures 清理加入 `payment_close_receipt`。

## 4. 可重跑命令

```bash
mvn -f shop-b2b2c-backend/pom.xml test
# 定向
mvn -f shop-b2b2c-backend/pom.xml test -Dtest="PaymentCloseRetryServiceTest,PaymentChannelSecurityTest,Sprint1SecurityIntegrationTest"
```

预期：全量通过；H2 用例证明回执可插入、候选 SQL 的窗口/次数/最新结果过滤生效。`-DforkCount=0` 会让既有静态扫描用例的相对路径解析失败，手动验证请勿带该参数。

## 5. 未验证边界

- 本轮未执行 `mvn test`；真实 MySQL 8 上 `V21` 迁移、`INSERT ... SELECT` 递增序号与索引未演练。
- 未连接真实微信/支付宝：关单接口的重试语义（重复关闭是否报错、错误码分类）需联调确认；当前把所有异常都记为 `FAILED` 并按次数重试。
- 未取得分布式锁，按单实例部署假设；多实例部署前必须补跨实例抢占，否则会重复调用渠道关单（渠道侧应幂等）。
- 未做回执查询管理页与人工“立即重试”入口；排障仍需 SQL 与日志。
- 迟到支付自动退款、异常队列处置动作未实现，F08 保持部分修复。

## 6. 回滚

- 回滚代码：移除 `PaymentGatewayService` 回执写入与 `PaymentCloseReceiptService` 注入，删除实体/Mapper/Service/Job 与相关用例，恢复原 `closePaymentBestEffort` 只记 WARN 的行为。
- 数据库：`payment_close_receipt` 为独立新表，无外键引用；回滚应用后可保留只读留存或由运维按策略删除，不影响既有业务表。
