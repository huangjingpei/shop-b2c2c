<!--
文件：2026-11-06-diagnostic-baseline-revised.md
用途：2026-11-04 diagnostic 报告与 remediation todo 的纠正后基线。按 HEAD（bcb1dab）源码重写 R01–R35 状态，保留原 D/F/R 编号，补充两份原文档遗漏的 N1–N12 发现，作为后续开发与回归的统一状态入口。
维护注意：
 1. 本文件取代两份 2026-11-04 文档的“当前状态/当前事实”结论；原文档保留历史时点，不修改、不删除。
 2. 本轮只读静态分析：未修改源码，未运行 mvn/编译/测试/构建/部署；“已实现·待人工验证”不等于验收通过。
 3. 行号以 bcb1dab 工作区为准，后续提交会漂移；修复时以符号名定位，并回写本表状态与证据。
 4. 新增 N 编号只用于本次补充发现，不替代原 D/F 台账；需要并入主台账时由用户确认映射。
 5. P0/P1/P2 是审查建议分级，不是已批准排期；“需业务确认”项不得直接按缺陷改写业务规则。
 6. 修复必须复用既有 TradeGuards、AuthSessionService、OrderIdempotencyService、支付事件与预占状态机，禁止另建第二套金额/库存/幂等权威。
-->
# B2B2C 纠正后诊断基线（2026-11-06）

复核基线：`bcb1dab`（2026-11-06 工作区）。
取代关系：
- 本文件**取代** [2026-11-04 diagnostic](2026-11-04-project-diagnostic-report.md) 与 [2026-11-04 todo](2026-11-04-remediation-todo.md) 中的“当前状态/当前事实”结论。
- 两份原文档**不修改**，保留 2026-11-04 历史时点；其问题背景、D/F 归属、审查方法与 §4 回归场景仍可用。
- 纠错过程与逐条证据见 [errata](2026-11-06-diagnostic-docs-errata.md)。

---

## 1. 结论摘要

| 维度 | 现状 |
| --- | --- |
| 基础交易骨架 | 多商户商品、匿名建单/领取、数据库预占、卡密认领、商品维护、租户隔离与部分运营追溯可用；拦截器角色门禁、会话态复核、对象归属校验、预占 CAS/行锁、幂等请求哈希等关键路径经静态核对成立。 |
| 经营闭环 | 仍不具备真实支付、渠道对账、结算、退款和实物履约闭环；不能作为“资金交易全闭环已验收”的生产系统。 |
| 当前最高风险 | 商品详情页运行即崩（N4，主链路不可用）；支付事件/金额/重复支付三处 P0（N1–N3，接入真实渠道后立即造成资金/资产不一致）；员工可读店主 PII（N5）；下架与预占竞态（N7）；跨店 SKU 编码冲突（N6）。 |
| 文档可信度 | 原报告“状态”约 3/10，原 todo“状态列”约 4/10；两者均不能作为进度基线，本文件取代之。 |

**最重要的正面结论**：未发现跨店读取订单、跨店操作卡密、跨店改库存的直接越权路径；`markPaidIfUnpaid` 对 `RESERVED_LOCK` 的预占有效性约束、建单请求哈希冲突 409、金额位数守卫、公开店铺白名单、Redis 原子限流均成立。

---

## 2. R01–R35 状态表（按 HEAD 重写）

状态口径：
- **未修复**：代码仍存在原缺陷，且未见有效缓解。
- **部分修复**：主要路径已改，但仍有关键残留，不能关闭。
- **已实现·待人工验证**：代码与用例/说明齐备，但用户未执行验证。
- **待专项验证**：需要环境/并发/工具证据，静态无法定性。
- **需业务确认**：规则未定，不能直接按缺陷改写。

| 编号 | 原报告 | 原清单 | 纠正后状态 | 标记 | 关键依据（摘要） |
| --- | --- | --- | --- | --- | --- |
| R01 | 待处理 | 待处理 | 未修复 | 延续原判断 | `application.yml` 数据源口令字面回退；schema 固定演示账号/种子；管理端登录页预填演示口令。 |
| R02 | 待处理 | 已修复 | **部分修复** | 本次修正 | 卡密查看/导入/作废、商品创建/编辑、库存调整已限 `MERCHANT_ADMIN`；代客推单与实物发货仍对员工开放；`/auth/me`、`GET /merchant/profile` 仍向员工返回身份证/银行卡/结算账户。 |
| R03 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | CORS 已允许 `X-Reservation-Token`（`SaTokenConfigure:52`）。 |
| R04 | 待处理 | 已修复 | 已实现·待人工验证（残留：卡密库授权明文） | 本次修正 | 订单列表/详情已清 `queryToken`/`posterUrl` 并按角色脱敏；卡密库存列表仍向店长返回解密正文（另记 N11）。 |
| R05 | 待处理 | 已修复 | 部分修复 | 本次修正 | 新导入已 AES-GCM + 指纹；默认密钥回退、schema 种子明文、存量未迁移、备份仍可能含可用卡密。 |
| R06 | 待处理 | 待专项验证 | 未修复 | 延续原判断 | 仍取 `getRemoteAddr()`，无可信代理配置；Nginx 回环连接会使真实客户端共享 IP 桶。 |
| R07 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | `TradeCostLimiter` 已有建单/预占/海报 IP 与联系人或令牌预算；残留受 R06 与累计存储配额影响。 |
| R08 | 待专项验证 | 待专项验证 | 待专项验证 | 延续原判断 | 30 天会话、`active-timeout:-1`；无依赖扫描与日志审计证据。 |
| R09 | 待处理 | 已实现·待人工验证 | 已实现·待人工验证 | 本次修正 | 已改为 `updateProfileOnly`/`updatePaymentChannelOnly`/`updateServiceFeeOnly` 定向列更新。 |
| R10 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | 同 ownerToken 可恢复原预占；残留为 ownerToken 可由客户端自选。 |
| R11 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | ID 游标 + 单条异常捕获，失败也前移游标；残留为失败记录不重试/无追踪。 |
| R12 | 待处理 | 已修复 | **部分修复** | 本次修正 | 已有 15 分钟关单任务；但 DB 层 `markOrderPaidIfUnpaid` 允许 `lock_expire_time IS NULL` 的迟到支付。 |
| R13 | 待处理 | 已修复 | **部分修复（仍有 3 个 P0）** | 本次修正 | 事件表已建，但先记 `SUCCESS` 后提交、金额校验自证、已支付提前返回（N1–N3）。 |
| R14 | 待专项验证 | 待专项验证 | 待专项验证 | 延续原判断 | 无 MySQL 并发/锁序证据。 |
| R15 | 待处理 | 已修复 | 部分修复 | 本次修正 | CHECK/唯一索引已补；仍无 ALTER 版本迁移，schema 只建不改。 |
| R16 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | 前端无依据折扣已移除，后端按 SKU 价计算并由 SKU 推导 `minPrice`。 |
| R17 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | 创建校验已补齐；残留为创建默认 `status=1` 直接上架。 |
| R18 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | schema 已为年卡 SKU 注入 3 张卡密（ID 6–8）。 |
| R19 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | 新增类目已 `setId(null)`，不再覆盖既有类目。 |
| R20 | 待处理 | 已修复 | **部分修复且引入 P0 回归** | 本次修正 | 路由观察已加，但 `ProductDetail.vue` TDZ 使详情页运行即崩（N4）。 |
| R21 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | 收银台改用 `detail.productId` 且 `v-if` 保护，不再回退商品 1。 |
| R22 | 待处理 | 已修复 | **部分修复** | 本次修正 | `1.38` 与“官方合规认证”已移除；“专户存管/官方直结/担保/秒发”等未验证承诺仍在。 |
| R23 | 待处理 | 后续修订待人工验证 | 已实现·待人工验证 | 本次修正 | `clearFeeFreeUntil=true` 可显式清空，null 保留旧值。 |
| R24 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | 分页 + 批量明细 + SQL 投影统计；残留为无 `created_at` 索引、默认 50 条/上限 200 条。 |
| R25 | 待处理 | 已修复 | **部分修复** | 本次修正 | 指纹索引优先；未命中仍全店明文全量扫描（同一查询两次）并持商户锁；NULL 指纹行不受唯一索引保护。 |
| R26 | 待处理 | 已修复 | **部分修复** | 本次修正 | 列表已排除海报，但海报仍在建单事务内渲染（`PeerPayServiceImpl:138-141`）。 |
| R27 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | 72h 分批清理已实现；残留为快照保存完整响应（含凭证）、业务历史无归档。 |
| R28 | 待处理 | 已修复 | 已实现·待人工验证 | 本次修正 | 腾讯构建已强制三端、`npm ci`、原子 latest、失败标记。 |
| R29 | 待处理 | 已修复 | **部分修复** | 本次修正 | 预检与 SQL 失败阻断已实现；残留为迁移前无数据库备份、schema 无回滚。 |
| R30 | 待处理 | 待处理 | 未修复 | 延续原判断 | systemd `User=root`；证书失败仅告警并降级 HTTP。 |
| R31 | 待专项验证 | 待专项验证 | 待专项验证 | 延续原判断 | PowerShell 已检查退出码/UTF-8，但 Windows 路径与编码矩阵未执行。 |
| R32 | 待处理 | 已修复 | **部分修复** | 本次修正 | PowerShell 已参数数组化；Bash 仍拼命令字符串、口令在进程参数、含空格路径会拆参。 |
| R33 | 待处理 | 已修复 | **部分修复** | 本次修正 | 已有后端 25 个测试类与前端规则用例；无 CI、无执行记录/环境证据，不能标已修复。 |
| R34 | 待处理 | 已修复 | **部分修复** | 本次修正 | 400/404/405/415 已分类；`BusinessException` 仍返回 HTTP 200。 |
| R35 | 待专项验证 | 已修复 | 未修复 | 本次修正 | 仅有入参 `StrictIdDeserializer`；无全局 `Long→String` 序列化，公开视图 ID 仍为 number。 |

统计：延续原判断 6 项（R01、R06、R08、R14、R30、R31）；本次修正 29 项；新增发现 12 项（N1–N12）。

---

## 3. 新增发现 N1–N12

> 完整证据见 [errata 第 3 节](2026-11-06-diagnostic-docs-errata.md#3-两份文档共同遗漏的问题新增发现)。此处给出结论与最小依据。

### P0

| 编号 | 问题 | 触发条件 | 影响 | 依据 |
| --- | --- | --- | --- | --- |
| N1 | 支付事件在外层事务提交前以 `REQUIRES_NEW` 记 `SUCCESS`；重放直接返回成功，不补履约。 | 订单更新后、事务提交前崩溃/提交失败，渠道重试同一流水号。 | 资金已收、订单永久 `UNPAID`、卡密未交付；重试无法自愈。 | [PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L232-L234)、[PaymentEventRecorder.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L32-L41) |
| N2 | 代付事件金额用 `order.getPayAmount()` 回填，校验恒真。 | 任何渠道适配器调用 `notifyPaymentSuccess`。 | 少付/错币种/部分退款后补付均可标记 `PAID` 并交付。 | [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L495-L505)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L105-L109) |
| N3 | 订单已 `PAID` 时在记录事件前提前返回。 | 重复支付、双人同时付款、渠道用新流水号重试。 | 第二笔资金无事件、无对账、无退款线索。 | [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L486-L488) |
| N4 | `ProductDetail.vue` 在 `watch(..., { immediate: true })` 中同步调用 `loadProduct`，访问尚未初始化的 `peerPayResult`，触发 TDZ。 | 打开任意商品详情页。 | 详情页整体抛 ReferenceError，主交易入口不可用。 | [ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L446-L474)、[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L516) |

### P1

| 编号 | 问题 | 影响 | 依据 |
| --- | --- | --- | --- |
| N5 | 员工可读取店主身份证号、银行卡与结算账户。 | 隐私/合规风险；员工离职或越权可批量获取。 | [AuthController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java#L124-L128)、[MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L200-L216) |
| N6 | `uk_sku_code` 全局唯一。 | 跨商户复用 SKU 编码触发唯一键冲突，接口 500，且无法通过友好提示恢复。 | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L111) |
| N7 | 下架不校验 HELD 预占。 | 下架后已预占订单付款成功、履约阶段被拒，资金与资产不一致。 | [ProductEditingService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductEditingService.java#L72-L93)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L165-L171) |
| N8 | 商城前端从不调用 pre-lock/cancel-lock。 | 真实收银台路径没有预占保护，R10 的恢复能力在 UI 上不可达。 | `shop-storefront-vue/src` 无匹配；接口见 [PeerPayController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java#L61-L75) |
| N9 | 阿里云 `.env` 缺 DB 用户名/密码与商城跳转 URL。 | 生产启动回退默认口令、商城链接回退 `localhost:3002`；Redis 口令有硬编码默认值。 | [02-init-infra.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh#L33-L55) |

### P2

| 编号 | 问题 | 依据 |
| --- | --- | --- |
| N10 | `payment_channel` 建单写 `A/B/C` 收款模式，付款后又被真实渠道覆盖，同列双语义。 | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L30)、[PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L108) |
| N11 | 卡密库页面直接展示/复制明文，扩大肩窥与截图泄露面。 | [Cards.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Cards.vue#L21-L23) |
| N12 | 同一幂等键并发请求触发唯一键冲突，`DuplicateKeyException` 未映射，返回 500 而非 409/幂等结果。 | [GlobalExceptionHandler.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java)（无处理） |

---

## 4. 商业能力 F01–F20（纠正后）

原判断基本成立，仅补充与 R 相关的边界。

| 原编号 | 纠正后状态 | 说明 |
| --- | --- | --- |
| F01 | 未实现（仅有事件骨架） | 无渠道网关/验签通知/主动查单；事件表存在但 N1–N3 未修，不能视为可付款商城。 |
| F02 | 模拟实现 | 本地 SUB 号/PASSED 不等于官方开通。 |
| F03 | 未实现 | 无分账、结算、退款回退流水。 |
| F04 | 未实现 | 有收款码配置，无可靠对单/核账。 |
| F05 | 未实现 | `REFUNDED` 状态不构成退款服务。 |
| F06 | 仅模型 | Complaint 实体/Mapper/表，无服务/接口/三端闭环。 |
| F07 | 未实现 | `recipientAddress` 仅存在于 DTO，未落库；普通 checkout 无地址快照。 |
| F08 | 部分实现 | 预占超时释放已有；缺统一意向关闭与真实渠道关单补偿。 |
| F09 | 仅模型 | BillingStatement 无账本生成服务。 |
| F10 | 配置实现 | 五状态标签与免收期可配；无扣费/停复服引擎。 |
| F11 | 部分实现待验证 | 编辑/改价/上下架/人工库存记录已有；SKU 增删、媒体、售罄策略未闭环。 |
| F12 | 部分实现待验证 | 加密/去重/批次/作废已有；存量迁移、明文种子、NULL 指纹与全量回退扫描仍在（R05/R25）。 |
| F13 | 部分实现待验证 | 会话退出/状态核验/部分资产记录已有；员工最小权限与强认证未闭环（R02）。 |
| F14 | 未闭环 | 商户超管创建、广告只读；种子跳转无对应商城路由。 |
| F15 | 未实现 | 无短信/邮件/站内信发送服务。 |
| F16 | 录单基础 | 仅物流公司/运单号，无轨迹与完整配送模型。 |
| F17–F20 | 待业务确认 | 会员/搜索、购物车/跨店/营销/评价/发票、阶梯价、其他候选能力均需先确认经营范围与规则。 |

---

## 5. 验证边界与未覆盖项

本轮**没有执行**编译、测试、构建、部署、数据库操作或压测，也没有连接远端。因此：

- “已实现·待人工验证”只表示静态代码与用例齐备；不代表测试通过。
- R14（锁序/库存守恒）、R08（依赖漏洞/日志）、R31（Windows 备份编码）、R06（真实代理拓扑）、R30（主机权限/证书）仍需环境证据。
- 前端仅做了调用点与关键逻辑核对，未做浏览器 E2E；N4 的运行时影响需实际打开详情页确认。
- H2/静态推断不能替代 MySQL 并发与真实渠道联调。

用户手动验证入口（按 AGENTS.md）：
- 后端：`mvn -f shop-b2b2c-backend/pom.xml test`
- 前端规则：`node --test shared/*.test.mjs`（先 `npm --prefix shop-storefront-vue ci`）
- 部署隔离用例：按 Sprint 18–21 文档在 Linux/WSL 执行。

---

## 6. 建议优先处理顺序

| 顺序 | 范围 | 理由 |
| --- | --- | --- |
| 1 | **N4** 详情页 TDZ | 当前商城主链路不可用，修复成本最低、收益最高。 |
| 2 | **N1–N3** 支付事件/金额/重复支付 | 未接渠道前是潜伏缺陷；接入后立即造成资金与资产不一致。先改为 `afterCommit` 记成功、显式传入渠道实收金额、删除提前返回并记录重复支付事件。 |
| 3 | **N5/N7/N6** PII、下架竞态、SKU 全局唯一 | 直接影响隐私、履约一致性与跨商户可用性。 |
| 4 | **R01/R06/R30/N9** 生产隔离 | 凭据/种子、可信代理、root 运行、证书降级、阿里云 env 缺项；属上线门槛。 |
| 5 | **R12/R07/R25/R26** 预占与成本/资产 | 关闭规则、限流真实地址、卡密全扫与锁、海报事务。 |
| 6 | **R34/R35/R33** 契约与验证 | HTTP 状态、ID 字符串契约、CI/执行记录；影响前端兼容与回归可信度。 |
| 7 | **R02/R05/R04/R22** 权限与资产分级 | 员工最小权限、密钥轮换/存量迁移、卡密库明文策略、宣传真实性。 |
| 8 | **R29/R32/R28/R24/R27/R15** 发布与治理 | 备份、回滚、索引、归档、迁移方案。 |
| 9 | **R13 + F01/F03/F05/F09** 真实交易闭环 | 需先确认经营/收款模式，再设计渠道事件、履约、售后、账务。 |

依赖说明：R05 与 R25 共用同一次卡密迁移设计；R10–R13 共用预占/支付状态机；R24 不得另算财务金额；R34 的 HTTP 分类调整需同步前端调用点。

---

## 7. 相对原文档的变更清单

**本次修正（29 项）**：R02、R03、R04、R05、R07、R09、R10、R11、R12、R13、R15、R16、R17、R18、R19、R20、R21、R22、R23、R24、R25、R26、R27、R28、R29、R32、R33、R34、R35。

**延续原判断（6 项）**：R01、R06、R08、R14、R30、R31。

**新增发现（12 项）**：N1–N12。

**原文档仍可用的部分**：审查方法与边界声明、D/F 归属、todo §2 批次顺序、todo §4 回归场景、F01–F20 能力缺口判断。

**明确作废的结论**：
- “R13 已修复”“R20 已修复”“R34 已修复”“R35 已修复”“R02 已修复”“R05 已修复”“R33 已修复”等状态结论。
- “R18 无年卡种子卡密”“R03 CORS 缺预占头”“R19 新增可覆盖”“R21 回退商品 1”“R23 不能清空免收日”“R28 腾讯可缺端成功”等已被后续提交改变的事实描述。
