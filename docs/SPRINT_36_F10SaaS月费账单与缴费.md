<!--
文件：SPRINT_36_F10SaaS月费账单与缴费.md
用途：F10「SaaS 服务费账单和缴费」本轮交付记录：已实现范围、代理细化规则、接口、数据库变更、测试结果与未验证项。
维护注意：
 1. 本记录只描述代理已实现并有测试覆盖的内容；所有条目均为“待人工验证”，不得据此宣称业务验收通过。
 2. 代理细化的规则（次月 10 日到期、免收判定、仅线下缴费、不自动停复服）必须由业务确认；变更时同步更新本文件与台账。
 3. 月度 SaaS 月费与交易抽佣/merchant_account 账户分离；不得把本表数据并入 F09 商户账单或 T34 结算批次。
 4. 重新执行验证时使用下方命令；真实 MySQL 迁移（V19）与浏览器交互不能由 H2/构建结果替代。
-->
# Sprint 36：F10 SaaS 月费账单与缴费

- 日期：2026-11-07
- 主台账：F10（SaaS 服务费账单和缴费）
- 状态：部分修复 · 待人工验证
- 执行方：代码代理实现与静态审阅；编译、测试执行、生产构建按本轮授权由代理运行，结果仅作参考，验收由用户管理。

## 1. 本轮范围

F10 初始清单要求：账期、试用/豁免判定、账单、支付、欠费提醒、停复服规则，并与交易抽佣区分。本轮落地最小闭环：

1. 新增 `saas_fee_bill` 表：同店同月唯一，一个自然月一张固定月度技术服务费账单。
2. 按月幂等生成：同店同月已存在（含已缴/免收）原样返回，不重算覆盖。
3. 免收判定：`monthly_fee=0`、`fee_status=EXEMPTED`、`fee_status=TRIAL`、`fee_free_until` 覆盖当月最后一日，记 `WAIVED` 且应收 `0.00`，`standard_amount` 保留配置原值。
4. 缴费：仅超管登记线下转账人工确认（`OFFLINE` + 凭证号），双人复核复用 `FinanceApprovalGuard`。
5. 逾期扫描：已过到期日仍 `PENDING` 的账单置 `OVERDUE`，写站内信；重复扫描不重复通知。
6. 查询：商家只读本店账单；超管跨店筛选 + 单店手动生成。
7. 定时任务：每月 1 日 01:10 为全部店铺生成上一自然月账单；每日 02:10 扫描逾期。
8. 站内信：出账、逾期、缴费完成三类事件写入 `merchant_notification_outbox`。

不包含：自动从商家账户扣费、自动停复服/改店铺状态、在线支付、账单作废/退款、短信/邮件催缴。

## 2. 代理细化规则（待业务确认）

| 规则 | 本轮实现 | 说明 |
| --- | --- | --- |
| 出账月份 | 只能为已结束的自然月生成 | 避免对未结束账期提前出账；手动与定时入口一致 |
| 到期日 | 次月 10 日 | `due_date = 次月10日`，未接业务确认，后续可调整为配置项 |
| 免收口径 | 0 月费/EXEMPTED/TRIAL/免费期覆盖当月末日 | TRIAL 一律免收；免费期按 `fee_free_until >= 当月最后一日 00:00` 判定 |
| 缴费方式 | 仅 `OFFLINE` 线下转账 | 必须填凭证号（≤64），备注 ≤255；不接在线支付 |
| 逾期处置 | 只改账单状态 + 站内信 | 不自动停店/停服，不改 `merchant_store.fee_status`，避免与 O6 店铺状态机冲突 |
| 账户关系 | 不读写 `merchant_account`/账本/结算批次 | 防止月度固定费与订单抽佣资金混算 |

## 3. 数据库变更

- 新表 `saas_fee_bill`（基线第 37 张表）：`uk_saas_fee_bill_no`、`uk_saas_fee_merchant_month`、`idx_saas_fee_status_due`、外键 `merchant_store`。
- `src/main/resources/db/schema.sql` 已同步；`src/test/resources/sprint1-schema.sql` H2 夹具同步（H2 不含外键）。
- 保留数据的开发库使用幂等迁移 `db/migrations/V19__f10_saas_fee_bill.sql`（`CREATE TABLE IF NOT EXISTS`，不回填历史账单）。
- 不新增列、不修改 `merchant_store` 既有 `monthly_fee/fee_free_until/fee_status` 语义。

## 4. 后端接口

商家（`/api/v1/merchant/settlement`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/saas-bills?limit=24` | 本店月费账单，按账期倒序 |
| GET | `/saas-bills/{billNo}` | 本店账单详情，越店 404 |

超管（`/api/v1/admin/settlement`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/saas-bills?merchantId&status&month&limit&offset` | 跨店筛选，最多 200 条/页 |
| POST | `/merchants/{merchantId}/saas-fee-bills/generate?month=yyyy-MM` | 单店生成，幂等 |
| POST | `/saas-bills/{billNo}/confirm` | 线下缴费确认，body：paymentMethod/voucherNo/remark/confirmedBy |
| POST | `/saas-bills/overdue-scan?asOf=yyyy-MM-dd` | 手动逾期扫描 |

所有超管接口控制器内显式 `StpUtil.checkRole("SUPERADMIN")`；商家接口固定会话店铺，不接受客户端 `merchantId`。

## 5. 前端

- 商家端 `/merchant/saas-fee`：只读账单表（账期、标准月费、应收、状态、到期日、免收原因、缴费信息）。
- 超管端 `/admin/saas-fee`：筛选、分页、单店生成、缴费确认对话框（凭证号 + 复核人）、逾期扫描。
- 纯规则 `shop-admin-vue/src/utils/saasFeeBill.mjs`：状态文案/颜色、账期格式、查询与请求参数校验。
- 路由与菜单均为店长/超管可见；员工直接访问由路由守卫与后端角色双重拒绝（账单页 role=MERCHANT_ADMIN）。

## 6. 测试与验证（代理执行，仅作参考）

| 命令 | 结果 |
| --- | --- |
| `mvn -f shop-b2b2c-backend/pom.xml test` | 1086 项通过，0 失败/0 错误，1 项既有跳过（`SchemaMySqlTest`） |
| `node --test shared/saasFeeBill.test.mjs` | 6 项通过 |
| `npm --prefix shop-admin-vue run build` | 通过（Vite 5，1738 模块） |

新增覆盖：

- `SaasFeeBillServiceTest` 9 项：幂等生成、未来月份拒绝、四类免收、免费期未覆盖收费、缴费确认与复核、重复/免收/非法方式拒绝、逾期置位、列表参数边界。
- `shared/saasFeeBill.test.mjs` 6 项：状态/账期、查询归一与非法值、生成请求、线下缴费请求边界。

## 7. 未完成与未验证

- 未做自动扣费、自动停复服、店铺状态联动；`merchant_store.fee_status` 保持配置标记语义。
- 未实现在线支付、账单退款/作废、滞纳金与阶梯月费、短信/邮件催缴、缴费凭证附件上传。
- 到期日 10 日、TRIAL 一律免收、免费期覆盖判定均为代理细化，待业务确认。
- 真实 MySQL 执行 V19 迁移、`schema.sql` 全新建库、并发重复生成/并发缴费的唯一键与行锁行为未验证。
- 浏览器交互（筛选、对话框、复核人选择）未验证；H2 通过不能替代 MySQL 与真实前端联调。
- 定时任务 cron 未在真实环境触发验证；仅通过手动接口与单测覆盖语义。

## 8. 回滚与兼容

- 新表独立，删除/停用不影响既有交易抽佣、结算批次与商家账户；无既有表结构变更。
- 若业务确认不启用月度 SaaS 费，可将月费配置保持 0（全部 `WAIVED`），无需改代码；不建议直接删表以免丢失已缴/免收记录。
