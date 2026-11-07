<!--
文件：SPRINT_35_F08取消订单与支付异常队列.md
用途：记录 F08 取消订单、超时关闭与支付补偿的交付范围、代理细化规则、验证命令与未验证项。
维护注意：状态为“部分修复·待人工验证”，不得改写为已验收；迟到支付自动退款与渠道关单回执仍未实现，异常队列只读、不修改资金状态，后续改动须同步台账与 shared/orderCancel 规则。
-->
# Sprint 35：F08 取消订单、超时关闭与支付补偿

## 1. 主台账编号与子范围

- 主台账编号：F08（取消订单、超时关闭与支付补偿）
- 实际子范围：
  1. 买家主动取消未支付订单：`POST /api/v1/public/orders/cancel`，凭订单号、联系方式、领取凭证校验，订单行锁内释放预占并置 `CLOSED`。
  2. 统一预占释放：新增 `InventoryReservationService`，`HELD → RELEASED` 条件迁移并回补 SKU 库存；代付撤销、订单取消、超时关单共用。
  3. 渠道关单：`PaymentGatewayService.closePaymentBestEffort`，在本地事务提交后尽力关闭渠道交易；失败只记录，不阻断本地关单。
  4. 超时关闭：沿用既有 `ReservationExpiryJob` 对全部超时 `UNPAID` 单的扫描关闭，本次补齐关单后的渠道关单调用。
  5. 迟到支付：沿用 `PaymentEventService` 的 `LATE_PAYMENT` 拒绝口径，本次新增超管只读异常队列 `GET /api/v1/admin/settlement/payment-exceptions`。
  6. 商城查单页：`UNPAID` 订单展示“取消订单”，成功后以服务端返回状态回写页面。
  7. shared 规则：`shared/orderCancel.mjs` 统一可取消状态、终态判断、请求体白名单与成功状态回显。

## 2. 代理自行细化的规则（待业务确认）

| 细化规则 | 性质 | 说明 |
| --- | --- | --- |
| 仅 `UNPAID` 订单可由买家取消 | 业务规则细化，待业务确认 | `PAID/SHIPPED/COMPLETED` 返回 409，必须走退款/售后流程，不能以取消接口绕过 |
| `CLOSED/CANCELLED/REFUNDED` 重复取消按幂等成功 | 技术实现细化 | 返回当前状态，不重复释放库存、不覆盖完成时间 |
| 取消凭证沿用免密查单三要素 | 安全实现细化 | 订单号+联系方式+领取凭证；任一不匹配统一 400，不区分具体字段，也不回显凭证 |
| 取消不删除订单、不自动退款 | 业务规则细化，待业务确认 | 订单保留用于审计；已支付订单只能通过退款流程处理 |
| 有预占才回补库存，无预占不算失败 | 技术实现细化 | 普通直接下单可能无预占记录；条件迁移保证重复释放不重复加库存 |
| `RESERVED_LOCK` 或存在代付类型的订单一并置 `peerStatus=CANCELLED` | 状态一致性细化 | 已锁定订单不得在关闭后继续展示可支付；同时清空 `lock_expire_time`，避免前端继续倒计时 |
| 渠道关单仅在账户已审核通过且适配器启用时执行 | 技术实现细化 | 未配置/未启用/调用失败返回 false 并记录，不阻断已提交的本地 `CLOSED` |
| 支付异常队列仅 `SUPERADMIN` 可读 | 权限实现细化 | 商家与匿名访问分别返回 403/401；只读，不提供退款、核销或状态修改 |
| 异常队列只返回白名单字段 | 安全实现细化 | 不返回 `raw_payload`、`payer_id`、`channel_merchant_id`；`funds_received=NULL` 表示未判定，必须人工核对 |
| 异常队列覆盖迟到/重复/金额/币种/商户不符/人工复核/履约失败 | 业务规则细化 | 事件状态集合与 `PaymentEvent` 注释保持一致；`reason` 仅按错误说明模糊过滤，最长 64 字符 |

## 3. 验收状态

**部分修复 · 待人工验证。**

已交付：买家取消接口、统一预占释放与库存回补、取消/超时后的渠道关单尽力调用、超管支付异常只读队列、商城查单页取消入口、shared 取消规则与后端集成用例。

未完成：
- 迟到支付的实际自动退款未实现，仍依赖真实渠道退款能力或人工处理；异常队列只做可见化。
- 渠道关单仅调用一次且不记录回执，未实现失败重试、关单结果查询与对账落账。
- 异常队列暂无处置动作（人工退款、核销、忽略并审计），也未做分页总数与导出。
- 超时关闭仍由 `ReservationExpiryJob` 扫描，未合并为单一取消服务；多实例部署下的扫描协调未验证。
- 真实 MySQL/支付渠道关单联调、Redis 限流与浏览器取消交互未验证。

## 4. 验证命令与结果

执行目录：`e:\shop-b2b2c`

1. 后端全量：

```bash
mvn -f shop-b2b2c-backend/pom.xml test
```

结果：1077 项通过，0 失败，0 错误，1 项既有跳过（H2 隔离库，未连接业务数据库）。

2. shared 规则：

```bash
node --test shared/orderCancel.test.mjs
```

结果：4 项通过（仅内存用例，不访问网络）。

3. 商城前端生产构建（管理端未改动）：

```bash
npm --prefix shop-storefront-vue run build
```

结果：Vite 生产构建成功，105 个模块完成转换。

## 5. 新增/修改文件

- 后端新增：`OrderCancellationService`、`InventoryReservationService`、`PaymentExceptionView`。
- 后端修改：`OrderController`（取消接口）、`AdminSettlementController`（异常队列）、`PaymentEventMapper`（白名单查询）、`PaymentGatewayService`（`closePaymentBestEffort`）、`PeerPayServiceImpl`（统一预占释放与超时关单渠道调用）、`PeerStatus`（注释同步）。
- 前端：`shop-storefront-vue/src/views/QuickQuery.vue`（取消入口）。
- shared：`shared/orderCancel.mjs`、`shared/orderCancel.test.mjs`，并纳入 `AGENTS.md` 手动验证清单。
- 测试：`Sprint1SecurityIntegrationTest` 新增 5 项 F08 用例（取消成功/幂等/凭证错误、已支付 409、超时关单渠道调用、异常队列权限、异常队列字段白名单与过滤）。

## 6. 未验证项与人工核对建议

1. 真实渠道下取消后关单是否成功、迟到支付回调是否进入 `LATE_PAYMENT`，需在测试商户号环境联调。
2. 渠道关单失败后订单已本地关闭，需人工在渠道后台核对交易状态并按需退款。
3. 异常队列中的 `funds_received=1/NULL` 事件必须人工核对资金去向，不得直接按 0 处理。
4. 浏览器端取消按钮、确认弹窗与状态回写需人工点验。
