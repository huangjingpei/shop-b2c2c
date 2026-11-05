<!--
文件：2026-11-06-diagnostic-docs-errata.md
用途：把 2026-11-04 的 diagnostic 报告与 remediation todo 本身作为审查对象，逐条回到 HEAD（bcb1dab）源码核对，给出价值评估、问题定性分类（事实错误/状态错误/范围错误/已失效/遗漏）及文件行号证据，作为纠正后基线（2026-11-06-diagnostic-baseline-revised.md）的依据说明。
维护注意：
 1. 本轮为只读分析：未修改附录的两份原文档，未修改任何源码，未运行 mvn/编译/测试/构建/部署。
 2. 原两份文档保留 2026-11-04 历史时点；本文只纠正其“当前状态/当前事实”结论，并说明由新基线取代，不删除其历史记录地位。
 3. 行号以 HEAD=bcb1dab 的工作区为准；后续提交会使行号漂移，引用时以符号名和路径为主、行号为本次快照。
 4. “已修复/仍存在”均指静态代码现状，不代表运行验证或用户验收；P0/P1/P2 是审查建议分级，不是用户已确认的排期。
 5. 修复应复用既有 TradeGuards、AuthSessionService、OrderIdempotencyService、支付事件与预占状态机，不得另建第二套金额/库存/幂等权威。
-->
# 2026-11-04 两份诊断文档的复核与纠错（errata）

复核基线：`bcb1dab`（2026-11-06 工作区，含未提交的 `deploy/scripts/backup_mysql.ps1` 修改）。
复核对象：
- [diagnostic 报告](2026-11-04-project-diagnostic-report.md)
- [remediation todo](2026-11-04-remediation-todo.md)
旁证（仅作线索，关键结论已回源码复核）：
- [后端持久化与并发一致性审查](2026-11-06-backend-persistence-concurrency-review.md)
- [前端与部署审查](2026-11-06-frontend-deploy-review.md)

---

## 0. 总体结论

两份文档**不能直接作为后续开发的状态基线**，但仍可作为问题域清单、依赖排序和回归场景来源。

| 指标 | 结果 |
| --- | --- |
| todo 中标注“已修复”的 R 条目 | 27 项 |
| 其中按 HEAD 代码并未完成或只是部分完成 | **14 项（R02、R05、R12、R13、R15、R20、R22、R25、R26、R29、R32、R33、R34、R35）** |
| 状态错误比例 | **14/35 = 40%，超过三分之一** |
| 报告 §4 逐节状态与代码现状不符 | **29/35 节**（仅 R01、R06、R08、R14、R30、R31 的开放状态仍成立） |
| 两份文档之间状态互相矛盾 | **27 项**：报告写“待处理/待验证”，todo 同一编号写“已修复”；另有 R09、R23 的进度描述也不一致 |
| 仍可继续沿用的结论 | R01、R06、R08、R14、R30、R31 及 F01/F03/F05/F06/F07/F09/F15/F16 的能力缺口判断 |

最关键的三类问题：

1. **状态错误集中且有高风险后果**：R13 被标“已修复”，但支付事件仍有 3 个 P0；R20 被标“已修复”，但商品详情页在 HEAD 上运行即抛 ReferenceError；R02/R05/R34/R35 把部分实现写成完成。
2. **报告正文从未回填状态**：§3 索引与 §4 逐节仍以 2026-11-04 的“待处理/待验证”呈现，而 todo 已把它们改成“已修复”，读者无法从报告判断真实进度。
3. **证据行号大面积漂移**：R03/R04/R05/R07/R09/R10/R12/R17/R18/R19/R20/R21/R23/R24/R26/R28/R29/R32 的关键行号已指向别的代码或已删除代码。

---

## 1. 价值评估

### 1.1 diagnostic 报告

**真正有用的部分**

| 部分 | 判断 | 说明 |
| --- | --- | --- |
| §1 审查基线与限制 | **仍应保留** | “静态确认≠动态复现/验收”“待专项验证不得升级为已复现漏洞”的方法论正确，且与本轮约束一致。 |
| §2 已有整改应保留表 | **约 9/10 仍成立** | Mock 付款已移除、建单幂等、预占流水、token 分离、服务端退出、公开店铺白名单、Redis 原子限流、唯一 schema 入口等均可回源码确认；仅“Sprint22 待人工验证”已由 `5686f95` 改变。 |
| R13 的问题框架 | **方向正确** | “到账事实与本地履约未解耦、支付事件未建模”仍是真实支付接入的阻断点；只是“未建模”在 HEAD 已变成“模型存在但实现有 P0”，详见第 2 节。 |
| F01/F03/F05/F06/F07/F09/F15/F16 | **仍成立** | 无渠道网关/验签通知/主动查单、无分账结算流水、无退款服务、投诉仅模型、收货地址未落库、对账无生成服务、无通知发送、物流仅单号。 |
| D/F 归属与依赖说明 | **可继续用** | R05/R25 同属卡密资产迁移、R10–R13 同属预占/支付生命周期等判断正确。 |

**已过期或有误导的部分**

| 部分 | 判断 | 说明 |
| --- | --- | --- |
| §3 索引的“当前问题”与 §4 逐节状态 | **不可信** | 全部仍是 2026-11-04 的“待处理/待验证”，至少 20 项在 HEAD 已被后续提交改变。 |
| R03/R04/R07/R09/R10/R11/R12/R15/R17/R18/R19/R20/R21/R23/R24/R25/R26/R27/R28/R29/R32/R33/R34 的具体证据 | **已失效或部分失效** | 行号与描述指向旧实现；例如 CORS 已允许 `X-Reservation-Token`，年卡已有 3 张种子卡密，类目新增已清空 ID，收银台已不再 `|| 1`。 |
| R02 的“员工可读取卡密/导入/作废/改价” | **事实已变** | 卡密查看、导入、作废、商品创建/编辑、推单均已限制为 `MERCHANT_ADMIN/SUPERADMIN`；但员工仍可通过 `/auth/me`、`/merchant/profile` 读取店主身份证/银行卡，问题缩小但未消失。 |
| R05 的“未见字段加密、脱敏存取服务” | **已失效** | 已实现 AES-256-GCM 与指纹；仍保留默认密钥回退、种子明文、存量未迁移。 |
| R18 的“年卡 SKU 库存为 3 却无对应种子卡密” | **事实错误（现状）** | schema 已注入 3 张年卡（ID 6–8）。 |
| R31 的“Windows 备份可能假成功或产生错误编码” | **代码已改，结论仍待验证** | PowerShell 已检查 `$LASTEXITCODE` 并使用 UTF-8；但 Windows 路径/编码矩阵仍未执行，保留“待专项验证”合理。 |

**可信度评分（针对“作为后续开发基线”）**

- 问题空间与架构方向：7/10。
- 当前状态与完成度：3/10。
- 证据可追溯性（行号/描述）：4/10。
- 结论：**只能作为“问题地图”，不能作为“进度表”**。

### 1.2 remediation todo

**真正有用的部分**

| 部分 | 判断 | 说明 |
| --- | --- | --- |
| §2 批次 A0/A1/A2/B1/B2/B3/C1/C2/V1 | **仍可作为排序参考** | 依赖关系（先封禁/费率覆盖，再价格/种子，再发布/备份，最后真实交易）与当前 P0/P1 分布仍然吻合。 |
| §4 每项最小回归场景 | **价值最高、可直接复用** | 例如 R13 的“重复/乱序/错金额/错商户、到账后崩溃”、R35 的“2^53 边界”、R29 的“缺端/SQL 失败阻断”等，仍然覆盖了本轮新发现的核心风险。 |
| §5 F01–F20 能力表 | **大部分仍成立** | “有表≠有功能”的判断正确；F07 的地址未保存、F09 的账本仅模型、F15 的通知缺失均可在 HEAD 复核。 |
| §1 状态定义与“不催验收”纪律 | **规则本身正确** | 问题在于实际填写违反了这条规则。 |

**已过期或有误导的部分**

| 部分 | 判断 | 说明 |
| --- | --- | --- |
| §3 状态列 | **不可信** | 14 项“已修复”与代码不符；且多数未按 §1 要求写成“已实现·待人工验证”。 |
| “已修复”与“已实现·待人工验证”混用 | **违反自身规则** | §1 明确“不能凭文档描述使用实现中”“已验证需用户反馈”；§3 却直接把 27 项写成“已修复”。 |
| R13 “已修复” | **最危险的一条** | 掩盖了支付事件的 3 个 P0（见 2.1）。 |
| R20 “已修复” | **反向回归** | 修复引入了商品详情页的 TDZ 崩溃。 |
| 对 diagnostic 的链接 | **继承其行号漂移** | 读者按链接跳转后看到的证据多数已不对应当前代码。 |

**可信度评分**

- 工作排序与回归设计：8/10。
- 状态与完成度：4/10。
- 结论：**可作为“待办模板/验收清单”，不能作为“已完成证明”**。

---

## 2. 逐条纠错

分类定义：
- **事实错误**：文档对代码行为的描述本身与 HEAD 不符。
- **状态错误**：标注的状态（已修复/待处理/待专项验证）与 HEAD 现状不符。
- **范围错误**：把部分实现写成完整实现，或把实现细化写成已确认需求。
- **已失效**：当时可能正确，后续提交改动后不再成立。
- **遗漏**：代码存在明显问题，但两份文档都未收录（见第 3 节）。

### 2.1 高风险状态错误（必须先纠正）

| 编号 | 原文怎么说 | HEAD 实际是什么 | 分类 | 依据 |
| --- | --- | --- | --- | --- |
| **R13** | todo：`已修复`；报告：`待处理`，理由是“支付事件未建模”。 | 事件模型已存在，但仍有 3 个 P0：(1) 订单保存后立即用 `REQUIRES_NEW` 记 `SUCCESS`，外层事务回滚时事件已提交；(2) 重放只回读订单状态并直接返回成功，不补履约；(3) 失败分支在业务事务内写 `FULFILLMENT_FAILED` 但不回滚，异常时事件与订单不一致。**当前 `notifyPaymentSuccess` 无 HTTP 入口，属接入真实渠道前必须修复的潜伏缺陷，不是已可利用漏洞。** | **状态错误 + 范围错误** | [PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L232-L234)、[PaymentEventRecorder.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L32-L41)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L58-L69) |
| **R20** | todo：`已修复`；报告：`待处理`（同组件切路由可能展示旧商品/旧账单）。 | `ProductDetail.vue` 的 `watch(..., { immediate: true })` 在 setup 阶段同步执行 `loadProduct`，其中访问了尚未初始化的 `const peerPayResult`（声明在更下方），触发 TDZ `ReferenceError`，详情页整体不可用；“旧资源”问题未真正解决，反而新增 P0 回归。 | **状态错误 + 遗漏（新回归）** | [ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L446-L474)、[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L516) |
| **R02** | todo：`已修复`；报告：员工可读取卡密、导入/作废、改价、履约、更改收款码。 | 卡密查看/导入/作废（`ProductController` 237/280/289）、商品创建/编辑（135/213）、库存调整（`InventoryAdjustmentController` 22）已限 `MERCHANT_ADMIN/SUPERADMIN`；但**代客推单（`OrderController` 352-362）与实物发货（318-349）仍只校验商户归属，员工可操作**；`/auth/me`、`GET /merchant/profile` 仍向员工返回身份证号、银行卡、结算账户。 | **状态错误（主）+ 已失效（报告关于改价/收款码的指控）** | [SaTokenConfigure.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaTokenConfigure.java#L33)、[AuthController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java#L124-L128)、[MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L200-L216)、[OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L318-L362)、[ProductController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L234-L240) |
| **R05** | todo：`已修复`；报告：卡密多份明文，未见字段加密/脱敏存取服务。 | 新导入卡密已 AES-256-GCM 加密并写指纹；但默认密钥硬编码回退、schema 种子卡密仍为明文且无指纹、存量数据无迁移，备份中仍可能含可直接使用的卡密。 | **状态错误 + 范围错误（报告已失效）** | [CardCipherService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L63-L68)、[CardAssetService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java#L85-L87)、[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L326-L334) |
| **R12** | todo：`已修复`；报告：无预占意向单缺统一后台关闭。 | 已新增 `expireUnpaidOrders`，按 `createdAt+15min` 关闭普通未付单；但数据库层 `markOrderPaidIfUnpaid` 仍允许 `lock_expire_time IS NULL` 的订单在扫描前到账并标记 `PAID`，“15 分钟规则”只在任务运行后成立。 | **状态错误（主）+ 范围错误** | [ReservationExpiryJob.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L60-L69)、[TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L54-L60) |
| **R34** | todo：`已修复`。 | 参数类型错误、缺头、缺参、405、415、404 已正确映射；但 `BusinessException` 仍未设置 HTTP 状态，业务失败继续返回 200。 | **状态错误 + 范围错误** | [GlobalExceptionHandler.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java#L25-L79)、[GlobalExceptionHandler.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java#L81-L85) |
| **R35** | todo：`已修复`。 | 只有 `StrictIdDeserializer` 约束入参；`config/` 下没有 Jackson 配置类，实体/公开视图的 `Long` ID 仍以 JSON number 输出，超过 2^53 仍会丢精度。 | **状态错误** | [StrictIdDeserializer.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/StrictIdDeserializer.java)、[PublicProductView.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/dto/PublicProductView.java#L13-L19)、`config/` 无 Jackson 配置类 |
| **R33** | todo：`已修复`。 | 后端已有 25 个测试类、前端已有 shared 规则用例，仓库仍无 CI 工作流，也没有任何执行结果/环境记录；按文档自己的定义不能标“已修复”。 | **状态错误** | `shop-b2b2c-backend/src/test/java/**`（25 个测试类）、仓库无 `.github/workflows/*` |
| **R22** | todo：`已修复`；报告：担保认证、虚构原价、隐藏商品与开通提示不真实。 | `1.38` 划线价与“官方合规认证”已移除；但“持牌官方专户存管”“微信/支付宝官方二级直结”“持牌专户担保·零二清”“已自动秒发”等未验证承诺仍在首页/详情/页脚展示。 | **状态错误 + 范围错误（部分已失效）** | [Home.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/Home.vue#L99)、[Home.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/Home.vue#L141)、[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L149-L150)、[App.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/App.vue#L17) |

### 2.2 已失效条目（当时可能成立，HEAD 已改变）

| 编号 | 原文怎么说 | HEAD 实际是什么 | 分类 | 依据 |
| --- | --- | --- | --- | --- |
| **R03** | 报告：CORS 未允许 `X-Reservation-Token`，跨域取消预占预检失败；todo：已修复。 | CORS 已精确允许该头，预检可通过。 | **已失效**（报告）；todo 正确 | [SaTokenConfigure.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaTokenConfigure.java#L52) |
| **R04** | 报告：管理列表批量返回 `queryToken`、卡密正文、服务凭证。 | 订单列表/详情已清空 `queryToken`、`posterUrl`，卡密正文按角色脱敏，并设置 `no-store`；仅“卡密库存列表”对店长返回解密正文（属授权资产查询，非订单列表）。 | **已失效（订单部分）**；卡密库仍保留暴露面 | [OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L224-L243)、[OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L266-L282)、[ProductController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L255-L260) |
| **R07** | 报告：公开建单、预占、海报无应用成本预算。 | 已实现 `TradeCostLimiter`：建单按 IP 60/5min、联系方式 30/5min，预占按 IP/令牌，海报按 IP/令牌；Redis Lua 原子计数，超限 429。 | **已失效**；残留为 IP 桶受 R06 影响、无累计存储配额 | [TradeCostLimiter.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/TradeCostLimiter.java#L73-L119)、[application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L80-L88) |
| **R09** | 报告：整实体保存可能恢复旧封禁状态或费率。 | 已改为 `updateProfileOnly`、`updatePaymentChannelOnly`、`updateServiceFeeOnly` 定向列更新，不再整实体回写。 | **已失效**；todo 的“已实现·待人工验证”准确 | [MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L218-L234)、[MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L185-L196) |
| **R10** | 报告：预占提交后丢响应无法恢复 ownerToken。 | 已实现同尝试恢复：订单处于 `RESERVED_LOCK` 且预占仍 `HELD` 时，持相同 ownerToken 返回原预占，不重复扣库存。 | **已失效**；残留为 ownerToken 可由客户端自选（P2） | [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L376-L393) |
| **R11** | 报告：持续失败的头 100 条会饿死后续到期预占。 | 已改为 ID 游标 + 单条异常捕获，失败记录也会前移游标；批量仍为 100。 | **已失效**；残留为失败记录不重试/无追踪 | [ReservationExpiryJob.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L38-L58) |
| **R15** | 报告：主表约束缺失（SKU 非负、金额、预占数量）、无版本迁移。 | 已补 `chk_sku_stock`、`chk_sku_price`、`chk_order_amounts`、`chk_item_quantity`、`chk_reservation_quantity` 等 CHECK 与唯一索引；仍无 ALTER 版本迁移，schema 只建不改。 | **部分已失效**；todo“已修复”为范围错误 | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L111-L114)、[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L169)、[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L189)、[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L261) |
| **R16** | 报告：批量折扣只在前端，展示价与订单价不同；todo：已修复。 | 前端按数量打折的逻辑已删除（详情按 SKU 价、列表按 `minPrice` 展示）；后端建单按 SKU 单价计算，展示与订单口径一致。 | **已失效**；todo 正确 | [ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L428-L433)、[PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java)（无数量折扣分支） |
| **R17** | 报告：创建与编辑商品校验及最低价语义分裂。 | 创建接口已补标题/主图/类目/SKU 数量、SKU 编码长度、价格、初始库存、规格 JSON 校验，并由 SKU 计算 `minPrice`；残留为创建默认 `status=1`（直接上架）。 | **已失效（主要部分）**；残留仍在 | [ProductController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L141-L181) |
| **R18** | 报告：年卡 SKU 库存为 3 却无对应种子卡密。 | schema 已为 `sku_id=2` 注入 3 张年卡（ID 6–8），库存与卡密一致。 | **已失效/事实错误（现状）** | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L325-L334) |
| **R19** | 报告：新增类目接口携 ID 可修改既有类目。 | 新增接口已 `setId(null)` 并校验名称，携 ID 不再覆盖；父级仅做默认值/非负校验，未校验存在性。 | **已失效** | [CategoryController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/CategoryController.java#L33-L46) |
| **R21** | 报告：自己付款跳转依赖响应中不存在的 `productId`，回退到 `1`。 | 收银台已改用 `detail.productId` 且 `v-if` 保护，不再回退到商品 1。 | **已失效** | [PeerPayCashier.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/PeerPayCashier.vue#L180-L186) |
| **R23** | 报告：服务费免收截止日不能清空。 | 已支持 `clearFeeFreeUntil=true` 显式清空，null 保留旧值。 | **已失效**；todo 的“后续修订待人工验证”已落后于代码 | [MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L155-L175) |
| **R24** | 报告：全量列表、N+1 明细及内存统计残留。 | 已支持分页（默认 50、上限 200）、批量 `findByOrderSns` 消除 N+1、统计改为 SQL 投影；残留为 `trade_order` 无 `created_at` 索引，超过上限的历史订单需逐页翻。 | **已失效（主要部分）**；todo 基本正确 | [OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L203-L217)、[TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L110-L135)、[TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L137-L139) |
| **R27** | 报告：私密幂等快照与历史数据缺保留策略。 | 已新增 `IdempotencyPurgeJob`，默认保留 72h、每小时分批删除；但快照仍保存完整响应（含领取凭证），业务历史无归档。 | **已失效（保留策略已实现）**；范围仍需收窄 | [IdempotencyPurgeJob.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/IdempotencyPurgeJob.java#L32-L56) |
| **R28** | 报告：腾讯构建仍可缺端成功、依赖与 latest 不稳。 | 腾讯 `03-build.sh` 已改为强制三端产物、`npm ci`、唯一 jar、原子切换 latest，并在失败时写 `BUILD_FAILED`。 | **已失效**；todo 正确（但无执行证据） | [03-build.sh](file:///e:/shop-b2b2c/deploy/tencent/server/03-build.sh)（三端产物与 `npm ci` 段） |
| **R29** | 报告：部署先覆盖线上且吞 SQL 错误，失败无整套恢复。 | 阿里云/腾讯 `04-deploy.sh` 已在切换前做产物预检（缺端/失败标记/空 jar/缺 schema 直接 `die`），SQL 失败也立即中止；残留为迁移前不备份数据库、schema 仍无回滚方案。 | **已失效（主流程）**；todo“已修复”为范围错误 | [04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L43-L83)、[04-deploy.sh](file:///e:/shop-b2b2c/deploy/tencent/server/04-deploy.sh#L43-L83) |
| **R32** | 报告：备份旧默认目标、参数拆分及恢复证据不足。 | PowerShell 路径已改为参数数组并检查退出码；Bash 仍把整条 `mysqldump` 拼成字符串并含口令，遇空格路径会拆参，口令仍出现在进程参数；保留期清理已实现。 | **部分已失效**；todo“已修复”为范围错误 | [backup_mysql.sh](file:///e:/shop-b2b2c/deploy/scripts/backup_mysql.sh#L56-L86)、[backup_mysql.ps1](file:///e:/shop-b2b2c/deploy/scripts/backup_mysql.ps1#L79-L108) |

### 2.3 范围错误（部分实现被写成完成）

| 编号 | 原文怎么说 | HEAD 实际是什么 | 分类 | 依据 |
| --- | --- | --- | --- | --- |
| **R25** | todo：`已修复`；报告：导入每次全扫全店历史明文且锁商户。 | 已增加指纹索引优先查询；但 `findExistingFingerprint` 未命中后仍全店 `historyContents` 全量读入（且同一查询调用两次），仍持有商户行锁；`card_fingerprint` 为 NULL 的存量/加密行不会被唯一索引覆盖。 | **范围错误** | [CardAssetService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java#L63-L76)、[CardAssetMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/CardAssetMapper.java#L12-L21)、[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L123-L129) |
| **R26** | todo：`已修复`；报告：海报事务渲染、公开大字段与幂等副本仍在。 | 列表 SQL 已排除 `posterUrl`，但 `createPeerPay` 仍在 `@Transactional` 方法内生成并渲染海报（`L138-L141`），慢渲染仍占用建单事务/连接。 | **范围错误** | [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L138-L141) |
| **R29** | 同 2.2 | 迁移前无数据库备份；失败时只能恢复 jar/前端，不能恢复已执行的 schema 副作用。 | **范围错误** | 同 2.2 依据 |
| **R02/R05/R22** | 同 2.1 | 均为“局部修复被写成整体修复”。 | **范围错误** | 同 2.1 依据 |

### 2.4 复核后基本无误、可沿用的条目

| 编号 | 结论 | 依据 |
| --- | --- | --- |
| R01 | 仍成立：数据源口令字面回退、schema 固定演示账号/种子、管理端登录页预填演示口令。 | `application.yml`（datasource 默认口令）、[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L279-L301)、[Login.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/login/Login.vue#L30-L34) |
| R06 | 仍成立：应用仍取 `getRemoteAddr()`，未见可信代理/`forward-headers-strategy` 配置。 | [AuthController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java#L54)、[OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L69) |
| R08 | 仍成立：Sa-Token 30 天有效期、`active-timeout: -1`，依赖扫描无证据。 | [application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L58-L59) |
| R14 | 仍成立：只做了静态核对，无 MySQL 并发/锁序证据。 | 本轮未执行任何并发测试 |
| R30 | 仍成立：systemd `User=root`；证书签发失败仅告警并降级 HTTP。 | [04-deploy.sh](file:///e:/shop-b2b2c/deploy/tencent/server/04-deploy.sh#L196-L199)、[04-deploy.sh](file:///e:/shop-b2b2c/deploy/tencent/server/04-deploy.sh#L258-L267)、[04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L202) |
| R31 | 状态可保留“待专项验证”：代码已检查退出码/UTF-8，但 Windows 路径与编码矩阵仍未执行。 | [backup_mysql.ps1](file:///e:/shop-b2b2c/deploy/scripts/backup_mysql.ps1#L79-L108) |

---

## 3. 两份文档共同遗漏的问题（新增发现）

以下条目在两份 2026-11-04 文档中均未收录，或只被笼统归入 R13/R25 而未描述具体缺陷；均已在 HEAD 源码确认。

| 序号 | 级别 | 问题 | 证据 |
| --- | --- | --- | --- |
| N1 | P0 | 支付事件在外层事务提交前记 `SUCCESS`，提交失败后重放直接返回成功，订单永久 `UNPAID`。 | [PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L232-L234)、[PaymentEventRecorder.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L32-L41)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L58-L69) |
| N2 | P0 | 到账金额校验用订单自身金额回填，校验恒真；少付/币种不符无法拦截。 | [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L495-L505)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L87)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L105-L109) |
| N3 | P0 | 订单已 `PAID` 时在记录事件前提前返回，第二笔付款无事件、无对账、无退款线索。 | [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L486-L488) |
| N4 | P0 | 商品详情页 setup 阶段 TDZ 崩溃，页面不可用（由 R20 修复引入）。 | [ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L446-L474)、[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L516) |
| N5 | P1 | 员工可读取店主身份证号、银行卡与结算账户（`/auth/me`、`/merchant/profile`）。 | [AuthController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java#L124-L128)、[MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L200-L216) |
| N6 | P1 | `uk_sku_code` 为全局唯一，跨商户复用同一 SKU 编码会触发唯一键冲突并返回 500，且无友好错误处理。 | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L111)、[ProductController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L183-L193) |
| N7 | P1 | 商品下架不检查 HELD 预占；下架后已预占订单在履约阶段被 `requirePurchasable` 拒绝，付款成功但交付失败。 | [ProductEditingService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductEditingService.java#L72-L93)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L165-L171) |
| N8 | P1 | 商城前端从不调用 `/pre-lock` 与 `/cancel-lock`，预占只存在于后端接口与测试；真实收银台付款路径没有预占保护。 | `shop-storefront-vue/src` 无 `pre-lock/cancel-lock` 调用；接口见 [PeerPayController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java#L61-L75) |
| N9 | P1 | 阿里云 `02-init-infra.sh` 生成的 `.env` 缺 `SPRING_DATASOURCE_USERNAME/PASSWORD` 与 `APP_FRONTEND_STOREFRONT_URL`，生产启动会回退到 application.yml 默认口令，商城链接回退 `localhost:3002`；同时 Redis 口令有硬编码默认值。 | [02-init-infra.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh#L33-L55)、[application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml) |
| N10 | P2 | 订单 `payment_channel` 在建单时写入 `A/B/C` 收款模式，付款成功后又被真实渠道覆盖，同一列承载两种语义，对账/退款按渠道筛选会混入模式值。 | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L30)、[PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L108)、[PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L212) |
| N11 | P2 | 卡密库页面直接展示并允许复制卡密明文；后端已按角色授权并加密存储，但产品层仍把明文作为列表字段，扩大肩窥/截图泄露面。 | [Cards.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Cards.vue#L21-L23)、[ProductController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L255-L260) |
| N12 | P2 | 同一幂等键并发请求时依赖数据库唯一键兜底，`DuplicateKeyException` 未映射，客户端收到 500 而非可重试的 409/幂等结果。 | [GlobalExceptionHandler.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java)（无 `DuplicateKeyException` 处理）；幂等表见 [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql) |

补充说明：
- N1–N3 属 R13 的子缺陷，但原文档把 R13 描述为“事件未建模”，与 HEAD 的“模型已存在但实现错误”不同，因此必须单列。
- N4 是后续修复引入的新回归，不能算“原文档漏掉的老问题”，但它使 R20 的“已修复”结论直接失效。

---

## 4. 使用建议

1. **不要再引用 2026-11-04 两份文档的状态列**；需要进度时使用 [纠正后基线](2026-11-06-diagnostic-baseline-revised.md)。
2. **原文档仍可用于**：问题背景、D/F 归属、审查方法与边界声明、todo §4 回归场景。
3. **优先修复顺序**（详细依据见基线文档）：
   - 先修 N4（详情页崩溃）——它让商城主链路当前不可用；
   - 再修 N1–N3（支付事件/金额/重复支付）——虽未接渠道，但一旦接入立即造成资金/资产不一致；
   - 再修 N5/N7/N6（PII、下架竞态、跨店 SKU 冲突）；
   - 最后按原批次处理 R01/R06/R30 等环境项与 R35/R33 等契约/验证项。
4. **文档维护要求**：今后每完成一项，必须在状态列写“已实现·待人工验证”，并在本文件或基线文档记录“证据文件:行号 + 未验证项”，禁止再出现“已修复”但无执行证据的写法。
5. 本文与基线文档只描述静态代码；所有“修复”仍需用户按 AGENTS.md 的命令手动验证。
