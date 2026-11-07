# Sprint 30 — 商户账单生成（F09）

## 主台账编号
- F09 商户账单生成：以支付/退款事件生成商户日/月账单，提供查询、导出与重算。

## 实际子范围
- 后端：`BillingStatementService` 聚合已支付订单与成功退款为 `billing_statement` 行，支持日/月账期、状态判定（PENDING/SETTLED/DISPUTED）、upsert 幂等、CSV 导出（含公式注入防护）。
- 后端：商家端只读接口（查询+导出，仅店长）、平台超管接口（全平台查询+重算+导出）、定时任务（每日昨日日账单、每月上月月账单）。
- 前端：商家「本店账单」页与超管「商户账单治理」页（类型切换、日期范围、分页、导出、重算），纯规则模块 `billingStatement.mjs`。
- 复用：`findFeeBearingByMerchantAndPeriod`、`findSuccessfulRefundsByPeriod`、`ServiceFeeSnapshotService.resolveFeeAmount`，不新增退款查询。

## 代理细化规则（待业务确认，不冒充已确认业务规则）
- 金额口径：GMV=成交总额；代付/自付按 `peer_pay_type>0` 拆分；`refund_amt`=窗口内 source=ORDER 的成功退款；`fee_amt`=订单应收服务费合计 − 退款冲正服务费（可为负）。
- 状态：窗口内存在未关闭对账差异（DISPUTED/RETRY/MANUAL）→ DISPUTED；窗口未结束 → PENDING；否则 SETTLED。
- 跨度上限：日账单 92 天、月账单 24 个月；MONTHLY 的 periodDate 归一到月初。
- 非法金额（null/负/超 2 位小数）跳过并告警；非 ORDER 来源退款不计入口径。
- 商家端不提供触发入口，账单由定时任务与超管重算生成。

## 验收状态
- 待人工验证。后端 H2 单测+集成通过、shared 规则通过、管理端生产构建通过；真实 MySQL 唯一键并发、调度触发、渠道联调未覆盖。

## 验证命令与结果
- 后端：`mvn -f shop-b2b2c-backend/pom.xml test` → Tests run: 1044, Failures: 0, Errors: 0, Skipped: 1, BUILD SUCCESS。
- shared 规则：`node --test ... shared/billingStatement.test.mjs`（含全部 23 个规则用例文件）→ tests 322, pass 322。
- 管理端构建：`npm --prefix shop-admin-vue run build` → ✓ built in 10.56s。

## 未验证项
- 真实 MySQL 下 `uk_shop_period` 并发插入撞键降级更新（单测用 Mockito 覆盖，集成未构造并发）。
- 定时任务 cron 实际触发（Spring 调度，单测只验证遍历与失败隔离）。
- 渠道联调与跨月退款冲正的真实到账证明。

## 文件变更
- 新增：`BillingStatementService.java`、`BillingStatementController.java`、`AdminBillingStatementController.java`、`BillingStatementDTOs.java`、`BillingStatementJob.java`、`CsvEscaper.java`、`BillingStatementServiceTest.java`、`BillingStatementJobTest.java`、`billingStatement.mjs`、`billingStatement.test.mjs`、`views/merchant/Billing.vue`、`views/admin/Billing.vue`。
- 修改：`BillingStatementMapper.java`（pageByPeriodRange/listByPeriodRange）、`ReconciliationDifferenceMapper.java`（countOpenByMerchantAndBusinessDateBetween）、`ReconciliationService.java`（CSV 转义委托 CsvEscaper）、`router/index.js`、`AdminLayout.vue`、`sprint1-schema.sql`（billing_statement 唯一键、reconciliation_difference.business_date）、`Sprint1SecurityIntegrationTest.java`（+6 集成用例）、`AGENTS.md`。
