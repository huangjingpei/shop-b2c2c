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

## G15 T33 全额退款后已交付虚拟卡密未作废（用户已确认默认口径，已实现）

- 发现位置：T33 退款成功流程、订单卡密交付与 [ProductVirtualCardMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/ProductVirtualCardMapper.java)。
- 现象：此前全额退款成功后订单置 REFUNDED 并回退库存，但已分配给买家的虚拟卡密未作废，仍可能被继续使用。
- 处理结果：用户已给出默认口径“卡密全额退款成功即作废该卡密”。已在 T40 第三提交（`eb84b4b`）落地：虚拟卡密订单全额退款成功时将订单已售卡密 status 1→2，条件 UPDATE 幂等；卡密密文保留用于倒查，不删除。
- 库存口径：卡密可能已交付，全额退款时**不恢复 SKU 库存**，避免“库存数大于可用卡密数”导致后续超卖；实物/服务订单仍按原逻辑回退库存。
- 残留：部分退款不作废卡密；真实 MySQL 并发退款与库存一致性、作废后的买家侧展示/客服口径仍须用户手动验证与确认。

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
- 影响面：文档不得把 3% 写成“已确定默认费率”。开店 0% 推广费率已按用户默认口径实现（仅 PROMOTION 且必须填写原因，见 `eb84b4b`），不再以“未确认”为由拒绝。

## G18 T39 价税分离税率未确认（待用户确认）

- 发现位置：`shop.settlement.fee-tax-rate`（默认 `0.06`）与 [ServiceFeeSnapshotService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ServiceFeeSnapshotService.java)。
- 现象：服务费含税金额按 6% 税率做内部价税分离并写入订单快照，用户尚未答复技术服务费适用税率与含税口径。
- 未确认原因：税率涉及财税口径与开票，不得由开发默认成业务结论；本轮沿用既有配置值。
- 建议归属：T39 后续或财税专项；确认后调整配置默认值与快照用例，历史订单税额不重算。
- 影响面：当前税额仅作内部记账与对账数据，不代表已开票或税务合规；不得把 6% 表述为最终税率。

## G19 T40 平台代收/分账通道未联调（待资质与通道联调）

- 发现位置：[ProfitSharingChannelAdapter.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/ProfitSharingChannelAdapter.java)、[WeChatProfitSharingAdapter.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/WeChatProfitSharingAdapter.java)、[AlipayProfitSharingAdapter.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/AlipayProfitSharingAdapter.java)、[PlatformCollectionService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PlatformCollectionService.java)。
- 现象：T40 只落地数据模型、本地分账账本、结算台账与通道适配契约；微信/支付宝适配器均为占位，`enabled()` 固定 false，任何分账/查分/退分调用明确拒绝。
- 未实现原因：平台代收与分账依赖支付机构分账资质、协议与沙箱联调；用户明确本轮只做骨架，不得调用真实代收/分账接口，也不得形成资金池。
- 建议归属：T40 资质后阶段；取得资质后按 Q09-A 灰度切换，补真实渠道调用、验签/防重放/乱序容忍、冻结/退分/退款联动与 T+1 四方对账。
- 影响面：`shop.settlement.platform-collect-enabled` 默认 false；T40 状态只能表述为“骨架已实现·待资质后联调”，不得标为可用或已完成；阶段一 `MERCHANT_DIRECT` 流程不受影响。

## G20 财务基线缺口总表（2026-11-06 自检）

- 发现位置：[docs/2026-11-06-financial-baseline.md](2026-11-06-financial-baseline.md) 第 3–4 节；覆盖 T32/T33/T34/T35/T39/T40。
- 现象：24 条硬性基线中，本轮已整改 A/B/C/D/E 五项；其余仍为“部分合规/缺失”，不得表述为已完成。
- 建议归属：按下列 G21–G29 分批；任何新增资金功能必须先过基线文档判定。

## G21 T+1 定时对账与长短款分类未实现（已登记未做）

- 发现位置：[ReconciliationService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java)。
- 现象：有手工 `reconcile` 入口与差异队列，但无 T+1 调度、无长款/短款分类、无处理时限与超时升级。
- 建议归属：后续对账专项；不得用“差异已入队”替代 T+1 闭环。

## G22 支付事件/充值/对账差异状态迁移未统一 CAS（已登记未做）

- 发现位置：`PaymentEventService`、`MerchantAccountService.confirmRecharge`、`ReconciliationService.resolve`。
- 现象：部分状态推进使用锁行后 `updateById`，未统一“条件更新 + 状态校验 + 审计”；并发下依赖行锁而非 CAS。
- 建议归属：状态机专项；改造时必须保留现有幂等键与审计字段。

## G23 费率政策缺“同店同生效时点”唯一键（已登记未做）

- 发现位置：[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql) `merchant_fee_policy`。
- 现象：已有生效区间逻辑，但数据库层没有 `(merchant_id, effective_from)` 唯一索引，并发调价仍可能写入同一生效时点。
- 建议归属：费率专项；开发期可直接加唯一索引，需先确认存量重算口径。

## G24 复式记账与余额重算/漂移告警未实现（未实现·仅登记）

- 发现位置：[MerchantAccountLedgerMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/MerchantAccountLedgerMapper.java)、`merchant_account`。
- 现象：当前为单边 DEBIT/CREDIT + `balance_after`，可由账本重算但无重算入口、无快照/漂移告警；不等同复式记账。
- 建议归属：账务内核专项；需先确认科目表与借贷规则，不能直接把单边账本称为复式记账。

## G25 币种绑定未覆盖订单/账本/退款（未实现·仅登记）

- 发现位置：`trade_order`、`merchant_account_ledger`、`trade_refund`。
- 现象：仅 `payment_event` 有币种；其他资金表按单币种 CNY 假设，无列级约束。
- 建议归属：多币种专项；单币种期间须在接口/文档明确“仅 CNY”。

## G26 发票域不存在（未实现·仅登记）

- 现象：技术服务费开票状态、发票号、红冲发票均无表、无接口。
- 建议归属：税务口径确认后立项；本轮只登记，不写代码。

## G27 二清持牌与分账资质（资质依赖，未实现）

- 发现位置：[ProfitSharingChannelAdapter.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/ProfitSharingChannelAdapter.java)。
- 现象：T40 通道占位且默认关闭；平台代收再分账必须由持牌机构完成，平台不得形成资金池。
- 建议归属：资质与通道联调；取得资质前不得开启 `shop.settlement.platform-collect-enabled`。

## G28 资金异常告警阈值与备份恢复演练未实现（未实现·仅登记）

- 现象：扣费失败率、退款率、对账差异、余额漂移均无阈值与告警通道；备份脚本存在但无恢复演练与账实核对记录。
- 建议归属：运维专项；演练记录必须由环境负责人执行并留档。

## G29 回调持久化审计表缺失（本轮已知限制）

- 发现位置：[CallbackReplayGuard.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/CallbackReplayGuard.java)。
- 现象：超窗回调已拒绝并写 WARN 结构化审计日志，但未持久化到审计表，无法在后台检索。
- 建议归属：审计专项；补表前不得把日志留存表述为完整审计。

## G30 开发环境单管理员与职责分离的冲突（已整改·待人工验证）

- 现象：财务基线 C 要求调价人/复核人、结算制单人/确认人、充值申请人/确认人必须不同；单超管环境下确认类操作会被 403 拒绝。
- 整改：新增 `shop.finance.dual-approval-required`（环境变量 `FINANCE_DUAL_APPROVAL_REQUIRED`）。开发/试点默认 false，允许同人操作但必须落 `approvalMode=SAME_OPERATOR` 并写 WARN；生产部署脚本生成 `.env` 时固定 true，同人一律 403。开店初始费率走同一开关与同一复核入口 `rateConfirmedBy`。
- 约束：**单管理员模式仅在开发/试点可用，生产禁止**；生产必须至少配置两个具备财务权限的后台账号，否则确认类操作无法完成。
- 验证：代理未运行编译/测试，状态为已实现·待人工验证。

## G31 生产双人复核的后台操作入口缺少复核人选择（已实现·待人工验证，含复核人资质校验）

- 发现位置：`shop-admin-vue/src/views/admin/Merchants.vue` 佣金弹窗与开店弹窗、[MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java) 的 `updateCommission`/`createMerchant`、[MerchantOnboardingController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantOnboardingController.java) 的 `rateConfirmedBy`、管理端结算确认与充值确认页面。
- 原现象：后台佣金弹窗只传 `rate` 与内联默认原因，不带复核人；生产开关打开时服务端按“未传复核人=操作人”判定为同人并 403。入驻审核、结算确认、充值确认也没有前端复核人入口。
- 实现：
  - 后端 `FinanceApprovalGuard` 严格模式缺复核人返回业务码 428（HTTP 428 Precondition Required），同人仍 403；宽松模式未传按操作人处理并落 `SAME_OPERATOR`，不再由服务层提前补默认值，避免“缺参”和“同人”混为一谈。
  - 调价、开店初始费率、结算批次确认、充值确认的请求均支持显式复核人；服务层统一经 `resolveReviewer` 落实际复核人，`prepared_by` / `requested_by` 仍独立记录申请人。
  - 新增 `GET /api/v1/admin/settlement/reviewers`，仅 SUPERADMIN 可读，返回开关状态与“启用且非当前账号”的 SUPERADMIN 候选；新增管理端“财务复核与结算”页（充值确认、结算批次确认），商户管理页调价/开店弹窗补复核人下拉。
  - 前端 `shop-admin-vue/src/utils/financeReview.mjs` 统一校验：严格模式必填、禁止选择自己、拒绝未知账号；无第二账号时明确提示“需至少分配两个财务账号”，不抛通用错误。前端只做提示与参数传递，后端权限模型不变。
  - 复核人资质校验（已实现）：`FinanceApprovalGuard.resolveReviewer` 对“操作人以外的复核人”统一查询 `sys_user`，必须为启用状态的 SUPERADMIN；禁用超管、非 SUPERADMIN、不存在的账号一律 403，四个财务入口自动共用，不允许各入口各写一遍，也不允许只靠前端候选列表放行。
- 验证：代理未运行 mvn/编译/测试/构建，也未连库；新增 shared 用例 `shared/financeReview.test.mjs`、后端 `FinanceApprovalGuardTest`（启用超管放行 / 禁用超管 / 非超管 / 不存在账号），并同步 `MerchantFeePolicyServiceTest`/`MerchantSettlementServiceTest`/`MerchantOnboardingServiceTest`，状态为**已实现·待人工验证**。

## G32 后端 19 条测试失败的定性修复（已实现·待人工验证）

- 数据来源：用户 2026-11-06 的 `mvn -f shop-b2b2c-backend/pom.xml test` 结果，696 用例中 11 failures + 7 errors + 1 skipped 共 19 条非绿；报告位于 `shop-b2b2c-backend/target/surefire-reports`。
- 真实缺陷（已修复）：
  1. `AuthSessionService.isManagementRole()` 直接 `Set.of(...).contains(role)`，会话缺少角色时 `contains(null)` 抛 NPE 并映射为 500；改为 null 一律视为无权限（403）。
  2. `PaymentEventServiceImpl` 并发通知触发唯一键冲突时，把所有 `DuplicateKeyException` 都判成“渠道流水号已被其他订单使用”；改为冲突后回查，同订单按幂等重放继续，只有流水号确属其他订单才报归属冲突。**该修法把冲突 catch 留在主事务内，已在 G33 取代重做。**
  3. `OrderController` 重复发货/非待发货状态使用无业务码的 `BusinessException`，默认映射 500；改为显式 400，与 T31 契约一致。
- 过期用例/夹具（已更新，不放宽业务断言）：
  - `ManagementSecurityTest`、`MerchantStoreUpdateTest`：G31 起调价操作人取自服务端会话、Q13-A 起资料更新走 `AuthSessionService` 角色校验，纯单元测试补 `SecurityUtils` 静态桩；新增“角色缺失仍 403”回归。
  - `SchemaInitializationTest`：初始化基线允许幂等 `DROP TRIGGER IF EXISTS`（仍禁止删表/删库/TRUNCATE/DELETE/USE）；T19 后管理员账号为“仅空默认”的环境变量占位符，断言改为校验空默认形态。
  - `Sprint1SecurityIntegrationTest`：T37 后测试侧 ObjectMapper 会把包装 Long 序列化为字符串，库存 HTTP 用例改为显式 JSON 整数；限流用例显式模拟不可信直连 IP（MockMvc 默认 127.0.0.1 属可信回环，导致 XFF 被采纳）；卡密指纹用例按 G08 已删除的回填语义改为“不写回、仅内存比对去重”；金额不符消息按关键语义断言。
  - 店铺交错写用例补管理角色桩，修复 Q13-A 后资料更新在读取快照前被拒导致屏障超时的问题。
- 环境限制：`SchemaMySqlTest` 在 Windows 原生跳过，不计通过；真实 MySQL 事务/锁与 H2 差异仍须用户按手动入口复跑确认。
- 验证：代理未运行 mvn/编译/测试/构建，上述结论为静态核对的**已实现·待人工验证**，以用户复跑结果为准。

## G33 错误码口径：先授权后存在性（已实现·待人工验证）

- 数据来源：用户复跑 `mvn -f shop-b2b2c-backend/pom.xml test`，706 用例中
  `MerchantStoreUpdateTest.everyExistingStoreOperationRejectsMissingRecordWithoutAnyUpdate:41` 期望 404 实际 403。
- 定性：403 来自 [MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java) 的
  `updateMerchantProfile` 先调用 `AuthSessionService.requireManagementRole()`；用例未桩 `SecurityUtils.getCurrentRoleCode`，
  角色为 null 被授权判定先拒绝，并非“查不到记录被当成无权”。
- 统一口径：**先身份/授权，后资源存在性**。无权限/未绑定 → 403（不泄露资源是否存在）；有权限但资源不存在 → 404；
  参数非法 → 400；内部不变量失败（如创建后回查不到）→ 500。该口径适用于带资源 ID 的后台读写入口。
- 处理：`MerchantStoreUpdateTest` 补 `MERCHANT_ADMIN` 角色桩后验证“有权但记录缺失”返回 404；无角色 403 仍由
  `updateMerchantProfile` 自身用例断言，不把两种拒绝混同。
- 未处理偏差（按“不顺手改别的”登记，不与本卡混改）：`MerchantController.getMerchantProfile` 的店铺缺失仍为无业务码 500；
  `AuthController` 口令错误仍为 500；`OrderController` 部分资源缺失仍为 500。后续按本口径统一。
- 验证：代理未运行 mvn/编译/测试/构建，也未连库；状态为**已实现·待人工验证**，复跑入口
  `mvn -f shop-b2b2c-backend/pom.xml test`。

## G34 并发支付事件占位、rollback-only 与 PROCESSING 残留补偿（已实现·待人工验证）

- 数据来源：用户复跑后 `Sprint1SecurityIntegrationTest.concurrentNotificationsFulfillExactlyOnce:1985 → race:3880`
  抛 `UnexpectedRollbackException: Transaction rolled back because it has been marked as rollback-only`。
- 定性（`fcd9710` 引入的真实缺陷）：`PaymentEventRecorder.recordInitialEvent` 默认 REQUIRED，参与主事务并在主事务内
  catch 唯一键冲突；Spring 对参与事务的内层 `@Transactional` 方法抛异常会把外层标为 rollback-only，外层
  `TransactionTemplate` 提交即抛 `UnexpectedRollbackException`。
- 处理：
  - `PaymentEventServiceImpl.claimEvent` 在业务事务之外做事务前预检，并以独立 `TransactionTemplate` 插入事件占位；
    冲突只在独立事务完成后回查：同单 → 幂等重放，跨单 → 400 归属冲突；主事务内不再捕获唯一键异常。
  - `recordInitialEvent` 已删除；`recordEventStatus` 仍随主事务提交 SUCCESS，失败审计仍走 `recordFailureAudit` 独立事务。
  - `DUPLICATE_PAYMENT` 判定改为“本次新建事件且订单实际渠道+流水号不同”，避免占位线程被并发重放线程抢先履约时误判第二笔资金。
  - PROCESSING 残留窗口（本次补齐，不再留到后续）：新增 `PaymentEventReconcileService`，在
    `ReservationExpiryJob.reconcilePaymentEvents()` 的**同一轮**内先处理“超过阈值仍 PROCESSING”的事件，再执行既有
    `reconcileSuccessfulWithoutFulfillment`，不新增第二个调度任务，两条查询均排除已 PAID/COMPLETED/SHIPPED 的订单，
    避免互相干扰或重复履约。陈旧 PROCESSING 先按事件渠道调用 `PaymentGatewayService.queryChannelPayment` 回查渠道事实；
    只有渠道 `SUCCESS` 且渠道流水号与本地事件一致时，才交给 `PaymentEventService.processPaymentEvent` 补履约，
    复用既有占位/订单行锁/CAS/卡密认领幂等路径，不允许第二次交付。渠道无法确认、流水号/金额不一致或补履约异常时，
    通过 `PaymentEventRecorder.recordFailureAudit` 写 `FULFILLMENT_FAILED`（保留“SUCCESS 不降级”保护）并以 ERROR 日志告警。
- 配置项：
  - `payment.reconcile.processing-threshold-minutes`（环境变量 `PAYMENT_RECONCILE_PROCESSING_THRESHOLD_MINUTES`，
    默认 **5 分钟**）：超过该阈值仍 PROCESSING 才进入渠道回查。
  - 扫描频率沿用 `inventory.expiry-delay-ms`（默认 **30000ms / 30 秒**）：补偿与预占到期、超时关单共用同一调度轮次。
- 语义保持：同单重放幂等成功、跨单流水号盗用仍拒绝、订单已支付后的第二笔资金仍落 `DUPLICATE_PAYMENT`、
  金额/币种/迟到款/履约失败仍落对应失败态且不把 SUCCESS 降级。
- 用例：`concurrentNotificationsFulfillExactlyOnce` 增断言“并发同单只落 1 条事件且最终 SUCCESS”，
  `race()` 任一线程抛 `UnexpectedRollbackException` 即失败；跨单冲突沿用既有用例。新增 4 条 G34 用例：
  `g34StaleProcessingEventFulfilledAfterChannelConfirmation`（陈旧 PROCESSING 经渠道确认后补履约且只发一次卡）、
  `g34StaleProcessingWithoutChannelConfirmationBecomesFailed`（渠道未确认落 `FULFILLMENT_FAILED` 且不交付）、
  `g34FreshProcessingEventBelowThresholdIsNotScanned`（未到阈值不扫描）、
  `g34ProcessingEventOnFulfilledOrderIsNotTouched`（主事务已正常履约的订单不被误处理）。
- 残余风险：
  - ①补偿依赖渠道查单可用性：单次查单失败（含渠道临时不可用）即落 `FULFILLMENT_FAILED` 并告警，不会自动重试，
    需要人工或对账按告警重新驱动；这是按“补不了即失败并告警”的口径刻意设计，不是静默丢弃。
  - ②**真实 MySQL REPEATABLE READ 下并发用例仍未验证**：主事务首条普通读会建立快照，若交付明细在行锁后被普通读读取，
    极端交错时可能读到旧明细；H2 为 READ COMMITTED，不代表生产行为。该条保留为“未验证”，不得记为已验证。
- 验证：代理未运行 mvn/编译/测试/构建，也未连库；上述结论为静态核对的**已实现·待人工验证**，复跑入口
  `mvn -f shop-b2b2c-backend/pom.xml test`；H2 通过不替代真实 MySQL 与渠道联调。
