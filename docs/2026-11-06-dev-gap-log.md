# 2026-11-06 开发过程缺口记录

用途：记录执行修复清单时发现的、任务卡未覆盖或与实际代码/阶段边界不一致的问题，避免顺手改动或静默遗漏。每条包含发现位置、现象、不属本卡范围的原因、建议归属与影响面。已验证状态由用户反馈填写，本文件只记录事实与待办。

## 记录规则

- 只记录，不在本文件标记“已修复”，除非对应任务卡提交并附证据。
- 不记录已由任务卡明确覆盖的普通实现细节。
- 发现 P0 级新缺陷时应即时向用户报告，再补录本文件。

## G01 T01 与 T28 同文件“须同批改”约束在 S0 无法满足

- 发现位置：[docs/2026-11-06-remediation-plan.md](2026-11-06-remediation-plan.md) T01"依赖/冲突"；[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue)。
- 现象：T01 卡片要求与 S4 的 T28（迟到响应覆盖新商品）同批修改同一状态机；本轮用户明确只执行 S0 T01–T04，且 S0 要求可独立上线。
- 不属本卡范围的原因：T28 属 S4，涉及迟到响应代付/海报链路，提前实现会扩大 S0 业务范围。
- 建议归属：T28；实施时必须基于本次抽取的 [productDetail.mjs](file:///e:/shop-b2b2c/shop-storefront-vue/src/utils/productDetail.mjs) 增加请求序号/取消令牌，避免回退本次的 finally 复位。
- 影响面：公开商品详情页在极慢请求与新商品快速切换时仍可能出现旧响应覆盖新商品；不影响 S0 主链路恢复。

## G02 T04 与 T19 同文件，后续须合并模板修改

- 发现位置：[04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh)、[02-init-infra.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh)。
- 现象：T04 已在 `.env` 生成与部署预检中加入 `SPRING_DATASOURCE_USERNAME/PASSWORD` 强制校验；T19 还要改前台地址与 Redis 库号，同文件。
- 不属本卡范围的原因：T19 属 S1，涉及前台地址/Redis 回退，本轮不处理。
- 建议归属：T19；修改时必须保留 T04 的四键非空校验与 `admin123` 拒绝逻辑，并同步腾讯脚本。
- 影响面：若 T19 整段重写 `.env` 模板，可能回退凭据强制注入，导致生产再次回退默认账号。

## G03 生产首次管理员仅支持环境变量注入，未实现随机口令生成

- 发现位置：[AdminBootstrap.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/AdminBootstrap.java)。
- 现象：T03 允许“首次随机口令或环境变量注入”，本轮实现环境变量注入并拒绝 `admin123`；未生成随机口令，也未提供口令展示渠道。
- 不属本卡范围的原因：随机口令需要一次性安全展示与轮换流程，涉及运维约定，任务卡未给出展示渠道。
- 建议归属：T03 的运维补强或新增候选（生产初始化彩排）。
- 影响面：合法管理员创建仍可用；缺少随机口令便利性，不影响安全边界。首次创建后须由环境负责人轮换并移除环境变量。

## G04 T10 与 T29 同批约束在 S1 无法满足

- 发现位置：[PeerPayController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java)、前端 T29 pre-lock/cancel-lock 接入（S4）。
- 现象：T10 已改为服务端签发 ownerToken，恢复预占必须回传 `X-Reservation-Token`；仅带 `Idempotency-Key` 的重试现在会被拒绝。
- 不属本卡范围的原因：T29 属 S4，涉及前端保存/回传凭据与重试交互；S1 未包含前端改造。
- 建议归属：T29；上线必须与 T10 同批，前端需持久化 ownerToken，不得继续只带幂等键恢复。
- 影响面：在 T29 完成前，现有前端若重试 pre-lock 会收到“已被其他付款人预占中”；不影响首次预占与取消（取消已要求 X-Reservation-Token）。

## G05 T11 keyset 分页与 T24 同批约束在 S1 无法满足

- 发现位置：[TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java)、T24 前端分页（S4）。
- 现象：T11 已补 `created_at + id` tiebreaker 与组合索引，但接口仍是 page/pageSize offset 分页，未改为游标 keyset。
- 不属本卡范围的原因：T24 属 S4，keyset 会改变前后端契约，本轮不得提前修改前端或接口形状。
- 建议归属：T24；实施时后端新增 `created_at + id` 游标参数，前端同步传 cursor，并保留旧分页兼容期。
- 影响面：同秒订单翻页重复/漏行已消除；深页 offset 性能仍随数据量增长，十万级订单需 T24 解决。

## G06 T07 重复支付已留痕，退款执行归属 T33

- 发现位置：[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java) 的 `DUPLICATE_PAYMENT` 分支。
- 现象：已支付订单收到不同流水号时已落 `DUPLICATE_PAYMENT` 事件并返回已支付结果，但未自动退款、未生成退款单、未通知渠道。
- 不属本卡范围的原因：T07 只要求留痕并进入补偿队列；自动/人工退款执行属 T33，涉及渠道退款 API 与对账。
- 建议归属：T33；需提供按 `DUPLICATE_PAYMENT` 查询待退款队列、幂等退款单与对账可见视图。
- 影响面：资金不会静默丢失，但第二笔付款在 T33 前需人工按事件流水退款。

## S0 环境核对待办（满足阶段退出条件，由环境负责人执行）

- 生产 `.env` 必须重新生成或补写：`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`、`DB_USER`、`DB_PASS`，并确认应用账号非 `root`。
- 首次管理员通过 `BOOTSTRAP_ADMIN_USERNAME`/`BOOTSTRAP_ADMIN_PASSWORD` 注入，口令不得为 `admin123`；创建成功后轮换口令并移除环境变量。
- 存量生产库若曾执行旧 `schema.sql`，需人工核查并清理/禁用 `superadmin`、`seller01`、`seller02` 等演示账号及虚构支付账户；不得用自动种子重跑覆盖真实数据。
- 发布流程只能导入 `db/schema.sql`，禁止导入 `db/demo-seed.sql`。

## G07 Q13 发货权限口径修正（用户 2026-11-06 澄清）

- 发现位置：[OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java) `deliverOrder`；[Sprint1SecurityIntegrationTest.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/test/java/com/shop/b2b2c/Sprint1SecurityIntegrationTest.java) 的 T13 用例。
- 现象：T13 首次实现把“实物订单发货”与推单、虚拟履约一起收紧为店长专属，员工发货被 403。
- 口径修正（用户 2026-11-06 明确）：**实物订单发货 MERCHANT_ADMIN 与 MERCHANT_STAFF 均可**；推单/代客下单/改价、资金操作、身份证/银行卡原文、卡密明文、虚拟履约仍仅店长/超管。员工发货仍须通过本店归属、`PAID` 状态、`goodsType=1` 与运单字段校验；跨店发货必须拒绝且订单状态/运单不变。
- 处理：已单独提交修正，删除 `deliverOrder` 的角色裁剪，保留租户与状态校验；测试改为“员工发货成功 + 跨店发货 403 且无副作用”。后续任何权限整理不得再把员工发货禁掉。
- 虚拟订单交付判断（不构成权限扩大）：现有 `deliverOrder` 只接受 `goodsType=1`，员工不能通过发货接口交付虚拟卡密；卡密自动交付发生在支付事件内，不向调用方返回明文；`fulfillVirtualOrder` 仅接受 `goodsType=3` 且只回写 `serviceVoucher`，不返回卡密；商家订单详情对 `MERCHANT_STAFF` 已把 `serviceVoucher` 与 `cardDetails` 替换为“员工账号无权查阅明文”。因此员工履约/发货不会被要求返回明文卡密，与 Q21“员工不看明文”不冲突。T16 实现卡密查看/导出接口时必须继续只允许店长，不得因为发货放开而扩大。
- 影响面：员工可独立处理本店实物订单发货；推单与资金链路权限不变；无数据库变更。

## S1 环境核对待办（由环境负责人执行）

- 存量 MySQL 升级前先备份（T21 未在 S1 实现）；将 `db/migrations/V1__s1_order_lifecycle.sql` 纳入发布包，确认 `app.schema-migration.enabled=true`。
- 在独立 MySQL 连续执行两次 V1 迁移，确认第二次不报错且 `schema_migration` 只记录一次；再比对 `SHOW INDEX` 与 `information_schema.columns`。
- 空库首次部署必须先导入 `db/schema.sql`，再启动应用；V1 面向存量库，不替代新库建表。
- H2 集成测试通过不代表 MySQL 迁移语法通过；迁移报告须注明使用的数据库版本与执行次数。

