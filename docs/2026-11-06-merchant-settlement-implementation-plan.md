<!--
文件：2026-11-06-merchant-settlement-implementation-plan.md
用途：盘点商家入驻、实名、收款账户、技术服务费、商家账户、结算对账的当前代码现状与差距，并按 M1–M5 拆分实施顺序；同时说明与修复清单 S5（T32–T35、T39、T40）的细化/重构关系。
维护注意：
 1. 本文是现状差距与实施计划，不是已实现功能说明；“现有”均指 HEAD bcb1dab 的代码事实，“拟做”均为待实现。
 2. 不修改源码、不运行 mvn/编译/测试/构建、不连接数据库；所有结论来自静态阅读，未做并发/渠道/财务联调。
 3. M1–M5 的顺序不可倒置：实名与费率政策先于计费，账户与结算先于欠费对账，阶段二分账最后；阶段二不得重写订单与支付事件模型。
 4. 与 [修复清单](2026-11-06-remediation-plan.md) 的关系：S5 由“支付/退款/分账/对账”重构为“M1–M5”，T32–T35 编号保留但范围重定义，新增 T39（M1）、T40（M5）；修复清单是本文件的任务入口，本文件是 S5 的设计与拆分依据。
 5. 任何“已修复/已验证”状态只能由用户反馈填写；本文件不宣称任何能力已完成。
 6. 涉及身份证、营业执照、银行卡、结算账户的改造必须同时满足最小权限与加密/脱敏要求；不得把审核资料写入日志或演示数据。
-->
# 商家结算现状差距与分期实施（2026-11-06）

复核基线：`bcb1dab`。
文档性质：**现状盘点 + 实施计划（设计/待实现）**。
设计依据：[商家结算与技术服务费设计](2026-11-06-merchant-settlement-design.md)。
任务入口：[修复清单与排期](2026-11-06-remediation-plan.md)。

---

## 1. 结论摘要

| 维度 | 现状 |
| --- | --- |
| 商家入驻 | 仅超管直接创建店铺并同时创建店长；无申请单、无实名材料、无审核状态机、无驳回/补件；创建即 `status=1`。 |
| 收款账户 | 只有本地模拟的 `MerchantPaymentAccount`（`SUB+时间戳`、默认 `PASSED`）；无账户登记审核、无脱敏、无生效区间。 |
| 技术服务费 | 下单按店铺 `commission_rate`（默认 3%）算费并写订单；校验为 0–100，不是 1%–10%；无费率政策、无调价审计、无费用状态/冲正。 |
| 商家账户 | 无预存/余额/欠费/账本；无扣费任务。 |
| 结算对账 | `billing_statement` 仅表结构且默认 `SETTLED`；无生成、无明细、无差异处理。 |
| 阶段二 | 无分账接收方/明细/冻结/退分；`sharing_status` 是订单字段而非真实分账结果；切换方式已确认 Q09-A（资质 + 灰度名单，可按渠道/商家回滚）。 |
| 隐私 | `/auth/me`、`/merchant/profile` 向员工返回身份证/银行卡/结算账户（R02/N5）。 |
| 对外文案 | 首页/详情/导航仍展示“存管/官方直结/担保/秒发”等未验证承诺（R22）。 |

**总体判断**：当前只有“下单时算一个佣金数字”的能力，不能称为“商家结算”。阶段一必须先把实名、账户、费率政策、费用快照、账户账本、结算单六件事补齐；阶段二只在其上增加分账，不得另起一套订单/资金模型。

---

## 2. 现状盘点（带代码证据）

### 2.1 商家入驻与实名

| 能力 | 现状 | 证据 | 差距 |
| --- | --- | --- | --- |
| 入驻申请 | 无申请实体/表；超管直接 `POST /api/v1/admin/merchants` 创建 | [MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L68-L133) | 无主体资料、无材料上传、无申请状态 |
| 实名认证 | 无第三方核验、无人工审核记录；身份证号直接存店铺表 | [MerchantStore.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/MerchantStore.java#L28)、[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L15-L36) | 个人/企业两条路径均缺失；无加密/脱敏 |
| 审核状态机 | 创建即营业 `status=1`；只有营业/打烊/封禁状态 | [MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L74-L83)、`:135-L143` | 无 `SUBMITTED/REVIEWING/APPROVED/REJECTED/SUPPLEMENT_REQUIRED` |
| 驳回/补件 | 无 | 同上 | 商家无法自助补件，审核无留痕 |
| 员工 PII | 员工可读店铺完整实体（含身份证、银行卡、结算账户） | [AuthController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java#L124-L128)、[MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L200-L216) | 未按角色裁剪；与 M1 同批修复 |

### 2.2 收款账户与进件

| 能力 | 现状 | 证据 | 差距 |
| --- | --- | --- | --- |
| 账户登记 | 创建商户时写 `MerchantPaymentAccount` | [MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L119-L130) | 只有一张表，无多账户/默认账户/生效区间 |
| 真实进件 | 未接入；`sub_mchid = "SUB"+时间戳` | 同文件 `:124` | 假流水号；不能作为真实商户号 |
| 审核状态 | 默认 `PASSED` | 同文件 `:128`；[MerchantPaymentAccount.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/MerchantPaymentAccount.java#L26) | 未审核即“通过”，页面可能显示已开通 |
| 账户脱敏 | 实体含银行卡号，接口未按角色脱敏 | [MerchantPaymentAccount.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/MerchantPaymentAccount.java#L4)、`:22-L26` | 员工/列表可见敏感信息 |
| 阶段二绑定 | 无渠道绑定生命周期 | 同上 | 无法平滑替换为真实 `sub_mchid` |

### 2.3 技术服务费

| 能力 | 现状 | 证据 | 差距 |
| --- | --- | --- | --- |
| 费率来源 | 店铺 `commission_rate`，默认 3.00% | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L26)、[MerchantStore.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/MerchantStore.java#L31) | 无政策版本、无来源/原因/审计 |
| 费率校验 | `ManagementGuards.percent` 只校验 0–100 | [ManagementGuards.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/ManagementGuards.java#L20)、[MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L85)、`:149` | 未限制 1%–10%；可设 0 或 100 |
| 管理员调价 | 定向 SQL 更新 `commission_rate` | [MerchantStoreMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/MerchantStoreMapper.java#L26)、[MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L146-L153) | 无历史、无生效时间、无二次确认 |
| 订单快照 | 下单写 `commission_rate/commission_amount/merchant_settle_amount` | [OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L113-L131)、[PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L91-L104)、`:191-L208` | 缺计费基数、政策版本、费用状态、已冲正金额 |
| 费用状态 | 只有订单状态，无 `fee_status`；统计把 `commission_amount` 当预计佣金 | [OrderStatistics.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/OrderStatistics.java#L17-L47) | 无法区分应收/已收/已冲正；统计不是账本 |
| 月度费/免收期 | `monthly_fee/fee_free_until/fee_status` 只是配置标记 | [MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L155-L176)、[ServiceFeeStatus.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/ServiceFeeStatus.java#L3-L17) | 不扣费、不到期转换、不停服；与按订单计费未拆分 |

### 2.4 商家账户、结算与对账

| 能力 | 现状 | 证据 | 差距 |
| --- | --- | --- | --- |
| 预存/余额/欠费 | 无账户表、无账本、无扣费任务 | `schema.sql` 无 `merchant_account*` | M3 全缺 |
| 结算单 | `billing_statement` 只有表结构，默认 `SETTLED` | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L208-L224) | 无生成服务、无明细、无确认/差异 |
| 订单费用明细 | 无结算明细表 | 同上 | 无法逐单对账 |
| 退款冲正 | 只有订单 `REFUNDED` 状态，无退款表/服务/费用冲正 | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L142-L146) | 退款与费用两条账均缺 |
| 对账 | 无渠道流水导入、无差异队列 | 无对应表/服务 | M4 全缺 |

### 2.5 支付事件与资金语义

| 能力 | 现状 | 证据 | 差距 |
| --- | --- | --- | --- |
| 支付事件 | 有 `payment_event` 表与状态 | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L449-L466) | **现状无** `collection_mode/platform_trade_no`（均为计划新增）；无费用/结算关联 |
| 事件顺序 | 外层事务提交前 `REQUIRES_NEW` 记 `SUCCESS`；重放直接返回 | [PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L232-L234)、[PaymentEventRecorder.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L32-L41) | 事件三连 P0 未修（T05–T07）；结算不得建立在此之上 |
| 实收金额 | 代付事件金额从订单回填，校验恒真 | [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L495-L505) | 无渠道实收金额，不能作为计费依据 |
| 重复支付 | 已支付订单提前返回，第二笔无事件 | 同文件 `:486-L488` | 退款/冲正无数据来源 |

### 2.6 收款模式与渠道语义混用

| 问题 | 现状 | 证据 |
| --- | --- | --- |
| A/B/C 与渠道同列 | `merchant_store.payment_channel_type` 为 A/B/C；代付建单把它写入 `trade_order.payment_channel` | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L30)、[PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L108)、`:212` |
| 普通订单写 WECHAT/ALIPAY | 同一列默认写渠道 | [OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L132) |
| 支付成功再覆盖 | 事件处理把渠道写回同一列 | [PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L155) |
| 结论 | 无法区分“谁收钱”和“用什么渠道收钱”，阶段一/阶段二切换必须拆列 | 设计 §2.3、§11 |

### 2.7 对外承诺与合规

| 问题 | 现状 | 证据 |
| --- | --- | --- |
| 虚构担保/存管/直结 | 首页“微信/支付宝官方二级直结”“持牌官方专户存管”；详情“持牌专户担保·零二清”；导航“资金微信直付通存管” | [Home.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/Home.vue#L99)、`:141`、[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L149-L150)、[Navbar.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/components/Navbar.vue#L14) |
| “秒发”承诺 | 详情/首页/页脚多处“已自动秒发” | [ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L196)、[Home.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/Home.vue#L103) |
| 影响 | 阶段一尚未完成实名/账户/计费，对外已宣称存管与直结，存在合规与客诉风险 | 设计 §13 |

---

## 3. 目标能力分层

```text
M1 入驻/实名/收款账户/费率政策
        │
        ▼
M2 订单计费与费率快照（含代付/普通单统一）
        │
        ▼
M3 商家账户/扣费/结算单（服务费账单）
        │
        ▼
M4 欠费/对账/差异/退款冲正
        │
        ▼
M5 阶段二平台代收 + 分账（资质后）
```

每一层都必须能独立验证；M2 不在 M1 完成前上线真实计费，M3 不在 M2 完成前扣费。

---

## 4. 迭代拆分

### M1 商家入驻、实名、收款账户与费率政策

| 项 | 内容 |
| --- | --- |
| 交付范围 | `merchant_onboarding_application/audit`、`merchant_settlement_account`、`merchant_fee_policy/audit`；个人/企业两条资料路径；审核状态机；1%–10% 校验与调价审计；账户登记审核；角色化 PII 展示。 |
| 依赖前置 | T13（员工 PII/权限）先或同批；T12（迁移机制）；T03（默认凭据/演示种子清理）；加密服务复用 `CardCipherService` 模式或独立 PII 加密服务。 |
| 不可拆 | 申请单 + 审核状态机 + 审计必须同批；费率政策 + 1%–10% 校验 + 调价审计必须同批；账户登记 + 审核 + 脱敏必须同批。 |
| 回归要点 | 合法：个人/企业提交→审核通过→创建店铺/店长；管理员调价 1%–10% 成功并留痕。非法：超范围费率拒绝；员工读完整 PII 被拒/脱敏；重复主体/重复审核被拒。失败后数据不变：审核失败不改店铺状态；调价失败不产生新政策。 |
| 状态 | 待实现（设计完成，代码未改） |

关键设计选择：
- 申请单独立于 `merchant_store`，审核通过才创建/激活店铺；存量店铺补录，不伪造实名。
- 费率用 `rate_bps`（100–1000）；`commission_rate` 仅作兼容/迁移来源。
- 收款账户按“新增生效记录”处理，换账户不覆盖历史。

### M2 订单计费与费率快照

| 项 | 内容 |
| --- | --- |
| 交付范围 | 下单写**计划新增的** `collection_mode/pay_channel/fee_policy_id/fee_rate_bps/fee_base_amount/fee_amount/fee_status`（现有代码只有 `payment_channel`/`payment_channel_type`，不存在前两列）；普通单与代付单统一；订单金额/费用字符串契约；调价只影响新单；缺失政策阻断。 |
| 依赖前置 | M1 费率政策；T05–T07 支付事件三连修复（费用不得建立在错误事件上）；T06 实收金额校验；T08 订单截止。 |
| 不可拆 | 订单快照列 + 下单计费逻辑 + 普通/代付两条建单路径 + 回归用例必须同批；不能只改代付而漏普通单。 |
| 回归要点 | 合法：费率 3% 订单费用=基数×3%，普通/代付一致；调价后新单用新费率、旧单不变。非法：缺政策/超范围费率拒绝下单；金额为负/精度超 2 位拒绝。失败后数据不变：计费失败不建单、不预占、不扣库存。 |
| 状态 | 待实现 |

注意：
- 现有 `commission_rate/commission_amount/merchant_settle_amount` 不可直接删除；先双写/回填，读路径逐步切换。
- `merchant_settle_amount` 阶段一只是展示“商家应收净额”，不是平台应付款。

### M3 商家账户、扣费与结算单

| 项 | 内容 |
| --- | --- |
| 交付范围 | `merchant_account/ledger`；充值/扣费/冲正账本；余额充足时支付成功即扣（Q02-A）；余额不足转欠费并按 7/21/30 天账期宽限；推广 0 费率记应收/免收；含税扣款、内部价税分离；结算批次 `settlement_batch/item`（**Q06-C 月结**，同时提供逐笔明细查询）；**Q08-A 线下转账充值与人工确认（充值单 + 唯一凭证号 + 幂等入账）**；管理员/商家查询与导出。 |
| 依赖前置 | M2 费用快照；M1 账户与费率；T21 迁移前备份；T18 错误码契约；Q01–Q21 与 S01–S12 已于 2026-11-06 全部确认或撤回，无待确认阻塞。 |
| 不可拆 | 账户余额 + 账本 + 幂等键必须同批；结算批次 + 明细 + 确认状态必须同批；不能先做“余额”后补账本；扣费与欠费状态必须同批。 |
| 回归要点 | 合法：余额充足支付成功即扣、账本余额一致；余额不足按已确认账期转欠费；推广 0 费率生成应收/免收记录；含税金额可分净收入/税额。非法：重复结算/重复回调重复扣费；0 费率漏记。并发交错：退款与扣费同时发生，冲正不重复、不出现负费用。失败后数据不变：扣费失败不改订单费用状态、不重复生成明细。 |
| 状态 | 待实现 |

### M4 欠费、对账与差异处理

| 项 | 内容 |
| --- | --- |
| 交付范围 | 欠费额度/停单/解除；渠道流水与订单/事件对账；差异队列；退款冲正入账；账单导出；运营审核。 |
| 依赖前置 | M3 账本与结算单；T32 真实渠道（至少阶段一直收流水可导入）；T07 重复支付事件。 |
| 不可拆 | 对账输入（订单/事件/渠道流水）+ 差异处理 + 账本调整必须同批；欠费停单与恢复必须同批。 |
| 回归要点 | 合法：账单与流水一致时确认；欠费在额度内继续接单。非法：超额度停新单；缺流水/金额不符标差异不静默平衡。并发交错：停单与支付成功同时发生，已支付订单仍履约。失败后数据不变：对账失败不覆盖已确认账单。 |
| 状态 | 待实现 |

### M5 阶段二平台代收与分账

| 项 | 内容 |
| --- | --- |
| 交付范围 | 平台商户号接入；`merchant_channel_binding/split_receiver/split_detail/split_reverse`；分账、冻结、退分；平台留成；对账。切换按**已确认 Q09-A**：资质取得 + 灰度名单，支持按渠道/按商家回滚；发票阶段一已确认不做（S12-A），后续另立。 |
| 依赖前置 | M1–M4 全部完成；T15 卡密密钥；支付资质/协议/渠道沙箱；S11 切换条件已确认（Q09-A）。 |
| 不可拆 | 代收 + 分账 + 退分 + 对账必须按渠道能力同批设计；不能先上线“平台代收但手工转账”。 |
| 回归要点 | 合法：实收=分账+平台留成；退款先退分再退款。非法：未绑定接收方/超额分账拒绝；重复分账幂等。并发交错：支付回调、分账、退款交错，金额守恒。失败后数据不变：分账失败可重试，订单支付状态不回退。 |
| 状态 | 待实现（资质前不排期） |

---

## 5. 与修复清单 S5（T32–T35、T39、T40）的关系

### 5.1 结论：**重构 S5 范围，保留编号，不另立第二套入口**

原 S5 的四项是“真实支付/退款/分账结算/对账账单”，其中“分账结算”默认阶段二能力，与用户已确认的“阶段一商家直收”不一致。处理方式：

| 原任务 | 新范围 | 关系 |
| --- | --- | --- |
| T32 真实支付渠道接入 | **M2：阶段一商家直收渠道接入**（真实回调、验签、查单、关单；不含平台代收） | 细化 + 范围收窄 |
| T33 退款与重复付款补偿 | **M3/M4：退款、重复付款补偿与费用冲正** | 保留并细化 |
| T34 分账、结算与资金流水 | **M3：服务费计费、商家账户与结算单**（阶段一不做渠道分账） | 重定义（分账移到 T40） |
| T35 对账账单与差异处理 | **M4：欠费、对账与差异处理** | 细化 |
| 无对应 | **M1：商家入驻、实名、收款账户与费率政策** → 新增 T39 | 新增前置 |
| 无对应 | **M5：阶段二平台代收与分账** → 新增 T40（资质后） | 新增延后 |

### 5.2 同步修改

- 修复清单 §2 阶段表：S5 主题改为“阶段一商家结算闭环 + 阶段二预留”，任务改为 T32–T35、T39、T40。
- 修复清单 §3 S5 卡片：T32/T33/T34/T35 按上表改写；新增 T39（M1）、T40（M5）。
- 修复清单任务总数：38 → 40；分布 S5 6 项。
- README 的修复清单描述同步为 40 个修复单元。
- 本文件与设计文档作为 S5 的详细依据；修复清单仍是唯一状态入口，两者不一致时以修复清单状态为准并回写。

### 5.3 为什么不是“替换”

- T32–T35 的编号已被其他文档/回归用例引用，重排会造成追溯断裂；保留编号、改写范围更安全。
- S5 仍是“商业闭环”阶段，只是把“阶段一可交付”和“阶段二资质后”分开；不是取消 S5。
- M1 与 M5 必须进入任务表，否则 S5 会缺少前置与延后两端，出现“直接做支付但没有实名/费率”的断层。

---

## 6. 风险与未决项

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 在 T05–T07 未修前做计费 | 事件成功但订单未履约，费用已应收，退款无依据 | M2 必须等 T05–T07 完成；费用只从订单快照读，不从事件反推 |
| `commission_rate` 双写期读路径不一致 | 同一订单展示/结算金额不同 | 规定唯一权威：订单快照；旧列只读兼容；迁移完成后删除旧读路径 |
| 账户余额与账本不一致 | 对账失败、商户投诉 | 所有余额变更走账本；日终校验余额=账本累计；禁止直接 update |
| 阶段二提前改订单模型 | 历史订单资金归属混乱 | `collection_mode` 阶段一即写入；阶段二只新增绑定/分账表 |
| 实名材料泄露 | 合规事故 | 加密+脱敏+角色最小权限+审计；禁止日志/演示数据 |
| 对外文案先于能力 | 客诉/监管风险 | M1 上线前清理“存管/直结/担保/秒发”文案；以真实状态展示 |
| 退款责任不清 | 商户/平台争议 | 已确认 Q03-C：按 `collection_mode` 分叉；阶段一商家自行退款，平台只冲正服务费 |
| 结算周期（Q06 已确认月结） | 账单与明细口径不一致 | 按自然月生成结算单；必须同时提供逐笔明细查询，结算单只是汇总 |
| 发票阶段一不做（S12-A 已确认） | 后续补票工作量 | 阶段一只做价税分离记账，不建开票入口、不接电子发票服务、不对外承诺发票类型 |

未决项：业务规则无残留；渠道选型（微信/支付宝/银行）、第三方实名服务商、阶段二资质时间表均未确定，属于外部前提而非业务决策。Q01–Q21 与 S01–S12 已于 2026-11-06 全部确认或撤回（Q10 撤回，旧字段处理由开发决定）。

---

## 7. 验证边界

本轮**没有执行**编译、测试、构建、数据库操作或渠道联调。因此：
- 现状证据均为静态代码阅读；行号以 `bcb1dab` 为准。
- 未验证 `billing_statement` 是否被其他代码引用（需在 M3 前全局检索确认）。
- 未验证存量 `commission_rate` 分布与异常值（0、100、NULL），迁移前需数据画像。
- 未验证员工实际可访问的接口范围；M1 需配合权限矩阵确认。
- H2/静态推断不能替代 MySQL 迁移、并发扣费、渠道回调与财务对账。

用户手动验证入口（与修复清单一致）：
- 后端：`mvn -f shop-b2b2c-backend/pom.xml test`
- 前端规则：`node --test shared/*.test.mjs`（先 `npm --prefix shop-storefront-vue ci`）
- 部署隔离：按 Sprint 18–21 的 Linux/WSL 用例执行。

---

## 8. 决策状态清单（与设计 §14 对应；S01–S12 全部确认）

| 编号 | 规则 | 影响里程碑 |
| --- | --- | --- |
| S01 | 扣费时点 | **已确认 A：余额充足支付成功即扣；不足按 7/21/30 天账期宽限** | M3 |
| S02 | 费率含税与记账 | **已确认 D：对外含税展示，内部价税分离** | M1、M3 |
| S03 | 退款责任 | **已确认 C：按 `collection_mode` 分叉** | M3、M4 |
| S04 | 欠费宽限与停单 | **已确认（用户 2026-11-06）：7/21/30 天账期 + 固定信用额度；欠费≥额度或账期到期即停收款，补齐自动恢复** | M3、M4 |
| S05 | 结算周期 | **已确认（用户 2026-11-06）：月结 + 逐笔明细查询** | M3 |
| S06 | 计费基数 | **已确认 B：商品成交额，剔除运费/平台优惠与补贴/税费；必须新增字段** | M2 |
| S07 | 调价生效时间 | **已确认 S07-B：次日 00:00 生效，按自然日一天一套费率；降费可即时、调高必须次日** | M1、M2 |
| S08 | 信用额度分级 | **已确认（用户 2026-11-06）：固定信用额度，初始 500 元、后台可配** | M3 |
| S09 | 充值方式与到账确认 | **已确认（用户 2026-11-06）：线下转账 + 人工确认；充值单 + 唯一凭证号 + 幂等入账** | M3 |
| S10 | 平台优惠券成本归属 | **已确认 B：平台承担，不计商家基数** | M2 |
| S11 | 阶段二切换条件与灰度 | **已确认 Q09-A：资质取得 + 灰度名单，支持按渠道/按商家回滚** | M5 |
| S12 | 服务费发票开具方式 | **已确认 A：阶段一不做开票，只保留价税分离数据** | M1、M3 |

> 业务决策已全部确认；所有能力仍需代码实现，保持“设计/待实现”状态。