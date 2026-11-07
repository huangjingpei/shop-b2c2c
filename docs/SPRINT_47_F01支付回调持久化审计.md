<!--
文件：SPRINT_47_F01支付回调持久化审计.md
用途：记录主台账 F01（真实支付与可信通知）中“持久化回调审计”缺口的实现范围、表结构、指纹口径、可重跑命令与未验证边界。
维护注意：本文只描述代码代理已完成的实现与待用户执行的命令，不代表已连接真实微信/支付宝渠道、完成生产 MySQL 迁移或通过验收。
-->
# Sprint 47：F01 支付回调持久化审计

- 日期：2026-11-08
- 主台账：F01（P0，`部分修复（待人工验证）`）
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：待人工验证（本轮未执行编译、测试或生产构建）

## 1. 复核结论（F01 前序部分）

台账 2026-11-03 初始描述“仅 checkout 直接成功与 mock-pay，无真实渠道网关实现”已与代码不符，本轮先校正描述，再补剩余缺口。

| 能力 | 现状 | 依据 |
| --- | --- | --- |
| 下单只建待付款单 | 已实现（checkout 落 UNPAID，无直接 PAID/发卡） | `OrderController.checkout`，D01 |
| 微信/支付宝真实适配器 | 已实现（RSA 验签、微信 AES-GCM 解密、金额/币种/商户号核验） | `service/payment` 适配器与 `PaymentChannelSecurityTest` |
| 渠道回调落账幂等 | 已实现（`payment_event` 唯一流水 + 占位/行锁/CAS，重复回调不重复履约） | `PaymentEventServiceImpl`，T05–T07 |
| 主动查单与补偿 | 已实现（`MerchantPaymentController` 查单、G34 补偿扫描复用幂等履约） | `PaymentGatewayService.queryChannelPayment` |
| 关单 | 尽力而为：失败只 WARN，不阻断本地关单 | `closePaymentBestEffort`，F08 |
| 回调审计留痕 | 仅结构化日志，无持久化审计表 | `CallbackReplayGuard` 原文件说明 |
| 支付尝试状态机（pay_attempt） | 未建表，支付尝试无独立生命周期 | F01 初始建议 |
| 关单回执重试、异常队列处置、迟到支付自动退款 | 未实现 | F08 |

本轮只补“回调审计持久化”一项，其余缺口保留在 F01/F08。

## 2. 本轮实现

### 2.1 表结构

新增 `payment_callback_audit`（`schema.sql` 第 38 张基线表 + `V20__f01_payment_callback_audit.sql` 幂等迁移 + H2 夹具同步）：

| 列 | 说明 |
| --- | --- |
| `channel` | 回调渠道，写库前统一大写；缺失/未知写 `UNKNOWN` |
| `order_sn` / `pay_trade_no` / `channel_merchant_id` | 回调声明值；验签/解析失败时允许为空 |
| `outcome` | `ACCEPTED` / `REJECTED`（验签、归属或资金要素拒绝）/ `ERROR`（未预期异常） |
| `processing_result` | 仅受理成功时写支付事件状态（`SUCCESS`/`DUPLICATE_PAYMENT` 等） |
| `reason` | 拒绝/异常摘要，单行化并截断 200 字符，不含报文正文 |
| `payload_sha256` | 规范化输入的 SHA-256，用于同源重试关联，不保存原文 |
| `duration_ms` | 进入网关到写入审计的耗时 |
| `created_at` | 写入时间，默认当前时间 |

索引：`(channel, order_sn, created_at)`、`payload_sha256`、`created_at`。**不设外键**：拒绝类回调的订单可能不存在或已被清理，审计仍须留痕。

表只增不改：`PaymentCallbackAuditMapper` 只继承 `BaseMapper`，刻意不提供更新/删除封装；清理由运维按留存策略执行。

### 2.2 指纹口径

`PaymentCallbackAuditService.fingerprint`：

- 原始报文非空时直接取原始报文的 SHA-256（微信回调）；
- 仅有表单参数（支付宝）时排除 `sign`、`sign_type`，按 key 升序拼接后取 SHA-256，使重复通知即使签名重复生成也能关联；
- 任何输入（含 null/空）都返回 64 位小写十六进制，不抛异常。

### 2.3 网关接入

`PaymentGatewayService.handleCallback` 在 `finally` 中写审计：

- 受理成功：`ACCEPTED` + `processingResult`；
- `BusinessException`（验签失败、订单不存在、渠道商户号不符、金额/币种不符、重复支付拒绝等）：`REJECTED` + 异常摘要，并原样抛出；
- 其它 `RuntimeException`：`ERROR` 并原样抛出；
- 审计写入失败只记 WARN，**不改变返回给渠道的 ACK/失败结果**，避免审计故障引发渠道重试风暴。

`CallbackReplayGuard` 的时间窗校验仍独立执行，不因审计表存在而放宽；审计表不替代验签。

## 3. 用例

- 新增 `PaymentCallbackAuditServiceTest` 6 项：原始报文/表单指纹稳定性、签名与参数顺序无关、空输入兜底、字段归一与原因截断（200 字）、缺省 `UNKNOWN`/`ERROR` 兜底、null 不入库。
- `PaymentChannelSecurityTest` 新增 3 项：受理成功写 `ACCEPTED`/事件状态/指纹、验签失败写 `REJECTED` 且不触发履约为空、审计抛异常时回调结果不变。
- `Sprint1SecurityIntegrationTest` 新增 1 项 H2 持久化用例：两次插入（受理+超长原因拒绝）后按指纹查回列值，断言 `reason` 截断 200、`processing_result` 为空、`created_at` 有值、计数为 2；fixtures 清理加入 `payment_callback_audit`。

## 4. 可重跑命令

```bash
# 后端（串行执行，避免共享 target 被覆盖）
mvn -f shop-b2b2c-backend/pom.xml test

# 定向
mvn -f shop-b2b2c-backend/pom.xml test -Dtest="PaymentCallbackAuditServiceTest,PaymentChannelSecurityTest,Sprint1SecurityIntegrationTest"
```

预期：全量通过；H2 用例证明表可写、可查、截断生效。`-DforkCount=0` 会让既有静态扫描用例的相对路径解析失败，手动验证请勿带该参数。

## 5. 未验证边界

- 本轮未执行 `mvn test`；真实 MySQL 8 上 `V20` 迁移、`CHAR(64)`/`INT` 列与索引未演练。
- 未连接真实微信/支付宝沙箱：`payload_sha256` 只覆盖代码内规范化，渠道实际重试报文差异（字段顺序、空白、编码）需联调确认。
- 审计表无留存/分区/归档策略：高流量或恶意回调刷库的用户侧容量与限流未评估。
- 未实现回调审计查询接口与管理端页面；排障仍需 SQL/日志。
- 支付尝试状态机、关单回执重试、异常队列处置动作、迟到支付自动退款仍未做，F01 保持部分修复。

## 6. 回滚

- 回滚代码：移除 `PaymentGatewayService` 的审计调用与 `PaymentCallbackAuditService` 注入、删除新增实体/Mapper/Service 与两端用例，恢复 `CallbackReplayGuard` 原说明。
- 数据库：`payment_callback_audit` 为独立新表，无外键引用；回滚应用后可保留只读留存或由运维按策略删除，不影响既有业务表。
