# T+1 定时对账与长短款分类：方案与问题清单

> 用途：第 ③ 卡的方案与问题清单。先于代码交付，用于确认对账覆盖范围、T+1 跑批幂等、长短款分类口径、差异留痕与数据来源缺口。
> 维护注意：本文件是设计与问题台账，不替代 `schema.sql`、接口契约或用户验收记录；实现状态最高为“已实现·待人工验证”。

## 1. 范围与非目标

本卡范围：

- 按天 T+1 自动跑批，覆盖订单实付、退款、服务费应收与账本、平台代收与结算。
- 长短款/单边账/精度/币种/跨天分类，差异只入队，不自动改账。
- 差异处理动作全量留痕，自动重试到上限转 `MANUAL` 且可见。
- 跑批幂等、断点续跑、失败重试与跑批结果可见。

非目标：

- 不接真实支付渠道 API，不做渠道账单自动下载。
- 不自动追平长款/短款，不自动补记收入或扣款。
- 不做汇率换算、跨币种折算。
- 不删除 V 迁移脚本，不改本卡无关的既有修改。

## 2. 现状与覆盖矩阵

现有 `ReconciliationService` 已具备：渠道流水导入幂等、流水↔支付事件、事件↔订单、缺失服务费账本、重复付款、结算批次合计、差异队列 `DISPUTED/RETRY/RESOLVED/IGNORED/MANUAL`、重试 3 次转人工、CSV 公式注入防护。

| 对账对象 | 现状 | 本卡动作 |
| --- | --- | --- |
| 渠道流水 ↔ 支付事件 | 已有金额/状态/订单号比对 | 补币种、跨天分类；差异带 `diff_category` |
| 支付事件 ↔ 订单 | 已有金额与缺单比对 | 复用，补分类 |
| 退款 ↔ 渠道退款流水 | **缺失** | 新增：成功退款逐笔匹配渠道退款流水，金额/缺流水/单边 |
| 服务费应收 ↔ 账本入账 | 只查“账本缺失” | 新增金额不一致；新增退款冲正账本缺失/金额不一致 |
| 平台代收分账 ↔ 结算 | **缺失** | 新增：关联支付事件金额、结算批次存在性、已结算金额一致性 |
| 结算批次合计 ↔ 逐笔明细 | 已有 | 复用，补分类与留痕 |
| 重复付款 | 已有 | 复用，不自动退款 |

## 3. T+1 触发与跑批设计

- 触发：Spring `@Scheduled`，默认每天 02:30（`shop.reconciliation.daily-cron`），时区 `Asia/Shanghai`（`shop.reconciliation.zone`）。
- 维度：按商户逐店跑批，每店每业务日一条跑批记录；单线程顺序执行，避免同一商户并发写差异。
- 业务日：默认“昨天”；回看 `shop.reconciliation.lookback-days`（默认 3）内的业务日，已成功的不重复跑，失败的可续跑。
- 切日字段与时区：
  - 渠道侧按 `payment_channel_statement.trade_time` 切日，解释为 `Asia/Shanghai` 墙上时间。
  - 我方支付事件按 `payment_event.created_at`；退款按 `trade_refund.updated_at`；平台代收按 `platform_collection_settlement.created_at`。
  - 窗口统一为 `[业务日 00:00, 业务日+1 00:00)`。
  - 跨天：若两侧单据都存在但业务日不同，记 `TIME_CROSS_DAY`，不按单边账处理；跨天归属以渠道交易时间所在业务日为准。
- 跑批幂等保证：
  1. `reconciliation_batch_run` 唯一键 `(business_date, merchant_id)`，同一业务日同一商户只有一条跑批记录。
  2. 跑批状态机 `PENDING/RUNNING/SUCCESS/FAILED/MANUAL`；`SUCCESS` 直接跳过，`RUNNING` 未超时跳过，超时视为僵尸可接管，`FAILED` 允许续跑，达到最大尝试次数转 `MANUAL`。
  3. 差异唯一键 `uk_difference_key`；同键未关闭只刷新金额/说明，不重复插入；已关闭不再改写。
  4. 自动重试同一业务日只允许一次：差异新增 `last_retry_date`，重复跑批不会把重试次数翻倍。
  5. 审计表只追加，不参与差异去重。
- 断点续跑：跑批记录按商户粒度落库，中断后可从未完成商户继续；手动入口按业务日重跑未成功商户。
- 失败处理：单店失败不影响其他商户；错误写入 `last_error`，达到上限转 `MANUAL`，跑批列表可见。
- 数据来源：本阶段没有真实渠道 API。渠道流水仍由管理端导入（JSON 列表），T+1 跑批假设业务日流水已在跑批前导入；未导入时跑批记录标记 `PARTIAL` 与“渠道流水未导入”，不把全部本地支付事件误判为单边账。

## 4. 长短款分类口径

分类字段 `reconciliation_difference.diff_category`，按以下优先级判定：

| 分类 | 判定 | 处理 |
| --- | --- | --- |
| `CURRENCY` | 两侧币种均非空且不一致 | 人工；不折算、不自动平账 |
| `ONE_SIDED_LOCAL` | 我方有、渠道无（事件/退款/账本/订单） | 挂账；下次跑批复核，必要时人工补证 |
| `ONE_SIDED_CHANNEL` | 渠道有、我方无（流水/退款流水） | 挂账；核对是否漏记事件或重复付款 |
| `PRECISION` | 金额差绝对值 ≤ 0.01 | 人工确认精度尾差；不自动平衡 |
| `LONG` | 渠道实收 > 我方应收，且差额 > 0.01 | 渠道多收；人工决定退款/挂账，走既有退款流程 |
| `SHORT` | 渠道实收 < 我方应收，且差额 > 0.01 | 渠道少收；人工决定补收/平台承担，不自动扣商家 |
| `TIME_CROSS_DAY` | 两侧存在且金额一致，但业务日不同 | 归属渠道交易日；人工确认，不重复入账 |
| `INTERNAL` | 订单缺失、事件状态异常、结算/账本内部不一致 | 人工或对应补偿流程 |

金额统一 `BigDecimal`，入库前 `setScale(2, HALF_UP)`；比较使用 `compareTo`，禁止浮点。`PRECISION` 是容差分类，不代表自动通过。

## 5. 差异留痕

新增 `reconciliation_difference_audit`，记录：差异 ID、动作、前状态、后状态、操作人、说明、时间。

- 动作：`CREATE`（自动入队）、`RETRY`（人工/自动重试）、`RESOLVE`、`IGNORE`、`ESCALATE`（转人工）。
- 自动跑批与自动重试的操作人记为系统（`0`），人工动作记录登录管理员 ID。
- `CREATE` 与状态迁移留痕；同一键的金额刷新不产生审计噪声。
- 达到重试上限转 `MANUAL` 时写 `ESCALATE`，跑批与差异列表均可查。

## 6. 问题清单

| ID | 问题 | 影响 | 本卡处理 | 当前状态 |
| --- | --- | --- | --- | --- |
| R1 | 没有按天自动跑批 | 只能人工按月触发，T+1 失效 | 新增每日任务与业务日跑批记录 | 已返工·待人工验证 |
| R2 | 退款完全没有与渠道退款流水对账 | 退款成功但渠道未退/金额不符无法发现 | 新增退款对账 | 已返工·待人工验证 |
| R3 | 服务费只查账本缺失，不查金额 | 账本金额写错仍显示通过 | 新增金额不一致差异 | 已实现·待人工验证 |
| R4 | 退款冲正账本缺失/金额不符未检查 | 退款成功但服务费未冲正 | 新增退款冲正账本比对 | 已实现·待人工验证 |
| R5 | 平台代收分账未对账 | 代收金额、结算批次、已结算金额可能不一致 | 新增平台代收对账 | 已实现·待人工验证 |
| R6 | 差异没有长/短/单边/精度/币种/跨天分类 | 运营无法按口径处理 | 新增 `diff_category` 与分类器 | 已返工·待人工验证 |
| R7 | 切日字段与时区未定义 | 跨天重复或漏对 | 明确 `Asia/Shanghai` 与各侧切日字段 | 已返工·待人工验证 |
| R8 | 同一天重复跑批会重复刷新/重复重试 | 差异噪声、重试次数虚高 | 跑批唯一键 + 状态机 + `last_retry_date` | 已返工·待人工验证 |
| R9 | 差异只保留最后一次处理人/说明 | 无法追责与复盘 | 新增审计表 | 已实现·待人工验证 |
| R10 | 自动重试到上限后仅改状态，无独立留痕 | 转人工不可追溯 | 写 `ESCALATE` 审计 | 已返工·待人工验证 |
| R11 | 渠道流水未导入时可能把本地事件全判单边 | 误报淹没真实问题 | 跑批标记 `PARTIAL`，不生成全量单边差异 | 已返工·待人工验证 |
| R12 | 跑批结果不可见 | 失败静默 | 新增跑批记录查询接口 | 已返工·待人工验证 |
| R13 | 渠道账单来源只有手工 JSON 导入 | 无法真正 T+1 自动拉单 | 明确缺口，不假装完成 | 登记缺口 |
| R14 | 平台代收结算的退款/结算状态组合未穷举 | 极端组合可能漏判 | 只校验可证明的恒等式，其余人工 | 登记缺口 |
| R15 | 渠道退款流水字段格式未经真实渠道验证 | 匹配可能失配 | 以 `channel_refund_no ↔ channel_trade_no` 为基线，真实渠道待联调 | 登记缺口 |

## 7. 用例计划（优先不连库）

- `ReconciliationClassifierTest`：长款、短款、单边（我方/渠道）、精度 0.01、币种不一致、跨天、金额一致。
- `ReconciliationServiceTest` 增补：退款缺流水、退款金额不符、服务费金额不符、退款冲正账本缺失、平台代收不一致、审计写入、同日重试不重复计数。
- `ReconciliationBatchServiceTest`：同日同店重复跑跳过、失败续跑、达到上限转 `MANUAL`、同日自动重试只执行一次、单店失败不影响其他商户。
- 静态 schema 用例：新表主键/唯一键/审计时间戳、差异分类列、跑批唯一键、外键类型一致。

## 8. 未验证项与缺口

- 未运行 `mvn`、编译、测试或构建，未连接 MySQL；跑批并发、唯一键冲突与真实 CHECK 行为待用户手工验证。
- 没有真实渠道 API/对账单文件，T+1 依赖管理端先导入流水；自动拉单仍是缺口。
- 渠道退款流水的真实字段、币种精度、跨天结算规则未联调。
- `success_at IS NULL` 的 `SUCCESS` 退款不会进入 T+1 窗口（缺口，未补数）：产生路径是上线前已成功的旧数据，或迁移/异常导致 `markSuccess` 未写入。跑批按商户每轮统计一次 `status='SUCCESS' AND success_at IS NULL` 的数量并 `warn` 告警（复用 `idx_refund_success` 的 `merchant_id` 前缀，不为巡检新增索引）；补数方案为按渠道退款流水 `trade_time` 回填，无流水时按 `updated_at` 的人工核对值回填并留审计，补数脚本/人工 SQL 须单独评审，本卡不自动改写历史资金记录。
- 支付事件仍按 `payment_event.created_at`（回调受理时间）切日，不是渠道成功时间；跨日回调可能归属错误业务日，已登记缺口（G21），缓解手段是渠道流水侧按 `trade_time` 对账并标 `TIME_CROSS_DAY`。
- 结算批次合计仅当对账窗口与批次开区间账期完全一致（`period_start = from`、`period_end = to`）时校验；当前只有 `SettlementService.generateMonthly` 实现月结，`periodEnd` 为次月 1 日。半年度/季度结的账期规则属于**设计约定、尚未实现生成器**，后续实现时必须沿用同一开区间口径。T+1 单日窗口不比对月结批次，避免恒不成立。
- 部署前提：`updated_at`/`finished_at` 等列的 `CURRENT_TIMESTAMP` 由数据库会话时区生成，必须与业务时区 `Asia/Shanghai` 对齐（连接串 `serverTimezone=Asia/Shanghai` 或库/会话 `time_zone='+08:00'`）。验收时执行 `SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP()`，确认 `NOW()` 与 `Asia/Shanghai` 当前时间偏差不超过 1 分钟；未对齐时 Java 截断时间与 DB 生成时间会漂移，`finishRun` 的 `started_at` 条件可能失配。
- 平台代收结算的极端状态组合（部分结算、多次退款、批次重算）只做静态恒等式校验，未做资金演练。
- 跑批回看窗口默认 3 天；超过窗口的历史失败需手动按业务日重跑。自动重试每店每次跑批只做一轮，`retry_failed` 已落库，但真实并发下的条件回写与接管行为仍待 MySQL 演练。

## 9. 实现落点（本卡）

- 对账主逻辑：`ReconciliationService`，新增退款↔渠道退款、服务费金额、退款冲正账本、平台代收、币种、跨天与分类写入。
- 分类规则：`ReconciliationClassifier`（纯静态，无数据库依赖）。
- 跑批：`ReconciliationBatchService` + `ReconciliationDailyJob`；手动入口与记录查询在 `AdminSettlementController`。
- 表结构：`reconciliation_batch_run`、`reconciliation_difference_audit`，差异表新增 `diff_category`、`business_date`、`last_retry_date`，渠道流水新增 `currency`；返工补充 `trade_refund.success_at`（不可变成功时点，含 `idx_refund_success` 索引）与 `reconciliation_batch_run.retry_failed`。
- 用例：`ReconciliationClassifierTest`、`ReconciliationBatchServiceTest`、`ReconciliationServiceTest` 增补与 `SchemaConsistencyConstraintTest` 基线更新。

状态：**已返工·待人工验证**。本卡提交说明记录返工范围、幂等保证手段与未验证项；未运行编译/测试/构建、未连接数据库。

### 9.1 人工验证入口

- 执行目录：`E:\shop-b2b2c`。
- 后端单测：`mvn -f shop-b2b2c-backend/pom.xml test`；预期新增/更新用例通过，且不连接业务数据库（H2 隔离）。
- 接口验证（启动后端后）：`POST /api/v1/admin/settlement/reconciliation/runs?businessDate=2026-11-05` 触发单日跑批；`GET /api/v1/admin/settlement/reconciliation/runs?businessDate=2026-11-05` 查看状态、`attempts`、`last_error` 与计数。
- 未验证项：真实 MySQL 的唯一键/CHECK/并发抢占、调度实际触发、渠道账单导入后的分类结果、前端管理页展示。

## 10. 复核返工记录（2026-11-06）

复核结论：基线提交 `d83e8db` **不能算实现完成**，以下逐项返工。原状态统一由“已实现”改为“已返工·待人工验证”。

| 返工项 | 原判断错在哪 | 返工落点 |
| --- | --- | --- |
| 必修一 退款切日 | 原按 `trade_refund.updated_at` 取窗口，但该列 `ON UPDATE CURRENT_TIMESTAMP`，`markSuccess` 会改写；同一条跨天退款被 D/D+1 两次扫到，`business_date` 被后一次覆盖，按月对账还会被统一盖成月初 | `trade_refund` 新增不可变 `success_at`，仅 `markSuccess` 写一次；窗口改按 `success_at`，新增 `idx_refund_success`；归属日优先匹配流水 `trade_time`，否则 `success_at`；刷新差异不再覆盖已定 `business_date`；保留渠道流水反查本地退款路径；T34 退款明细同改用 `success_at` |
| 必修二 PARTIAL 转人工 | 原 `markManual` 只允许 `FAILED/PENDING/僵尸RUNNING`，`PARTIAL` 达上限命中 0 行，记录永远停留 `PARTIAL`，汇总却照常 `manual++` | `markManual` 前置状态加入 `PARTIAL`；服务仅在实际更新 >0 时 `manual++`，否则计 `skipped`；补“连续 `PARTIAL` 达上限真转 `MANUAL`”与“命中 0 不虚报”用例 |
| 必修三 退款单边账门禁 | 支付事件侧有“该渠道当日流水 >0”门禁，退款侧没有；0 流水时每笔成功退款都生成 `REFUND_WITHOUT_STATEMENT`，与 `last_error` 文案“单边账暂不生成”自相矛盾 | 退款侧复用当期流水渠道集合做门禁，0 流水不生成该差异、交由跑批标记 `PARTIAL`；删除把“0 流水仍生成单边账”写成期望的旧用例，改为门禁用例 + 0 流水不生成用例 |
| 必修四 回看与重试冲突 | 原 `last_retry_date` 存跑批业务日，且同一次夜间任务对 `[今日-3, 今日-1]` 逐日调用 `autoRetry`，一条旧差异一夜被拆成 3 次重试计数 | 每店每次跑批只调用一次 `autoRetry`，`last_retry_date` 改用实际执行日；跳过 `createdAt >= runStartedAt` 的新建差异与未来业务日 |
| 必修五 重试异常不可见 | 原 `autoRetry` 只 `failed++` 与 warn，`RetryResult.failed` 未写入跑批记录与 Summary | 新增 `reconciliation_batch_run.retry_failed` 与 `addRetryTally`，异常摘要写 `last_error`，`failed` 计入汇总 |
| 支付事件切日 | 原按 `payment_event.created_at`（回调受理时间）切日，不是渠道成功时间 | 本卡未新增 `paid_at`，作为缺口登记于 `docs/2026-11-06-dev-gap-log.md` G21，风险：跨日回调可能归属错误业务日 |
| 结算批次合计 | 原声明“覆盖结算批次合计校验”，但 T+1 单日窗口与月结批次账期不重叠，校验恒不成立 | 第一轮改为“窗口完整等于 `period_start/period_end` 才校验”；第二轮发现该口径把开区间 `periodEnd` 误当闭区间，已回归修正，见第 11 节 |
| 时区混用 | 原多处 `LocalDateTime.now()` 用 JVM 默认时区，业务日按 `Asia/Shanghai` | 统一 `BUSINESS_ZONE`（`Asia/Shanghai`）取记录时间、审计时间与兜底时间 |
| 僵尸接管无租约 | 原超时任务被接管后，原节点结束时无条件 `updateById` 覆盖接管方结果 | 完成回写改 `finishRun` 条件更新（`status='RUNNING' AND attempts AND started_at`）；`updated==0` 计 `skipped`，不覆盖接管方。`started_at` 按 DATETIME 秒级精度截断，避免纳秒被库截断导致条件永不命中 |

## 11. 第二轮回核返工（2026-11-06）

第二轮复核：**6 项通过、2 项不通过、1 项待定口径**；以下 3 项继续返工，其中第 1 项为上一轮返工引入的回归。第三轮又补 3 个边界问题，见第 12 节。

| 返工项 | 错在哪 / 回归原因 | 返工落点 |
| --- | --- | --- |
| 必修一 结算批次合计（回归） | 上一轮把 `windowEnd` 定义成 `to.minusDays(1)`，再要求 `batch.periodEnd.equals(windowEnd)`；但批次账期是开区间，`SettlementService.generateMonthly` 与手工按月入口的 `periodEnd` 都是次月 1 日，导致原本可用的手工按月对账被判为“窗口不完整”，任何入口都不再校验合计 | 改为 `batch.getPeriodEnd().equals(to.toLocalDate())`（等价于 `windowEnd.plusDays(1)`）；用例改用真实账期形状 `periodStart=10-01, periodEnd=11-01`，删除 `periodEnd=10-31` 的假数据 |
| 必修二 退款单边账门禁只修一半 | 上一轮只给“带渠道号 + 渠道有流水”的 `REF|id|ST` 加了门禁，`REF|id|NO`（无渠道/无渠道退款号）与 `REFFEE`（服务费冲正）仍不受约束，0 流水时照旧生成 `REFUND_WITHOUT_STATEMENT`，与 `last_error` 文案矛盾 | 三条路径统一按“该退款渠道当期是否有流水”判断（渠道为空时退化为窗口内是否有任何流水）；0 流水全部推迟到流水导入后再判；跑批文案改为“相关单边账暂不生成” |
| 必修三 多渠道部分导入静默漏对 | 门禁与 `PARTIAL` 判定都按窗口总流水数：A 渠道已导入、B 渠道未导入时，B 的单边账被压住但总流水不为 0，跑批记 `SUCCESS`，既不报差异也不标不完整 | `reconcile` 按“本地单据实际使用的渠道”与“有流水的渠道”求差集，返回 `incompleteChannels`；跑标记 `PARTIAL` 并把缺失渠道写入 `last_error` |

### 11.1 登记缺口（本卡不修）

- `success_at IS NULL` 的成功退款不进 T+1 窗口：产生路径与补数方案见第 8 节；跑批已加数量巡检 `warn`，需人工按补数方案回填，不能当作无差异。
- DB `CURRENT_TIMESTAMP` 与 `Asia/Shanghai` 的会话时区对齐：作为部署前提写入第 8 节，验收时用 `SELECT @@session.time_zone, NOW(), UTC_TIMESTAMP()` 校验。

### 11.2 复核口径更正（`fk_statement_merchant`）

**重复问题在 `317cbce` 修复，早于本卡基线 `d83e8db`；本卡基线时该重名已不存在。前一版本节“首轮发现属实、第二轮属时空错位”的表述有误，现更正：修复记录保留，但不能认为本卡基线仍存在重复。** 详见 [schema 吸收审计](2026-11-06-schema-absorption-audit.md) §5.2。

## 12. 第三轮回核返工（2026-11-06）

第三轮确认上一轮三条必修代码主体已修好，但改动带出 3 个边界问题。

| 项 | 触发场景 | 处理 |
| --- | --- | --- |
| 必修一 空串渠道 | 收集本地单据渠道只排除 `null`，空串 `""` 被当成真实渠道；流水导入强制非空，`""` 永远不在流水渠道集合，该店每天 `PARTIAL`，三次后错误转 `MANUAL` 且永不消失 | 事件与退款两处收集渠道都改为 `!isBlank()`；空串不进入缺失渠道差集 |
| 必修二 非成功事件污染差集 | 事件循环最前收集渠道时未筛状态；`PROCESSING`/`MANUAL_REVIEW`/`MERCHANT_MISMATCH`/`CURRENCY_MISMATCH` 等天然不会产生本商户流水，却被判“渠道未导入”，三次后永久错误升级 `MANUAL` | 仅 `SUCCESS`、`DUPLICATE_PAYMENT`、`LATE_PAYMENT` 计入差集；`PROCESSING`（未确认）、`MANUAL_REVIEW`（结果未知）、`MERCHANT_MISMATCH`（他人商户）、`CURRENCY_MISMATCH`（币种异常）、`AMOUNT_MISMATCH`（金额异常待核）、`FULFILLMENT_FAILED`（已支付后履约失败与“明确未支付/查无此单”语义混合）不参与该判定，但仍按各自逻辑参与原有对账/跳过处理 |
| 必修三 `success_at` 巡检全局扫描 | `countSuccessWithoutSuccessAt` 没有 `merchant_id`，却在“店铺 × 业务日”循环内调用，回看 3 天即 `3 × 店铺数` 次全表扫描，且用不上 `(merchant_id, success_at)` 索引 | 采用方案①：按 `merchant_id` 统计，调用上移到 `runRange` 每店每轮一次；复用 `idx_refund_success` 前缀，不为巡检新增 `(merchant_id, status, success_at)` 索引 |

新增反向用例：空串渠道不进入 `incompleteChannels` 且跑批不 `PARTIAL`；只有 `MERCHANT_MISMATCH`/`PROCESSING` 事件的渠道不判缺失；流水落在相邻业务日但已匹配的渠道不留下永久 `PARTIAL`；巡检按商户每轮只统计一次。