<!--
文件：SPRINT_41_D33代付与服务费状态编码统一.md
用途：D33「代付状态默认值/注释与持久化编码不一致、服务费 DTO 与 SQL 状态集合不一致」的收尾交付记录。
维护注意：
 1. peerStatus 与 merchant_store.fee_status 分别在 Sprint 16/17 已统一，本文件只补齐订单级 trade_order.fee_status 的单一来源与契约验证，不重复改造既有入口。
 2. OrderFeeStatus 与 ServiceFeeStatus 语义不同，禁止合并；新增编码必须同步 schema.sql、H2 夹具、退款条件 SQL 与 OrderFeeStatusContractTest。
 3. 所有结论均为“待人工验证”，H2/契约测试不替代真实 MySQL 历史数据核对与浏览器验证。
-->
# Sprint 41：D33 代付与服务费状态编码统一（收尾）

- 日期：2026-11-07
- 主台账：D33
- 状态：已修复 · 待人工验证
- 执行方：代码代理实现与静态审阅；本轮经用户授权由代理执行后端测试，结果仅作参考，验收由用户管理。

## 1. 复核结论

| 台账子项 | 现状 | 处理 |
| --- | --- | --- |
| TradeOrder.peerStatus 默认 INTENT、注释 LOCKED 与服务/SQL 的 INIT/RESERVED_LOCK 不一致 | Sprint 16 已统一：实体默认 `INIT`，建单/服务/CAS SQL 全部引用 `PeerStatus`；`PeerStatusContractTest` 已验证默认值与两份 schema 一致 | 无需改代码，保持现状 |
| 服务费 DTO 与 SQL 状态集合不一致（商户配置） | Sprint 17 已统一：`ServiceFeeStatus` 五个编码 + `ServiceFeeConfigDTO` 白名单校验 + 前端 `serviceFee.mjs` + schema 默认 `NORMAL` | 无需改代码，保持现状 |
| 服务费订单级状态（T33/T34 新增）无单一来源 | `trade_order.fee_status` 的 PENDING/ACCRUED/ARREARS/PARTIAL_REVERSED/REVERSED 直接散落在实体默认值、`MerchantAccountService` 字面量与 `TradeRefundMapper` 条件 SQL 中，后续新增路径可能写入页面/对账不识别的状态 | 本轮收尾：集中定义并补契约测试 |

## 2. 本轮修改

1. 新增 `com.shop.b2b2c.common.OrderFeeStatus`：声明 PENDING、ACCRUED、ARREARS、PARTIAL_REVERSED、REVERSED 及 `SUPPORTED` 集合；文件说明明确它与 `ServiceFeeStatus`（商户月费配置标记）不是同一语义，禁止互相赋值或合并。
2. `TradeOrder.feeStatus` 默认值由字面量 `"PENDING"` 改为 `OrderFeeStatus.PENDING`，注释指向唯一来源。
3. `MerchantAccountService` 的扣费入账 `ACCRUED`、余额不足 `ARREARS` 及退款冲正对原扣费方式的判断改用常量，行为不变。
4. `TradeRefundMapper.reserveRefund` / `releaseRefundReservation` 的 `CASE` 条件 SQL 改用常量拼接（编译期常量，注解 SQL 文本与改前完全一致），避免字面量漂移。
5. 新增 `OrderFeeStatusContractTest`（5 项）：
   - 实体默认值与 JSON 序列化均为字符串 `PENDING`；
   - `SUPPORTED` 恰好为五个已声明编码；
   - `db/schema.sql` 与 H2 夹具 `trade_order.fee_status` 默认 `PENDING` 且说明覆盖全部编码；
   - `merchant_store.fee_status` 保持 NORMAL 五编码，且不得混入订单侧 PENDING/ACCRUED/PARTIAL_REVERSED/REVERSED；
   - 退款注解 SQL 中出现的状态字面量只允许 `OrderFeeStatus.SUPPORTED` 与已知订单状态（PAID/SHIPPED/COMPLETED）。

## 3. 测试（代理执行，仅作参考）

| 命令 | 结果 |
| --- | --- |
| `mvn -f shop-b2b2c-backend/pom.xml -Dtest=OrderFeeStatusContractTest test` | 5 项通过 |
| `mvn -f shop-b2b2c-backend/pom.xml test` | 1092 项通过，0 失败/0 错误，1 项既有跳过 |

未改动前端与 shared 规则，未执行前端生产构建。

## 4. 未验证与边界

- 真实 MySQL 存量 `trade_order.fee_status` 历史值不做转换或回填；若存在既有库中的未知编码，页面/对账仍按未知值处理。
- 本轮到数据库只读取 SQL 资源文本与内存序列化，未连接业务库；H2 通过不代表 MySQL 8 迁移与并发已验收。
- 订单级 `feeStatus` 当前不对外提供独立写接口，本轮未新增 API 字段或前端展示，故 API 契约仅验证字符串编码不变。
