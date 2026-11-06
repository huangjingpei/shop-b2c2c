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
- 关闭记录：T28 已与 T01 同批完成（提交 b4e1993），productDetail.mjs 增加 isCurrent 请求序号守卫，旧响应不覆盖新商品且不复位新请求 loading。

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
- 关闭情况：T29 已随 S4 实现（提交 0bf223e）。前端按分享 token 在 sessionStorage 保存 ownerToken，Idempotency-Key 尝试键与 ownerToken 分离；刷新恢复回传 `X-Reservation-Token`，离开/超时/切换 token 调 cancel-lock。上线顺序：后端 T10（8874b6a）先发、前端 T29 后发，禁止前端先发。
- 残留：若 pre-lock 响应在网络上丢失，客户端拿不到 ownerToken，重试仍会被判为“已被其他付款人预占中”；当前不做 attemptKey 授权兜底，需等待预占到期后重新发起。真实支付通道仍未开放。

## G05 T11 keyset 分页与 T24 同批约束在 S1 无法满足

- 发现位置：[TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java)、T24 前端分页（S4）。
- 现象：T11 已补 `created_at + id` tiebreaker 与组合索引，但接口仍是 page/pageSize offset 分页，未改为游标 keyset。
- 不属本卡范围的原因：T24 属 S4，keyset 会改变前后端契约，本轮不得提前修改前端或接口形状。
- 建议归属：T24；实施时后端新增 `created_at + id` 游标参数，前端同步传 cursor。按用户 2026-11-06 开发期无兼容负担口径，T24 未保留旧 page/pageSize 兼容期。
- 关闭情况：T24 已随 S4 实现（提交 0710544），商家/平台订单接口改 `cursor + pageSize + status`，返回 `page.total/hasMore/nextCursor`，非法参数 400；前端同批改为游标追加。未运行测试，待人工验证。
- 影响面：同秒订单翻页重复/漏行已消除；深页 offset 性能问题随 T24 移除。

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

## G08 T14 指纹补算边界（2026-11-06 基线清理后已删除补算）

- 发现位置：[CardAssetService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java)、[CardAssetMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/CardAssetMapper.java)、[V2__s2_card_fingerprint_state.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/migrations/V2__s2_card_fingerprint_state.sql)、[ProductVirtualCard.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/ProductVirtualCard.java)。
- 历史事实（已作废）：曾按“存量兼容”实现 V2 明文 `SHA2(TRIM(card_content),256)` 回填与导入时补写指纹分支；该实现现已在开发期基线清理中删除。
- 用户口径（2026-11-06）：开发期无真实运营数据，历史数据与存量兼容一律不作为待办或验收项。
- 基线清理（提交 `de36118`）：
  1. V1 删除存量订单截止时间回填语句，只保留幂等结构增量；
  2. V2 只保留 `fingerprint_unavailable` 的幂等列补齐，删除明文回填、加密行跳过与去重选行段；
  3. `CardAssetService` 无指纹分支改为纯内存解密比对，不再回写指纹；解密失败或缺少明文仍标记 `fingerprint_unavailable=1` 并计数 WARN，不中断导入；
  4. 删除随之无用的 `fillFingerprint` 与私有 `fingerprint` 方法。
- 现状边界：所有新导入卡密在入库前计算指纹；无指纹行只作防御性内存比对。`card_fingerprint` 保持可空 + 同店铺唯一，`fingerprint_unavailable` 仅表示无法比对，不改变卡密状态、库存或履约。
- 未验证：未运行 mvn/编译/测试，状态为“已实现·待人工验证”。

## G09 T15 密钥项取消：按单密钥假设继续，历史是否轮换过仍待确认

- 2026-11-06 用户口径更新（覆盖此前的“阻塞·待决策”实现要求）：
  1. 项目处于开发阶段，无真实运营数据，**历史数据一律不管**；不再为存量密钥分布做任何补算、兼容或验收。
  2. 卡密密钥按“当前只有一把、无轮换”处理；T15 删除换钥/缺省回退/历史补算项，只做卡密查看限量与导出审计（已并入 T16 同批实现）。
  3. T14 中“解密失败即标记跳过并计数、不中断导入”的兜底保留，不删除。
  4. 用户尚未确认历史上是否轮换过密钥；本项保留为待确认，但不阻塞当前开发与验收。
- 维持现状的代码事实：`CardCipherService` 仍存在硬编码种子回退、密文无 keyId、兼容构造器可用；本轮**不修改**密钥行为。若未来接入真实生产，须先确认生产密钥来源与保管，再评估是否收紧。
- 影响面：当前安全边界仍依赖源码内默认种子；生产密钥治理未完成，属于上线前必须人工确认的事项，不是“已修复”。
- 建议归属：上线前安全评审；不在 T15/T16/T17/T18 范围。

## G10 预占截止时间口径冲突：已按“严格从下单起算 15 分钟”统一

- 发现位置：[2026-11-06-backend-logic-review.md](2026-11-06-backend-logic-review.md) P2-4（原文“统一订单生命周期：预占不得超过 `createdAt+15`，或明确订单有效期随预占延长”，两种口径并存）；[2026-11-06-decision-questionnaire.md](2026-11-06-decision-questionnaire.md) Q15“所有订单 15 分钟截止”、Q17“默认 15 分钟”；[2026-11-06-remediation-plan.md](2026-11-06-remediation-plan.md) T08/T10。
- 现象：`preLockInventory` 原实现以“当前时刻 + 15 分钟”写入 `lockExpireTime`，第 14 分钟预占可占用库存到第 29 分钟，与详情页按 `createdAt + 15` 展示的剩余时间不一致；P2-4 把“延长订单有效期”列为可选方案。
- 用户确认（2026-11-06）：**严格从下单起算 15 分钟**；预占成功不得重置或延长 `lockExpireTime`，库存占用最长不超过 15 分钟；超时即释放预占，超时付款落 `LATE_PAYMENT`。
- 处理：提交 `4a4db61` 修改 `PeerPayServiceImpl.preLockInventory`，预占到期/支付截止统一取 `createdAt + 15min`，`lockSeconds` 按剩余时间返回；补集成用例覆盖“预占不延长”“到期释放”“超期付款 LATE_PAYMENT”。文档已在 Q15/Q17、T08/T10 与决策问卷 §0.2 覆盖原推荐口径。
- 未验证：未运行 mvn/编译/测试，状态为“已实现·待人工验证”。
- 残留：原 `RESERVED_LOCK + lock_expire_time NULL` 的兜底处理与取消预占清空截止已随基线清理提交 `de3d6bd` 移除；提前扫描不再有 `created_at` NULL 回退分支。取消预占现保留原截止时间，意向单继续按 `createdAt+15` 关闭。

## G11 开发期基线清理：已删除的兼容补丁与剩余事项

- 授权（用户 2026-11-06）：开发阶段不存在兼容性约束，已提交代码也允许改；清理范围限定为“为兼容旧库/旧数据而写的补丁”，不重构真实业务逻辑。
- 结构（提交 `6416a87`，外键名统一无引号见 `8029b71`）：`schema.sql` 一次写全主键、唯一键、非空、外键、默认值、检查与索引；新增 37 个外键（外键约束共 39 个，含原有 `card_import_item` 2 个；覆盖订单明细、预占、卡密、审计、导入批次、作废、账单、投诉等）；预占与支付事件关联 ID 统一 `BIGINT UNSIGNED`；`trade_order.payment_channel` 收紧为 `NOT NULL DEFAULT 'WECHAT'`。`SchemaMySqlTest` 同步表数量、到期索引列与作废夹具。
- 卡密（提交 `de36118`）：V1 删除存量订单截止回填；V2 仅保留 `fingerprint_unavailable` 幂等列补齐；`CardAssetService` 无指纹行改纯内存解密比对，不回写指纹，解密失败跳过计数保留；删除 `fillFingerprint` 与冗余私有 `fingerprint`。
- 订单截止（提交 `de3d6bd`）：`findExpiredUnpaidOrderIds` 删除 `lock_expire_time IS NULL AND created_at <= cutoff` 兼容分支；`cancelPreLock` 不再置空 `lockExpireTime`；`expireIfNecessary` 对缺失截止回退 `createdAt+15`，不把 NULL 当作永不到期。
- 已从清单/缺口划掉：G08 的历史指纹回填、G10 的 NULL 预占兜底、T14 的 V2 明文回填与补写分支均不再作为待办或验收项；原“存量 NULL 回填见 T12”的状态描述同步更新。
- 保留（不属于兼容补丁）：`CardCipherService` 的明文透明返回与默认种子属密钥行为，按用户指令不动，仍在 G09 待上线前确认；`fingerprint_unavailable` 列保留，用于解密失败防御性标记。
- 未验证：未运行 mvn/编译/测试/构建/连库；MySQL 外键需用户在真实库执行 `schema.sql` 验证。状态“已实现·待人工验证”。

## G13 T21 备份/恢复路径仅在脚本与夹具层验证

- 已实现（`6445357`）：两云 `04-deploy.sh` 迁移前强制 `mysqldump` + `gzip -t`，失败不迁移；迁移/启动失败自动 `gunzip | mysql` 恢复并打印手工命令；凭据走临时 600 `--defaults-extra-file`。
- 未验证：未连接真实 MySQL，未执行 `schema.sql` 的 37 个外键、未演练“迁移中途失败→恢复”与大数据量 dump 耗时/磁盘占用。H2 通过不能替代 MySQL。
- 上线前必须由环境负责人在预发库演练并记录：备份文件大小、`gzip -t` 结果、恢复耗时、恢复后表数/外键数核对；并确认 `BACKUP_DIR` 有足够空间与保留策略。
- 残留口径：脚本不做自动回滚代码，数据库恢复与 `05-rollback.sh` 是两条独立路径；应用启动失败时 EXIT 回滚会先恢复 DB 再重启旧 jar。

## G12 T19 之后：仓库内 env.sh 仍含真实 Redis 口令

- 发现位置：[deploy/aliyun/env.sh](file:///e:/shop-b2b2c/deploy/aliyun/env.sh)、[deploy/tencent/env.sh](file:///e:/shop-b2b2c/deploy/tencent/env.sh)。
- 现象：两云 `env.sh` 为实际环境配置且已被 Git 跟踪，其中 `REDIS_PASS` 为真实口令；脚本默认值已在 `ff8bc1a` 移除，但这两个文件是显式配置，不属于“回退默认值”。
- 不属本卡范围的原因：T19 只处理模板与脚本；直接改用户现有环境配置可能破坏其部署参数，且真实口令轮换需环境负责人执行。
- 建议归属：上线前安全评审；由环境负责人轮换 Redis 口令并确认 `env.sh` 是否应移出版本库。
- 影响面：仓库读者可见历史真实口令；不改变脚本“缺失即终止”的行为。

## S1 环境核对待办（由环境负责人执行）

- 存量 MySQL 升级前先备份（T21 未在 S1 实现）；将 `db/migrations/V1__s1_order_lifecycle.sql` 纳入发布包，确认 `app.schema-migration.enabled=true`。
- 在独立 MySQL 连续执行两次 V1 迁移，确认第二次不报错且 `schema_migration` 只记录一次；再比对 `SHOW INDEX` 与 `information_schema.columns`。
- 空库首次部署必须先导入 `db/schema.sql`，再启动应用；V1 面向存量库，不替代新库建表。
- H2 集成测试通过不代表 MySQL 迁移语法通过；迁移报告须注明使用的数据库版本与执行次数。


## G14 T34/T39 费率来源切换（已随 T39 落地）

- 发现位置：shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ServiceFeeSnapshotService.java。
- 现象：T34 曾直接读取 `merchant_store.commission_rate` 并在订单固化费率，`fee_policy_id` 留空；费率政策版本、降费即时/调高次日 00:00/自然日切分未落地。
- 处理结果：已随 T39 第三段（`81840c3`）落地。快照唯一来源改为 `MerchantFeePolicyService.getEffectivePolicy`，订单写 `fee_policy_id/fee_rate_bps/fee_rate_type`；`merchant_store.commission_rate` 降为展示镜像，仅由政策服务同步；已生成订单的快照字段不回溯。
- 残留：存量店铺无政策时按旧佣金费率懒初始化一条 `MIGRATION` 政策（开发期过渡），生产迁移策略仍待用户确认；真实 MySQL 生效区间唯一性与并发创建仍须人工验证。

## G15 T33 全额退款后已交付虚拟卡密未作废（待用户确认，未实现）

- 发现位置：T33 退款成功流程与订单卡密交付；用户在本轮明确“先登记为待确认，不要擅自实现作废逻辑”。
- 现象：全额退款成功后订单置 REFUNDED 并回退库存，但已分配给买家的虚拟卡密未作废，仍可能被继续使用。
- 不属本卡范围的原因：作废/换发涉及已交付资产的业务规则（是否允许、是否可追回、是否影响买家权益），用户答复未到。
- 建议归属：T33 后续补卡或新增候选；实现前必须先确认业务口径。
- 影响面：当前不得把“退款后卡密仍可用”写成既定规则；文档与代码均保持该缺口开放。

## G16 T39 入驻/账户审核角色未确认（待用户确认）

- 发现位置：[MerchantOnboardingController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantOnboardingController.java)、[MerchantSettlementAccountController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantSettlementAccountController.java)、[MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java)。
- 现象：入驻审核、收款账户核验目前只沿用既有 `/api/v1/admin/**` 超管鉴权，未新增独立“审核员”角色码，也未实现多级复核。
- 未确认原因：用户尚未答复谁可审核（仅超管 / 独立审核员 / 多级复核），本轮不凭空定义角色。
- 建议归属：T13 权限矩阵或 T39 后续增强；确认后补角色码、接口鉴权与越权/合法两条用例。
- 影响面：当前任一超管均可审核；文档与代码不得表述为“审核角色已分离”或“已实现多级复核”。

## G17 T39 默认费率具体数值未确认（待用户确认）

- 发现位置：[MerchantOnboardingService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantOnboardingService.java) 的 `DEFAULT_COMMISSION_RATE`、[MerchantFeePolicyService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantFeePolicyService.java) 的 `DEFAULT_RATE_BPS`。
- 现象：新店初始费率沿用既有默认 3.00%（300 基点），用户尚未答复该数值是否为最终业务值。
- 未确认原因：默认费率属业务定价，不得由开发臆定；本轮只复用现有默认值并集中到政策服务。
- 建议归属：T39 后续；确认后只改常量与迁移默认值并补用例，已生成订单快照不回溯。
- 影响面：文档不得把 3% 写成“已确定默认费率”。另：开店初始费率是否允许 0% 推广尚未答复，当前按 1%~10% 拒绝，0% 只能在开店后通过后台调价设置。

## G18 T39 价税分离税率未确认（待用户确认）

- 发现位置：`shop.settlement.fee-tax-rate`（默认 `0.06`）与 [ServiceFeeSnapshotService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ServiceFeeSnapshotService.java)。
- 现象：服务费含税金额按 6% 税率做内部价税分离并写入订单快照，用户尚未答复技术服务费适用税率与含税口径。
- 未确认原因：税率涉及财税口径与开票，不得由开发默认成业务结论；本轮沿用既有配置值。
- 建议归属：T39 后续或财税专项；确认后调整配置默认值与快照用例，历史订单税额不重算。
- 影响面：当前税额仅作内部记账与对账数据，不代表已开票或税务合规；不得把 6% 表述为最终税率。
