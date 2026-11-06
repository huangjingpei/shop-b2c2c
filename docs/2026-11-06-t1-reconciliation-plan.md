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

**方向约定（防反接）**：`ReconciliationClassifier.classify` 前三个参数固定为平台侧（应有金额/币种/记账时间），后三个固定为渠道侧（实际金额/币种/交易时间）；`platformExpected=null` 表示我方无单据 → `ONE_SIDED_CHANNEL`，`channelActual=null` 表示渠道无单据 → `ONE_SIDED_LOCAL`。`ReconciliationService` 的三处调用（订单↔事件、事件↔流水、退款↔流水）均按“平台在前、渠道在后”传参，2026-11-07 全量测试已核对；修改调用方或用例时不得交换参数顺序。

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
| 必修二 非成功事件污染差集 | 事件循环最前收集渠道时未筛状态；`PROCESSING`/`MANUAL_REVIEW` 等结果未知或被拒绝的事件也被判“渠道未导入”，三次后永久错误升级 `MANUAL` | 改为按“是否已收到渠道回调、资金是否已进本商户渠道账户”筛状态；**本卡第三轮曾写成“仅 `SUCCESS`/`DUPLICATE_PAYMENT`/`LATE_PAYMENT` 三种”，该口径是错误判断（见第 13 节更正）**，现为七个状态，见 §13.1 |
| 必修三 `success_at` 巡检全局扫描 | `countSuccessWithoutSuccessAt` 没有 `merchant_id`，却在“店铺 × 业务日”循环内调用，回看 3 天即 `3 × 店铺数` 次全表扫描，且用不上 `(merchant_id, success_at)` 索引 | 采用方案①：按 `merchant_id` 统计，调用上移到 `runRange` 每店每轮一次；复用 `idx_refund_success` 前缀，不为巡检新增 `(merchant_id, status, success_at)` 索引 |

新增反向用例：空串渠道不进入 `incompleteChannels` 且跑批不 `PARTIAL`；流水落在相邻业务日但已匹配的渠道不留下永久 `PARTIAL`；巡检按商户每轮只统计一次。第三轮“只有 `MERCHANT_MISMATCH`/`PROCESSING` 事件的渠道不判缺失”的用例已在第四轮改写，见 §13.3。

## 13. 第四~七轮回核返工（2026-11-06）

第四轮更正上一轮的差集状态口径，并补“非成功事件不得静默”的可见信号；第五轮新增 `funds_received` 三值拆分“钱有没有到账”，并纠正两条前提判断；第六轮纠正第五轮把渠道退款 `REFUNDED` 归为“明确未到账”的错误，并禁止补偿链路覆盖已确认的 `funds_received=1`；第七轮定死 `1` 的语义并全量对齐文档与用例，按成因拆出可筛选的差异类型，并补充支付宝退款金额证据判定。

**明确更正：本卡前一轮（第三轮）“仅 `SUCCESS`、`DUPLICATE_PAYMENT`、`LATE_PAYMENT` 三种状态计入差集”的口径是错误判断，已在本轮纠正。该错误导致 `AMOUNT_MISMATCH` 等已收款事件既不进差集、也被事件循环直接 `continue`，形成新的静默漏对。后续维护者不得照抄旧三类口径。**

### 13.1 状态清单与取舍理由

`STATEMENT_EXPECTED_EVENT_STATUSES` = `SUCCESS`、`DUPLICATE_PAYMENT`、`LATE_PAYMENT`、`MERCHANT_MISMATCH`、`AMOUNT_MISMATCH`、`CURRENCY_MISMATCH`、`FULFILLMENT_FAILED`。状态命中集合只是必要条件，**还必须 `funds_received=1`（渠道侧存在该笔资金往来、应有对应流水）才计入差集**；三值语义见 §13.5。

支付事件状态清单（`payment_event.event_status`）明确包含 `PROCESSING`、`SUCCESS`、`MANUAL_REVIEW`、`DUPLICATE_PAYMENT`、`AMOUNT_MISMATCH`、`CURRENCY_MISMATCH`、`MERCHANT_MISMATCH`、`LATE_PAYMENT`、`FULFILLMENT_FAILED`，以及收款后写入的 `ORDER_STATUS_INVALID`。其中 `MANUAL_REVIEW` 是 G34 重试耗尽后的人工队列状态（**前一轮“MANUAL_REVIEW 不是支付事件状态”的判断有误，已纠正**）。

| 事件状态 | 是否计入“应产生本商户流水” | 理由 |
| --- | --- | --- |
| `SUCCESS` | `funds_received=1` 时计入 | 渠道支付成功，必然有对应流水 |
| `DUPLICATE_PAYMENT` | `funds_received=1` 时计入 | 同一订单重复收款，资金已进渠道账户，流水至少一笔；重复部分另有 T33 补偿差异 |
| `LATE_PAYMENT` | `funds_received=1` 时计入 | 回调晚到但已收款，资金已进渠道账户 |
| `MERCHANT_MISMATCH` | `funds_received=1` 时计入 | 由 `PaymentEventServiceImpl.reject(...)` 在**已收到渠道支付回调之后**写入，资金已进渠道账户，不能因商户号校验失败就认定无流水 |
| `AMOUNT_MISMATCH` | `funds_received=1` 时计入 | 同上，回调已到、金额与预期不符，资金已收，只是业务侧拒绝 |
| `CURRENCY_MISMATCH` | `funds_received=1` 时计入 | 同上，币种校验失败不改变“已收款”事实 |
| `FULFILLMENT_FAILED` | 仅 `funds_received=1` 时计入 | **一码多义（前一轮把它当成单一“已收款”状态是错误判断，已纠正）**：回调后 `reject(...)` 写入为 `1`；G34 渠道退款 `REFUNDED` 写 `1`；G34 渠道明确未支付**请求写 `0`，但新事件占位即 `1`，SQL `CASE` 保留 `1`，形成“渠道答复与已收款记录冲突”行**；G34 查单前置校验失败写 `NULL`。**第五轮把 `REFUNDED` 归为 `0` 是错误，第六轮已纠正（见 §13.6）；第七轮定死 `1` 语义并明确 `0` 仅对未标注行生效（见 §13.7）** |
| `PROCESSING` | 不计入 | 渠道结果尚未确认；按 §13.2 的悬挂阈值单独给信号 |
| `MANUAL_REVIEW` | 不计入 | G34 重试耗尽后的人工队列，资金状态未知且固定回写 `funds_received=NULL`；生成 `PAYMENT_EVENT_REVIEW` 差异兜住 |
| 其它状态（如 `ORDER_STATUS_INVALID`） | 不计入 | 无法证明“本商户渠道已收款”；仍生成 `PAYMENT_EVENT_REVIEW` 差异，见 §13.2 |

不计入差集只表示“不能据此判定渠道未导入”，不等于允许静默：事件循环对所有非 `SUCCESS`（`DUPLICATE_PAYMENT` 单独处理）事件调用 `signalNonSuccessEvent`。

### 13.2 非成功事件的可见信号与选择理由

| 场景 | 信号 | 说明 |
| --- | --- | --- |
| 状态在七状态集合内、`funds_received=1`、`FULFILLMENT_FAILED` 且原因为退款类 | 生成 `PAYMENT_EVENT_REFUNDED`（键 `EV\|{eventId}\|REFUNDED`，分类 `INTERNAL`） | 渠道已收款后已退款，需核对退款流水与履约结果；**不得写“未收到资金”** |
| 状态在七状态集合内、`funds_received=1`、`FULFILLMENT_FAILED` 且原因为渠道明确未支付或“支付宝关闭无法判定” | 生成 `PAYMENT_EVENT_CHANNEL_CONFLICT`（键 `EV\|{eventId}\|CHANNEL_CONFLICT`，分类 `INTERNAL`） | 渠道答复与占位记录冲突，可能为全额退款或渠道状态异常，需核对渠道流水；`detail` 带 `state=XXX` |
| 状态在七状态集合内且 `funds_received=1`、其余拒绝 | 生成 `PAYMENT_EVENT_REJECTED` 差异（键 `EV\|{eventId}\|{status}`，分类 `INTERNAL`，渠道/事件单据/金额可定位） | 渠道已收款但履约或校验被拒，需人工核对资金与履行结果 |
| 状态在集合内且 `funds_received=0` | 生成 `PAYMENT_EVENT_REJECTED` 差异 | **仅未标注行/历史或异常行**：明确无资金往来，不预期流水、不进差集；但拒绝事件仍必须可见，不能因“本来就没钱”静默 |
| 状态在集合内且 `funds_received IS NULL` | 生成 `PAYMENT_EVENT_REVIEW` 差异，文案含“funds_received IS NULL” | 未标注必须人工确认是否已收款；**不得把 NULL 当成“无需流水”静默放过**。`SUCCESS`/`DUPLICATE_PAYMENT` 的历史漏写行也走此信号（键 `EV\|{eventId}\|FUNDS`） |
| `PROCESSING` 且 `created_at` 早于当前时间 **5 分钟** | 生成 `PAYMENT_EVENT_REVIEW` 差异（键 `EV\|{eventId}\|PROCESSING`） | 视为“悬挂”：渠道回查迟迟未回写，需人工确认 |
| `PROCESSING` 未超 5 分钟 | 不生成差异，计入 `reconcile` 返回的 `processingPending`，并由跑批汇总 `processingPending` 可见 | 渠道回查仍在进行，逐笔入队只会制造噪音 |
| 其它状态（`ORDER_STATUS_INVALID`、`MANUAL_REVIEW` 等） | 生成 `PAYMENT_EVENT_REVIEW` 差异 | 无法归类或结果未知也不得静默 |

- **悬挂阈值口径**：5 分钟，与 G34 `PaymentEventReconcileService` 陈旧 `PROCESSING` 补偿扫描的默认阈值一致，避免对账侧与补扫侧出现两套“悬挂”定义。`created_at` 缺失时无法判龄，按“未悬挂”处理，交给 G34 回查补写。
- **选择“生成差异”而不是只做汇总计数的理由**：差异可分派、可审计（`reconciliation_difference_audit`）、可按 `biz_id` 定位到具体事件，`retry` 在事件回写为 `SUCCESS` 后可自动关闭；只写日志或只做汇总计数无法定位是哪一笔，也无法进入人工处理队列。跑批侧同时保留 `processingPending` 汇总，覆盖“尚未悬挂、暂不入队”的在途事件。
- **新增漏对场景（第四轮修复目标）**：A 渠道流水已导入、B 渠道未导入且 B 当天只有 `AMOUNT_MISMATCH` 事件时，第三轮口径下总流水数非 0、差集为空、事件被 `continue` 跳过，跑批记 `SUCCESS`。第四轮起 B 进入 `incompleteChannels` → 跑批 `PARTIAL` 且 `last_error` 点名 B，同时生成 `PAYMENT_EVENT_REJECTED` 差异；即便 B 流水已导入，仍有该差异作为信号。
- **第五轮补充（资金三值）**：`funds_received=1` 才进差集；`=0` 不进差集但落 `REJECTED`；`NULL` 不进差集但落 `REVIEW`。这样既消除“渠道明确未支付被每天判未导入”的持续误报，又不让未标注事件静默消失。第七轮进一步定死 `1` 的语义（渠道侧存在资金往来），并明确 `0` 仅对未标注行/历史或异常行生效。
- **第七轮补充（差异类型可筛选）**：`funds_received=1` 的失败事件按 G34 原因前缀拆成 `PAYMENT_EVENT_REFUNDED` / `PAYMENT_EVENT_CHANNEL_CONFLICT` / `PAYMENT_EVENT_REJECTED`，差异键分别带 `REFUNDED`、`CHANNEL_CONFLICT` 或事件状态，人工可按类型筛选、统计与分发，不再依赖 `detail` 文案。
- `PAYMENT_EVENT_REJECTED` / `PAYMENT_EVENT_REFUNDED` / `PAYMENT_EVENT_CHANNEL_CONFLICT` / `PAYMENT_EVENT_REVIEW` 在 `retry` 中判定：事件已回写 `SUCCESS` 才可自动关闭（`REVIEW` 还要求 `funds_received` 非 `NULL`），否则保持人工队列。

### 13.3 防回退用例

- `amountMismatchOnlyChannelIsIncompleteAndQueuesRejectionDifference`：A 渠道有流水、B 渠道只有 `AMOUNT_MISMATCH` → `incompleteChannels=ALIPAY`、`added=1`、差异类型 `PAYMENT_EVENT_REJECTED`、金额 `20.00`。
- `fulfillmentFailedEventIsIncompleteAndQueuesRejectionDifference`、`currencyMismatchEventIsIncompleteAndQueuesRejectionDifference`：同上，分别断言 `UNIONPAY`/`ALIPAY` 进入差集且差异文案含对应状态。
- `staleProcessingIsVisibleEvenWhenOtherChannelHasStatements`：其他渠道有流水时，悬挂 `PROCESSING` 仍生成 1 条 `PAYMENT_EVENT_REVIEW` 差异（文案含“5分钟”），且不误判渠道缺失。
- `freshProcessingIsCountedInSummaryWithoutNoiseDifference`：未悬挂 `PROCESSING` 生成 0 条差异、`processingPending=1`。
- `unknownEventStatusStillQueuesReviewDifference`：`ORDER_STATUS_INVALID` 生成 1 条 `PAYMENT_EVENT_REVIEW`，不静默。
- 跑批侧 `onlyAmountMismatchChannelIsNeverReportedAsSuccess`：`partial=1`、`success=0`、`run.status=PARTIAL`、`last_error` 含 `ALIPAY`。
- 跑批侧 `fulfillmentAndCurrencyMismatchChannelsAreNeverReportedAsSuccess`：`partial=1`、`success=0`、`differencesAdded=2`、`last_error` 含两个渠道。
- 跑批侧 `freshProcessingIsVisibleInRunSummaryCount`：`success=1` 时汇总仍给出 `processingPending=2`。

- `explicitlyNotPaidEventIsNotIncompleteButStillHasDifference`：`funds_received=0` + 同渠道未导入流水 → `incompleteChannels=""`（不 `PARTIAL`）、`added=1`、类型 `PAYMENT_EVENT_REJECTED`。
- `unmarkedFundsEventIsNotIncompleteButStillHasReviewDifference`：`funds_received=NULL` → `incompleteChannels=""`、`added=1`、类型 `PAYMENT_EVENT_REVIEW`（文案含 `funds_received IS NULL`）。
- `mixedFundsReceivedFlagsStillMarkChannelIncomplete`：同一渠道同时有 `=1` 与 `=0` 事件 → `incompleteChannels=ALIPAY`、`added=2`（只要一条 `1` 就必须导入流水）。
- `manualReviewEventIsVisibleButNotTreatedAsExpectedStatement`：`MANUAL_REVIEW` + `funds_received=NULL` → 不进差集、`added=1`、类型 `PAYMENT_EVENT_REVIEW`。
- 跑批侧 `notPaidOrUnmarkedFundsStaySuccessButDifferencesAreCounted`：`success=1`、`partial=0`、`differencesAdded=2`（不误报但差异可见）。
- `successEventWithoutFundsAnnotationStillProducesReview`：`SUCCESS` 但 `funds_received=NULL` → 不进差集、`added=1`、类型 `PAYMENT_EVENT_REVIEW`（文案含 `funds_received IS NULL`），`retry` 不会在资金未标注时自动关闭。
- 集成用例补充：G34 明确未支付仅在**未标注行/历史或异常行**写 `funds_received=0`（新事件占位 `1` 会被保留并归冲突类）；G34 重试耗尽 `MANUAL_REVIEW` 的 `funds_received IS NULL`；渠道确认成功事件 `funds_received=1`。

第六轮补充：
- `refundedEventIsIncompleteAndDetailSaysRefundedNotUnreceived`：`REFUNDED`（`FULFILLMENT_FAILED` + `funds_received=1`）+ 该渠道支付流水未导入 → `incompleteChannels=ALIPAY`（跑批据此 `PARTIAL`），差异类型 `PAYMENT_EVENT_REFUNDED`、键 `EV|16|REFUNDED`（第七轮拆分），文案含“渠道已收款后已退款”且**不含“未收到资金”**。
- `retryResolvesPaymentEventReviewOnlyWhenEventSuccessAndFundsAnnotated`：`PAYMENT_EVENT_REVIEW` + 事件转 `SUCCESS` 且 `funds_received=1` → 差异状态 `RESOLVED`。
- `retryKeepsPaymentEventReviewOpenWhenFundsStillUnmarked`：`PAYMENT_EVENT_REVIEW` + 事件 `SUCCESS` 但 `funds_received IS NULL` → 状态 `RETRY`（不关闭）。
- `retryKeepsPaymentEventReviewOpenWhenEventNotSuccess`：`PAYMENT_EVENT_REVIEW` + 事件仍 `FULFILLMENT_FAILED` → 状态 `RETRY`。
- `retryResolvesPaymentEventRejectedOnlyWhenEventSuccess`：`PAYMENT_EVENT_REJECTED` + 事件转 `SUCCESS` → `RESOLVED`；事件仍 `AMOUNT_MISMATCH` → `RETRY`。
- 集成 `g34RefundedKeepsFundsReceivedAndFailsFulfillment`：渠道 `REFUNDED` → 状态 `FULFILLMENT_FAILED`、`funds_received=1`、`error_message` 含“渠道已收款后已退款”。
- 集成 `g34DefinitiveNotPaidKeepsFundsOnClaimedEvent`：占位 `funds_received=1`（渠道侧存在资金往来）+ 渠道 `CLOSED` → 状态 `FULFILLMENT_FAILED` 但 `funds_received` 仍为 `1`（断言列值，不只看差异）。
- 集成 `g34DefinitiveNotPaidMarksZeroWhenFundsUnmarked`：占位 `NULL` + 渠道 `NOT_EXIST` → `funds_received=0`。
- 集成 `g34ExceedingRetryLimitMovesToManualReview` 起点改为 `funds_received=1`，再断言被显式写成 `NULL`，证明 `SET funds_received=NULL` 生效（起点为 `NULL` 时该断言无法证明任何事）。

第七轮补充：
- `refundedEventIsIncompleteAndDetailSaysRefundedNotUnreceived`：断言差异类型 `PAYMENT_EVENT_REFUNDED` 与键 `EV|16|REFUNDED`（不再用 `PAYMENT_EVENT_REJECTED`）。
- `channelConflictEventUsesOwnTypeAndKey`：`FULFILLMENT_FAILED` + `funds_received=1` + 原因“渠道明确未支付: state=CLOSED” → 类型 `PAYMENT_EVENT_CHANNEL_CONFLICT`、键 `EV|17|CHANNEL_CONFLICT`、文案含冲突与 `state=CLOSED`。
- `refundedAndConflictDifferencesUseDistinctTypesAndKeys`：同一渠道两条事件同时断言两类差异的类型与键互不相同。
- `retryResolvesRefundedAndConflictDifferencesWhenEventSuccess`：两类差异在事件转 `SUCCESS` 后均 `RESOLVED`。
- `retryKeepsRefundedAndConflictDifferencesOpenWhenEventNotSuccess`：事件仍 `FULFILLMENT_FAILED` 时均 `RETRY`，不误关。
- 集成 `g34DefinitiveNotPaidKeepsFundsOnClaimedEvent`：占位 `1` + 渠道 `CLOSED` → 列值仍为 `1`，原因前缀“渠道明确未支付”可被 T+1 归入冲突类。
- 集成 `g34AlipayClosedWithoutRefundEvidenceKeepsFunds`：支付宝 `TRADE_CLOSED` 无退款金额证据 → 保持 `1`，原因前缀“渠道已关闭，可能为全额退款，需核对退款流水”，并断言不含“渠道明确未支付”。
- 集成 `g34DefinitiveNotPaidMarksZeroWhenFundsUnmarked` 明确标注为“仅未标注行/历史或异常行”，新事件不可达。
- 适配器 `AlipayRefundEvidenceTest`：`refund_fee` / `refund_amount` 大于 0 判为退款证据；为 0、字段缺失或非数值时不判退款，保持 `CLOSED` 冲突路径。

### 13.4 前提纠正记录（第五~六轮）

第四轮提交 `b0c1e10` 有两条前提判断错误，第五轮已纠正；第五轮“`REFUNDED` 归 `0`”的指令在第六轮复核中被确认为错误，一并记录：

1. **`MANUAL_REVIEW` 不是支付事件状态**——错误。G34 `PaymentEventReconcileService` 在陈旧 `PROCESSING` 重试耗尽后确实通过 `PaymentEventMapper.markManualReview` 写入 `payment_event.event_status='MANUAL_REVIEW'`。本轮把它明确列入支付事件状态清单；它不进入“预期有流水”差集（资金状态未知），但生成 `PAYMENT_EVENT_REVIEW` 差异。
2. **`FULFILLMENT_FAILED` 只有“回调后已收款”一个含义**——错误。该状态一码多义：回调后 `reject(...)` 写入表示已有资金往来；G34 `markFailed` 在渠道已退款时表示“到过账又退回”；G34 渠道明确未支付时表示“渠道答复与占位记录冲突”（占位即 `1`，不会被降级）。无法用单一状态值判定是否需要流水，必须增加 `funds_received` 显式标记（见 §13.5）。第四轮“整体计入差集”因此会对明确未支付事件每天误判 `PARTIAL`，连续 3 次转 `MANUAL`。
3. **`REFUNDED` 归入“明确未支付”（`0`）**——错误（第五轮指令错误）。已退款是钱到过又退回，不是没收到；归 `0` 还会覆盖受理占位的 `1`，并在支付流水未导入时让整批漏导记为 `SUCCESS`。第六轮改为 `1` 并禁止补偿链路降级已有 `1`（见 §13.6）。

### 13.5 资金到账标记 funds_received（第五~七轮）

- **列定义**：`payment_event.funds_received TINYINT NULL`，**不设默认值**。第七轮正式定死语义为“**渠道侧存在该笔资金往来、应当有对应渠道流水**”，不是“此刻仍持有资金”。
  - 依据：能走到 `claimEvent` 的路径已通过验签、订单存在、商户号匹配、金额与币种校验、归集阶段校验，说明渠道侧确实发生过这笔资金往来；因此受理占位即写 `1` 是合理判定，而不是乐观猜测。
- **三个取值与行为**：
  - `1`（预期渠道存在流水）：状态在七状态集合内时进入“预期产生流水”差集；该渠道未导入流水 → 跑批 `PARTIAL`。**新事件一律为 `1`**。
  - `0`（明确无资金往来）：不进差集，但仍生成 `PAYMENT_EVENT_REJECTED` 差异。**仅对 `funds_received IS NULL` 的行（未标注行/历史或异常行）生效；新事件不可达**——`claimEvent` 已写 `1`，G34 的 `CASE` 又保留 `1`。
  - `NULL`（未判定）：不进差集，但生成 `PAYMENT_EVENT_REVIEW` 差异，要求人工确认是否已收款；**禁止把 NULL 当作“无需流水”静默放过**。
- **为什么不用默认值**：若给默认值（尤其默认 0 或 1），任何漏写都会得到一个“看起来正常”的结论——默认 1 会把未到账事件误判为应有流水，默认 0 会把已到账事件静默排除。NULL 有意保留为“未标注、必须产生可见信号”的合法取值。第七轮保留“1 恒为 1”的规则，正是因为 `1` 表示的是“渠道侧发生过资金往来”，不是可被后续答复撤销的临时状态。
- **写入点（逐个显式赋值）**：
  - `PaymentEventServiceImpl.claimEvent`（受理占位）：`1`；各 `reject(...)` 分支、`recordEventStatus(SUCCESS)`、`DuplicatePayment`、`auditFailure`：`1`。
  - G34 `markFulfillmentFailed`：渠道明确未支付（`PENDING/CLOSED/FAILED/NOT_EXIST`）**请求写 `0`**，但对已建事件（占位即 `1`）由 SQL `CASE` 保留 `1`，实际形成“`FULFILLMENT_FAILED` + `1` + 原因‘渠道明确未支付’”的**冲突行**；渠道已收款后已退款（`REFUNDED`）=`1`；查单前置校验失败（400/401/403/404）=`NULL`（只说明无法确认，不等于未收款）。
  - **只增不减**：`markFulfillmentFailed` 的 SQL 使用 `CASE WHEN funds_received = 1 THEN 1 ELSE #{fundsReceived} END`。`1` 表示“渠道侧发生过资金往来”，是既成事实，后续未支付/退款/无法确认答复不得覆盖成 `0/NULL`。共存语义为“状态 `FULFILLMENT_FAILED` + `funds_received=1`”，成因由 `error_message` 固定前缀区分（见 §13.6 第 6 条），T+1 据此生成不同类型的差异要求人工核对。
  - G34 `markManualReview`：固定回写 `NULL`（资金状态未知），由 `PAYMENT_EVENT_REVIEW` 差异兜住。
  - 写入接口 `PaymentEventRecorder.recordEventStatus/recordFailureAudit` 的 `fundsReceived` 参数为必填，不提供默认值，强制调用方显式说明资金状态。
- **索引**：不新增索引。T+1 先按 `(merchant_id, created_at)` 取当日事件再在内存判定 `funds_received`，没有按该列过滤的查询路径。
- **历史数据**：本项目无历史数据，不做回填；`NULL` 是合法且有意为之的取值（表示未标注、需要人工核对），不得用脚本批量改成 `0/1` 来“消除告警”。
- **口径边界**：`REFUNDED` 归入“已到账”（`1`），不是“明确未支付”；语义为“曾收款后退回”。退款侧仍由 `reconcileRefund` / `REFST` 覆盖，不因支付事件侧改标记而改变。真实渠道联调时确认退款流水与支付流水的对应关系。

### 13.6 第六轮返工：REFUNDED 纠正与资金标记只增不减

1. **`REFUNDED` 归 `0` 是错误（第五轮指令错误，已纠正）**。调用链：微信适配器把渠道 `REFUND` 映射为 `REFUNDED` → 该状态曾在 `DEFINITIVE_NOT_PAID_STATES` 中 → `markFailed(..., 0)` → SQL 把受理占位时的 `funds_received=1` 覆盖成 `0` → T+1 生成“渠道明确未收到资金”的 `PAYMENT_EVENT_REJECTED`。语义上“已退款是钱到过又退回”，不是没收到。
2. **实际漏口**（比“退款流水不被核对”更精确）：支付流水已导入时仍能通过 `findByChannelAndTradeNo` 比到该事件并记 `EVENT_STATUS_MISMATCH`；**支付流水未导入时**因 `funds_received=0` 不进差集、且缺流水检查只在 `SUCCESS` 分支，跑批会记 `SUCCESS`，整批流水未导入也被掩盖。改为 `1` 后事件进入 `channelsWithLocalDocs`，未导入即 `PARTIAL`。
3. **实现选择**：不新增事件状态，仍落 `FULFILLMENT_FAILED`，用 `funds_received=1` + `error_message`（“渠道已收款后已退款: state=REFUNDED”）区分。理由：状态机与既有的 `FULFILLMENT_FAILED` 迁移条件（`PROCESSING` → 失败态）不变；管理端/统计无需新增状态映射；`REFUNDED` 与 `CLOSED/FAILED` 的本质差异正是“资金是否到过账”，该语义已由 `funds_received` 承担，新增状态会与列语义重复。
4. **补偿链路禁止降级资金标记**：`markFulfillmentFailed` 的 SQL 改为 `funds_received = CASE WHEN funds_received = 1 THEN 1 ELSE #{fundsReceived} END`；已有 `1` 时状态与标记共存为“`FULFILLMENT_FAILED` + `1`”，由 T+1 差异要求人工核对，不允许把“渠道侧发生过资金往来”的记录改写成无资金往来。
5. **退款侧未受影响**：退款对账仍走 `refundMapper.findSuccessfulRefundsByPeriod`、`reconcileRefund` 与 `REFST` 键，不读取 `payment_event.funds_received`；本次只改支付事件侧。

### 13.7 第七轮返工：资金标记语义定死、差异类型可筛选、支付宝退款识别

1. **`funds_received=1` 语义定死**：`1` = “渠道侧存在该笔资金往来、应当有对应渠道流水”，不是“此刻仍持有资金”。`claimEvent` 占位即写 `1`，`markFailed` 的 `CASE` 又保护 `1`，两者叠加使 `DEFINITIVE_NOT_PAID_STATES → 0` 对新事件不可达；因此 `0` 的文档口径改为“仅对 `funds_received IS NULL` 的未标注行/历史或异常行生效”，不得再宣称新事件会落 `0`。第七轮据此同步 schema 列注释、实体注释、Mapper 注释、本方案与缺口台账状态表。
2. **差异类型按成因拆分**（不再只靠 `detail` 自由文本）：
   - `PAYMENT_EVENT_REFUNDED`（已收款后已退款），键 `EV|{eventId}|REFUNDED`；
   - `PAYMENT_EVENT_CHANNEL_CONFLICT`（渠道答复与已收款记录冲突），键 `EV|{eventId}|CHANNEL_CONFLICT`；
   - 其余拒绝仍为 `PAYMENT_EVENT_REJECTED`，键 `EV|{eventId}|{eventStatus}`（普通 `FULFILLMENT_FAILED` 履约失败即此类）。
   - 成因识别使用 G34 写入原因的**固定前缀**（`渠道已收款后已退款` / `渠道明确未支付` / `渠道已关闭，可能为全额退款，需核对退款流水`），由 `PaymentEventReconcileService` 的常量与 `ReconciliationService` 共享，不依赖任意文本匹配；`detail` 额外带上 `state=XXX` 便于定位。
   - `stillConsistent`：`PAYMENT_EVENT_REFUNDED` / `PAYMENT_EVENT_CHANNEL_CONFLICT` 与 `PAYMENT_EVENT_REJECTED` 一样，事件回写 `SUCCESS` 时自动关闭，否则保持差异等待人工处理。
3. **支付宝退款识别**：`alipay.trade.query` 全额退款后返回 `TRADE_CLOSED`，仅凭状态无法与超时关闭区分。适配器改为：响应携带 `refund_fee` 或 `refund_amount` 且大于 0 时映射为 `REFUNDED`（进入退款类）；未携带时保持 `CLOSED`，G34 写“渠道已关闭，可能为全额退款，需核对退款流水”原因（**不得写“渠道明确未支付”**），T+1 归入冲突类，文案提示“可能为全额退款或渠道状态异常，需核对渠道流水”。**不宣称已覆盖支付宝全部退款情形**：
   - 微信：`trade_state=REFUND`（全额退款）可识别为已退款；部分退款仍可能返回 `SUCCESS`，当前不按退款类处理（仍预期渠道流水）。
   - 支付宝：`TRADE_CLOSED` + 退款金额字段可识别；`TRADE_CLOSED` 无退款字段时只能归冲突类；部分退款（`TRADE_SUCCESS`）不按退款类处理。
   - 其他渠道：无 `REFUNDED` 映射时，渠道关闭/查无此单一律按冲突类或明确未支付处理，识别能力以各适配器的状态映射为准。
4. **残留风险（已登记）**：部分导入场景下，非 `SUCCESS` 但 `funds_received=1` 的事件没有逐笔 `EV|id|ST` 缺流水检查，只有整批未导入才 `PARTIAL`；`markFailed` 的 `CASE` 在“现有 `0` + 入参 `NULL`”时会把 `0` 退化为 `NULL`（当前状态机不可达）；`markManualReview` 用例未回读中间态。详见缺口台账 G46–G48。

状态：**已实现·待人工验证**（第七轮回核返工）。未运行编译/测试/构建，未连接数据库，未接真实渠道；支付宝查单是否稳定返回退款金额字段、各渠道退款覆盖范围仍需真实联调确认。