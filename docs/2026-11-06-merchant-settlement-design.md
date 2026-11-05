<!--
文件：2026-11-06-merchant-settlement-design.md
用途：定义“阶段一商家直收货款 + 按订单向平台支付技术服务费、阶段二平台代收后分账”的业务边界、数据模型、计费口径、结算对账与合规前提；为后续 M1–M5 实施提供设计依据。
维护注意：
 1. 本文是设计文档，不是已实现功能说明；文中所有表、字段、状态机、接口均标为“拟新增/待实现”，不得引用为现状。
 2. 阶段一不依赖分账资质，平台不得代收货款；阶段二必须在不重写订单与资金模型的前提下扩展，禁止把阶段一做成临时结构再推倒。
 3. 技术服务费率区间为 1%–10%（建议以整数基点 bps 存储 100–1000）；存量未结算订单采用订单级费率快照，理由与退款影响见 §5。
 4. 金额沿用现有 DECIMAL(10,2)/BigDecimal 与“元、两位小数、字符串输出”契约；新增账本列建议 DECIMAL(12,2)，禁止 double/float。
 5. schema 变更仍以 shop-b2b2c-backend/src/main/resources/db/schema.sql 为唯一运行时入口；存量环境必须另做版本迁移，不得依赖 CREATE TABLE IF NOT EXISTS 升级。
 6. 涉及身份证、营业执照、银行卡、结算账户的字段必须加密/脱敏存储并按角色最小化展示；本文不复制任何真实凭据。
 7. 阶段一不得对外宣称“持牌存管/官方直结/担保/秒发”等未取得资质的能力；合规边界见 §13。
-->
# 商家结算与技术服务费设计（2026-11-06）

复核基线：`bcb1dab`。
文档性质：**设计 / 待实现**。本文不表示任何能力已落地。
关联文档：
- [修复清单与排期唯一入口](2026-11-06-remediation-plan.md)
- [商家结算现状差距与分期实施](2026-11-06-merchant-settlement-implementation-plan.md)
- [纠正后诊断基线](2026-11-06-diagnostic-baseline-revised.md)

---

## 0. 设计前提与结论摘要

### 0.1 已确定的业务方向（用户口径）

| 阶段 | 资金流 | 平台收入 | 资质前提 |
| --- | --- | --- | --- |
| 阶段一（先做） | 买家货款直接进入商家收款账户；平台不代收、不沉淀货款 | 按订单向商家收取技术服务费 | 不需要分账资质；但需商户实名、收款账户登记、服务费发票与账务能力 |
| 阶段二（后做） | 买家货款先进入平台商户号，再由微信/支付宝分账给商家 | 平台留存技术服务费后的净额分给商家 | 需要支付/分账资质、平台商户号、分账接收方绑定、合规与对账能力 |

### 0.1.1 已确认决策（用户 2026-11-06）

| 决策 | 已确认口径 | 状态 |
| --- | --- | --- |
| Q01 费率适用 | 订单级快照：下单锁定费率、基数、费用额、政策版本；后续调价不影响存量单 | **已确认：A** |
| Q02 扣费时点 | 商家余额充足时，支付成功即扣，平台不留敞口；管理员可对单店铺设佣金费率；账期可配 7/21/30 天，作为余额不足时的欠费宽限；高频商家账期更短，系统给建议值但需管理员确认；推广期可设 0 费率 | **已确认：A + 补充规则** |
| Q02 推广与审计 | 0% 是“推广状态”独立取值，不并入 1%–10% 正常区间；每次费率变更记录操作人、时间、前后值；0 费率仍生成计费记录，账上区分应收与免收 | **已确认：补充规则** |
| Q03 退款责任 | 按 `collection_mode` 分叉：阶段一商家直收由商家退款、平台只冲正服务费；阶段二平台代收由平台退款 | **已确认：C** |
| Q04 计费基数 | 商品成交额（单价×数量－商家优惠），剔除运费、平台优惠/补贴、税费；平台补贴计平台成本，不向商家收服务费；必须新增字段，不用实付额过渡 | **已确认：B** |
| Q05 含税口径 | 对外按含税口径签约与展示；系统内部价税分离记账（净收入 + 税额） | **已确认：D** |
| Q06 结算周期 | **已确认：C 月结出结算单；后台同时提供逐笔明细查询（结算单是汇总，明细是证据）** | **已确认：C** |
| Q07 欠费与停单 | 宽限期内允许欠费；欠费累计达到 500 元或账期到期，任一触发即停收款；补齐后自动恢复；500 元为初始值，后台可配 | **已确认：Q07-B（宽限期+固定信用额度）** |
| Q08 充值方式 | 线下转账 + 后台人工确认到账；必须设计充值单、唯一凭证号与幂等入账，防重复确认 | **已确认：A + 补充规则** |
| Q11 实名路径 | 分阶段：阶段一人工审核（个人身份证、企业营业执照+法人资料）；预留“认证方式”与“核验来源”字段；第三方实名核验（人脸/工商）作为后续增强 | **已确认：A 落地 + B 预留，不采用 C** |
| Q12 账户审核 | 登记 + 人工核对户名与账号；“户名必须等于实名主体”为硬校验；不接第三方账户验证 | **已确认：A + 硬约束，B 暂不采用** |
| Q09 阶段二切换 | 资质取得 + 灰度名单；支持按渠道/按商家回滚 | **已确认：A** |
| Q10 旧 A/B/C 字段 | 撤回，不作为业务决策 | **已撤回**：旧列保留只读、新语义另起列、历史值不重算，由开发写入迁移说明 |
| Q13 员工权限 | 员工最小权限 | **已确认：A** |
| Q14 SKU 唯一域 | 店内唯一 | **已确认：A**（`merchant_id + sku_code`） |
| Q15 订单截止 | 所有订单 15 分钟截止 | **已确认：A** |
| Q16 有预占时下架 | 禁止下架 | **已确认：A** |
| Q17 代付预占 | 强制预占 15 分钟、最多重发 3 次 | **已确认：A** |
| Q18 幂等键保留 | 轻量“键→订单号”30 天 | **已确认：B** |
| Q19 商品搜索 | 店内 + 全平台双入口 | **已确认：A** |
| Q20 匿名下单 | 免登录 + 联系方式必填 + 现有 IP/联系人限流 | **已确认：A** |
| Q21 卡密权限 | 店长按 SKU 限量查看；导出二次确认并审计 | **已确认：A** |
| S12 阶段一开票 | 阶段一不做开票，只保留价税数据 | **已确认：A** |

> 字段口径提醒：本表及后文出现的 `collection_mode`、`pay_channel`、`verification_method`、`verification_source` 等均为**计划新增/待迁移字段**；现有代码实际只有 `merchant_store.payment_channel_type`（A/B/C）与 `trade_order.payment_channel`（默认 `WECHAT`），不存在 `collection_mode`/`pay_channel`。

### 0.1.2 已确认的运营边界（Q13–Q21）

| 决策 | 已确认口径 | 影响任务 |
| --- | --- | --- |
| Q13 员工权限 | 员工最小权限：只读展示资料、处理被指派订单；推单/发货/资金/卡密/完整 PII 仅店长 | T13、T02、T31 |
| Q14 SKU 唯一域 | 店内唯一（`merchant_id + sku_code`） | T17、T18 |
| Q15 订单截止 | 所有订单 15 分钟截止；超时支付拒绝并记录，不静默 PAID | T08、T09 |
| Q16 有预占时下架 | HELD 预占结束/释放前禁止下架 | T09、T33 |
| Q17 代付预占 | 强制预占，15 分钟，可重发最多 3 次 | T10、T29 |
| Q18 幂等保留 | 轻量“键→订单号”映射 30 天，不保留敏感响应 | T27 |
| Q19 商品搜索 | 店内 + 全平台双搜索入口（关键词/类目） | T30、F17 |
| Q20 匿名下单 | 免登录 + 联系方式必填 + 现有 IP/联系人限流 | T30、T11 |
| Q21 卡密权限 | 店长按 SKU 限量查看；导出二次确认并审计 | T16、T14 |

### 0.2 核心设计结论

1. **订单级费率快照**：下单时把费率、计费基数、服务费额、费率政策版本写入订单；存量未结算订单不随管理员调价而变。理由与退款影响见 §5.4。
2. **阶段一与阶段二共用同一订单/支付事件模型**：订单从一开始就记录 `collection_mode`（商家直收/平台代收），阶段二只增加分账接收方与分账明细，不改订单主表和支付事件主键。
3. **阶段一不写真实进件**：只做收款账户登记与人工审核；`merchant_payment_account` 留作阶段二渠道绑定，禁止再写入 `SUB+时间戳`、默认 `PASSED` 的假数据。
4. **服务费与月度费分离**：现有 `monthly_fee` 是配置标记，阶段一按订单费率计费；两者不混算，是否启用月度费由业务确认。
5. **平台不碰货款**：阶段一平台只收服务费，避免形成“二清”；结算单在阶段一是服务费账单/收款凭证，不是平台应付商家的货款。
6. **金额精度**：数据库新表用 `DECIMAL(12,2)`，服务费率用整数 bps，计算用 `BigDecimal` + `HALF_UP`，接口金额用两位小数字符串；不引入分为单位的全库改造。
7. **扣费时点（已确认 Q02-A）**：余额充足时支付成功即扣；余额不足进入 7/21/30 天欠费宽限（管理员确认），0 费率推广仍记费。
8. **计费基数与税（已确认 Q04-B、Q05-D）**：只按商品成交额计费，剔除运费/平台优惠与补贴/税费；对外含税展示，内部价税分离记账。
9. **结算周期（已确认 Q06-C）**：月结出结算单，后台同时提供逐笔明细查询；结算单只是汇总，明细才是证据。
10. **欠费与停收款（已确认 Q07-B）**：宽限期内允许欠费；欠费累计达到信用额度上限（初始 500 元、后台可配）或账期到期，任一触发即停收款；补齐后自动恢复。
11. **充值（已确认 Q08-A）**：线下转账 + 后台人工确认；必须用充值单、唯一凭证号与幂等入账防止重复确认。

---

## 1. 现状边界（只列与本设计相关的既有事实）

| 现状 | 代码事实 | 对本设计的含义 |
| --- | --- | --- |
| 店铺主表直接承载经营者身份证、费率、收款模式 | `merchant_store` 有 `owner_name/id_card_number/commission_rate/monthly_fee/fee_free_until/fee_status/payment_channel_type/custom_pay_qrcode`（[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L15-L36)） | 阶段一需要新增入驻申请、费率政策、结算账户等表；`merchant_store` 只保留经营展示与当前生效指针，不继续堆业务流水。 |
| 入驻由超管直接创建，店铺直接 `status=1` | [MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L68-L133) | 缺少申请、审核、驳回、补件状态机；阶段一必须补齐，不能把“超管创建”当实名审核。 |
| 进件账户是模拟值 | `sub_mchid = "SUB" + System.currentTimeMillis()`、`apply_status = "PASSED"`（同文件 `:119-L130`） | 阶段一不得展示为“已开通”；阶段二必须替换为真实渠道返回值与回调核验。 |
| 订单已有抽佣快照列 | `commission_rate/commission_amount/merchant_settle_amount/sharing_status`（[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L142-L146)） | 可复用为阶段一服务费快照基础，但需补充费率政策版本、计费基数、费用状态与冲正字段。 |
| 下单即按店铺当前费率算费 | [OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L113-L131)、[PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L91-L104) | 已有“下单时点费率”行为，但没有费率政策表、没有 1%–10% 约束、没有调价审计。 |
| 服务费配置只有月度费/免收期/状态标记 | [MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L155-L176)、[ServiceFeeStatus.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/ServiceFeeStatus.java#L3-L17) | `fee_status` 只是标记，不扣费、不到期转换、不停服；不能作为账本或结算依据。 |
| 账单表只有结构 | `billing_statement` 有 `gmv/fee_amt/status`，默认 `SETTLED`（[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L208-L224)） | 无生成服务、无明细、无对账；阶段一应新建结算批次/明细，不把汇总表当账本。 |
| 支付事件无费用/结算关联 | `payment_event` 只有渠道流水、金额、事件状态（[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L449-L466)） | **计划新增** `collection_mode/platform_trade_no` 等扩展字段；费用从订单快照读取，不从事件金额反推。 |
| 收款模式 A/B/C 与渠道语义混用 | `merchant_store.payment_channel_type` 的 A/B/C（[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L30)）被写入 `trade_order.payment_channel`（[PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L108)） | 阶段一**计划新增** `collection_mode` 与 `pay_channel` 两列（现有代码中不存在），逐步替代一列双语义；迁移完成前 `payment_channel` 保留只读兼容。 |

---

## 2. 业务模式与阶段边界

### 2.1 阶段一：商家直收 + 平台服务费

```text
买家 ──货款──> 商家收款账户（微信/支付宝商户号或收款码）
平台 ──技术服务费账单──> 商家（从预存余额/账期扣收或线下支付）
```

设计约束：
- 平台**不得**代收、归集、沉淀货款；不得以平台商户号统一收款后再转给商家（否则构成阶段二）。
- 订单金额、支付流水仍由平台记录，用于计费、对账与售后，但资金不经过平台。
- 平台只向商家开具技术服务费发票/收据，不同买家开具货款发票。
- 阶段一可使用商家已有微信/支付宝商户号或收款码；平台只登记与审核，不做真实进件。

### 2.2 阶段二：平台代收 + 分账

```text
买家 ──货款──> 平台商户号
平台商户号 ──分账──> 商家分账接收方（扣除技术服务费）
平台留存技术服务费
```

设计约束：
- 订单主表、支付事件表、费用快照在阶段一已建好；阶段二只增加分账接收方绑定、分账明细、冻结/解冻、退分。
- 阶段二切换按**已确认的 Q09-A**：资质取得后按“灰度名单”切换，支持按渠道/按商家回滚；灰度只影响新单，不修改历史订单的资金归属。
- 阶段二需要平台商户号、分账资质、渠道协议、KYC/AML、对账与发票能力；未取得前不得对外宣称已支持。

### 2.3 订单维度模型

以下字段**在现有代码与 schema 中均不存在，属于计划新增/待迁移**，不得表述为“现有字段”：

- `collection_mode`（计划新增）：`MERCHANT_DIRECT`（阶段一）/ `PLATFORM_COLLECT`（阶段二）。
- `pay_channel`（计划新增）：`WECHAT`/`ALIPAY`（真实支付渠道）；现有 `trade_order.payment_channel` 仍是默认 `WECHAT` 的兼容列，不是本设计所指的 `pay_channel`。
- `platform_trade_no`：阶段二平台商户号流水；阶段一为空。
- `fee_snapshot_id` 或订单内费用快照列：见 §5。

**不要**把 `A/B/C` 写入 `pay_channel`；也不要继续用 `payment_channel` 同时表示“收款模式”和“支付渠道”。

---

## 3. 商家入驻、实名认证与审核状态机

### 3.1 两条主体路径

| 字段/材料 | 个人（含个体工商户可选） | 企业 |
| --- | --- | --- |
| 主体类型 | `INDIVIDUAL` | `ENTERPRISE` |
| 姓名/法人 | 经营者姓名 | 法定代表人姓名 |
| 证件 | 身份证号（加密）、身份证正反面 | 营业执照号/统一社会信用代码、执照扫描件、法人身份证 |
| 联系信息 | 手机号、邮箱 | 联系人、手机号、邮箱 |
| 收款账户 | 本人银行卡/微信/支付宝 | 对公账户或平台允许的对私账户 |
| 实名方式 | 身份证二要素/三要素核验（需第三方服务） | 营业执照核验 + 法人身份核验 |
| 审核重点 | 人证一致、账户名与经营者一致 | 主体存续、法人一致、账户名与主体一致 |

设计说明：
- **已确认（用户 2026-11-06）**：阶段一采用“人工审核 + 第三方结果预留”的分阶段方案。个人提交身份证，企业提交营业执照+法人资料，由平台人工审核；认证数据表必须预留 `verification_method`（人工/第三方）与 `verification_source`（证件核验/人脸/工商核验等）字段。第三方实名核验（人脸/工商）作为后续增强，不阻塞阶段一。
- 未接入第三方时只能标“人工核验”，不得标“已实名认证”；第三方结果接入后按来源分别标注，禁止把人工审核伪装成第三方认证。
- 身份证号、营业执照号、银行卡号必须加密存储；列表只返回脱敏值；完整值仅审核角色按需查看并记审计。

### 3.2 拟新增表 `merchant_onboarding_application`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT UNSIGNED PK | 申请单 |
| `merchant_id` | BIGINT NULL | 审核通过后关联店铺；提交时为空 |
| `subject_type` | VARCHAR(16) | `INDIVIDUAL` / `ENTERPRISE` |
| `subject_name` | VARCHAR(128) | 姓名/企业名称 |
| `id_card_cipher` | VARCHAR(512) NULL | 身份证号密文 |
| `id_card_fingerprint` | VARCHAR(128) NULL | 去重/核验指纹 |
| `license_no_cipher` | VARCHAR(512) NULL | 营业执照号密文 |
| `license_url` | VARCHAR(512) NULL | 执照影像地址 |
| `legal_person` | VARCHAR(64) NULL | 法人姓名 |
| `contact_phone` | VARCHAR(32) | 联系电话 |
| `contact_email` | VARCHAR(128) NULL | 联系邮箱 |
| `status` | VARCHAR(24) | 状态机见 §3.3 |
| `verification_method` | VARCHAR(24) | `MANUAL` / `THIRD_PARTY`；阶段一为 `MANUAL`，预留第三方 |
| `verification_source` | VARCHAR(32) NULL | `ID_CARD_CHECK` / `FACE_CHECK` / `BUSINESS_REGISTRY` / `MANUAL_REVIEW` 等；记录核验来源 |
| `reject_reason` | VARCHAR(512) NULL | 驳回/补件原因 |
| `submitted_at/reviewed_at` | DATETIME NULL | 提交/审核时间 |
| `reviewer_id` | BIGINT NULL | 审核人 |
| `version` | INT | 乐观锁/补件版本 |
| `created_at/updated_at` | DATETIME | 时间戳 |

索引：`uk_subject_fingerprint(subject_type, id_card_fingerprint)`（防重复主体，需按隐私规则评估）、`idx_status_submitted(status, submitted_at)`、`idx_merchant(merchant_id)`。

### 3.3 审核状态机

```text
DRAFT -> SUBMITTED -> REVIEWING -> APPROVED
                         |  \-> SUPPLEMENT_REQUIRED -> SUBMITTED
                         \-> REJECTED -> (重新提交生成新版本) -> SUBMITTED
SUBMITTED/REVIEWING -> WITHDRAWN（申请人撤回）
APPROVED -> SUSPENDED（后续合规/账户异常，另立状态）
```

规则：
- `APPROVED` 才允许创建/激活店铺并生成店长账号；`merchant_store.status` 不由申请单直接写成 `1`。
- `REJECTED` 必须记录原因与审核人；商家可新建一次申请或补件重提，旧申请保留不可改。
- `SUPPLEMENT_REQUIRED` 保留原申请与已提交材料，补件后 `version + 1`，审核历史完整。
- 审核操作写 `merchant_onboarding_audit`：动作、前后状态、操作人、原因、材料快照哈希；不得只更新主表状态。
- 重复提交/并发审核用乐观锁；同一申请只能有一个终态。

### 3.4 与现有店铺创建的关系

- 新商家一律走申请单；超管“直接创建”仅保留为迁移/应急入口，并必须补齐审核记录，否则不得上线。
- 申请通过后：创建 `merchant_store`（`status` 按审核结果）、创建店长账号、写默认费率政策（见 §5）。
- 现有存量店铺：补录申请单为 `APPROVED`（标注“存量补录”），不伪造实名结果；缺材料的列 `SUPPLEMENT_REQUIRED` 限期补齐。

---

## 4. 收款账户：阶段一只登记，阶段二平滑替换

### 4.1 阶段一账户模型

拟新增 `merchant_settlement_account`：

| 列 | 说明 |
| --- | --- |
| `id/merchant_id` | 主键/店铺 |
| `account_type` | `BANK` / `WECHAT` / `ALIPAY` |
| `holder_name` | 开户名/收款人 |
| `bank_name/bank_branch` | 银行及支行（银行账户） |
| `account_no_cipher` | 银行卡号/账号密文 |
| `account_fingerprint` | 去重指纹 |
| `account_no_masked` | 脱敏展示值（后四位） |
| `status` | `PENDING/VERIFIED/REJECTED/DISABLED` |
| `reject_reason` | 驳回原因 |
| `is_default` | 默认收款账户 |
| `effective_from/effective_to` | 生效区间，支持换卡不覆盖历史 |
| `verified_at/verified_by` | 审核信息 |
| `created_at/updated_at` | 时间戳 |

约束：
- 同一商户可有多个账户，但默认账户唯一；换账户采用“新增+停用旧账户”，不原地改历史账户。
- **已确认（用户 2026-11-06）**：账户登记采用“人工核对户名与账号”，并以 **“户名必须等于实名主体”** 作为硬校验；不一致直接拒绝/进入补件，不允许人工放行。阶段一不接第三方账户验证（Q12-B 暂不采用）。
- 账户名必须与主体名称/经营者一致；不一致进入 `REJECTED` 并说明。企业账户名应与营业执照主体一致，个人账户名应与身份证姓名一致；个体工商户按经营者实名主体校验。
- 阶段一不生成 `sub_mchid`，不调用渠道进件；只记录“已登记/已审核”。

### 4.2 阶段二平滑替换

保留现有 `merchant_payment_account` 作为**渠道绑定表**，但改造：
- 删除/停止写入 `SUB+时间戳`、默认 `PASSED`；`sub_mchid` 只允许渠道真实返回值。
- 增加 `binding_status`（`UNBOUND/BINDING/BOUND/REJECTED/DISABLED`）、`channel_merchant_id`、`bound_at`、`effective_from/to`、`raw_result`。
- 与 `merchant_settlement_account` 通过 `settlement_account_id` 关联：阶段一账户是结算去向，阶段二渠道绑定是分账接收方。
- 阶段二切换时：新增绑定记录 + 设置 `collection_mode=PLATFORM_COLLECT` 的生效时间；历史订单保留 `MERCHANT_DIRECT`，不迁移资金归属。

**平滑原则**：账户/绑定都按“新增生效记录”处理，不 update 历史行；订单只记录当时使用的 `collection_mode` 与账户/绑定的只读快照 ID。

### 4.3 审核边界

- 阶段一由平台运营人工审核账户资料；审核记录进入 `merchant_onboarding_audit` 或独立 `merchant_account_audit`。
- 不对接银行四要素/打款验证时，页面只能写“账户已登记，待人工核验”，不得写“已验证/已绑定官方”。
- 阶段二渠道进件结果以渠道回调/查询为准，平台不得自行把状态改为 `BOUND`。

---

## 5. 技术服务费

### 5.1 费率来源

| 来源 | 触发 | 约束 |
| --- | --- | --- |
| 开店设置 `ONBOARDING` | 入驻审核通过时由运营录入 | 正常费率必须在 100–1000 bps（1%–10%） |
| 管理员调整 `ADMIN_ADJUST` | 超管对单个店铺调整 | 必填原因、操作人、调整前后值；正常费率不得越过 1%–10%；系统可给建议值，但管理员确认后才生效 |
| 推广期 `PROMOTION` | 管理员对单店铺开启推广 | `rate_bps=0`，属于独立推广状态，**不并入 1%–10% 正常区间**；必须记录推广原因、开始/结束时间、操作人 |
| 系统默认（仅开发/演示） | 无政策时的兜底 | 生产禁止用默认值直接计费；缺失政策应阻断下单或进入人工确认 |

已确认（用户 2026-11-06）：
- 管理员可对单个店铺设置佣金费率；费率区间 1%–10%，推广期可设 0%。
- 0% 是“推广状态”的独立取值，不属于正常费率区间；每次费率变更必须记录操作人、时间、前后值。
- 推广期“免费”不等于“不记费”：照常按 0 费率生成计费记录，账面同时展示“标准应收”和“实际免收”，作为运营数据。

### 5.2 数据模型 `merchant_fee_policy`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 政策版本 |
| `merchant_id` | BIGINT | 店铺 |
| `rate_type` | VARCHAR(16) | `NORMAL/PROMOTION`；`NORMAL` 时 `rate_bps` 必须 100–1000，`PROMOTION` 时允许 0 |
| `rate_bps` | INT | 正常 100–1000（1%–10%）；推广 0；禁止其他值 |
| `promotion_reason` | VARCHAR(512) NULL | 推广原因，`PROMOTION` 必填 |
| `effective_from` | DATETIME | 生效时间（含） |
| `effective_to` | DATETIME NULL | 失效时间（不含），NULL 表示当前 |
| `source` | VARCHAR(16) | `ONBOARDING/ADMIN_ADJUST/PROMOTION/MIGRATION` |
| `reason` | VARCHAR(512) | 调整原因，`ADMIN_ADJUST/PROMOTION` 必填 |
| `confirmed_by` | BIGINT NULL | 管理员确认人；系统建议值必须经确认才生效 |
| `operator_id` | BIGINT | 操作人 |
| `created_at` | DATETIME | 记录时间 |

索引：`idx_merchant_effective(merchant_id, effective_from, effective_to)`；同一商户同一时刻只能有一条有效政策（应用层校验 + 可选的数据库约束方案）。

### 5.3 校验与审计

- 正常费率必须为整数 bps，校验 `100 <= rate_bps <= 1000`；推广费率仅允许 `rate_type=PROMOTION` 且 `rate_bps=0`；其他值一律拒绝。
- 现有 `commission_rate` 的 0–100 校验必须改为：正常 1–10（%），推广 0 单独走推广接口/状态，不能把 0 混入正常区间。
- 禁止负数、小数超过 2 位、`NaN`、超范围；前端输入按百分比展示，后端只收 bps 或百分比字符串并统一转换。
- 每次调整写 `merchant_fee_audit`（或复用统一审计表）：前后费率、前后 `rate_type`、原因、操作人、时间、关联政策 ID；管理员列表可查历史。**已确认（用户 2026-11-06）**。
- 调价不能修改历史政策行，只能关闭旧政策的 `effective_to` 并新增政策；历史订单仍指向原政策 ID/费率快照。
- 费率调整需二次确认；不得在同一请求里同时改费率和结算账户。
- 系统可根据出单频次给出账期建议值，但必须由管理员确认后才生效；建议值和确认记录一并留痕。

### 5.4 存量未结算订单按哪个费率：已确认“订单级费率快照”

**已确认（用户 2026-11-06）：A 订单级快照（下单时固化）**，不是结算时读实时费率。
**被否决：B 结算时实时费率**——原因：金额会随调价变化、退款冲正无法复现、跨期结算争议大、审计困难。
**未采用：C 支付成功时快照**——原因：下单展示金额与最终扣费可能不一致，需要补差/二次确认，阶段一不引入这层复杂度。

| 方案 | 规则 | 优点 | 缺点/退款影响 |
| --- | --- | --- | --- |
| A. 订单级快照（**已确认**） | 下单时写入 `fee_rate_bps/fee_base_amount/fee_amount/fee_policy_id`；之后调价不影响该单 | 金额可预期、可审计、对账可复现；退款可按原费率冲正；商户无法因调价争议 | 调价只影响新单；若运营调价写错，需通过冲正/补充政策修正，不能改历史 |
| B. 结算时实时费率 | 结算任务读取商户当前 policy 计费 | 调价立即覆盖所有未结算单 | 下单后金额变化，买家/商户无法预期；跨期结算争议大；退款时费率先行变化会导致冲正金额无法复现；审计困难 |

推荐理由：
1. 现有代码已经在下单时计算 `commission_amount`，改成快照是顺着现状收敛，不需要重算历史订单。
2. 阶段二分账必须在支付成功时确定平台留成，实时费率会导致“同一订单支付与结算金额不一致”。
3. 退款冲正必须能复现原订单费率；快照是唯一可靠依据。

**退款影响**：
- 全额退款：按快照 `fee_amount` 全额冲正（若已扣费，写反向账本；若未扣费，订单费用状态置 `REVERSED`）。
- 部分退款：按退款商品金额占计费基数的比例计算应退服务费，四舍五入到分；累计冲正不得超过原 `fee_amount`。
- 退款发生在结算后：不直接改历史结算单，生成下一期“调整项/红字”并关联原订单/原结算单。
- 退款后订单仍需保留快照，禁止删除或把 `fee_amount` 改为 0；用 `fee_refunded_amount/fee_status` 表达。

### 5.5 费率生效规则（已确认 S07-B）

**已确认（用户 2026-11-06）：S07 = B 次日生效 + 降费即时生效例外。**

- 默认规则：费率调整于**次日 00:00** 生效，按自然日切分，**一天一套费率**，便于商家按日核账。
- 例外规则：**降费率可即时生效；调高费率必须次日生效**。理由：降费对商家有利，即时生效让商家立刻受益、无争议；调高会增加商家成本，必须留出缓冲，避免“当天突然涨价”引发争议或跨日套利。
- 同一自然日只保留一套生效费率：降费即时覆盖当日政策；调高排到次日，不覆盖当日。同日先降后升时，升必须次日，且两套政策分别留痕。
- 新政策 `effective_from` 不得回填到过去时间；订单级快照（Q01-A）优先，生效时点只影响新单，不影响存量单。
- 已创建订单不受影响；已支付未结算订单也不受影响（快照优先）。
- 管理员调价可预约生效；预约期间再次调价必须关闭上一预约或明确覆盖关系，避免两条重叠政策。
- 缺失有效政策时：生产环境应阻断新订单计费并提示运营，不得静默按 0 或默认 3% 计费。

---

## 6. 计费基数与退款冲正

### 6.1 计费基数定义（已确认）

**已确认（用户 2026-11-06）：B 商品成交额口径。** 被否决：A 按实付额全额（运费/平台优惠也会计费，商家多付）；C 商品成交额+运费（运费争议大）；D 按渠道实收扣除补贴（依赖补贴明细，阶段一渠道能力不足）。

```text
计费基数 = 商品成交金额（下单时商品单价 × 数量 - 商家优惠）
         不含运费、不含平台优惠券、不含平台补贴、不含税费、不含打赏
服务费（含税） = 计费基数 × 费率（bps/10000），按订单四舍五入到分
服务费净收入 = 服务费（含税） / (1 + 税率)
服务费税额 = 服务费（含税） - 服务费净收入
```

说明：
- **必须新增字段，不用 `pay_amount` 过渡（已确认）。** 订单至少需要 `goods_amount`、`merchant_discount_amount`、`platform_discount_amount`、`shipping_amount`、`tax_amount`，下单时计算并固化 `fee_base_amount`。缺失字段时不得直接按实付额计费。
- 平台优惠券/平台补贴由平台承担，计为平台成本，**不从商家侧扣任何服务费**；商家优惠（商家自己承担）才从商品成交额中扣除。
- 代付订单：付款人变化不改变计费基数与费率；服务费仍向订单所属商家收取，不向付款人收取。
- 跨境/多币种：阶段一仅人民币；外币订单不启用计费，另立设计。

### 6.2 退款与冲正

**已确认（用户 2026-11-06）：C 按收款模式分流。** 被否决：A 只做商家自行退款（无法覆盖阶段二平台代收）；B 平台统一代退（阶段一平台需垫资/代收，接近二清）；D 服务费不退（商家争议与合规风险）。

| 收款模式 | 货款退款责任 | 服务费处理 |
| --- | --- | --- |
| `MERCHANT_DIRECT`（阶段一） | 商家自行原路退款；平台不代退、不垫资 | 平台只按原快照冲正服务费，不退货款 |
| `PLATFORM_COLLECT`（阶段二） | 平台按渠道规则退款 | 平台内部冲正服务费，并按分账规则处理已分账金额 |

接口必须按 `collection_mode` 分叉；阶段一虽然只走 `MERCHANT_DIRECT` 一条路径，但退款服务/状态的契约要同时容纳两种模式，避免阶段二重写。

| 场景 | 服务费处理 |
| --- | --- |
| 未支付取消/超时关闭 | 不计费（订单快照可为空或 `PENDING` 后置 `VOID`） |
| 支付前退款（若渠道支持） | 订单转退款态，费用快照置 `VOID`，不扣费 |
| 支付后全额退款 | 按原快照全额冲正；已扣费写反向账本，未扣费置 `REVERSED` |
| 支付后部分退款 | 按退款商品金额比例冲正；累计不超过原费用 |
| 结算后退款 | 下一期结算单生成红字调整，关联原结算明细；不修改已确认账单 |
| 重复支付退款 | 仅对有效订单计费一次；重复支付本身不计服务费，退款按渠道流水处理 |

规则：
- 冲正必须幂等（同一退款单号只冲一次）；冲正金额不得为负费用，用方向字段表达。
- 退款金额与订单金额异常时进入人工差异队列，不自动冲正。
- 服务费冲正与货款退款是两条账：`MERCHANT_DIRECT` 下平台不代收货款，不得从平台余额“退货款”。
- 阶段二 `PLATFORM_COLLECT` 退款时，必须先处理已分账金额（退分）再退款；阶段一不实现分账，但退款单据保留 `collection_mode` 与分账关联字段空位。

---

## 7. 金额精度与接口契约

### 7.1 建议：DECIMAL + BigDecimal，不改为全库分单位

| 层 | 约定 |
| --- | --- |
| 数据库 | 现有订单/支付金额保持 `DECIMAL(10,2)`；新增费用、账户、结算金额用 `DECIMAL(12,2)`（允许更大累计）；费率用 `INT bps` |
| Java | `BigDecimal`，`setScale(2, RoundingMode.HALF_UP)`；禁止 `double/float`；比较用 `compareTo` |
| 计算 | 先按订单计费基数乘费率，再四舍五入到分；按订单一次取整，不按商品行多次取整 |
| API | 金额输出两位小数字符串（与现有 `OrderStatistics.money()` 一致）；入参接受字符串或数字但统一转 `BigDecimal` 并校验两位小数 |
| 前端 | 展示字符串；不以 JS number 做金额加减；提交前只做格式校验，不做权威计算 |

不推荐“全库改为分”的原因：
- 现有 `trade_order/payment_event/billing_statement` 均为 `DECIMAL`，改分需要迁移所有读写与历史数据，风险高于收益。
- 只要固定两位小数 + `HALF_UP` + 字符串契约，已能避免浮点误差；阶段二分账金额也以分为最小单位，`DECIMAL(12,2)` 可精确表达。
- 若未来多币种/多精度，再单独引入 minor unit，不在阶段一混改。

### 7.2 费率表示

- 数据库存 `rate_bps`（正常 100–1000，推广 0），避免 `3.00%` 字符串解析歧义；0 仅用于 `rate_type=PROMOTION`。
- 对外可展示 `3.00%`；接口可同时返回 `rateBps` 与 `rateText`，但计算只认 bps。
- 禁止用 `commission_rate` 的 `DECIMAL(5,2)` 直接做乘除后不取整；迁移时把现有百分比转换为 bps。

### 7.3 价税分离（已确认 Q05-D）

**已确认（用户 2026-11-06）：对外按含税口径签约与展示；系统内部价税分离记账（净收入 + 税额）。**

| 层 | 约定 |
| --- | --- |
| 对外合同/页面 | 展示“含税服务费率/含税服务费金额”，商家看到的是最终含税成本 |
| 订单快照 | 同时记录 `fee_amount`（含税总额）、`fee_tax_rate`、`fee_net_amount`（净收入）、`fee_tax_amount` |
| 账本 | 扣商家余额按含税 `fee_amount`；平台收入按 `fee_net_amount` 与 `fee_tax_amount` 分列，便于开票与税务申报 |
| 计算 | `fee_amount = 计费基数 × 费率`；`fee_net_amount = fee_amount / (1 + 税率)`；`fee_tax_amount = fee_amount - fee_net_amount`；均 `HALF_UP` 到分 |
| 发票 | **已确认（用户 2026-11-06）：S12-A 阶段一不做开票功能，只保留价税分离数据**。账本记录含税/净收入/税额，供后续开票；阶段一不建开票入口、不接电子发票服务、不对外承诺发票类型。后续开票时金额以含税 `fee_amount` 为准 |

说明：
- 税率是系统配置，不写死在代码；费率调整不等于税率调整，两者分开审计。
- 价税分离只影响记账与开票，不改变商家实际应扣的含税金额。
- 若某类主体/地区适用免税或不同税率，按配置处理；阶段一未配置时不得默认 0 税额。

---

## 8. 商家账户、扣费时点与余额不足

### 8.1 账户模型

拟新增：
- `merchant_account`：`merchant_id` 唯一，`balance_available/balance_frozen/arrears/credit_limit/currency/version`。
- `merchant_account_ledger`：每笔充值、扣费、冲正、调整一条流水，含 `entry_type/direction/amount/balance_after/biz_type/biz_id/idempotency_key`。

原则：
- 账户是**服务费账户**，不是货款账户；阶段一货款不进入平台，因此账户余额只用于服务费。
- 所有余额变更必须走账本，禁止只 update 余额；账本 `idempotency_key` 唯一，重复回调不重复扣费。
- 余额、欠费、冻结额三者独立；可用余额 = 充值 - 已扣 - 冻结 + 冲正。

### 8.2 扣费时点（已确认 Q02-A）

**已确认（用户 2026-11-06）：A 商家余额充足时，支付成功即扣，平台不留敞口。**
被否决：B 结算周期扣（平台要垫账期，敞口大）；C 发货/完成扣（虚拟商品与未发货订单长期挂账）；D 下单冻结（冻结/解冻/超时释放复杂，阶段一不必要）。

规则：
1. 支付事件成功且订单为 `PAID` 后，读取订单费用快照，从 `merchant_account.balance_available` 扣减含税 `fee_amount`，写 `merchant_account_ledger`（`entry_type=SERVICE_FEE_CHARGE`，幂等键按订单号+费用版本）。
2. 余额不足时，不部分扣款：整笔费用转入 `arrears`，订单 `fee_status=ARREARS`，并按管理员确认的账期进入宽限；宽限期可配 **7/21/30 天**，用于余额不足时的欠费宽限。
3. 出单频次高的商家暴露更大，账期应更短；系统可根据出单频次给出建议值（7/21/30），但**必须管理员确认后才生效**，建议值与确认人留痕。
4. 推广期 0 费率：仍生成计费记录，`fee_amount=0`，同时记录 `fee_standard_amount`（标准应收）与 `fee_waived_amount`（免收），不扣余额；账面可查应收与免收。
5. 退款发生的服务费冲正，按 §6.2 反向写账本；已结算后退款进入下一期红字调整，不回写已确认账单。
6. 这里的“账期”是**欠费宽限**，不是结算单周期；结算周期已由 Q06-C 确认为**月结**（§9.3），两者不得混为一谈。

### 8.3 余额不足策略（已确认 Q07-B）

**已确认（用户 2026-11-06）**：
- 余额不足时可进入欠费宽限，账期支持 7/21/30 天，按店铺配置，管理员确认后生效。
- 系统可给出建议账期，但不得自动生效；高频商家建议更短账期。
- 宽限期内允许欠费并继续接单收款。
- **欠费累计达到 500 元或账期到期，任一触发即停收款**；500 元为**初始值**，后台做成可配项（按店铺配置信用额度）。
- 商家补齐欠费后**自动恢复**收款；恢复动作需记录触发原因、时间与操作人/系统任务。
- 被否决：Q07-A “到期直接停、不设额度”不采用；Q07-C “只提醒不停单”和 Q07-D “动态额度”暂不采用，原因是前者坏账风险高、后者阶段一过重。

| 事项 | 状态 | 说明 |
| --- | --- | --- |
| 欠费宽限期 | **已确认** | 7/21/30 天可配；管理员确认；高频商家更短 |
| 信用额度 | **已确认** | 初始 500 元，后台可配；按店铺设置 |
| 停收款触发 | **已确认** | 欠费≥额度 或 账期到期，任一满足即停收款 |
| 恢复条件 | **已确认** | 欠费补齐后自动恢复；记录恢复审计 |
| 已支付订单 | **已确认** | 停收款只影响新单收款，已支付订单继续履约与退款 |

### 8.4 充值与对账

**已确认（用户 2026-11-06）：Q08-A 线下转账 + 后台人工确认到账。** 阶段一不做服务费收款码、不做自动扣款（Q08-B/C/D 暂不采用），充值链路必须建立在“充值单 + 唯一凭证号 + 幂等入账”三个约束上。

#### 8.4.1 充值单模型（拟新增 `merchant_recharge_order`）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT UNSIGNED PK | 充值单主键 |
| `recharge_no` | VARCHAR(40) | 平台充值单号，全局唯一 |
| `merchant_id` | BIGINT | 店铺 |
| `amount` | DECIMAL(12,2) | 申请充值金额（服务费余额，不含货款） |
| `voucher_no` | VARCHAR(128) | **唯一凭证号**：商家线下转账的银行流水号/回单号；人工确认时录入 |
| `voucher_proof_url` | VARCHAR(512) NULL | 回单影像地址 |
| `payer_name` | VARCHAR(128) | 付款户名（需与转账回单一致） |
| `status` | VARCHAR(24) | `PENDING`/`CONFIRMED`/`REJECTED`/`VOID` |
| `confirmed_amount` | DECIMAL(12,2) NULL | 实际到账金额（允许与申请金额不一致，以到账为准） |
| `confirmed_at/confirmed_by` | DATETIME/BIGINT NULL | 人工确认人与时间 |
| `ledger_entry_id` | BIGINT NULL | 入账成功后关联的账本分录 |
| `remark` | VARCHAR(512) NULL | 差异说明/驳回原因 |
| `version` | INT | 乐观锁 |
| `created_at/updated_at` | DATETIME | 时间戳 |

唯一约束：`uk_recharge_no(recharge_no)`、**`uk_voucher_no(voucher_no)`**（同一凭证号全局只能入账一次，这是防重复确认的核心约束）。

#### 8.4.2 人工确认与幂等入账规则

1. 商家提交充值申请 → 生成 `PENDING` 充值单并展示平台收款账户；此时不写可用余额。
2. 商家线下转账后回填 `voucher_no` 与回单；运营在后台核对户名、金额、到账流水后确认。
3. 确认动作在一个事务内完成：校验充值单仍为 `PENDING` → 校验 `voucher_no` 未被任何其他充值单使用 → 更新 `status=CONFIRMED` → 写一条 `RECHARGE` 账本分录（幂等键用 `recharge_no` 或 `voucher_no`）。
4. **重复确认防护**：同一 `voucher_no` 第二次确认必须被唯一索引或幂等键拒绝；并发双确认用乐观锁 `version` + 唯一约束双保险，禁止只靠前端防重。
5. 确认金额与申请金额不一致时，以实际到账金额入账，差异写入 `remark` 并进入对账差异队列；不得静默按申请金额入账。
6. 驳回/作废的充值单不产生账本分录；已确认的充值单不得直接改金额，只能新增反向调整分录。
7. 平台收款账户仅限服务费充值，**不得与货款混同**；阶段一货款仍由商家直收。

#### 8.4.3 对账口径

- 充值单、账本分录、结算单、发票四者可对上；差异进入人工队列。
- 未人工确认前计入 `PENDING`，不作为可用余额；不得让商家在未人工确认充值下先用余额抵扣服务费。
- 人工确认操作必须记审计：操作人、时间、充值单号、凭证号、金额、前后余额。

---

## 9. 结算单与对账

### 9.1 阶段一的“结算单”是什么

阶段一平台不代收货款，因此结算单**不是平台应付商家的货款单**，而是：
- 服务费应收/已收账单；
- 商家账户扣费凭证；
- 与订单、支付事件、退款事件的对账载体。

页面与文案必须避免“平台将货款结算给商家”的表述，否则会被误解为二清。

### 9.2 拟新增表

`merchant_settlement_batch`：
| 列 | 说明 |
| --- | --- |
| `id/merchant_id` | 批次/店铺 |
| `period_start/period_end` | 结算周期（含/不含） |
| `biz_type` | `SERVICE_FEE`（阶段一）/ `GOODS_PAYABLE`（阶段二预留） |
| `gross_amount` | 周期内计费基数合计 |
| `refund_base_amount` | 周期内退款基数合计 |
| `fee_amount` | 应收服务费 |
| `adjustment_amount` | 上期差异/红字调整 |
| `paid_amount` | 已从余额扣收 |
| `arrears_amount` | 转欠费金额 |
| `status` | `DRAFT/CONFIRMED/PARTIAL_PAID/PAID/DISPUTED/VOID` |
| `confirmed_at/confirmed_by` | 确认信息 |
| `version` | 乐观锁 |

`merchant_settlement_item`：
- `batch_id/order_id/order_sn/payment_event_id/refund_id`
- `billable_base/fee_rate_bps/fee_amount/fee_refunded_amount/net_fee`
- `item_type`（`ORDER_FEE/REFUND_REVERSAL/ADJUSTMENT`）
- `created_at`
- 唯一键：`(batch_id, item_type, order_id, refund_id)`，保证重算幂等。

### 9.3 周期与明细粒度

| 维度 | 建议 |
| --- | --- |
| 周期 | **已确认（用户 2026-11-06）：Q06-C 月结**。按自然月生成结算单（`period_start` 为月初、`period_end` 为月末，含/不含边界在实现时固定并写入文档）；日结/周结不采用，不得按“默认周结”实现 |
| 明细粒度 | 一订单一行费用明细；退款一行冲正；调整一行 |
| 逐笔明细查询 | **已确认（用户 2026-11-06）**：后台必须同时提供逐笔明细查询；结算单是汇总，明细是证据。明细可按下单时间/支付时间/订单号/退款单号/费用状态筛选，并能追到 `payment_event_id`、`refund_id` 与订单快照费率 |
| 生成方式 | 定时任务按周期扫描“已支付且费用状态 ACCRUED/REVERSED”的订单与退款事件 |
| 重算 | 同一周期未人工确认前可重算并覆盖草稿；确认后只能追加调整项，不覆盖 |
| 差异 | 订单金额/费用/事件缺失进入 `DISPUTED` 或差异队列，人工处理 |

### 9.4 与订单、支付事件的关系

- 订单是计费基数的权威来源；`payment_event` 是“是否真实到账”的权威来源；结算单只做汇总与应收确认，不反推订单状态。
- 阶段一结算明细必须能追到 `payment_event_id`；没有支付事件的订单不得计费。
- 阶段二增加 `split_detail_id` 关联；结算单可同时包含 `GOODS_PAYABLE` 与 `SERVICE_FEE` 两类业务，但阶段一不生成货款应付。
- 现有 `billing_statement` 不再作为账本使用：可迁移为视图/历史表，或由新表替代；不得继续默认 `SETTLED`。

### 9.5 差异处理

- 金额差异、重复支付、退款缺失、渠道流水找不到订单：进入差异队列，状态 `DISPUTED`，不自动平衡。
- 差异处理动作（补单、冲正、人工确认）必须留痕；不得直接改订单金额或删除明细。
- 对账输出支持导出 CSV/Excel，字段与账本一致。

---

## 10. 阶段二预留：分账扩展点

### 10.1 阶段一就建的表/字段

| 对象 | 阶段一是否建 | 原因 |
| --- | --- | --- |
| `collection_mode`（订单） | **建字段并写值** | 决定资金归属，阶段二灰度必需 |
| `pay_channel`（订单） | **建/拆字段** | 与 A/B/C 解耦，渠道统计必需 |
| `platform_trade_no`（订单/事件） | **建字段，阶段一为空** | 阶段二平台流水回填，不增加迁移 |
| 费用快照列（订单） | **建并启用** | 阶段一计费；阶段二平台留成直接复用 |
| `merchant_fee_policy` | **建并启用** | 阶段一费率来源；阶段二同一费率 |
| `merchant_account/ledger` | **建并启用** | 阶段一服务费账户；阶段二可扩展货款待结算 |
| `merchant_settlement_batch/item` | **建并启用** | 阶段一服务费账单；阶段二加 `GOODS_PAYABLE` |
| `split_receiver` | 阶段二再建 | 依赖渠道进件与分账协议，阶段一无真实数据 |
| `split_detail` | 阶段二再建 | 依赖平台代收订单；阶段一无分账流水 |
| `freeze_until/reverse` 字段 | 阶段二建在 `split_detail` | 阶段一无冻结/退分语义 |

### 10.2 阶段二不得重做的部分

- 订单主键、订单号、支付事件主键、费用快照结构不变；阶段二只增加分账关联。
- 历史阶段一订单保持 `MERCHANT_DIRECT`，不得回填为平台代收。
- 服务费计费基数与费率快照继续复用，不另建第二套计费表。
- 商家账户可以增加“货款待结算/可提现”子账户，但不得把阶段一服务费余额与货款混用。

### 10.3 阶段二新增表（设计草案）

`merchant_channel_binding`（可由 `merchant_payment_account` 演进）：
- `merchant_id/channel/sub_mchid/channel_merchant_id/binding_status/effective_from/to/raw_result`

`split_receiver`：
- `merchant_id/channel/receiver_type/receiver_account_masked/receiver_name/bind_status/channel_receiver_id`

`split_detail`：
- `order_id/payment_event_id/platform_trade_no/receiver_id/split_amount/fee_amount/split_status/freeze_until/reversed_amount/channel_split_no`

`split_reverse`：
- `split_detail_id/refund_id/reverse_amount/reverse_status/channel_reverse_no`

约束：
- 分账金额 + 平台留成 = 实收金额；差额进入差异队列。
- 分账失败可重试，同一订单同一接收方幂等；退款先退分再退款。
- 冻结期、退分规则以渠道能力为准，不自行编造。

---

## 11. 与现有代码的衔接

| 现有对象 | 现状 | 建议动作 | 不要动 |
| --- | --- | --- | --- |
| `merchant_store.payment_channel_type`（A/B/C） | 一列表示收款模式，但默认 C，且被写入订单支付渠道 | 新增 `collection_mode`；A/B/C 仅保留历史/迁移映射，新逻辑不再读写 A/B/C；阶段一默认 `MERCHANT_DIRECT` | 不删除历史字段；不在阶段一强行清洗全部存量 |
| `merchant_store.commission_rate/monthly_fee/fee_free_until/fee_status` | 费率 0–100 校验、月度费配置、状态标记 | 费率权威迁移到 `merchant_fee_policy`；`commission_rate` 作为兼容展示/迁移来源；月度费是否启用由业务确认 | 不把 `fee_status` 当账本；不因新增费率表立即删除旧列 |
| `trade_order.commission_rate/commission_amount/merchant_settle_amount/sharing_status` | 下单时算费，字段已有 | 保留并回填为费用快照；新增 `fee_policy_id/base/refunded/status`；`merchant_settle_amount` 阶段一仅展示，不作为平台应付款 | 不重算历史订单；不把 `sharing_status` 当真实分账结果 |
| `trade_order.payment_channel` | 默认 WECHAT/ALIPAY，代付写 A/B/C | **计划新增 `pay_channel`（WECHAT/ALIPAY）**，收款模式迁到**计划新增的 `collection_mode`**；`payment_channel` 保留为兼容/迁移来源，修正 N10 | 不在同一列继续写两种语义 |
| `payment_event` | 渠道流水/金额/状态 | **计划新增 `collection_mode/platform_trade_no`**；费用从订单快照读取 | 不用事件金额反推服务费；不删除现有字段 |
| `MerchantPaymentAccount` | 模拟 SUB/PASSED | 阶段二改造为渠道绑定；阶段一不再写入假值 | 不在阶段一调用真实进件；不展示“已开通” |
| `billing_statement` | 汇总表，默认 SETTLED | 标记为历史/视图；新结算用 batch/item | 不继续把它当账本或对账依据 |
| `OrderStatistics` | 统计输出字符串，佣金字段来自订单 | 继续用 `commission_amount` 作为“预计服务费”，但需注明非账本收入；结算后以账本为准 | 不把统计当财务收入 |
| `MerchantController` 资料接口 | 员工可读身份证/银行卡（R02/N5） | 按角色裁剪/脱敏；审核角色单独授权 | 不在列表接口返回完整 PII |

---

## 12. 表结构变更草案与迁移

### 12.1 新增表清单

1. `merchant_onboarding_application` + `merchant_onboarding_audit`
2. `merchant_settlement_account`（阶段一）
3. `merchant_fee_policy` + `merchant_fee_audit`
4. `merchant_account` + `merchant_account_ledger`
5. `merchant_settlement_batch` + `merchant_settlement_item`
6. 阶段二：`merchant_channel_binding`、`split_receiver`、`split_detail`、`split_reverse`（阶段一不建）

### 12.2 现有表新增列（草案）

> 本节所有列均为**计划新增**，当前 `schema.sql` 与实体中不存在；迁移时按“先加列、回填、再切读路径”执行。

`merchant_store`：
- `collection_mode VARCHAR(24) NOT NULL DEFAULT 'MERCHANT_DIRECT'`
- `fee_policy_id BIGINT NULL`（当前生效政策指针，可选）
- `onboarding_application_id BIGINT NULL`
- `arrears_grace_days INT NULL`（欠费宽限期，仅允许 7/21/30；管理员确认后写入）

`trade_order`：
- `collection_mode VARCHAR(24)`（阶段一 `MERCHANT_DIRECT`）
- `pay_channel VARCHAR(32)`（与现有 `payment_channel` 并存迁移，最终替代）
- `goods_amount DECIMAL(12,2) NULL`（商品成交额，下单固化）
- `merchant_discount_amount DECIMAL(12,2) NULL`（商家承担优惠）
- `platform_discount_amount DECIMAL(12,2) NULL`（平台承担优惠/补贴，不计基数）
- `shipping_amount DECIMAL(12,2) NULL`（运费，不计基数）
- `order_tax_amount DECIMAL(12,2) NULL`（订单税费，不计基数）
- `fee_policy_id BIGINT NULL`
- `fee_rate_bps INT NULL`（正常 100–1000，推广 0）
- `fee_rate_type VARCHAR(16) NULL`（`NORMAL/PROMOTION`）
- `fee_base_amount DECIMAL(12,2) NULL`
- `fee_standard_amount DECIMAL(12,2) NULL`（推广期标准应收，非 0 费率时等于含税费用）
- `fee_amount DECIMAL(12,2) NULL`（实际含税应收；推广期为 0）
- `fee_waived_amount DECIMAL(12,2) NOT NULL DEFAULT 0`（推广期免收）
- `fee_net_amount DECIMAL(12,2) NULL`（不含税净收入）
- `fee_tax_amount DECIMAL(12,2) NULL`（税额）
- `fee_tax_rate DECIMAL(6,4) NULL`（税率，系统配置快照）
- `fee_refunded_amount DECIMAL(12,2) NOT NULL DEFAULT 0`
- `fee_status VARCHAR(20)`（`PENDING/ACCRUED/ARREARS/SETTLED/REVERSED/VOID`）
- `platform_trade_no VARCHAR(128) NULL`

`merchant_account`：
- `balance_available/balance_frozen/arrears DECIMAL(12,2)`
- `credit_limit DECIMAL(12,2)`（**已确认 Q07-B**：固定信用额度，初始 500 元、后台可配；不按流水动态计算）
- `arrears_due_at DATETIME NULL`（按店铺账期计算）
- `grace_days INT NULL`（7/21/30，管理员确认值快照）
- `currency/version`

`payment_event`：
- `collection_mode VARCHAR(24) NULL`
- `platform_trade_no VARCHAR(128) NULL`

索引：
- `idx_order_fee_status(fee_status, created_at)`
- `idx_order_merchant_collection(merchant_id, collection_mode, created_at)`
- `idx_settlement_batch(merchant_id, period_start, period_end)`
- `uk_settlement_item(batch_id, item_type, order_id, refund_id)`
- `uk_account_ledger(idempotency_key)`

### 12.3 迁移要求

- 新库：直接写入 `schema.sql`；存量库：必须提供版本化 `ALTER` 与回填脚本，不能只改 `CREATE TABLE IF NOT EXISTS`。
- 回填顺序：
  1. 建 `merchant_fee_policy`，把存量 `commission_rate` 转为 bps（3.00% → 300），来源标 `MIGRATION`；存量一律 `rate_type=NORMAL`，不得回填为 `PROMOTION`。
  2. 订单 `fee_rate_bps/fee_amount` 由 `commission_rate/commission_amount` 回填；`fee_status` 按订单状态初始化（已支付→`ACCRUED`，已退款→`REVERSED`，未支付→`PENDING`）；`fee_standard_amount` 等于回填的含税费用，`fee_waived_amount=0`。
  3. 存量店铺补 `collection_mode=MERCHANT_DIRECT`；A/B/C 保留只读映射；`arrears_grace_days` 初始为空，不自动套用 7/21/30。
  4. 结算/账户表初始为空，不伪造历史账本。
- 迁移前必须备份（与修复清单 T21 联动）；迁移失败阻断启动，不得忽略错误。
- 回滚：新表可保留，新增列回滚需谨慎；订单历史列不回填反操作，避免破坏证据。

---

## 13. 合规边界

### 13.1 阶段一无需牌照即可做的内容

在满足以下前提时，阶段一可作为“商家自营收款 + 平台技术服务”上线：
- 平台不代收、不归集、不沉淀货款；买家资金直接进入商家自己的微信/支付宝商户号或收款账户。
- 平台只收取技术服务费，并开具对应发票/收据；服务费定价、合同、退款责任清晰。
- 商家实名与收款账户登记完成；平台保留审核记录。
- 页面不得宣称“平台存管/官方直结/担保交易/二清合规/秒发”等未取得资质的能力。
- 平台不提供“代收代付”“统一收款后结算”等实质二清功能。

### 13.2 阶段二必须解决的资质与合规前提

- 平台商户号与支付机构/银行的合作资质、分账产品开通。
- 分账接收方进件、KYC、协议签署、结算账户验证。
- 反洗钱/反欺诈/商户风险评级、交易限额与可疑交易报告。
- 资金存管/备付金/客户资金隔离要求（按适用监管口径）。
- 发票、税务、平台抽成与商家结算的会计处理。
- 退款、退分、冻结、争议处理的渠道规则与客诉机制。
- 数据合规：身份证/营业执照/银行卡的最小化收集、加密、留存期限与删除策略。

### 13.3 明确不能做的事

- 阶段一用平台商户号统一收货款再转给商家（实质二清）。
- 用“担保/存管/官方直结”文案包装尚未签约的通道。
- 在未实名、未审核账户的情况下允许收款。
- 用服务费账户余额冒充货款余额，或从服务费余额退货款。
- 阶段二未开通分账前，提前在订单上标记 `sharing_status=FINISHED`。

---

## 14. 需要用户拍板的业务规则

| 编号 | 问题 | 可选方案 | 推荐 | 阻塞 |
| --- | --- | --- | --- | --- |
| S01 | 服务费扣费时点 | 支付成功即扣 / 结算周期扣 / 发货完成扣 | **已确认 A：余额充足时支付成功即扣；余额不足按 7/21/30 天账期欠费宽限（管理员确认）** | 已确认（M3） |
| S02 | 费率含税口径 | 含税价 / 不含税价（另加税） / 对外含税+内部价税分离 | **已确认 D：对外含税签约展示，系统内部价税分离记账（净收入+税额）** | 已确认（M1、M3） |
| S03 | 退款责任与通道 | 商家自行退款 / 平台代退 / 混合 | **已确认 C：按 `collection_mode` 分叉；阶段一商家退、平台只冲正服务费；阶段二平台退** | 已确认（M3、M4） |
| S04 | 欠费限制策略 | 允许欠费至额度 / 超额停新单 / 仅提醒 | **已确认（用户 2026-11-06）：Q07-B 宽限期 + 固定信用额度。宽限期内允许欠费；欠费累计达到上限或账期到期任一触发即停收款；补齐后自动恢复** | 已确认（M3、M4） |
| S05 | 结算周期 | 日结 / 周结 / 月结 | **已确认（用户 2026-11-06）：C 月结；后台同时提供逐笔明细查询（结算单是汇总，明细是证据）** | 已确认（M3） |
| S06 | 计费基数是否含运费/平台优惠 | 含 / 不含 | **已确认 B：商品成交额，剔除运费、平台优惠/补贴、税费；必须新增字段，不用实付额过渡** | 已确认（M2） |
| S07 | 调价生效时间 | 立即 / 次日 / 预约 | **已确认（用户 2026-11-06）：B 次日 00:00 生效，按自然日一天一套费率；例外：降费可即时、调高必须次日** | 已确认（M1、M2） |
| S08 | 信用额度分级 | 0 / 固定档 / 按流水 | **已确认（用户 2026-11-06）：Q07-B 固定信用额度，初始值 500 元且后台做成可配置项（按店铺/全局配置，具体键名实现时定）；按流水动态额度不采用** | 已确认（M3） |
| S09 | 充值方式 | 线下转账 / 平台服务费收款码 / 两者 | **已确认（用户 2026-11-06）：A 线下转账 + 后台人工确认到账；充值单 + 唯一凭证号 + 幂等入账，防重复确认** | 已确认（M3） |
| S10 | 平台优惠券成本归属 | 平台承担 / 商家承担 | **已确认 B：平台承担，不计商家计费基数，不向商家收服务费** | 已确认（M2） |
| S11 | 阶段二切换触发条件 | 资质取得 / 商户规模 / 强制 | **已确认（用户 2026-11-06）：Q09-A 资质取得 + 灰度名单；支持按渠道/按商家回滚** | 已确认（M5） |
| S12 | 服务费发票类型 | 平台开票 / 代开 / 不开 | **已确认（用户 2026-11-06）：A 阶段一不做开票功能，只保留价税分离数据；B/C 不采用** | 已确认（M1、M3） |

> S01–S12 已全部确认；任何未落地实现仍只能做“不改变计费语义”的结构与校验，不得自行定价、扣费或对外承诺。
