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

## G21 T+1 定时对账与长短款分类（已返工·待人工验证，2026-11-06 第③卡）

- 发现位置：[ReconciliationService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java)、`ReconciliationBatchService`、`ReconciliationDailyJob`。
- 原现象：有手工 `reconcile` 入口与差异队列，但无 T+1 调度、无长款/短款分类、无处理时限与超时升级。
- 本卡已补：每日跑批与按业务日逐店记录、`(business_date, merchant_id)` 唯一键与状态机续跑、退款/服务费金额/退款冲正/平台代收对账、长短款/单边/精度/币种/跨天分类、差异审计与 `MANUAL` 升级、`PARTIAL` 标记流水未导入。
- 复核返工（原判断错误已修正）：退款切日改用不可变 `success_at`、`PARTIAL` 纳入转人工前置状态、退款单边账加流水门禁、每店每次跑批只做一轮自动重试（`last_retry_date` 用执行日）、重试异常落 `retry_failed`、完成回写改条件更新防接管覆盖、结算批次合计只在完整账期校验、时间统一 `Asia/Shanghai`。详见 [2026-11-06-t1-reconciliation-plan.md](2026-11-06-t1-reconciliation-plan.md) 第 10 节。
- 第二轮回核返工：结算批次合计改按开区间 `periodEnd = to`（原 `to.minusDays(1)` 把月结/手工按月入口全关掉）；退款三条单边路径（`REF|id|NO`、`REF|id|ST`、`REFFEE`）统一受流水门禁；按渠道求差集返回 `incompleteChannels`，多渠道部分导入不再静默 `SUCCESS` 而是 `PARTIAL`。详见同一文档第 11 节。
- 第三轮回核返工：本地渠道收集排除空串（避免永久 `PARTIAL`→`MANUAL`）；`success_at` 巡检改为按 `merchant_id` 且每店每轮一次。第三轮曾把缺失渠道差集限定为 `SUCCESS`/`DUPLICATE_PAYMENT`/`LATE_PAYMENT`，**该口径在第四轮被认定为错误判断并已纠正**。详见同一文档第 12 节。
- 第四轮回核返工：差集状态扩为七种（`SUCCESS`/`DUPLICATE_PAYMENT`/`LATE_PAYMENT` 与 `reject(...)` 写入的 `MERCHANT_MISMATCH`/`AMOUNT_MISMATCH`/`CURRENCY_MISMATCH`/`FULFILLMENT_FAILED`）；非成功事件不再静默——拒绝状态生成 `PAYMENT_EVENT_REJECTED` 差异，悬挂超过 5 分钟的 `PROCESSING`（与 G34 阈值一致）生成 `PAYMENT_EVENT_REVIEW` 差异，未悬挂 `PROCESSING` 计入跑批 `processingPending` 汇总，未知状态（如 `ORDER_STATUS_INVALID`）生成 `PAYMENT_EVENT_REVIEW`。详见同一文档第 13 节。
- 第五轮回核返工（纠正第四轮两条前提）：新增 `payment_event.funds_received TINYINT NULL`（**无默认值**），按 `1`/`0`/`NULL` 三值分别处理——`1` 且状态在七状态集合内才计入缺失渠道差集；`0` 不进差集但仍生成 `PAYMENT_EVENT_REJECTED`；`NULL` 不进差集但生成 `PAYMENT_EVENT_REVIEW`，禁止当作“无需流水”静默放过。`FULFILLMENT_FAILED` 一码多义由该列区分（G34 明确未支付请求 `0`，但新事件占位 `1` 被 `CASE` 保留，第七轮已把 `0` 限定为仅未标注行/历史或异常行，见下条；查单前置失败=NULL、回调后 reject=1）；`MANUAL_REVIEW` 明确列入支付事件状态清单且重试耗尽固定回写 `funds_received=NULL`。本项目无历史数据，该列不回填。详见同一文档 §13.4–§13.5。
- 第六轮回核返工（纠正第五轮 `REFUNDED` 口径）：`REFUNDED` 从 `DEFINITIVE_NOT_PAID_STATES` 移出，按 `funds_received=1` 处理并单列 `DEFINITIVE_REFUNDED_STATES`，状态仍落 `FULFILLMENT_FAILED`、`error_message` 记“渠道已收款后已退款”，T+1 文案改写且不得出现“未收到资金”；`markFulfillmentFailed` 的 SQL 改为 `CASE WHEN funds_received = 1 THEN 1 ELSE ... END`，禁止把已确认到账覆盖成 0/NULL。补 retry 自动关闭 4 类用例、REFUNDED 差集/文案用例、资金标记保留与反向写 0 集成用例，`markManualReview` 集成用例起点改为 `funds_received=1` 以证明清空逻辑生效。详见同一文档 §13.6。
- 第七轮回核返工（口径与文档对齐 + 差异类型可筛选）：`funds_received=1` 语义定死为“渠道侧存在该笔资金往来、应当有对应渠道流水”（不是“此刻仍持有资金”），新事件占位即 `1` 且 `CASE` 只增不减，因此 `DEFINITIVE_NOT_PAID_STATES → 0` 对新事件不可达，`0` 仅对 `funds_received IS NULL` 的未标注行/历史或异常行生效；`funds_received=1` 的失败事件按 G34 原因前缀拆出 `PAYMENT_EVENT_REFUNDED`（键 `EV|id|REFUNDED`）与 `PAYMENT_EVENT_CHANNEL_CONFLICT`（键 `EV|id|CHANNEL_CONFLICT`），不再与普通 `PAYMENT_EVENT_REJECTED` 共用类型/键，`retry` 对两类均以事件 `SUCCESS` 自动关闭；支付宝 `TRADE_CLOSED` 增加 `refund_fee`/`refund_amount` 证据判定，无证据时原因写“渠道已关闭，可能为全额退款，需核对退款流水”（不写“渠道明确未支付”）并按冲突类处理。逐渠道退款识别范围见 t1 方案 §13.7。
- 登记缺口（未做）：支付事件仍按 `payment_event.created_at`（回调受理时间）切日，不是渠道成功时间；跨日回调可能归属错误业务日，缓解手段是渠道流水侧按 `trade_time` 对账并在差异中标注 `TIME_CROSS_DAY`。补齐需新增 `paid_at`/渠道成功时间字段并回填历史数据。
- 登记缺口（未补数）：`success_at IS NULL` 的成功退款不进 T+1 窗口（旧数据或异常迁移）。跑批会统计数量并 `warn`，补数需按渠道流水 `trade_time`（无流水时按人工核对值）回填，方案见 t1 方案第 8 节，不得自动改写历史资金记录。
- 部署前提：DB 会话时区必须与 `Asia/Shanghai` 对齐（`serverTimezone` 或 `time_zone='+08:00'`），否则 `CURRENT_TIMESTAMP` 与 Java 截断时间漂移；验收用 `SELECT @@session.time_zone, NOW(), UTC_TIMESTAMP()` 校验。
- 仍缺：渠道对账单自动拉取（当前只能管理端导入）、对账差异处理时限与超时升级策略；未运行构建/测试、未连库，待人工验证。

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
  - PROCESSING 残留窗口（G34 后续修复，不再留到后续）：新增 `PaymentEventReconcileService`，在
    `ReservationExpiryJob.reconcilePaymentEvents()` 的**同一轮**内先处理“超过阈值仍 PROCESSING”的事件，再执行既有
    `reconcileSuccessfulWithoutFulfillment`，不新增第二个调度任务；两条查询均排除已 PAID/COMPLETED/SHIPPED 的订单，
    避免互相干扰或重复履约。
  - 陈旧 PROCESSING 的状态流转（下发前口径，避免把“查询异常”和“明确未支付”混为一谈）：
    | 渠道回查结果 | 判定 | 状态流转 |
    | --- | --- | --- |
    | SUCCESS 且流水号一致、金额/币种齐全 | 确认已支付 | 复用 `processPaymentEvent` 幂等履约 → `SUCCESS`；业务校验拒绝 → 对应失败态 |
    | `PENDING`（NOTPAY/USERPAYING/WAIT_BUYER_PAY）、`CLOSED`、`FAILED`（PAYERROR）、`NOT_EXIST`（微信 404/ORDER_NOT_EXIST、支付宝 ACQ.TRADE_NOT_EXIST） | 渠道明确未支付/查无此单 | 落 `FULFILLMENT_FAILED` + ERROR 告警，原因前缀“渠道明确未支付”。**新事件占位即 `1`，SQL `CASE` 保留 `1`**，实际为“`FULFILLMENT_FAILED` + `1` + 渠道明确未支付”冲突行，T+1 记 `PAYMENT_EVENT_CHANNEL_CONFLICT`；仅未标注行/历史或异常行才会真的落 `0`（T+1 记 `PAYMENT_EVENT_REJECTED`）；失败态不阻止后续真实成功通知重新履约。**支付宝 `TRADE_CLOSED` 属退款/关闭无法判定场景，见下两行，不写“渠道明确未支付”** |
    | `REFUNDED` | 渠道已收款后已退款 | 落 `FULFILLMENT_FAILED` + ERROR 告警，原因前缀“渠道已收款后已退款”，`funds_received=1`（存在资金往来，进入 T+1 预期流水差集）；T+1 记 `PAYMENT_EVENT_REFUNDED`（键 `EV\|id\|REFUNDED`），不得写“未收到资金”（第六轮修正，第七轮拆分类型，见 t1 方案 §13.6/§13.7） |
    | 支付宝 `TRADE_CLOSED` 且响应携带 `refund_fee`/`refund_amount > 0` | 支付宝全额退款 | 适配器映射为 `REFUNDED`，按上一行处理；字段是否稳定返回需真实联调确认，**若 `alipay.trade.query` 不返回 `refund_fee`/`refund_amount`，全额退款会统一落 `CHANNEL_CONFLICT`**，仍需人工核对退款流水 |
    | 支付宝 `TRADE_CLOSED` 且无退款金额证据 | 可能为全额退款，也可能为超时关闭（无法区分） | 适配器保持 `CLOSED`，G34 原因写“渠道已关闭，可能为全额退款，需核对退款流水”（**不得写“渠道明确未支付”**）；`funds_received` 只对原本已是 `1` 的行保留 `1`，原本 `NULL` 的未标注行会写 `0`（新事件在 claimEvent 已占位为 `1`，故实际多为保留 `1`）；T+1 记 `PAYMENT_EVENT_CHANNEL_CONFLICT`，文案提示“可能为全额退款或渠道状态异常，需核对渠道流水” |
    | 400/401/403/404 | 查单前置校验失败 | 落 `FULFILLMENT_FAILED` + ERROR 告警，资金状态未知写 `NULL`，由 T+1 `REVIEW` 差异兜住 |
    | 事件已有 `funds_received=1` 再遇上述“未支付/退款/无法确认”答复 | 存在资金往来的记录不得降级 | Mapper SQL 用 `CASE WHEN funds_received=1 THEN 1 ELSE ... END` 保留 `1`；状态与标记共存为“`FULFILLMENT_FAILED` + `1`”，由 T+1 按原因前缀生成冲突类/退款类差异，要求人工核对 |
    | 超时、网络、限流、5xx、空响应/解析失败、未知 trade_state、SUCCESS 但流水号/金额不一致 | 查询异常或答复不确定 | **保持 PROCESSING**，`reconcile_attempts+1`、写 `next_reconcile_at` 指数退避；到期前不再回查 |
    | 上述不确定结果达到 `payment.reconcile.max-attempts` | 重试耗尽 | 转 `MANUAL_REVIEW` 人工队列 + ERROR 告警，不静默失败 |
  - 失败态不锁死资金：`processPaymentEvent` 对已存在的 `FULFILLMENT_FAILED` 事件没有“禁止重放”的短路，后续收到真实
    渠道 SUCCESS 通知会再次走占位/订单行锁/CAS/卡密认领并提交 SUCCESS；同一订单仍不会第二次交付。
  - 结构变更：`payment_event` 新增 `reconcile_attempts`、`next_reconcile_at` 与补偿索引；新库由 `db/schema.sql` 建列，
    保留数据的存量库由 `db/migrations/V5__g34_payment_event_reconcile_retry.sql` 幂等补列/索引，失败阻断启动，不回填历史事件。
- 配置项：
  - `payment.reconcile.processing-threshold-minutes`（环境变量 `PAYMENT_RECONCILE_PROCESSING_THRESHOLD_MINUTES`，
    默认 **5 分钟**）：超过该阈值仍 PROCESSING 才进入渠道回查。
  - `payment.reconcile.max-attempts`（`PAYMENT_RECONCILE_MAX_ATTEMPTS`，默认 **5 次**）：渠道回查最大尝试次数，
    超过转 `MANUAL_REVIEW` 人工队列。
  - `payment.reconcile.retry-backoff-seconds`（`PAYMENT_RECONCILE_RETRY_BACKOFF_SECONDS`，默认 **60 秒**）：
    首次退避时长，之后按 2 倍递增，单次最长 30 分钟。
  - 扫描频率沿用 `inventory.expiry-delay-ms`（默认 **30000ms / 30 秒**）：补偿与预占到期、超时关单共用同一调度轮次。
- 语义保持：同单重放幂等成功、跨单流水号盗用仍拒绝、订单已支付后的第二笔资金仍落 `DUPLICATE_PAYMENT`、
  金额/币种/迟到款/履约失败仍落对应失败态且不把 SUCCESS 降级。
- 用例：`concurrentNotificationsFulfillExactlyOnce` 增断言“并发同单只落 1 条事件且最终 SUCCESS”，
  `race()` 任一线程抛 `UnexpectedRollbackException` 即失败；跨单冲突沿用既有用例。G34 补偿用例：
  `g34StaleProcessingEventFulfilledAfterChannelConfirmation`（确认后补履约且只发一次卡）、
  `g34StaleProcessingWithoutChannelConfirmationBecomesFailed`（明确未支付落 `FULFILLMENT_FAILED` 且不交付）、
  `g34QueryExceptionKeepsEventRetryable`（查询异常保留 PROCESSING、写入尝试数/退避且在到期前不重复查单）、
  `g34ExceedingRetryLimitMovesToManualReview`（超过上限转 MANUAL_REVIEW 并告警）、
  `g34FreshProcessingEventBelowThresholdIsNotScanned`（未到阈值不扫描）、
  `g34ProcessingEventOnFulfilledOrderIsNotTouched`（主事务已正常履约的订单不被误处理）、
  `g34FailedEventIsFulfilledByLaterRealSuccessNotification`（失败态后收到真实 SUCCESS 通知仍能幂等履约）。
- 残余风险：
  - ①`MANUAL_REVIEW` 是人工队列状态，不是自动终态：重试耗尽后需要运维按 ERROR 日志处理；补偿依赖渠道查单可用性，
    但查询异常不再直接判死，明确未支付才落失败态。
  - ②**真实 MySQL REPEATABLE READ 下并发用例仍未验证**：主事务首条普通读会建立快照，若交付明细在行锁后被普通读读取，
    极端交错时可能读到旧明细；H2 为 READ COMMITTED，不代表生产行为。该条保留为“未验证”，不得记为已验证。
- 验证：代理未运行 mvn/编译/测试/构建，也未连库；上述结论为静态核对的**已实现·待人工验证**，复跑入口
  `mvn -f shop-b2b2c-backend/pom.xml test`；H2 通过不替代真实 MySQL 与渠道联调。

## G35 公开列表类目 ID 用 Number 校验，超 2^53 静默丢失筛选（已登记未做）

- 发现位置：[publicCatalog.mjs](file:///e:/shop-b2b2c/shop-storefront-vue/src/utils/publicCatalog.mjs) 的 `normalizeCategoryId`，用例 [publicCatalog.test.mjs](file:///e:/shop-b2b2c/shared/publicCatalog.test.mjs)。
- 现象：以 `Number()` 校验类目 Long 后回填查询参数；超过 `2^53-1` 的类目 ID 会被转成失真 number，条件被静默丢弃，用户看到的是未筛选列表且无提示。
- 未改原因：现网类目 ID 很小，改动会牵动商城端查询参数与请求序列化，本轮扫描只做登记，不顺手扩大改造。
- 建议归属：T37 后续统一 ID 文本处理；改时按 `inventoryAdjustment.mjs` 的 `idText` 口径，拒绝已失真 number 并保留十进制字符串。
- 影响面：不影响当前交易与资金；仅极端大 ID 时筛选失效。

## G36 部署必填键与生产启动未强制 FINANCE_DUAL_APPROVAL_REQUIRED（已整改·待人工验证）

- 发现位置：[04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L59-L75) 与腾讯云同名脚本的 `REQUIRED_ENV_KEYS`；[application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L149) 默认 `false`。
- 现象：两云 `02-init-infra.sh` 生成的 `.env` 已写 `FINANCE_DUAL_APPROVAL_REQUIRED=true`，但手工维护 `.env`（或复用旧 `.env`）时缺失该键不会被部署预检拦截，生产会按默认 `false` 允许同人复核并只落 `SAME_OPERATOR`，等于职责分离控制被静默取消。
- 定性：不是“可登记整洁性”，而是**上线前必修**；与 T36“证书失败不静默降级”同一原则。用户 2026-11-06 明确判定上升。
- 已整改：
  - 部署侧：两云 `04-deploy.sh` 把 `FINANCE_DUAL_APPROVAL_REQUIRED` 加入 `REQUIRED_ENV_KEYS`（缺失即 `die` 中止），并新增 `case` 只接受 `true|false`，其它值直接报错；腾讯云 `env.example.sh` 同步补键。
  - 运行侧：新增 [FinanceApprovalConfigValidator.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/FinanceApprovalConfigValidator.java)，`prod` profile 下开关非 `true` 时在 bean 构造期抛出 `IllegalStateException` 阻断启动并给出明确原因；dev/test 仍允许 `false`。
  - 应用侧：[FinanceApprovalGuard.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/FinanceApprovalGuard.java#L53-L77) 在开关关闭时对**每次真实资金操作**写显式 WARN（不论是否传复核人）；[financeReview.mjs](file:///e:/shop-b2b2c/shop-admin-vue/src/utils/financeReview.mjs) 增加 `singleAdminMode/singleAdminNotice`，财务复核页与商户治理页展示“当前为单管理员模式”。
  - 用例：`test_release_credentials.py` 增缺键中止、非法值拒绝、env 示例含 true；`FinanceApprovalConfigValidatorTest` 增 prod false 阻断、prod true 放行、dev false 放行；`FinanceApprovalGuardTest` 增宽松模式缺复核人仍落 `SAME_OPERATOR`；`financeReview.test.mjs` 增单管理员模式展示。
- 文档：本文与[部署入口与路径约定](部署入口与路径约定.md#L22-L31)已写明“生产必须开启”。
- 验证：代理未运行 mvn/编译/测试/构建，未执行部署脚本，未连数据库；状态为**已实现·待人工验证**，复跑入口 `mvn -f shop-b2b2c-backend/pom.xml test` 与 `python3 -m unittest discover -s deploy/tests -p 'test_release_credentials.py' -v`。
- 影响面：`02-init-infra.sh` 标准路径不变；手工部署路径由“静默单管理员”改为“缺键/非法值中止、prod 非 true 阻断启动”。


## G37 两云 Nginx 模板缺 /healthz、/readyz 别名（已登记未做）

- 发现位置：[nginx-gmall.conf](file:///e:/shop-b2b2c/deploy/aliyun/server/nginx-gmall.conf)、[nginx-shop.conf](file:///e:/shop-b2b2c/deploy/tencent/server/nginx-shop.conf)、[nginx.prod.conf](file:///e:/shop-b2b2c/deploy/nginx/nginx.prod.conf#L27-L38)。
- 现象：只有通用模板提供 `/healthz`→`/api/v1/auth/health`、`/readyz`→`/api/v1/auth/ready`；两云实际安装的 per-cloud 模板没有这两个别名，只透传 `/api/`。
- 未改原因：部署脚本与状态脚本都直接探 `/api/v1/auth/ready`，不会误判；别名属模板与文档漂移，改动会影响两云站点模板与网关用例，本轮只登记。
- 建议归属：T20/T36 网关模板同批；若保留别名，须两云同步并补 `test_nginx_gateway.py` 断言，避免只改一处。
- 影响面：外部监控若使用 `/readyz` 会在两云 404；改用 `/api/v1/auth/ready` 可正常工作。

## G38 迁移执行时机晚于 Web 端口监听（已登记未做）

- 发现位置：[SchemaMigrationRunner.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java)、[application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml)。
- 现象：迁移器实现 `ApplicationRunner`，在 Spring Boot 内嵌 Web 容器已监听之后才执行；迁移耗时期间端口可被访问，结构可能仍是旧版。
- 未改原因：提前到 Bean 初始化阶段会与 `spring.sql.init`（`schema.sql` 导入）争抢执行顺序，新库可能在建表前跑迁移；改动影响启动契约与全部集成测试，本卡只做审计与结构对齐。
- 建议归属：部署运维；以单实例停机发布或 `/readyz` 就绪门禁规避，必要时再设计“迁移完成前拒绝业务请求”的启动闸门。
- 影响面：迁移窗口内的请求可能命中旧结构（如缺列）而报错；不会写坏数据，因迁移失败会阻断启动。

## G39 V 系列不创建 V1 之后新增的 19 张表（路径约束，需运维遵守）

- 发现位置：[db/migrations](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/migrations)、[db/schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql)。
- 现象：`trade_refund`、`merchant_account`、`merchant_account_ledger`、结算/分账/入驻等 19 张表只由 `schema.sql` 的 `CREATE TABLE IF NOT EXISTS` 创建；只跑 V 系列不会建这些表。
- 未改原因：把 19 张表复制进迁移会形成两份权威结构，后续表结构变更要改两处，漂移风险更大；维持“schema.sql 建表 + V 脚本补旧表增量”的单一来源。
- 建议归属：部署运维；存量库升级必须先导入 `db/schema.sql`（部署 `--migrate`）再启动应用。缺表时 `ALTER TABLE` 会报“表不存在”并阻断启动，属明确失败，不会静默半迁移。
- 影响面：若运维跳过 `--migrate` 直接重启应用，启动会失败或业务查询缺表；不会产生错误数据。

## G40 多实例并发启动时迁移记账主键冲突（已登记未做）

- 发现位置：[SchemaMigrationRunner.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java) 的 `isApplied` / `recordApplied`。
- 现象：两个实例同时启动且都判断某版本未应用时，两者都会执行幂等脚本，随后插入同一 `schema_migration` 主键，后插入者启动失败。
- 未改原因：当前部署为单实例 systemd；多实例迁移锁需要数据库命名锁或外部编排，属部署架构决策，不在本卡范围。
- 建议归属：多实例部署改造时处理；最低要求先发布单实例完成迁移再扩副本。
- 影响面：后启动实例启动失败（非静默），数据库结构不会双写错乱；需人工重启。

## G41 旧库排序规则与新表不一致会导致建外键失败（上线前须确认）

- 发现位置：[db/schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql) 各表 `DEFAULT CHARSET=utf8mb4`（未显式 `COLLATE`）、[V6__s1_legacy_structure_reconcile.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/migrations/V6__s1_legacy_structure_reconcile.sql)。
- 现象：由 MySQL 5.7 延续的旧库可能保留 `utf8mb4_general_ci`，而 MySQL 8 新建表使用 `utf8mb4_0900_ai_ci`；两端排序规则不同时 MySQL 拒绝建立外键，V6 与 `--migrate` 导入都会失败。
- 未改原因：统一排序规则需要逐表 `ALTER ... CONVERT TO` 全量重建，属数据迁移动作，不能在幂等结构脚本里静默执行；开发期允许重建库。
- 建议归属：上线前环境核对；按 [迁移与基线审计](2026-11-06-migration-baseline-audit.md) §5.1 先检查 `information_schema.tables.table_collation`，不一致则重建库后再迁移。
- 影响面：迁移被阻断（明确失败），不会写入半套外键；但需要运维先做库级决策。

## G42 账本表无表内操作人，人工调整入口未实现

- 发现位置：[db/schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql) 的 `merchant_account_ledger`、[MerchantAccountService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantAccountService.java)。
- 现象：`merchant_account_ledger` 有 `balance_after`/`arrears_after`/`created_at`，但没有表内 `operator_id`；`entry_type` 声明了 `ADJUSTMENT`，代码尚无人工调整入口。
- 未改原因：当前入账全部由订单/退款/充值单驱动，操作人可由对应单据的 `confirmed_by`/`requested_by` 追溯；补一个永远为空的列会造成“审计字段齐全”的假象。
- 建议归属：开放人工调整功能时，同批补 `operator_id` 与调整原因，并写入用例。
- 影响面：现阶段无资金操作缺失审计；若绕过单据直接改账本（当前无入口），将无法在表内追溯操作人。

## G43 配置文件存在明文凭据，需改为环境变量注入

- 发现位置：`shop-b2b2c-backend/src/main/resources/application.yml`（工作区本地改动，未提交入库；本文件不回显任何凭据值）。
- 风险：数据源账号/口令以字面量写入配置文件，易随文件复制、打包、备份流转；一旦误提交即泄露，且轮换只能改文件发版，无法按环境独立注入。
- 建议：改为环境变量占位（按 G03 的注入方式），本地配置不入库；轮换凭据后确认仓库与构建产物无明文残留。
- 现状（2026-11-07）：用户全量测试暴露工作区 `application.yml` 被本地改为字面量账号，导致 `DatasourceCredentialContractTest` 失败；已把受跟踪文件恢复为 `${SPRING_DATASOURCE_USERNAME}` / `${SPRING_DATASOURCE_PASSWORD}` 占位符，未提交任何凭据。本地调试连接真实库时请用环境变量注入（`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`），或用未纳入版本库的本地 profile（如 `application-local.yml` + `--spring.profiles.active=local`）；不要改动受跟踪的 `application.yml`。

## G44 recordEventStatus 用 updateById，null 字段不会写回（已登记未做）

- 发现位置：[PaymentEventRecorder.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java) 的 `recordEventStatus` / `recordFailureAudit`。
- 现象：两条路径都用 `updateById`，MyBatis-Plus 默认 `NOT_NULL` 字段策略会忽略实体中的 `null` 字段。传入 `fundsReceived=null` 只是“不改”，不会写成 `NULL`；将来若有人想用它“清空资金标记”，会静默不生效且没有任何报错。
- 未改原因：当前所有需要写 `NULL` 的场景（G34 `markManualReview`、`markFulfillmentFailed` 的 NULL 分支）已使用显式 SQL，不依赖 `updateById`；为清空单独改全局字段策略会影响其他更新路径。
- 建议归属：若后续出现“清空资金标记/错误信息”的需求，改用显式 `SET funds_received = NULL` 的 Mapper SQL，或在方法内显式判断并走自定义更新；同时补清理语义的用例。
- 影响面：现状无功能缺失；仅在方法注释中写明陷阱，避免后续维护者误以为传 `null` 会清空。

## G45 payment_event 缺 (merchant_id, created_at) 索引，T+1 按商户扫全历史（已登记未做）

- 发现位置：[PaymentEventMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentEventMapper.java) 的 `findByMerchantAndPeriod`、[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql) 的 `payment_event` 索引。
- 现象：`payment_event` 只有 `(merchant_id, event_status)` 索引；`findByMerchantAndPeriod` 先按 `merchant_id` 取该商户**全部历史事件**，再在 SQL 条件里过滤 `created_at >= from AND created_at < to`，即“按商户扫全历史再过滤”，T+1 每日跑批的成本随历史事件量线性增长。
- 未改原因：这是 T35 既有查询（`payment_event` 建表与索引不在本卡范围），本卡只改判定口径；新增复合索引会影响其他写路径的索引选择，需与 T35 的查询清单一起评估。
- 建议归属：T35 查询优化或性能专项；补 `(merchant_id, created_at)` 复合索引，并确认 `findByMerchantAndPeriod` 能命中该索引（必要时改为范围条件下推）。
- 影响面：功能正确，仅性能随数据量下降；不会造成误报或漏对。

## G46 部分导入时非 SUCCESS 事件无逐笔缺流水检查（已登记未做）

- 发现位置：[ReconciliationService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java) 的事件循环与渠道差集。
- 现象：`funds_received=1` 的非 `SUCCESS` 事件只进入“渠道是否有任意本地单据”的整批判定：若该渠道已导入任意一条流水，整批未导入也会被掩盖；没有针对单个事件键（如 `EV|id|ST`）检查其对应支付流水是否缺失。当前只有“该渠道整批未导入”才 `PARTIAL`。
- 未改原因：逐笔检查需要为每个非成功事件按其 `pay_trade_no` 匹配流水，会改变差异数量与跑批噪声水平；本卡当前目标是“不漏整批”，逐笔补强涉及差异口径调整，未纳入本轮。
- 建议归属：T+1 后续增强；若业务要求“每笔已收款事件都必须有对应流水”，应新增 `EV|{id}|ST` 差异并在 `incompleteChannels` 之外单独统计，同时评估对人工队列量的影响。
- 影响面：功能不误报；残留风险是“渠道部分导入时，个别非成功事件仍可能没有对应流水却无逐笔差异”。

## G47 markFailed 的 CASE 在“现有 0 + 入参 NULL”时会把 0 退化为 NULL（已登记未做）

- 发现位置：[PaymentEventMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentEventMapper.java) 的 `markFulfillmentFailed`。
- 现象：`funds_received = CASE WHEN funds_received = 1 THEN 1 ELSE #{fundsReceived} END` 只保护 `1`；若某行已是 `0`，又收到“查单前置校验失败（400/401/403/404，入参 NULL）”的答复，`0` 会被改写成 `NULL`，SQL 层没有“0 也保留”的防御。
- 未改原因：当前状态机不可达——`0` 只在未标注行/历史或异常行出现，而这类事件在 G34 扫描前已被视为 `PROCESSING` 并可被 400/401/403/404 分支命中；一旦需要在 SQL 层兜底，应改为 `CASE WHEN funds_received IN (0,1) THEN funds_received ELSE #{fundsReceived} END`，但会改变 NULL 分支的语义，需专项评审。
- 建议归属：G34/对账专项；补防御 + 用例（现有 `0` + 入参 NULL 仍是 `0`）。
- 影响面：当前无实际触发路径；仅是 SQL 层缺少防御，不构成已发生的数据错误。

## G48 markManualReview 用例未回读中间态（已登记偏弱）

- 发现位置：[Sprint1SecurityIntegrationTest.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/test/java/com/shop/b2b2c/Sprint1SecurityIntegrationTest.java) 的 `g34ExceedingRetryLimitMovesToManualReview`。
- 现象：第六轮把起点改为 `funds_received=1`，并断言最终为 `NULL`，能证明 `SET funds_received=NULL` 生效；但没有在“第 1 次重试、尚未转 MANUAL_REVIEW”的中间态回读，无法证明重试过程中 `1` 一直保持、只在转人工时才被清空。
- 未改原因：需要精确控制多次扫描与 `next_reconcile_at` 到期时间，用例成本高于本轮收益；现有断言已覆盖最终态。
- 建议归属：G34 用例补强；分步执行三次扫描并在每次后回读 `funds_received`/`reconcile_attempts`，断言前两次仍为 `1`、第三次转 `MANUAL_REVIEW` 且为 `NULL`。
- 影响面：测试覆盖偏弱，不影响当前实现正确性判断。

## G49 旧格式游标在管理端“加载更多”会持续 400 直到刷新（前端已回第一页·待验证）

- 发现位置：[OrderCursor.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/OrderCursor.java)；[admin/Orders.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/admin/Orders.vue)；[merchant/Orders.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue)。
- 现象：2026-11-07 把游标从“毫秒时间戳 + id”改为“ISO 完整精度时间 + id”。部署前已打开并已翻页的浏览器仍持有旧格式 `nextCursor`，点“加载更多”会因 `decode` 返回 400；前端未在失败时清空 `nextCursor`，同一旧游标会被反复提交，直到刷新页面或重新选择筛选条件才恢复。
- 影响面：单次发布期间已打开的管理端订单列表需刷新一次；不影响新请求与数据正确性。
- 本轮处理（2026-11-07）：不引入后端双格式兼容，保持仍返回 400；只做前端体验修复。`orderList.mjs` 新增 `isInvalidOrderCursorError`，仅识别 HTTP 400 且消息含“游标”的失败；admin/merchant 的“加载更多”命中时清空 `nextCursor` 并自动重载第一页，首屏失败仍按普通错误提示。回归用例在 `shared/orderList.test.mjs`，前端生产构建与用例由用户执行。同时修正两处“刷新”按钮 `@click="loadOrders"` 把点击事件当成 `more=true` 的问题（无更多时刷新无响应、有更多时变成追加），改为显式 `loadOrders()`。

## G50 BusinessException 单参构造默认 500，用户输入/认证拒绝被计为服务端故障（已评估，未改代码）

- 发现位置：[BusinessException.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/BusinessException.java)；[GlobalExceptionHandler.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java)。
- 现象：单参构造把 `code` 固定为 `500`，`statusOf` 会映射成 HTTP 500。当前 57 处单参调用覆盖下单联系人/数量/库存校验、登录密码错误、商家越权、订单/SKU/店铺不存在、店铺编码或账号重复等；这些本是 400/401/403/404/409 语义，却统一按 5xx 返回，监控与按状态分支的前端拦截器会把正常业务拒绝当成系统故障。
- 未改原因：这 57 处并非同一语义，简单把默认值改为 400 会把认证失败、越权、不存在、冲突一起降级为参数错误；逐点补码会同时影响既有接口契约、前端状态分支与后端用例断言，属于独立契约整改，不能夹在本轮分页/对账收尾里静默改。
- 建议方案：①先明确映射表（输入校验 400、未登录 401、越权 403、不存在 404、重复/并发冲突 409）；②单参构造保留 500 以暴露遗漏，逐点改显式码并删除单参构造或改为需显式枚举；③补契约用例断言 HTTP 状态与 body.code 一致；④前端拦截器只在 401 清会话，其余按消息展示。
- 影响面：仅错误响应语义与监控口径；不改变成功链路。涉及登录、下单、商家管理等多入口，改动需单独立卡并与前端同批验证。

## G51 非安全上下文缺失 crypto.randomUUID，建单在发请求前就失败（已实现·待人工验证）

- 发现位置：[storefront idempotency.mjs](file:///e:/shop-b2b2c/shop-storefront-vue/src/utils/idempotency.mjs)；[admin idempotency.mjs](file:///e:/shop-b2b2c/shop-admin-vue/src/utils/idempotency.mjs)；[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue)。
- 现象：通过 `http://192.168.3.148:3002`（局域网 IP 的 http 页面）访问商品详情，点击“创建订单”弹出“下单失败”；浏览器网络面板中**没有** `/api/v1/public/orders/checkout` 请求，控制台也无异常。根因：`crypto.randomUUID` 只在安全上下文（HTTPS/localhost）可用，非安全上下文中为 `undefined`，`checkoutSubmission.prepare()` 抛 `TypeError` 并被 `catch` 吞掉，只显示通用兜底文案。该问题与 CORS 403 无关，代理修好后仍然存在。
- 修复：两端 `idempotency.mjs` 新增 `createIdempotencyKey`，优先 `crypto.randomUUID`，缺失时用同为 CSPRNG 的 `crypto.getRandomValues` 组装 UUIDv4；两者都不可用时明确报错，禁止 `Math.random` 降级。
- 验证（2026-11-07，浏览器实测）：修复前点击后无 checkout 请求；修复后同一页面点击“创建订单”已发出 `POST /api/v1/public/orders/checkout`，页面显示“订单已创建，等待付款”，控制台无报错。用例已补 `shared/idempotency.test.mjs`（randomUUID 优先、getRandomValues 降级生成 UUIDv4、无安全随机源报错）。
- 状态：已随本轮修复提交·待人工验证；前端用例与生产构建由用户执行。验证产生了一笔 `debug@example.com` 的测试订单。