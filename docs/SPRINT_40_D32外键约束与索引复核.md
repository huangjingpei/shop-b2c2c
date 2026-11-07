<!--
文件：SPRINT_40_D32外键约束与索引复核.md
用途：D32「SQL 缺业务外键/库存非负/流水唯一幂等/orderSn 索引」复核记录：确认台账点名项已全部落地，无新增代码。
维护注意：
 1. 本轮为纯复核，不改动任何结构/配置/代码；所有结论均为"待人工验证"。
 2. 结构与约束的唯一权威源是 db/schema.sql；存量库补建只走 db/migrations/V*.sql（见 Sprint 39）。
 3. H2 夹具无外键是既有隔离约定，H2 通过不证明 MySQL 8 的 CHECK/外键强制行为。
-->
# Sprint 40：D32 外键/约束/索引复核

- 日期：2026-11-07
- 主台账：D32
- 状态：已修复 · 待人工验证
- 执行方：代码代理复核与文档更新；本轮无代码改动，相关契约测试由代理按既有授权运行，结果仅作参考，验收由用户管理。

## 1. 台账问题与逐项核对

台账 D32 原文：SQL 未建立业务外键；未见库存非负约束、交易流水唯一幂等约束；订单明细按 orderSn 查询但无对应索引。修复建议：按业务补关键约束/索引；支付事件单独建模，避免仅依赖主表字段。

| 台账子项 | 复核结果 | 证据（[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql)） |
| --- | --- | --- |
| 未建立业务外键 | 已落地 | 51 张表 85 个 FOREIGN KEY；如 trade_order_item 的 fk_item_order/fk_item_product/fk_item_sku，payment_event 的 fk_payment_event_merchant；V6 迁移另补齐 V1 前旧表 37 个外键 |
| 库存非负约束 | 已落地 | product_sku `chk_sku_stock CHECK (stock_quantity >= 0)`、`chk_sku_price`；应用层仍以原子 UPDATE + 影响行数兜底（deductStock），CHECK 是最后防线 |
| 交易流水唯一幂等约束 | 已落地 | payment_event 单独建模，`uk_channel_trade_no(channel, pay_trade_no)` 渠道流水唯一；order_idempotency.request_key 主键承载建单幂等 |
| 订单明细 orderSn 无索引 | 已落地 | trade_order_item `idx_order_sn(order_sn)`，另有 idx_order_id |
| 支付事件单独建模（建议） | 已落地 | payment_event 独立表（渠道、流水号、事件状态、对账重试字段），不依赖 trade_order 主表字段 |

git -S 追溯：chk_sku_stock 与 idx_order_sn 于 2026-11-04（d1dcf5a）进入 schema.sql，uk_channel_trade_no 于 2026-11-04（76bcffd）进入；均晚于台账建账日（2026-11-03），属台账登记后已完成但状态未同步。

H2 夹具 `sprint1-schema.sql` 同步含 chk_sku_stock、idx_order_sn、uk_channel_trade_no（无外键属既有隔离约定）。

## 2. 关联确认（非台账点名，顺手核实）

- product_sku `idx_product_id` 存在：Sprint 38 的 `findByProductIds` 批量查询（IN productIds + merchantId）可命中该索引，D30 修复在结构上有支撑。
- trade_order `uk_order_sn` 唯一键存在：订单号全局唯一，与 trade_order_item.idx_order_sn 的非唯一查询索引职责分离。

## 3. 测试（代理执行，仅作参考）

| 命令 | 结果 |
| --- | --- |
| `mvn -f shop-b2b2c-backend/pom.xml test -Dtest="SchemaMigrationContractTest,SchemaMySqlTest"` | 通过（exit 0） |

本轮无行为变化，未新增测试；既有契约测试继续锁死 V 系列脚本的幂等/非破坏性与基线结构。

## 4. 未验证与边界

- MySQL 8.0.16 之前 CHECK 约束只解析不强制；生产版本须确认 ≥8.0.16，且即便低于该版本，应用层原子扣减仍保证库存不为负。
- 外键在真实 MySQL 下的级联行为（本轮均为默认 RESTRICT/NO ACTION）与大数据量索引选择性未实测。
- "按业务补关键约束/索引"是开放性建议：未来新增查询路径时应同步评估索引，新增表默认带业务外键，不再另开台账项追踪。
