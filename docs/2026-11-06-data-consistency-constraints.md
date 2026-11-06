<!-- 文件说明：第②卡“数据一致性约束加固”的问题清单、改动依据、风险与用例清单。
维护注意：本文件只基于 shop-b2b2c-backend/src/main/resources/db/schema.sql 的静态文本核对，不含 V1–V6 迁移脚本；
未运行 mvn/编译/测试/构建，未连接真实 MySQL，CHECK 强制行为依赖 MySQL 8.0.16+，结论待人工验证。 -->
# 数据一致性约束加固（第②卡）

日期：2026-11-06  
范围：`shop-b2b2c-backend/src/main/resources/db/schema.sql`（核对基线 38 张表、66 个外键、16 个 CHECK、2 个账本触发器；改动后 68 个外键、19 个 CHECK）  
边界：不改 V1–V6 迁移脚本；不运行构建/测试；不连接数据库。

## 0. 判定口径

一条约束只有能回答“它防住了哪个具体错误操作”才新增或修改。下表中“不改”的条目同样写明原因，
避免把“没发现问题”与“判断后决定不改”混为一谈。所有改动均为 `schema.sql` 的最终形态，
不是给存量库用的增量 DDL。

## 1. 唯一键与业务语义匹配

| 表 | 现状 | 问题 | 建议 |
| --- | --- | --- | --- |
| `trade_order` | `uk_order_sn`、`uk_peer_pay_token`；`query_token` 只有普通索引 `idx_query_token` | `query_token` 是买家免密查单凭证，`PaymentGatewayService` 同时接受 `peerPayToken` 或 `queryToken`；普通索引不阻止两条订单写入同一 token，碰撞会让持 token 者查到/操作他人订单 | **改**：`idx_query_token` 改为 `UNIQUE KEY uk_query_token (query_token)`；合法建单在幂等重试内重新生成订单号与 token（`OrderController.checkout` 第 125–126 行每次生成 UUID；`OrderIdempotencyService.execute` 第 69–106 行捕获 `DuplicateKeyException` 后整体重跑 supplier），碰撞时插入失败而不是静默串单。本轮复核见 3.1.3 |
| `trade_refund` | `uk_refund_no(refund_no)`、`uk_refund_order_key(order_id, idempotency_key)`、`uk_refund_source_event(source_event_id)` | 无 | **不改**。`refund_no` 是平台全局退款单号，也是渠道退款幂等参照，按 `(order_id, refund_no)` 会弱化唯一性；同一订单重复退款由 `(order_id, idempotency_key)` 挡住；重复付款补偿由 `source_event_id` 挡住 |
| `payment_event` | `uk_channel_trade_no(channel, pay_trade_no)` | 无 | **不改**。可挡住同一渠道流水重复回调；同订单二次付款不阻断，而是记 `DUPLICATE_PAYMENT` 走补偿，符合 T33 口径 |
| `merchant_account_ledger` | `uk_ledger_idempotency(idempotency_key)` | 无 | **不改**。同一订单/退款/充值只入账一次；账本另有 append-only 触发器 |
| `merchant_account` | `uk_account_merchant(merchant_id)` | 无 | **不改**。一店一账户 |
| `merchant_recharge_order` | `uk_recharge_no`、`uk_recharge_voucher` | 无 | **不改**。同一充值单、同一转账凭证只能确认一次 |
| `merchant_settlement_batch` | `uk_settlement_period(merchant_id, period_start, period_end, settlement_type)` | 无 | **不改**。同店同账期同类型只能一批 |
| `merchant_settlement_item` | `uk_settlement_item(batch_id, item_type, order_id, refund_id)` | 无 | **不改**。订单费用与退款冲正逐笔唯一；`refund_id=0` 作为非退款哨兵 |
| `payment_channel_statement` | `uk_statement_trade(channel, channel_trade_no)` | 无 | **不改**。同一渠道流水重复导入会被唯一键拒绝 |
| `reconciliation_difference` | `uk_difference_key(difference_key)` | 无 | **不改**。稳定差异键防重复入队 |
| `platform_collection_settlement` | `uk_platform_settlement_order(order_id)` | 无 | **不改**。一笔平台代收订单一条台账 |
| `split_detail` | `uk_split_order_receiver(order_id, payment_account_id)` | 无 | **不改**。同订单同接收方只分一次 |
| `split_reverse` | `uk_split_reverse_refund(split_detail_id, refund_id)` | 无 | **不改**。同分账明细同退款只退一次 |
| `billing_statement` | `uk_shop_period(shop_id, period_date, period_type)` | 无 | **不改**。同店同日/月只一份账单 |
| `merchant_fee_policy` | 无唯一键，只有 `(merchant_id, effective_from, effective_to)` 普通索引 | “同商户同一时刻仅一套生效政策”无法用普通唯一键表达；时间区间重叠需应用层行锁 + 校验 | **不改**，登记缺口；强行加唯一键会误伤正常的费率切换（旧政策关闭 + 新政策开启） |
| `merchant_settlement_account` | `uk_settlement_account_fingerprint`；`is_default` 仅服务层维护 | 同商户可能同时存在“已验证默认账户”与“新登记待验证且预设默认”的第二条 `is_default=1`，服务层在核验时统一清除旧默认 | **不改**。加 DDL 唯一会直接破坏“先登记后核验”的合法流程；登记应用层并发缺口 |
| `order_idempotency` | 主键 `request_key = SHA-256(scope + key)` | 无 | **不改**。scope 含商户，跨商户同键不会互相阻塞 |
| `inventory_adjustment` | `uk_inventory_adjustment_request(request_key)` | 无 | **不改**。请求键含作用域，重复调整只落一条 |
| `product_virtual_card` | `uk_merchant_fingerprint(merchant_id, card_fingerprint)`，指纹可空 | 多行 NULL 不受唯一约束；依赖 `fingerprint_unavailable=1` 标记 | **不改**。不可用指纹本就是显式例外，不能强制唯一 |
| `card_import_item` / `virtual_card_void` | 主键 `card_id` | 无 | **不改**。同一卡只能属于一个导入批次、只能作废一次 |
| `merchant_store` / `sys_user` / `product_sku` / `merchant_payment_account` | `uk_store_code`、`uk_username`、`uk_merchant_sku_code`、`uk_merchant_channel` | 无 | **不改** |

## 2. 非空与精度统一

### 2.1 金额列

| 表 | 金额列现状 | 问题 | 建议 |
| --- | --- | --- | --- |
| `trade_order` | 全部 `DECIMAL(10,2) NOT NULL`（`fee_tax_rate` 为 `DECIMAL(7,4)` 比例） | 无 NULL；无浮点 | **不改**精度分层：交易级 10,2，账户/结算级 12,2 是有意设计 |
| `trade_order_item` | `price`、`subtotal` `DECIMAL(10,2) NOT NULL` | 无 | **不改** |
| `trade_refund` | `DECIMAL(10,2) NOT NULL` | 无 | **不改** |
| `billing_statement` | `DECIMAL(12,2) NOT NULL` | 无 | **不改** |
| `complaint` | `refund_amount DECIMAL(10,2) NOT NULL DEFAULT 0` | 判定金额与订单退款未建关联，属业务缺口 | **不改**本卡，登记 |
| `payment_event` | `pay_amount DECIMAL(10,2) NOT NULL` | 无 | **不改** |
| `merchant_account` | `DECIMAL(12,2) NOT NULL` | 无 | **不改** |
| `merchant_account_ledger` | `amount/balance_after/arrears_after DECIMAL(12,2) NOT NULL` | 无 NULL；但无“余额不得为负”约束 | **改**：补 `chk_ledger_balance_nonnegative`（见第 3 节；本轮复核保留，见 3.1.1） |
| `merchant_recharge_order` | `amount NOT NULL`；`confirmed_amount` 可空 | `confirmed_amount` 在确认前必须为空，语义正确 | **不改** |
| `merchant_settlement_batch` | 全部 `DECIMAL(12,2) NOT NULL` | `adjustment_amount` 可为负（红字），不能一律 `>=0` | **不改** |
| `merchant_settlement_item` | 全部 `DECIMAL(12,2) NOT NULL` | `fee_amount` 退款明细可为负 | **不改** |
| `merchant_collection_status_audit` | `arrears_at_action NOT NULL`；`credit_limit` 可空 | 动作时额度可能未确认，语义正确 | **不改** |
| `payment_channel_statement` | `amount DECIMAL(12,2) NOT NULL` | 无 | **不改** |
| `reconciliation_difference` | `expected_amount`/`actual_amount` 可空 | 单边缺失的差异必须允许一方为空 | **不改** |
| `platform_collection_settlement` | 全部 `DECIMAL(12,2) NOT NULL` | 无 NULL；退分上限未约束 | **改**：扩展 CHECK（见第 3 节） |
| `split_detail` / `split_reverse` | 全部 `DECIMAL(12,2) NOT NULL` | 无 | **不改** |

全库扫描结论：无 `FLOAT`/`DOUBLE`/`REAL`；除 `fee_tax_rate`（税率比例，4 位小数）外，所有 `DECIMAL` 标度均为 2。

### 2.2 状态列与默认值

| 表 / 列 | 现状 | 问题 | 建议 |
| --- | --- | --- | --- |
| `merchant_payment_account.apply_status` | `NOT NULL DEFAULT 'PASSED'` | 注释写明“阶段一不写假 PASSED”，默认值却会把漏写状态变成“已开通” | **改**：`DEFAULT 'AUDITING'` |
| `payment_event.event_status` | `NOT NULL`，无默认 | 漏写应失败而不是猜状态 | **不改**，fail-fast 更安全 |
| `inventory_reservation.status` | `NOT NULL`，无默认 | 同上 | **不改** |
| 其余状态列 | 全部 `NOT NULL` 且有语义默认（DRAFT/PENDING/UNPAID 等） | 无 | **不改** |

全库所有列名以 `status` 结尾或 `is_*` 开关列均 `NOT NULL`。

### 2.3 同义列类型一致性

| 列 | 现状 | 问题 | 建议 |
| --- | --- | --- | --- |
| `trade_order.fee_policy_id` | `BIGINT NULL` | 指向 `merchant_fee_policy.id`（`BIGINT UNSIGNED`），签名为有符号 | **改**：`BIGINT UNSIGNED NULL` |
| `merchant_account_ledger.biz_id` | `BIGINT NULL` | 指向订单/退款/充值主键，均为 UNSIGNED | **改**：`BIGINT UNSIGNED NULL` |
| `reconciliation_difference.biz_id` | `BIGINT NULL` | 同上（订单/事件/流水/结算/充值主键） | **改**：`BIGINT UNSIGNED NULL` |
| `merchant_settlement_item.payment_event_id` | `BIGINT NULL` | 指向 `payment_event.id`（UNSIGNED），且无外键 | **改**：`BIGINT UNSIGNED NULL` + 外键 `fk_settlement_item_event` |
| `merchant_recharge_order.ledger_entry_id` | `BIGINT NULL` | 指向 `merchant_account_ledger.id`（UNSIGNED），且无外键 | **改**：`BIGINT UNSIGNED NULL` + 外键 `fk_recharge_ledger_entry` |
| 各表操作人/确认人列（`operator_id`、`confirmed_by`、`prepared_by`、`imported_by`、`reviewer_id`、`verified_by`、`handled_by`、`requested_by`、`actor_id` 等） | 全部 `BIGINT NULL`（`actor_id` 为 UNSIGNED NOT NULL） | 与 `sys_user.id` 的 UNSIGNED 不一致；但系统任务允许为空，且当前未加外键 | **不改**。统一改 UNSIGNED 收益不足以覆盖未知写入路径风险；登记为将来加操作人外键前的对齐项 |

## 3. CHECK 约束的实际生效性

MySQL 8.0.16 起才真正强制 `CHECK`；8.0.16 之前只解析不执行。部署基线为 MySQL 8.0.39/8.0.46，满足强制条件；
应用层仍保留同等校验，供低版本或绕过服务直写时兜底。

| CHECK | 表 | 防住的错误 | 应用层兜底 | 结论 |
| --- | --- | --- | --- | --- |
| `chk_sku_stock` | `product_sku` | 库存写成负数 | 库存调整/导入校验 + 用例 | 保留 |
| `chk_sku_price` | `product_sku` | 负售价 | 商品发布校验 | 保留 |
| `chk_order_amounts` | `trade_order` | 负订单额/负实付 | 建单金额校验 | 保留 |
| `chk_item_quantity` | `trade_order_item` | 零/负数量、负价、负小计 | 下单数量校验 | 保留 |
| `chk_refund_amount` | `trade_refund` | 零/负退款、负服务费冲正 | 退款服务校验 | 保留 |
| `chk_reservation_quantity` | `inventory_reservation` | 零/负预占 | 预占服务校验 | 保留 |
| `chk_adjustment_delta` | `inventory_adjustment` | 零变动调整 | 调整服务校验 | 保留 |
| `chk_adjustment_stock` | `inventory_adjustment` | 调整前后库存不自洽 | 同事务条件更新 | 保留 |
| `chk_card_void_stock` | `virtual_card_void` | 作废前后库存不自洽 | 同事务条件更新 | 保留 |
| `chk_card_batch_quantity` | `card_import_batch` | 导入 0 条/超 1000 条/库存不自洽 | 导入服务校验 | 保留 |
| `chk_account_amounts` | `merchant_account` | 账户余额/欠费/额度为负 | 账户服务校验 | 保留 |
| `chk_ledger_amount` | `merchant_account_ledger` | 负发生额 | 入账服务只写非负额 | 保留 |
| `chk_recharge_amount` | `merchant_recharge_order` | 零/负充值 | 充值服务校验 | 保留 |
| `chk_platform_settlement_amounts` | `platform_collection_settlement` | 金额为负、实收不守恒 | 台账服务校验 | 保留并扩展：累计退分不得超过商家应结净额 |
| `chk_split_amounts` | `split_detail` | 分账不守恒、退分超过分账 | 分账服务校验 | 保留 |
| `chk_split_reverse_amount` | `split_reverse` | 零/负退分 | 退分服务校验 | 保留 |
| **新增** `chk_order_refund_within_paid` | `trade_order` | 绕过 `reserveRefund` 的条件更新，或未来新增退款入口，把累计退款写到超过实付/服务费快照 | `TradeRefundMapper.reserveRefund` 条件更新 + 并发用例 | **新增；本轮复核保留**（表达式是总量上限，见 3.1.2 / 3.2） |
| **新增** `chk_ledger_balance_nonnegative` | `merchant_account_ledger` | 新入账路径未先判断余额，账本记录出现负余额/负欠费，账实不符 | 账户服务按余额/欠费分支记账 | **新增；本轮复核保留**（欠费记 `arrears` 正字段，不写负余额，见 3.1.1） |
| **新增** `chk_fee_policy_rate` | `merchant_fee_policy` | 手工 SQL 或新入口写入 50%/负数等非法费率，导致整店按错误费率计费 | `MerchantFeePolicyService.validateRate` 对三条插入路径均强制；DDL 只表达 0↔PROMOTION | **新增；本轮复核保留**（见 3.1.4） |

### 3.1 本轮复核结论（保留 / 修改 / 撤销）

复核方法：只读 `schema.sql` 与对应服务/Mapper 代码，不运行构建、不连库。结论按“保留 / 修改 / 撤销”三选一，并写明原判断是否成立。

| # | 约束 | 结论 | 原判断是否成立 | 一句话理由 |
| --- | --- | --- | --- | --- |
| 1 | `chk_ledger_balance_nonnegative` | **保留** | 不成立（把“欠费”误读成“负余额”） | 欠费记在 `merchant_account.arrears` 这个独立正数字段，账本 `balance_after` 始终非负 |
| 2 | `chk_order_refund_within_paid` | **保留** | 不成立（把总量上限误读成逐次比例上限） | 表达式只限制 `fee_refunded_amount <= commission_amount`，尾差在最后一次被吸收后累计仍不超快照 |
| 3 | `uk_query_token` | **保留** | 部分成立（NOT NULL 不等于禁止空串） | 列为 `NOT NULL`，三条建单路径均写 32 位 UUID，且幂等重试会重跑 supplier 重新生成 token |
| 4 | `chk_fee_policy_rate` | **保留** | 不成立（应用层确有拦截） | DDL 管 0↔PROMOTION，`validateRate` 对 0 费率强制 PROMOTION 且原因非空 |

#### 3.1.1 `chk_ledger_balance_nonnegative`：负余额不是欠费表达

结论：**保留，不改 DDL**。理由：

- `merchant_account` 把余额和欠费分成两个非负字段：`balance_available`（可用余额）、`arrears`（欠费累计），并由既有 `chk_account_amounts` 同时要求 `balance_available >= 0`、`arrears >= 0`（`schema.sql` 第 506–523 行）。
- `MerchantAccountService.chargeOrderFee` 第 63–104 行：余额 ≥ 服务费时 `balance_available -= fee`；余额不足时 **余额不变**，只做 `arrears = arrears + fee`，订单 `fee_status='ARREARS'`。欠费不是把余额写成负数。
- 账本快照同样不写负数：`insertLedger` 第 459–469 行（`setBalanceAfter`/`setArrearsAfter` 在第 467–468 行）取 `account.getBalanceAvailable()` 与 `account.getArrears()` 写入 `balance_after` / `arrears_after`。
- 其余写入路径也都保持非负：退款冲正 `reverseOrderFee` 第 111–147 行用 `offset = min(arrears, amount)` 先冲欠费、剩余才回补余额；充值确认 `confirmRecharge` 第 185–231 行同样先 `min(arrears, amount)` 抵扣欠费。
- 欠费额度确实可配置：`configurePolicy` 第 281–303 行允许 `credit_limit` 在 0~100000 元之间逐店设置，`grace_days` 只允许 7/21/30。但本 CHECK **没有写死任何固定下限**，只要求 `>= 0`，因此“不同商户不同额度”不会让这条 CHECK 失效；真正需要负余额模型时，先冲突的是既有 `chk_account_amounts`，而不是本条。
- 场景：某店余额 0、欠费额度 500，支付成功产生服务费 3.00 → `arrears` 从 0 变 3.00，账本写 `balance_after=0.00`、`arrears_after=3.00`，CHECK 通过；后续退款冲正 1.00 → `arrears_after=2.00`，仍通过。

防住的错误操作：新入账路径不先判断余额/欠费，直接把 `balance_after` 或 `arrears_after` 写成负数，导致账本快照与账户表不一致。

#### 3.1.2 `chk_order_refund_within_paid`：CHECK 是总量上限，不是逐次比例上限

结论：**保留，不改 DDL**。理由：

- 表达式（`schema.sql` 第 232–234 行）是：`refunded_amount >= 0 AND refunded_amount <= pay_amount AND fee_refunded_amount >= 0 AND fee_refunded_amount <= commission_amount`。它没有 `fee_refunded_amount <= 按比例应冲正额` 这一项。
- `RefundTransactionService.allocateFeeRefund` 第 229–249 行：非最后一次按 `commission_amount × refundAmount ÷ payAmount`、HALF_UP 两位计算；**最后一次**（`refundAmount + alreadyRefunded >= payAmount`）直接取 `commission_amount - alreadyFee`，并在超过 `feeRemaining` 时 clamp、低于 0 时归零。因此累计 `fee_refunded_amount` 恒 ≤ `commission_amount`。
- `TradeRefundMapper.reserveRefund` 第 58–65 行在应用层又加了一道总量条件：`refunded_amount + amount <= pay_amount AND fee_refunded_amount + feeAmount <= commission_amount`。`prepareOrderRefund` 还先校验 `amount <= pay_amount - alreadyRefunded`。
- 场景：尾差为正的那一次，`fee_refund_amount` 会大于该次的 HALF_UP 比例值，但累计仍等于 `commission_amount`，不会突破 CHECK；具体算例见 3.2。尾差本身记录在 `trade_refund.fee_rounding_adjustment`（DECIMAL(10,2)，允许正负），不参与 `trade_order` 的 CHECK。

防住的错误操作：绕过 `reserveRefund` 的退款入口，或未来新增入口，把 `refunded_amount` 累计写到超过 `pay_amount`、把 `fee_refunded_amount` 累计写到超过 `commission_amount`。

#### 3.1.3 `uk_query_token`：NOT NULL + UUID 生成 + 幂等重试重生成

结论：**保留，不改 DDL**。逐条回答两个细节：

- 是否允许 NULL：不允许。`query_token VARCHAR(64) NOT NULL`（`schema.sql` 第 212 行）。因此不存在“多行 NULL 在唯一键下互不冲突”的漏洞，也不存在“建单写 NULL”的路径。
- 是否可能写空串 `''`：当前没有。全仓 `setQueryToken` 只有三处，均为 `UUID.randomUUID().toString().replace("-", "")`（32 位十六进制）：
  - `OrderController.checkout` 第 125–126 行生成、第 153 行写入；
  - `PeerPayServiceImpl.createBuyerPeerPay` 第 92–93 行生成、第 118 行写入；
  - `PeerPayServiceImpl.createPushPayOrder` 第 201–202 行生成、第 223 行写入。
  其余 `orderMapper.save` 都是对已存在订单的更新（发货、预占、取消、到期关闭、支付履约、服务完成），不会覆盖 `query_token`。
- 幂等重试重新生成 token 的代码位置：`OrderIdempotencyService.execute` 第 69–106 行的 `for` 循环把整段 `transaction.execute` 包在 `try/catch (DuplicateKeyException)` 里；每次重试都会重新执行 `action.get()`（第 77 行），而 supplier 分别是 `OrderController` 第 84 行、`PeerPayController` 第 51 行、`OrderController` 第 437 行，最终都进入上面的建单方法重新生成 `orderSn` 与 `queryToken`。事务已回滚，`order_idempotency` 占位也一并回滚，因此重试可再次 `claim`。
- 合法场景：两个不同订单各拿一个 UUID，唯一键不冲突；若随机碰撞导致插入抛 `DuplicateKeyException`，整个幂等事务回滚并重跑 supplier，最多 3 次，合法建单不会被这条唯一键永久挡住。

残留风险：`NOT NULL` 允许空串，直接 SQL 或未来新增的非应用写入如果写 `''`，第二张空 token 订单会被唯一键拒绝。当前三条建单路径不写空串；本卡不额外加 `CHECK (query_token <> '')`，登记在 7.8。

#### 3.1.4 `chk_fee_policy_rate`：0 费率与原因都有约束

结论：**保留，不改 DDL**。理由：

- DDL（`schema.sql` 第 816–817 行）：`(rate_type='NORMAL' AND rate_bps BETWEEN 100 AND 1000) OR (rate_type='PROMOTION' AND rate_bps=0)`。0 基点只允许 `PROMOTION`，`NORMAL` 不允许 0。
- 应用层 `MerchantFeePolicyService.validateRate` 第 233–252 行：`PROMOTION` 必须 `rateBps == 0` 且 `promotionReason` 非空，否则 400；`NORMAL` 必须 100–1000。
- 所有写入 `merchant_fee_policy` 的路径都先过这个方法：`createInitialPolicy`（第 100 行）、`adjust`（第 138 行）、`bootstrapFromLegacyRate`（第 219 行）。`adjust` 还额外要求 `form.reason` 非空（第 139–141 行）。`MerchantOnboardingService` 开店调用 `createInitialPolicy` 时也把 `promotionReason` 原样传入。
- 场景：管理员提交 `rate_type='NORMAL', rate_bps=0` → 应用层 400，即使绕过应用，DDL 也拒绝；提交 `rate_type='PROMOTION', rate_bps=0, promotion_reason=null` → 应用层 400。不存在“DDL 放行但应用层没拦”的应用写入路径。
- 残留：DDL 无法表达“推广原因必须非空”，直接 SQL 仍可写 `PROMOTION + NULL` 原因；这是应用层职责，登记在 7.9。

### 3.2 服务费冲正尾差算例（证明 CHECK 不会误伤合法全额退款）

订单：`pay_amount = 33.33`，费率 300 基点（3%），`commission_amount = 33.33 × 300 ÷ 10000 = 0.9999`，按 `ServiceFeeSnapshotService` 的 HALF_UP 两位取 `1.00`。分 3 次退款，每次 11.11：

| 次序 | 本次退款 | 累计退款（含本次） | 比例应冲正（HALF_UP 2 位） | 实际 `fee_refund_amount` | `fee_rounding_adjustment` | 累计 `fee_refunded_amount` |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 11.11 | 11.11 | 1.00 × 11.11 ÷ 33.33 = 0.3333 → 0.33 | 0.33 | 0.00 | 0.33 |
| 2 | 11.11 | 22.22 | 0.33 | 0.33 | 0.00 | 0.66 |
| 3（最后一次） | 11.11 | 33.33 | 0.33 | 1.00 − 0.66 = **0.34** | **+0.01** | **1.00** |

第 3 次的 `fee_refund_amount(0.34)` 大于当次比例值 `0.33`，尾差 `+0.01`；但累计 `fee_refunded_amount = 1.00 = commission_amount`，累计 `refunded_amount = 33.33 = pay_amount`，两条上限都恰好取等，CHECK 通过。

反证：若 CHECK 写成 `fee_refunded_amount <= 按比例应冲正额`，第 3 次 `0.34 > 0.33` 会被误杀——当前表达式没有这一项，所以不会发生。

判断后不新增的约束：

- `merchant_settlement_batch.net_fee_amount = fee_amount + adjustment_amount`：应用层生成时计算，口径可能调整，DDL 等式收益低。
- `trade_order_item (order_id, sku_id)` 唯一：同 SKU 分多行在业务上不构成错误，加唯一会改变合法下单形态。
- `direction`/`entry_type`/`biz_type` 字符串枚举 CHECK：应用层常量控制，新增枚举时 DDL 会变成迁移负担。
- `merchant_settlement_account.is_default` 唯一：会破坏“登记第二账户并预设默认”的合法流程（见第 1 节）。

## 4. 财务表审计字段完整性

| 表 | 创建人/时间 | 操作人 | 状态变更留痕 | 问题 | 建议 |
| --- | --- | --- | --- | --- | --- |
| `trade_order` | 仅 `created_at` | 无 | 仅靠 `trade_refund`/账本间接 | 订单状态、退款预占、服务费状态都在本表被更新，却没有 `updated_at`，无法判断变更时间 | **改**：补 `updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` |
| `trade_refund` | `created_at` + `updated_at` | `requested_by` | 状态 + `failure_reason` | 无 | **不改** |
| `payment_event` | `created_at` + `updated_at` | 系统 | `event_status` + `error_message` + 原始报文 | 无 | **不改** |
| `merchant_account` | `created_at` + `updated_at` + `version` | 无 | 停收/恢复由审计表记录 | 变更均经账本与审计表 | **不改** |
| `merchant_account_ledger` | `created_at` | 无 | append-only 触发器 | 无操作人列；充值可由充值单回查，扣费/冲正为系统动作 | **不改**，登记 traceId 缺口 |
| `merchant_recharge_order` | `created_at` + `updated_at` | `requested_by`/`confirmed_by` | 状态 + 确认时间 + `approval_mode` | 无 | **不改** |
| `merchant_settlement_batch` | `created_at` + `updated_at` + `version` | `prepared_by`/`confirmed_by` | 状态 + 确认时间 + `approval_mode` | 无 | **不改** |
| `merchant_settlement_item` | `created_at` | 无 | 明细不可变 | 快照明细，不更新 | **不改** |
| `payment_channel_statement` | `created_at` | `imported_by` | 导入不可变 | 无 | **不改** |
| `reconciliation_difference` | `created_at` + `updated_at` | `handled_by` + `handled_at` | `status` + 重试次数 + 处理说明 | 无 | **不改** |
| `billing_statement` | `created_at` + `updated_at` | 无（系统生成） | `status` | 系统物化账单，无人工创建路径 | **不改** |
| `merchant_fee_policy` | 仅 `created_at` | `operator_id`/`confirmed_by` | 由 `merchant_fee_audit` 记录新增，但关闭旧政策的 UPDATE 无时间戳 | 费率生效区间被事后改动时无法判断改动时间 | **改**：补 `updated_at` |
| `merchant_fee_audit` | `created_at` | `operator_id`/`confirmed_by` | 前后值 + 生效时间 | 无 | **不改** |
| `merchant_collection_status_audit` | `created_at` | `operator_id`（系统为空） | 动作 + 动作时快照 | 无 | **不改** |
| `merchant_settlement_account` | `created_at` + `updated_at` + `version` | `verified_by`/`verified_at` | `status` + 审计表 | 无 | **不改** |
| `merchant_settlement_account_audit` | `created_at` | `operator_id` | 动作 + 原因 | 无 | **不改** |
| `merchant_onboarding_application` / `_audit` | `created_at` + `updated_at` + `version` | `reviewer_id`/`operator_id` | 前后状态 | 无 | **不改** |
| `platform_collection_settlement` | `created_at` + `updated_at` | 无 | `settlement_status` | 阶段二骨架，尚无写入代码；补操作人列会是空列 | **不改**，登记阶段二联调前补 |
| `split_detail` | `created_at` + `updated_at` | 无 | `split_status` + `failure_reason` | 同上 | **不改**，登记 |
| `split_reverse` | `created_at` + `updated_at` | `operator_id` | 状态 + 渠道退分单号 | 无 | **不改** |

全库无 `trace_id` 列。本卡不新增空列，登记为“日志/审计链路卡统一设计”的缺口。

## 5. 本卡改动与风险

| # | 改动 | 防住的错误操作 | 对合法流程的风险 |
| --- | --- | --- | --- |
| 1 | `trade_order.query_token` 改唯一键 | 两单同一免密 token，持 token 者可查/代付他人订单 | 碰撞时建单失败并走既有重试；重试内会重新生成 token，风险可接受 |
| 2 | `trade_order` 新增 `updated_at` | 无法判断订单金额/状态/退款预占的最近变更时间 | 纯新增列，默认值自动填充，不影响插入 |
| 3 | `merchant_payment_account.apply_status` 默认改 `AUDITING` | 漏写状态把未审核进件记为“已开通” | 演示种子显式写值；生产无默认插入路径 |
| 4 | `fee_policy_id`/`biz_id`/`payment_event_id`/`ledger_entry_id` 统一 UNSIGNED | 业务主键写成负数或与父键签名不一致，后续无法加外键 | 现有写入均为正主键，读取仍为 `Long` |
| 5 | 新增 `fk_settlement_item_event`、`fk_recharge_ledger_entry` | 结算明细/充值确认指向不存在的支付事件/账本分录，对账断链 | 两列均可空；写入顺序为父行先落库（已核对充值确认流程），不会阻断合法事务 |
| 6 | 新增 `chk_order_refund_within_paid` | 累计退款超过实付或服务费冲正超过快照 | 条件更新本来已拦截；CHECK 是**总量上限**（`refunded_amount <= pay_amount`、`fee_refunded_amount <= commission_amount`），不是逐次比例上限；尾差算例见 3.2，正尾差不会误伤 |
| 7 | 新增 `chk_ledger_balance_nonnegative` | 入账后余额/欠费为负 | 账户服务按余额与欠费分支，不会写负数；欠费记 `arrears` 正字段，不写负余额，见 3.1.1 |
| 8 | 新增 `chk_fee_policy_rate` | 写入 50%/负数等非法费率 | 服务层 `validateRate` 对三条插入路径均强制 0→PROMOTION 且原因非空；DDL 只表达 0↔PROMOTION，见 3.1.4 |
| 9 | 扩展 `chk_platform_settlement_amounts` | 退分累计超过商家应结净额 | 应用层已有 `refunded_amount + amount <= merchant_payable_amount` 条件更新 |
| 10 | `merchant_fee_policy` 新增 `updated_at` | 费率生效区间被事后改动且无可观测时间 | 纯新增列 |

## 6. 配套用例

新增 `shop-b2b2c-backend/src/test/java/com/shop/b2b2c/SchemaConsistencyConstraintTest.java`，只读 `schema.sql` 文本、不连库：

- 外键两端列类型必须一致（含 UNSIGNED），父表/父列必须存在。
- 资金列必须 `DECIMAL`、标度为 2（税率比例 `fee_tax_rate` 除外），全库无浮点类型。
- 状态列与 `is_*` 开关列必须 `NOT NULL`。
- 关键资金幂等唯一键的名称与列顺序必须与基线一致（含新增 `uk_query_token`），且不存在重复的普通 `idx_query_token`。
- `trade_order.query_token` 必须是 `VARCHAR(64) NOT NULL` 且由 `uk_query_token` 覆盖（对应 3.1.3 的结论）。
- 列解析已修正为匹配整行：原正则只截到类型前缀，`NOT NULL` 判定恒为 false，会让 `statusColumnsAreNotNull` 与新增的 `queryTokenIsNotNullAndUnique` 失真。
- 新增/扩展的 CHECK 表达式必须存在。
- 财务表审计时间戳：`trade_order.updated_at`、`merchant_fee_policy.updated_at` 及关键财务表 `created_at`。
- `apply_status` 默认值不得为 `PASSED`。

## 7. 登记缺口（本卡不改）

1. 全库无 `trace_id`：资金操作目前只能靠应用日志关联，需日志/审计链路统一设计。
2. 订单状态推进无独立审计表：`updated_at` 只给时间，不给操作人；卖家代客推单/人工关单的 actor 未落库。
3. `merchant_settlement_account` 默认账户唯一仅服务层保证，并发核验两个账户时依赖行锁与死锁回滚，缺显式唯一约束。
4. `merchant_fee_policy` 时间区间不重叠仅服务层保证，无法用普通唯一键表达。
5. 阶段二 `platform_collection_settlement` / `split_detail` 无操作人列，联调前补充。
6. `merchant_payment_account` 阶段二绑定状态变更无独立审计表。
7. `complaint.refund_amount` 与 `trade_refund` 无引用关系，仲裁退款金额可能只停留在工单。
8. `trade_order.query_token` 仅 `NOT NULL`，未禁止空串；当前三条建单路径均生成 32 位 UUID（见 3.1.3），但直接 SQL 或未来新增的非应用写入若写 `''`，第二张空 token 订单会被唯一键拒绝。若出现该类写入，再补 `CHECK (query_token <> '')`。
9. `merchant_fee_policy.promotion_reason` 的“推广必须填原因”只在应用层 `validateRate` 强制；DDL 只能表达 0↔PROMOTION，直接 SQL 仍可写 `PROMOTION + NULL` 原因（见 3.1.4）。

## 8. 未验证项

- 本卡按用户边界只改 `schema.sql`，未回写 V1–V6：`trade_order.updated_at`、`merchant_fee_policy.updated_at`、两个新增外键与 3 个新增/扩展 CHECK 不在迁移脚本中。开发库按 `schema.sql` 重建；若将来需要保留数据的库，须另开卡补增量，不能假设 V6 已覆盖。
- 未执行 `mvn`、编译、测试或生产构建；新增静态用例未经运行。
- 未连接真实 MySQL：`CREATE TABLE` 顺序、外键创建、CHECK 强制、唯一索引在空库上的实际行为均待用户演练。
- `CHECK` 强制依赖 MySQL 8.0.16+；低于该版本的实例会静默忽略，部署文档已要求 8.0.x，仍需以实际实例版本复核。
- 新增唯一键/外键对写入吞吐与索引体积的影响未评估。
- 本轮复核（3.1/3.2）只读代码与 `schema.sql`，未执行任何 SQL：欠费入账、尾差冲正、幂等重试均为静态推演，真实 MySQL 上 `CHECK` 的强制行为、`uk_query_token` 的碰撞重试次数仍待用户在手工验证时确认。
- 未验证 MySQL 在 `INSERT` 时对 `chk_order_refund_within_paid` 的取等边界（`= pay_amount`、`= commission_amount`）是否全部放行；按 MySQL 8.0 `CHECK` 语义应放行，但需实测。
- 未验证 `NOT NULL` + 空串 `''` 的直写行为；本卡按“应用层不写空串”保留，残留风险登记在 7.8。
