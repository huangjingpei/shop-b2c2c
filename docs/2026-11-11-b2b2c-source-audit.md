<!--
用途：以当前 Git 快照复核 B2B2C 历史问题与现有代码，提供有证据的开发顺序。
维护注意：状态取自源码观察，验收状态由主台账管理。修改实现后同步任务卡、编号映射及验收证据。引用的行号对应审计基线，后续应按方法名复查。禁止写入实际凭据、卡密和个人资料。
-->
# B2B2C 全工程源码复诊与开发路线（2026-11-11）

基线：`54c2a14`（最新业务代码 `5288e29`）。复核范围包括 Spring Boot 后端、MyBatis/SQL、管理端和商城 Vue、shared 规则、构建与 CI、两云部署、网关及备份。旧台账和设计用于定位问题；Sprint 文档与历史验证记录是交付历史，不能代替当前路径。复审基线为文档提交 `afd9d95`，对照工作区内 11-10 全景统计及 11-11 开发清单，并核对当前业务源码、SQL 和两云部署脚本。DOCX/PDF 同名规格用仓库内 Markdown 对照；本轮没有做二进制逐版比对、线上探测、依赖 CVE 枚举、真实数据库或支付验收。代码定位以文件名和方法为准。

**下一批优先处理凭据和真实双人审批，再收口入驻防滥用、迁移就绪与库存并发。支付渠道未开通期间保留总闸关闭，先完成本地支付尝试、补偿任务和可重放的渠道契约。** 现有 CAS、订单快照和幂等记录应复用；真实联调只解决外部契约和环境证据，不能替代本地状态机的修复。

文档入口：[主台账 D/F](B2B2C_项目缺陷与未实现功能清单_2026-11-03.md) · [旧复诊 R](2026-11-04-project-diagnostic-report.md) · [修复卡 T](2026-11-06-remediation-plan.md) · [开发缺口 G](2026-11-06-dev-gap-log.md) · [财务基线](2026-11-06-financial-baseline.md) · [依赖与边界](问题依赖与修复边界.md) · [最新适配器交付](SPRINT_59_微信电商收付通真实适配器与M6联调准备.md)。

状态口径：**有实现**＝找到针对原问题的实现；**部分**＝已有措施但存在同域残项；**缺口**＝源码有明确缺边或未见对应实现；**环境**＝行为或部署结果待专项验证；**候选**＝范围由业务决定。以下均为静态核对，不覆盖主台账原验收状态。

- [10 项优先任务和实施步骤](#audit-details)
- [D01–D39 缺陷任务与回归要求](#defect-details)
- [F01–F20 功能边界与交付要求](#feature-details)
- [财务、对账和支付执行清单](#specialist-details)
- [全景统计、开发清单与代码架构交叉审校](#landscape-cross-review)
- [本地异议复核与开发依赖修订](#local-review-findings)
- [退款重试金额不一致](#task-aud-11)

## 1. 本轮新增证据与优先开发项

| 编号、来源 | 触发与代码证据 | 后续任务及应写回归 |
| --- | --- | --- |
| [AUD-01](#task-aud-01) · P0 · 新证据，F14/F10/F03、G31、T34 | [`FinanceApprovalGuard.verify`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/FinanceApprovalGuard.java#L55) 比较操作人与请求指定的复核人 ID；[`SaasFeeBillService.confirmPayment`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/SaasFeeBillService.java#L148) 与 [`SettlementService.confirm`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/SettlementService.java#L161) 使用该结果及账号资质，未要求第二位登录人实际签署同一业务动作。单个超管可以选另一个有效超管并完成该确认；日志的 DUAL_APPROVAL 不证明两人参与。入驻另有分阶段审核，勿据此推论所有流程同样受影响。 | 提交与批准分两次会话，当前登录人作为批准人；保存对象版本和金额快照，用 CAS 认领审批；创建人、代填他人、过期/金额变化、重复审批均拒绝；双人合法流程与开发单人模式分别验证。 |
| [AUD-02](#task-aud-02) · P0 上线前 · Sprint 59 回归，F01/F02/F03 | [`PaymentGatewayService.handlePlatformCallback`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L115) 按“当前有效 BOUND 账号”比对二级商户，而非预下单时的账号快照；同店换绑、过期或解绑后，旧单合法回调可能被拒。该路径先履约，再在 `prepareSettlementBestEffort` 尝试写结算，失败仍可 ACK；注释指向 M5，未定位到确定性重建缺失台账的消费者。 | 在本地支付尝试持久化 channel、账号标识、二级商户号、金额币种和到期时间；回调按尝试核验。到账事实与待结算任务一起提交，消费者幂等建账。测换绑后旧单回调、错商户、ACK 前后故障、已到账但无结算记录的恢复。 |
| [AUD-03](#task-aud-03) · P0 上线前 · 已知缺口补实，F01/F03/F05/F08 | [`PaymentGatewayService.createPayment`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L59) 平台预下单已有分支，但 `queryPayment`、`queryChannelPayment` 和 `closePaymentBestEffort` 仍走直收账号/注册表。[`OrderCancellationService.cancelByBuyer`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderCancellationService.java#L42) 关单在提交后触发；进程可在本地提交与首次回执之间退出，[`PaymentCloseRetryService.retryPending`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentCloseRetryService.java#L37) 依赖可见的回执候选。 | 按尝试路由查单、关单及退款；关单意图和取消同事务落库，独立认领、退避和人工超限。模拟“本地已关、首次调用未开始”“渠道成功、响应丢失”“两个实例同时处理”。保留 SKIPPED 不自动重试的既定范围。 |
| [AUD-04](#task-aud-04) · P1 · 新证据，F02/F14、R06/R07 | [`PublicOnboardingController.create`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PublicOnboardingController.java#L65) 创建草稿未接交易或独立入驻频控；`clientIp` 直接取 XFF 首段，[`MerchantOnboardingGuideController.clientIp`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantOnboardingGuideController.java#L124) 同类；协议证据与已存在的 [`ClientIpResolver.resolve`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ClientIpResolver.java#L53) 信任模型不一致。附件 [`OnboardingAttachmentService.store`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OnboardingAttachmentService.java#L62) 有逐文件上限、类型判断和随机路径；应用/主体/来源累计预算、存储保留期及共享存储恢复仍缺可见闭环。 | 复用可信代理解析，按来源、申请、主体设置创建和上传预算；先做孤儿清理、私有存储与恢复演练，规模需要时再换对象存储。测伪造 XFF、多文件耗尽、跨申请附件、失败回收和多实例读取。 |
| [AUD-05](#task-aud-05) · P0 生产门槛 · 持续风险，D21/R01/R05、G09/G12/G43 | `application-prod.yml` 与 [`AdminBootstrap`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/AdminBootstrap.java#L29) 已做生产/演示隔离，`demo-seed.sql` 已从结构 SQL 分离；[`CardCipherService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L22) 构造仍支持空配置回退且只有固定密文版本；跟踪的云 `env.sh` 需独立按键核验和轮换，Git 无法证明历史凭据失效。 | 优先生产 fail-closed，模板与实际注入分开；先识别旧密钥，再双读单写、批次迁移、轮换和恢复。测试缺配置拒绝、旧新密文可读、错误密钥失败、发卡和补发不泄露。 |
| [AUD-06](#task-aud-06) · P0 发布门槛 · 延续，D31/D37/R15、G38–G41 | [`SchemaMigrationRunner.run`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java#L53) 在 ApplicationRunner 执行、按版本查后执行并记账；[`ReadinessService.check`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReadinessService.java#L51) 只探 DB/Redis，未共享迁移完成状态。多实例可能同时尝试迁移；MySQL DDL 隐式提交，失败不能假设全版本回滚。实际目录为 V1–V23，旧清单的 V1–V19 不再是完整边界。 | 单迁移者、就绪与迁移结果绑定，迁移期间不收流量；迁移版本和校验和、失败续跑/前滚、备份恢复写进发布手册。隔离 MySQL 演练双实例、重复启动、DDL 中途失败和旧库排序规则。 |
| [AUD-07](#task-aud-07) · P1 · 文档偏差与待验证，D10/D20、R10/R11/R12 | [`PeerPayServiceImpl.getPeerPayDetail`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L299) 未见旧文所述整行写回；`expireIfNecessary` 由预占和 job 调用，[`ReservationExpiryJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L35) 有分页游标和普通未付单扫描。[`BuyerOrderQueryService.lookup`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/BuyerOrderQueryService.java#L19) 已校验独立 token，[`OrderQueryLimiter.check`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderQueryLimiter.java#L23) 规范化单号，Redis 异常返回 503。旧清单 B21/B12 对应的旧描述不宜直接按原修法开工；预占入口的独立回滚问题见 AUD-10。 | 改为读路径无写回和过期竞争验证；验证 Redis 故障返回 503；当前每订单桶在凭证验证前扣减，评估已知单号被频繁请求时合法买家被连带限流，并验证来源/订单预算与合法凭证，避免重新设计领取凭证。 |
| [AUD-08](#task-aud-08) · P1 · 规模和账务残项，F09、G21/G45/G46 | [`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68) 已区分 PARTIAL、渠道缺流水、业务日及非成功事件；[`AdminSettlementController.paymentExceptions`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AdminSettlementController.java#L87) 用 offset；对账支付事件按商户/时间扫描，`schema.sql` 的索引应以实际过滤和排序验证，不能依据 H2 小数据判断。 | 先定义到账业务日/导入完整水位和差异关闭依据，再按 MySQL EXPLAIN 与样本量优化；文件重放不复制差异，延迟/零流水/相邻业务日/部分导入不能静默结案。 |
| [AUD-09](#task-aud-09) · P0 上线前 · Sprint 59 渠道契约不符，F03/F05 | [`WeChatPlatformCashierChannelAdapter.createPayment`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/WeChatPlatformCashierChannelAdapter.java#L95) 未提交分账标识；当前 Native 路径和服务商字段组合也需与目标产品逐项核对。其 `returnSplit` 将同一 `receiverChannelId` 传给 `sub_mchid` 和 `return_mchid`，并以订单号代分账单号。按[微信回退接口](https://pay.wechatpay.cn/doc/v3/partner/4012477737)，二级出资方和原分账接收方是不同业务字段，回退应引用原分账单；平台抽佣时二者通常是不同主体。 | 在尝试快照保存渠道产品；先按目标 Native 产品契约确定路径、字段和分账标识，在真实分账回执保存原分账单号与出资/接收方。用不同商户号、同订单多次分账、部分回退及重复回退做请求契约用例；签约前保持开关关闭。 |
| [AUD-10](#task-aud-10) · P1 · 历史问题仍存在，D10/F08、并发专项 P2-1 | [`PeerPayServiceImpl.preLockInventory`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L412) 在同一回滚事务中先执行 [`expireIfNecessary`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L526)（释放、关单、注册提交后动作），随后对已经 CLOSED 的订单抛异常；这条路径的释放和关闭随事务回滚。定时任务可兜底，但过期请求不会主动完成释放，不能用 D10 的详情读路径改造覆盖此问题。 | 预占事务遇过期仅拒绝，由独立事务完成过期（或提交明确失败结果后由控制器映射错误）；复用定时服务，不套相同订单锁的嵌套事务。验证过期订单释放一次、合法预占不受影响及支付竞争不覆盖已付款状态。 |

建议把 **AUD-01、AUD-04、AUD-05** 作为无渠道凭据的安全批；随后 **AUD-06、AUD-07、AUD-10** 与真实 MySQL 竞争测试同批，**AUD-08** 归 F09；**AUD-02/03/09** 本地部分可先落地，渠道验收单列，开通后再启用。凡本文建议新增约束，先处理存量异常与合法状态的兼容，再加约束。

## 2. 主台账逐项对照（D01–D39、F01–F20）

| 编号 | 当前状态 | 当前源码证据与开发边界 |
| --- | --- | --- |
| [D01](#task-d01) | 已有实现，回归待执行 | 有实现：[`OrderController.checkout`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L99) 建未付单，到账走 [`PaymentEventServiceImpl.processPaymentEvent`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L67)；真实渠道见 F01。 |
| [D02](#task-d02) | 旧公开模拟路由已移除，配置验证待执行 | 有实现：[`PeerPayController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java#L37) 无旧 mockPay，[`PaymentGatewayController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PaymentGatewayController.java#L37) 独立验签入口；验证生产 profile 对所有替代支付入口。 |
| [D03](#task-d03) | 已有守卫，三入口待回归 | 有实现：[`TradeGuards`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/TradeGuards.java#L14) 与 [`OrderController.checkout`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L99)、[`PeerPayServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L35) 建单校验 SKU、商品和商家关系；导卡边界另见 D16。 |
| [D04](#task-d04) | 已用条件写，真实并发待验证 | 有实现：[`ProductSkuMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/ProductSkuMapper.java#L17) 条件扣减/预占及回退，支付以影响行数决定；MySQL 同 SKU 热点压力待验证。 |
| [D05](#task-d05) | 已有条件认领，竞争待验证 | 有实现：[`ProductVirtualCardMapper.claimCard`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/ProductVirtualCardMapper.java#L29) 条件认领，预占用 [`InventoryReservationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/InventoryReservationService.java#L21)；同一卡发给两订单和事务失败应做竞争测试。 |
| [D06](#task-d06) | 已有锁和状态迁移，外围状态仍需核对 | 有实现：[`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46) 锁订单、区分重放和第二笔付款，[`TradeOrderMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L32) 执行条件状态迁移；G22 不代表全事件 CAS 已齐。 |
| [D07](#task-d07) | 已有数据库 HELD，待一致性验证 | 有实现：[`InventoryReservationMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/InventoryReservationMapper.java#L16) 与 [`InventoryReservationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/InventoryReservationService.java#L21) 将 HELD 和库存/卡密纳入数据库；Redis 不做库存真源。 |
| [D08](#task-d08) | 入口已有状态检查，渠道补偿见 AUD-03 | 有实现：[`PeerPayServiceImpl.preLockInventory`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L400) 和 [`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46) 校验状态、截止时间；晚到事实保留，补偿归 F08。 |
| [D09](#task-d09) | 已有 ownerToken，恢复体验待验证 | 有实现：[`PeerPayServiceImpl.cancelPreLock`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L511) 要求 ownerToken；对照 [`InventoryReservationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/InventoryReservationService.java#L21) 条件释放和保留期。 |
| [D10](#task-d10) | 详情读写已拆分，预占请求回滚仍在 | 原读写覆盖未复现于源码：[`PeerPayServiceImpl.getPeerPayDetail`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L299) 对照 [`ReservationExpiryJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L35)，见 AUD-07。 |
| [D11](#task-d11) | 已有类型区分，回归待执行 | 有实现：[`TradeGuards`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/TradeGuards.java#L14) 区分卡密/人工服务，[`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46) 不应把缺卡视为另一商品类型；验合法与库存不足。 |
| [D12](#task-d12) | 已有交易守卫，读写并发待验证 | 有实现：[`ProductController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L34) 公开过滤，[`TradeGuards`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/TradeGuards.java#L14) 和 [`MerchantStoreGuard`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantStoreGuard.java#L22) 交易态拦截；下架与 HELD 竞争属 R14/T09。 |
| [D13](#task-d13) | 各入口有部分校验，覆盖待补齐 | 部分：[`ProductEditingService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductEditingService.java#L30) 有明确字段校验；[`ProductController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L34)、[`PublicOnboardingController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PublicOnboardingController.java#L43) 和 [`MerchantCollectionModeService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantCollectionModeService.java#L37) 各有独立入口。统一 DTO 边界与领域规则，逐项列出绕过路径后再改。 |
| [D14](#task-d14) | 已有唯一键和幂等记录，重试待验证 | 有实现：[`OrderIdempotencyService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderIdempotencyService.java#L33) 配合 [`IdempotencyMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/IdempotencyMapper.java#L13)，订单号唯一键在 schema；还要验证同键换参、响应丢失与过期重放。 |
| [D15](#task-d15) | 已有角色、归属及状态校验 | 有实现：[`OrderController.deliverOrder`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L459) 店铺归属、实物及履约状态检查；[`PeerPayServiceImpl.fulfillVirtualOrder`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L611) 分离人工交付。 |
| [D16](#task-d16) | 已有商家和 SKU 范围，待全入口验证 | 有实现：[`CardAssetService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java#L20) 与 [`CardImportBatchService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardImportBatchService.java#L15) 按商家和 SKU 约束；对跨商家读取、导入、导出和作废保留负向测试。 |
| [D17](#task-d17) | 已分商家和超管入口，待权限矩阵回归 | 有实现：[`ManagementGuards`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/ManagementGuards.java#L11)、[`SecurityUtils`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/SecurityUtils.java#L15) 和 [`ProductController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L34) 区分身份及租户；后台全局查询需单独角色路径。 |
| [D18](#task-d18) | 已有专用 DTO，新增字段待约束 | 有实现：[`PublicProductView`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/dto/PublicProductView.java#L12)、[`PublicStoreView`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/PublicStoreView.java#L11) 和 [`BuyerOrderView`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/dto/BuyerOrderView.java#L14) 投影；资质资料的新入口继续做字段授权。 |
| [D19](#task-d19) | 已拆分，泄露路径待回归 | 有实现：[`PeerPayServiceImpl.getPeerPayDetail`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L299) 返回付款摘要，[`BuyerOrderQueryService.lookup`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/BuyerOrderQueryService.java#L19) 独立领取；拒绝用分享 token 读取交付。 |
| [D20](#task-d20) | 独立凭证与故障关闭已有，限流误伤待验证 | 原 queryToken 未校验已过时；[`OrderController.quickSearch`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L257)、[`BuyerOrderQueryService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/BuyerOrderQueryService.java#L14)、[`OrderQueryLimiter`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderQueryLimiter.java#L18) 见 AUD-07，Redis 故障返回 503 已有实现；按单号连带限流见 AUD-07。 |
| [D21](#task-d21) | 演示种子已独立，密钥回退和历史轮换仍有缺口 | 部分：生产配置、`demo-seed.sql`、[`AdminBootstrap`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/AdminBootstrap.java#L29) 与两云环境文件；密钥、历史轮换和示例一致性见 AUD-05。 |
| [D22](#task-d22) | 有服务端退出，异常场景待验证 | 有实现：[`AuthController.logout`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java#L134) 与 [`AuthSessionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/AuthSessionService.java#L21)；管理端 `AdminLayout.vue` 触发退出。验证多标签、离线退出及禁用账号失效。 |
| [D23](#task-d23) | 已有基础控制，代理和员工细分待验 | 部分：[`CorsOrigins`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/CorsOrigins.java#L14)、[`SaTokenConfigure`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaTokenConfigure.java#L25)、[`AuthLoginLimiter`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/AuthLoginLimiter.java#L21) 已加控制；F13 员工能力和代理部署仍要核验。 |
| [D24](#task-d24) | 通用响应已收口，业务错误与回调审计需复查 | 部分：[`GlobalExceptionHandler`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java#L25) 对通用异常控制回显；[`BusinessException`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/BusinessException.java#L11) 单参默认码与支付回调审计原因仍须逐入口分类脱敏。 |
| [D25](#task-d25) | 已有 SQL 聚合，财务口径需验证 | 有实现：[`OrderController.merchantStatistics`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L414)、[`TradeOrderMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L32) 聚合；财务指标和交易看板使用不同口径，待验时区、退款及取消。 |
| [D26](#task-d26) | 已接公开资料，浏览器待验证 | 有实现：[`PublicStoreController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PublicStoreController.java#L20) 与商城 `Store.vue`、管理看板真实资料；空店/禁用/失败页面专项验。 |
| [D27](#task-d27) | 已接 ownerToken，页面生命周期待验证 | 有实现：商城 `PeerPayCashier.vue` 与 [`PeerPayController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java#L37) 交换 ownerToken；多标签和页面卸载非可靠交付，用服务端过期兜底。 |
| [D28](#task-d28) | 已付摘要已有，待浏览器回归 | 有实现：`PeerPayCashier.vue` 已付状态展示，[`PeerPayServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L35) 不向付款人返回交付；凭证改由独立查单，勿补旧 deliveredVoucher。 |
| [D29](#task-d29) | 已加路由检查和兜底，待双端回归 | 有实现：管理端 `src/router/index.js` 角色路由与兜底，服务端 [`SaTokenConfigure`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaTokenConfigure.java#L25) 仍是安全边界。 |
| [D30](#task-d30) | 订单有游标及批量明细，残余列表待测 | 部分：[`ProductController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L34) 组合筛选，[`OrderController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L35) 游标、批量明细；卡密、运营队列和对账仍需按访问形态验证分页/索引。 |
| [D31](#task-d31) | 有迁移，仍缺就绪和真实升级证据 | 部分：`application.yml` 禁止忽略初始化错误；`schema.sql` 新库与 [`SchemaMigrationRunner`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java#L33) 存量分开，见 AUD-06。 |
| [D32](#task-d32) | 已增加 FK、CHECK 与索引，实库覆盖待确认 | 部分：`schema.sql` 有外键、CHECK、唯一键和索引；检查实际 MySQL 版本、存量孤儿与约束覆盖。 |
| [D33](#task-d33) | 主路径已统一，历史值回归待做 | 有实现：[`PeerStatus`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/PeerStatus.java#L8)、[`ServiceFeeStatus`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/ServiceFeeStatus.java#L10)、[`OrderFeeStatus`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/OrderFeeStatus.java#L15) 和 SQL 默认；历史未知状态兼容和页面标签仍要回归。 |
| [D34](#task-d34) | 构建脚本已对齐项目目录，真实构建待验 | 有实现：两云 `server/03-build.sh` 使用现有两端构建与后端产物；用隔离用例和真实构建确认缺端失败。 |
| [D35](#task-d35) | 有通用及两云模板，真实拓扑待验 | 部分：`deploy/nginx/nginx.prod.conf` 与两云模板覆盖应用路径；生产访问控制、探针与回滚逐云验。 |
| [D36](#task-d36) | 基线未写死目标库，权限演练待做 | 有实现：`db/schema.sql` 不固定目标库，两个 `02-init-infra.sh` 指定数据库；验自定义库名与最小权限。 |
| [D37](#task-d37) | 已有 DB 和 Redis 探针，迁移门槛待做 | 部分：[`ReadinessController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ReadinessController.java#L18)/[`ReadinessService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReadinessService.java#L21) 区分存活与依赖，Redis 写保护见两云初始化；迁移/依赖降级见 AUD-06。 |
| [D38](#task-d38) | 渲染已移出事务，旧数据和磁盘失败待验 | 部分：商城 `tailwind.css` 本地构建；[`PeerPayServiceImpl.attachPoster`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L652) 与 [`PosterStorageService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PosterStorageService.java#L34) 事务外和文件存储，旧 Base64 清理与容量/回退待查。 |
| [D39](#task-d39) | 已有 CI 与隔离入口，覆盖证据待补 | 部分：`.github/workflows/ci.yml`、`scripts/regression.sh` 与 Java/shared/deploy 用例；缺真实数据库、浏览器、渠道及当前版本执行证据。 |
| [F01](#task-f01) | 已接事件、回调和审计，支付尝试及真实渠道未闭环 | 部分：[`PaymentGatewayController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PaymentGatewayController.java#L37)、[`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46)、[`PaymentCallbackAuditService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentCallbackAuditService.java#L29)；完整支付尝试、旧单绑定、迟到资金补偿与环境见 AUD-02/03。 |
| [F02](#task-f02) | 本地资料与审核已实现，渠道进件和签约未闭环 | 部分：[`MerchantOnboardingService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantOnboardingService.java#L67)、[`MerchantPayoutVerificationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantPayoutVerificationService.java#L39) 本地审核及账户台账；渠道提交、补件、签约和 BOUND 回写闭环待接。 |
| [F03](#task-f03) | 本地台账和回退骨架已有，渠道分账和恢复待补 | 部分：[`PlatformCollectionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PlatformCollectionService.java#L45) 结算/拆分明细，[`WeChatPlatformCashierChannelAdapter`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/WeChatPlatformCashierChannelAdapter.java#L57) 实现回退；[`WeChatProfitSharingAdapter`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/WeChatProfitSharingAdapter.java#L17) 分账发起/查询非完整可运营链，需回执、解冻、对账。 |
| [F04](#task-f04) | 现有渠道查询可复用，独立确认流程未见 | 缺口：[`CollectionMode`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/CollectionMode.java#L12)、[`MerchantCollectionModeService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantCollectionModeService.java#L37) 区分模式，[`PaymentGatewayService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L46) 有可验证渠道查询；未见独立直收确认及人工核账证据流程。 |
| [F05](#task-f05) | 申请审批、额度和费用冲正已有，异步完成及退货待补 | 部分：[`RefundRequestService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/RefundRequestService.java#L35)、[`RefundTransactionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundTransactionService.java#L48)、[`RefundService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L43) 有申请、批准、额度与冲正；退货/RMA、异步退款确认和超时恢复待补。 |
| [F06](#task-f06) | 已有受理和仲裁，外发与资金履约待做 | 部分：[`ComplaintService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ComplaintService.java#L45)、[`AdminComplaintController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AdminComplaintController.java#L24) 受理、证据、裁决；外部触达与资金执行的可恢复联动待补，裁决状态与退款成功分开。 |
| [F07](#task-f07) | 已有收货地址与自动完成，改址和运费缺口待决 | 部分：[`OrderController.deliverOrder`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L459)、[`OrderReceiptService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderReceiptService.java#L25)、[`OrderAutoCompleteJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/OrderAutoCompleteJob.java#L26)；地址簿、改址授权/冻结、地区/重量运费待确认。 |
| [F08](#task-f08) | 本地取消与扫描已有，首次关单恢复及晚到资金缺口 | 部分：[`OrderCancellationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderCancellationService.java#L32)、[`ReservationExpiryJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L35)、[`PaymentCloseRetryService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentCloseRetryService.java#L26)、[`PaymentExceptionDispositionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentExceptionDispositionService.java#L31)；首次关单持久化和迟到资金闭环见 AUD-03。 |
| [F09](#task-f09) | 已做日跑批和人工队列，账单完整水位及真实拉取未闭环 | 部分：[`ReconciliationDailyJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReconciliationDailyJob.java#L26)、[`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68)、[`ReconciliationBatchService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationBatchService.java#L45)；真实账单拉取、月结自动化和审计证据见 AUD-08。 |
| [F10](#task-f10) | 已出账及确认缴费，真实审批与欠费恢复待完善 | 部分：[`SaasFeeBillService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/SaasFeeBillService.java#L43)/[`SaasFeeBillJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaasFeeBillJob.java#L26)；缴费复核见 AUD-01，欠费停收与恢复、账单规则和真实并发待验。 |
| [F11](#task-f11) | 编辑、库存调整和违规已有，批量和价格政策待拆 | 部分：[`ProductEditingService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductEditingService.java#L30)、[`InventoryAdjustmentService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/InventoryAdjustmentService.java#L24)、[`ProductModerationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductModerationService.java#L29)、商城 `ProductDetail.vue`；进一步做批量运营、价格历史和可审计折扣。 |
| [F12](#task-f12) | 导入、去重、作废、补发已有，轮换与异常处置待补 | 部分：[`CardAssetService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java#L20)、[`CardImportBatchService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardImportBatchService.java#L15)、[`CardCipherService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L22) 有导入去重、作废、核对和补发；密钥治理、存量扫描和异常处置见 AUD-05。 |
| [F13](#task-f13) | 已有基本会话和店铺角色，员工生命周期与细权限待建 | 部分：[`AuthController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java#L34)、[`StpInterfaceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/StpInterfaceImpl.java#L19)、[`ManagementGuards`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/ManagementGuards.java#L11)；有身份/角色与部分审计，员工生命周期、找回、细权限、敏感操作二次校验待补。 |
| [F14](#task-f14) | 有申请、协议和通知，安全预算与转化事件待完善 | 部分：[`PublicOnboardingController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PublicOnboardingController.java#L43)、[`MerchantAgreementService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantAgreementService.java#L41)、[`MerchantNotificationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantNotificationService.java#L29)；现有申请和导流不等于可用转化漏斗，审核权限、隐私与频控见 AUD-01/04。 |
| [F15](#task-f15) | 已有站内 outbox，外部投递未闭环 | 部分：[`MerchantNotificationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantNotificationService.java#L29)、[`NotificationController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/NotificationController.java#L24) 有站内和 outbox；外发供应商、送达/退订/有限重试、买家通知偏好待做。 |
| [F16](#task-f16) | 有发货字段，轨迹和配送引擎待建 | 缺口：[`OrderController.deliverOrder`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L459) 有发货信息，但未定位物流订阅、轨迹状态和配送模板引擎；和 F07 同一地址/运费快照。 |
| [F17](#task-f17) | 凭证查单可用，会员身份与绑定待决 | 候选：[`BuyerOrderQueryService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/BuyerOrderQueryService.java#L14) 与商城 `QuickQuery.vue` 为凭证查单，不是会员身份、订单中心或搜索；先明确匿名订单绑定和授权。 |
| [F18](#task-f18) | 扩展候选，当前按单商品 SKU 下单 | 候选：[`OrderController.checkout`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L99) 为单商品/SKU 下单，[`ProductModerationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductModerationService.java#L29) 是平台治理；购物车、父子拆单、活动、评价、发票分别设计，先做运费及退款分摊。 |
| [F19](#task-f19) | 扩展候选，现有零售库存模型可复用部分能力 | 候选：[`MerchantStore`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/MerchantStore.java#L22)、[`ProductSku`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/ProductSku.java#L17)、[`InventoryReservationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/InventoryReservationService.java#L21) 是零售基础；未见询报价、采购单、企业授信、供应商或多仓履约域。 |
| [F20](#task-f20) | 当前平台支付限制 CNY，多币种候选 | 候选：[`PaymentGatewayService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L46) 平台回调限制 CNY，[`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46) 校验实收；币种绑定、最小单位精度、汇率快照及多币种对账统一后再扩。 |

## 3. 历史复诊 R01–R35 回查

| 编号 | 当前状态 | 源码证据 | 影响、执行要求与任务归属 |
| --- | --- | --- | --- |
| R01 | 现有缓解 | [`AdminBootstrap`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/AdminBootstrap.java#L29)、生产配置、独立种子和云环境文件；隔离有实现，历史轮换未证，见 AUD-05。 | 生产缺配置会造成密钥回退，按 AUD-05 验启动和历史轮换。 [AUD-05](#task-aud-05) |
| R02 | 现有缓解 | [`ManagementGuards`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/ManagementGuards.java#L11)、[`OrderController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L35) 管理动作/字段已分工；F13 细粒度授权尚未成域。 | 员工接触店主证件或敏感操作会越权，按端点验证员工、店长和跨店。 [F13](#task-f13) |
| R03 | 待环境 | [`SaTokenConfigure`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaTokenConfigure.java#L25)/[`CorsOrigins`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/CorsOrigins.java#L14)；预占请求头和入驻新请求头均纳入实际浏览器预检矩阵。 | 遗漏预占或进件自定义头会让跨域浏览器无法提交，以实际 Origin 发 OPTIONS。 [D23](#task-d23) |
| R04 | 已有实现 | [`OrderController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L35) 列表、明细和 [`BuyerOrderView`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/dto/BuyerOrderView.java#L14) 已区分；复验管理员、员工、店主和匿名输出白名单。 | 把列表和明细共用投影会泄露字段，分别断言管理员、员工、店主及匿名响应。 [D18](#task-d18) |
| R05 | 部分 | [`CardCipherService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L22) 有加密及旧明文兼容，[`OnboardingAttachmentService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OnboardingAttachmentService.java#L36) 本地私有路径；密钥、备份和身份材料分级见 AUD-04/05。 | 固定密钥与明文兼容会放大库泄露影响，逐保存位置做旧新格式迁移及恢复。 [AUD-05](#task-aud-05) |
| R06 | 部分 | [`ClientIpResolver`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ClientIpResolver.java#L19) 已用于交易与认证，入驻协议记录仍有旁路，见 AUD-04。 | 代理共享单桶或伪造来源会误伤和绕限，验证两云和入驻入口解析。 [AUD-04](#task-aud-04) |
| R07 | 部分 | [`TradeCostLimiter`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/TradeCostLimiter.java#L22) 已用于建单、预占、海报；入驻与上传配额仍缺，按成本而非纯 IP 处理。 | 建单、海报、附件耗库存或存储，按路径验预算和过期释放。 [AUD-04](#task-aud-04) |
| R08 | 部分 | [`AuthSessionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/AuthSessionService.java#L21)、[`GlobalExceptionHandler`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java#L25)、CI；需会话撤销全矩阵、结构化敏感字段过滤和依赖扫描。 | 长会话和异常日志会扩大泄露窗口，验证撤销及请求日志字段，补 CI 依赖检查。 [D22](#task-d22)、[D24](#task-d24)、[D39](#task-d39) |
| R09 | 待环境 | [`MerchantStoreMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/MerchantStoreMapper.java#L16) 有窄列更新，[`MerchantController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L42) 分离资料与平台写路径；真实 MySQL 验资料与封禁并发。 | 资料更新可能误写平台封禁或费率，交错执行两次更新并检查窄列。 [D12](#task-d12) |
| R10 | 已实现持有校验 | [`PeerPayServiceImpl.preLockInventory`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L400) 使用 attemptKey 和服务端 ownerToken 区分重放与持有证明；首次响应丢失且未拿到 ownerToken 时，attemptKey 不能单独换回凭证；这是当前所有权约束。验拒绝第三方、持有者有限重发及失去凭证后等释放的体验。 | 首响丢失会延迟释放，测 attemptKey 单独不能换回凭证、持有者有限重发。 [D09](#task-d09) |
| R11 | 待环境 | [`ReservationExpiryJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L35) 有游标与逐单处理；多实例重复扫描、毒任务与积压上限仍需竞争验证。 | 毒任务或积压可能延迟释放，构造头部持续失败和后续正常候选，验游标及告警。 [D07](#task-d07)、[AUD-10](#task-aud-10) |
| R12 | 已有实现 | [`ReservationExpiryJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L35) 普通未付单扫描；不能替代渠道已支付未通知的查单补偿。 | 无预占未付单也要关闭，构造直购、意向单和有效预占，分别验普通扫描与关单。 [F08](#task-f08) |
| R13 | 部分 | [`PaymentEventRecorder`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L30)、[`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46) 与 [`PaymentEventReconcileService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentEventReconcileService.java#L48) 已分离事件和履约；平台结算可恢复性见 AUD-02。 | 事实已记录但履约或结算任务缺失时会漏账，注入事务失败并验证补偿一次。 [AUD-02](#task-aud-02) |
| R14 | 待环境 | [`TradeOrderMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L32)、[`ProductSkuMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/ProductSkuMapper.java#L17)、[`ProductVirtualCardMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/ProductVirtualCardMapper.java#L17) 负责 CAS 与锁；真实隔离级别、固定锁序、死锁重试和库存守恒待验。 | 锁顺序和真实隔离影响库存守恒，使用两个 MySQL 连接并发支付、取消和释放。 [D04](#task-d04)、[D05](#task-d05) |
| R15 | 部分 | `schema.sql`、V1–V23、[`SchemaMigrationRunner`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java#L33)；旧库约束与失败恢复见 AUD-06。 | 约束上线会被历史脏数据阻断，先查孤儿、索引和有效 CHECK，再演练升级。 [AUD-06](#task-aud-06)、[D32](#task-d32) |
| R16 | 候选 | 商城 `ProductDetail.vue` 展示金额，[`OrderController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L35) 按 SKU 服务端建单；没有统一阶梯报价/营销规则前，不承诺未接入的折扣。 | 页面优惠与支付金额不一致会影响买家判断，先确认定价，再以服务端报价快照对齐。 [F11](#task-f11) |
| R17 | 部分 | [`ProductController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L34) 与 [`ProductEditingService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductEditingService.java#L30) 仍分入口；提取共享领域校验并验证最低可售 SKU 价格。 | 创建和编辑校验分裂会接受不同数据，两个入口提交同一组价格和库存边界。 [D13](#task-d13) |
| R18 | 待夹具 | `demo-seed.sql` 已与生产分离，[`CardAssetService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java#L20) 支持一致性核对；将种子卡密数/库存作为显式验收。 | 演示库存与实卡不一致会出现演示可买但不可发卡，统计 demo SKU 与可用卡数。 [D21](#task-d21)、[F12](#task-f12) |
| R19 | 已有实现 | [`CategoryController.createCategory`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/CategoryController.java#L35) 单独新增入口；回归携 ID 不覆盖原记录并核验后台角色。 | 创建携 ID 会改既有分类，断言强制生成新 ID 且旧类目不变。 [D17](#task-d17) |
| R20 | 待浏览器 | 商城 `ProductDetail.vue`、`PeerPayCashier.vue` 监听路由；产品和账单都验旧请求晚回、组件复用、错误切换。 | 快速切路由旧响应可能覆盖新详情，分别给两次加载注入反向返回时序。 [D26](#task-d26)、[D27](#task-d27) |
| R21 | 待浏览器 | 商城 `ProductDetail.vue` 自购、代付两条提交和 [`PeerPayServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L35) 响应契约需联测；支付成功/失败/取消须以订单状态为准。 | 自付跳转依赖响应字段时可能跳错商品，核对产品 ID 与订单号的数据来源。 [D27](#task-d27)、[F01](#task-f01) |
| R22 | 部分 | [`PublicProductView`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/dto/PublicProductView.java#L12)、[`PublicStoreView`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/PublicStoreView.java#L11)、商城首页/详情；对比公开可售、资格与文案，移除无事实背书。 | 虚构原价、资质和开通提示影响购买判断，对可售状态、价格和文案逐项比对。 [D26](#task-d26)、[F11](#task-f11) |
| R23 | 待回归 | [`MerchantStoreMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/MerchantStoreMapper.java#L16) 窄列更新与商家免收日期输入契约；验证显式 null 清空、缺省不改、并发封禁不覆盖。 | 更新空值若被忽略，免收仍会持续，测缺省保持、显式 null 清空和并发封禁。 [D33](#task-d33) |
| R24 | 部分 | [`OrderController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L35)、[`OrderCursor`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/OrderCursor.java#L15)、[`TradeOrderMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L32) 已有游标和 SQL 聚合；管理待办/对账仍有分页和聚合尾项。 | 全量投影和 N+1 拉长请求，分别记录分页、关联查询次数和聚合执行计划。 [D30](#task-d30) |
| R25 | 部分 | [`CardImportBatchService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardImportBatchService.java#L15)、[`ProductVirtualCardMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/ProductVirtualCardMapper.java#L17)、指纹列唯一约束；复验锁持有时间、批次大小及老数据清点。 | 卡密全量扫描持锁过久，检查指纹命中和历史修复路径，按批次规模记录锁等待。 [F12](#task-f12) |
| R26 | 部分 | [`PeerPayServiceImpl.attachPoster`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L652)、[`PosterStorageService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PosterStorageService.java#L34)、[`MerchantOrderPosterController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantOrderPosterController.java#L22)；列表与文件分离，控制历史 Base64 和复制/磁盘容量。 | 旧海报字段和幂等快照放大读写，检查列表响应体和文件失效后的降级。 [D38](#task-d38) |
| R27 | 部分 | [`OrderIdempotencyService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderIdempotencyService.java#L33)、[`IdempotencyPurgeJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/IdempotencyPurgeJob.java#L28)；已有清理机制，重放窗口、买家数据最小化和备份保留期仍需统一。 | 过短清理破坏重试，过长保留扩大泄露，给每类私有数据设用途和保留规则。 [D14](#task-d14)、[D38](#task-d38) |
| R28 | 待环境 | `deploy/tencent/server/03-build.sh` 与 `test_tencent_build.py`；验证锁文件、三类产物齐备和 latest 更新时机。 | 缺端和依赖漂移会发布半套产物，锁版本并验证 latest 仅在三端齐备后更新。 [D34](#task-d34) |
| R29 | 待环境 | 两云 `04-deploy.sh`、`05-rollback.sh`、[`SchemaMigrationRunner`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java#L33)；制品切换、失败恢复和数据版本前滚策略一起演练。 | 切流后才发现结构错误会扩大故障，测试上线前就绪、回滚兼容与备份恢复。 [D35](#task-d35)、[AUD-06](#task-aud-06) |
| R30 | 待环境 | 两云 `04-deploy.sh` 和 Nginx 模板；按生产/开发策略验 TLS 失败和应用运行身份。 | 证书失败或 root 进程改变暴露面，验生产失败策略、服务用户和目录权限。 [D35](#task-d35) |
| R31 | 待环境 | `backup_mysql.ps1`、`test_backup_windows.ps1`；需要真实工具失败码、编码、文件原子发布验证。 | 备份脚本错编码或吞退出码会产生不可还原文件，使用假失败和真实恢复验。 [D35](#task-d35) |
| R32 | 待环境 | `backup_mysql.sh`、`backup_mysql.ps1`；恢复校验、异机副本、RPO/RTO 和附件覆盖待验。 | 备份位置及参数偏差会备错库，验证目标库、输出与异机恢复；明确附件覆盖。 [AUD-06](#task-aud-06) |
| R33 | 已有入口 | `.github/workflows/ci.yml` 和 `scripts/regression.sh` 已存在；历史执行不覆盖本基线，H2 不覆盖 MySQL 锁、DDL 和浏览器。 | 历史通过无法覆盖当前改动，按风险补 MySQL、浏览器与渠道层。 [D39](#task-d39) |
| R34 | 部分 | [`GlobalExceptionHandler`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java#L25) 有状态映射，[`BusinessException`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/BusinessException.java#L11) 默认码仍需分类；API、日志与监控维度同时校正。 | 统一返回 HTTP 200 或默认 500 会让客户端和监控误判，逐入口定 HTTP 和业务码。 [D24](#task-d24) |
| R35 | 可定位缺口 | [`JacksonConfig`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/JacksonConfig.java#L15)/[`StrictIdDeserializer`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/StrictIdDeserializer.java#L13) 有长整数契约，商城 `publicCatalog.mjs` 仍用 Number 解析类目；全链改成字符串，回归 2^53 边界。 | 超出 JS 安全整数的类目可能静默丢筛选，保留字符串并测 2^53 附近的值。 [D30](#task-d30) |

## 4. 后续修复卡、开发缺口与执行清单归并

下表每个编号均给出主归属和代码入口。与 D/F/R 重合的只开一张工单；设计里的“确认”“否决”“环境”保留原性质。

| 编号 | 当前状态 | 源码证据 | 影响、执行要求与任务归属 |
| --- | --- | --- | --- |
| T01 | 已见改造 | 商城 `ProductDetail.vue` 定义视图依赖后绑定 watch；保留首挂载回归。 | 首屏依赖未初始化会白屏，按挂载、重试和切 ID 顺序验证。 [D26](#task-d26) |
| T02 | 待前端联调 | [`OrderController.createPushPayOrder`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L495) 与管理商家 `Orders.vue` 商品下拉、SKU 字段；按店主路径验。 | 推单下拉契约错位会拿不到商品，核对真实商家商品和 SKU DTO。 [D03](#task-d03)、[D15](#task-d15) |
| T03 | 部分 | [`AdminBootstrap`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/AdminBootstrap.java#L29)、`demo-seed.sql`；已分环境，轮换归 AUD-05。 | 生产误加载种子会引入默认账号，验启动参数及制品内容。 [AUD-05](#task-aud-05) |
| T04 | 待部署 | `application-prod.yml`、阿里云 `04-deploy.sh`；按必填数据源注入复核。 | 缺数据源变量会启动失败或用错账号，核对发布必填与示例。 [AUD-05](#task-aud-05) |
| T05 | 部分 | [`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46) 事务协调事件 SUCCESS 与履约；平台结算脱节归 AUD-02。 | 状态先成功再回滚会让重放不补履约，测提交失败与重复流水。 [D06](#task-d06)、[AUD-02](#task-aud-02) |
| T06 | 本地有校验 | [`PaymentGatewayService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L46) 与 [`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46) 使用渠道实收核验；假渠道不可作真实金额证据。 | 若以订单额自证会放过错款，输入渠道实收错值和错币种。 [F01](#task-f01) |
| T07 | 本地有记录 | [`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46) 有第二笔支付留痕，[`RefundService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L43) 有重复支付补偿入口；迟到另归 AUD-03。 | 第二笔真实款不能当重放吞掉，验证原单只履约一次且可补偿。 [D06](#task-d06)、[F05](#task-f05) |
| T08 | 已有实现 | [`OrderController.checkout`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L99) 与 [`TradeOrderMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L32) 有截止语义，[`ReservationExpiryJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L35) 扫普通单。 | 普通单漏截止可长期占用或付款，测等于截止及历史空值。 [D08](#task-d08)、[AUD-10](#task-aud-10) |
| T09 | 待并发 | [`ProductEditingService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductEditingService.java#L30) 与 [`InventoryReservationMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/InventoryReservationMapper.java#L16) HELD 保护；真实下架竞争待验。 | 下架和预占竞争会导致已付款无法履约，真实 MySQL 验锁序。 [D12](#task-d12) |
| T10 | 已有实现 | [`PeerPayServiceImpl.preLockInventory`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L400) 服务端持有凭证；R10/AUD-07。 | 客户自造凭证会抢占他人库存，测 ownerToken 与幂等键不可替代。 [D09](#task-d09) |
| T11 | 待环境 | [`PaymentEventRecorder`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L30)、[`ReservationExpiryJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L35)、`schema.sql`；真实压力看连接池、扫描计划和锁。 | 无索引或嵌套事务会拖慢队列，按真实候选量测执行计划和连接占用。 [D30](#task-d30)、[AUD-06](#task-aud-06) |
| T12 | 部分 | [`SchemaMigrationRunner`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java#L33) 与 `db/migrations`；AUD-06。 | 多实例旧库升级会冲突，统一单迁移者并故障注入。 [AUD-06](#task-aud-06) |
| T13 | 待角色回归 | [`ManagementGuards`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/ManagementGuards.java#L11)、[`OrderController.createPushPayOrder`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L495)、[`MerchantController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L42)；员工 PII/动作边界按端点复核。 | 员工越权推单或读 PII，使用真实角色访问列表、详情与写入。 [F13](#task-f13) |
| T14 | 部分 | [`CardImportBatchService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardImportBatchService.java#L15)/[`ProductVirtualCardMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/ProductVirtualCardMapper.java#L17) 指纹导入；兼容数据需单独扫。 | 缺指纹历史记录会允许重复导入，按版本做离线扫描与结果复验。 [F12](#task-f12) |
| T15 | 部分 | [`CardAssetService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java#L20)/[`CardAccessAudit`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/CardAccessAudit.java#L17) 有资产访问控制和审计；限量、批量导出和保留期待验。 | 全量解密与导出扩大泄露，限定角色、批量和审计。 [F12](#task-f12) |
| T16 | 并入 T15 | 同 T15，[`CardCipherService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L22) 明文兼容与轮换归 AUD-05。 | 密文兼容读取及轮换按卡密资产治理执行，验缺密钥行为。 [AUD-05](#task-aud-05) |
| T17 | 已有约束 | `schema.sql` 和 V4 商户 SKU 编码唯一语义；跨店同码与同店重复分别验。 | 全局 SKU 编码限制会误拒跨店，验证同店重复和跨店同码。 [D32](#task-d32) |
| T18 | 部分 | [`GlobalExceptionHandler`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java#L25) 映射 HTTP；默认错误和冲突归 R34。 | 错误 HTTP 分类影响重试，校验业务拒绝、唯一键冲突及内部故障。 [D24](#task-d24) |
| T19 | 待部署 | 阿里云 `env.example.sh`、`04-deploy.sh`；发布脚本验实际环境覆盖。 | 遗漏商城、Redis 或数据源变量会用错环境，验证注入、缺参拒绝和制品。 [D34](#task-d34)、[AUD-05](#task-aud-05) |
| T20 | 待环境 | 两云 `04-deploy.sh`/`05-rollback.sh`；R29。 | 先切换再校验可能中断服务，模拟配置失败和制品回滚。 [D35](#task-d35) |
| T21 | 待环境 | 两个 `backup_mysql` 脚本与发布流程；R31/R32，不把快照文件存在当恢复成功。 | 只有快照文件无法证明可恢复，做迁移前备份到隔离库的恢复演练。 [AUD-06](#task-aud-06) |
| T22 | 待环境 | 两云生产/引导 Nginx 配置及部署脚本；R30。 | 证书安装失败若继续会导致流量异常，验证生产 TLS 失败处理。 [D35](#task-d35) |
| T23 | 待环境 | 两云 systemd 配置生成和备份脚本；验证非 root、文件权限与失败恢复。 | 服务高权限或备份误报扩大故障，验证应用账号与工具退出码。 [D35](#task-d35) |
| T24 | 已有分页 | [`OrderCursor`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/OrderCursor.java#L15)、[`OrderController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L35)、管理 `Orders.vue`；D30。 | 无稳定排序会翻页重漏，测同时间、筛选变化和游标失效。 [D30](#task-d30) |
| T25 | 已有聚合 | [`TradeOrderMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L32)、[`OrderStatistics`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/OrderStatistics.java#L14)；D25。 | 全量统计拖慢看板，手算状态样本并评估 SQL 行数。 [D25](#task-d25) |
| T26 | 已有事务拆分 | [`PeerPayServiceImpl.attachPoster`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L652)、[`PosterStorageService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PosterStorageService.java#L34)；D38。 | 渲染失败不应撤销订单，模拟写盘失败和海报重复生成。 [D38](#task-d38) |
| T27 | 部分 | [`IdempotencyPurgeJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/IdempotencyPurgeJob.java#L28)、[`OrderIdempotencyService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderIdempotencyService.java#L33)；R27。 | 清理和重放窗口冲突会重复下单，明确有效期与过期响应。 [D14](#task-d14) |
| T28 | 待浏览器 | 商城 `ProductDetail.vue` 与 `productDetail.mjs`，`shared/clipboard.mjs`；浏览器验证首屏与乱序。 | 迟到响应或剪贴板失败会误导用户，测路由切换、重试和降级。 [D26](#task-d26)、[D27](#task-d27) |
| T29 | 已接前端 | `PeerPayCashier.vue` 与 [`PeerPayController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java#L37)；D27。 | 缺预占或取消会卡在付款入口，验持有者、多标签与过期。 [D27](#task-d27) |
| T30 | 待浏览器 | [`ProductController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L34) 与商城 `Home.vue`；筛选、失败空态和公开承诺复核。 | 搜索与错误空态混淆会漏展示，验组合筛选、无结果、网络失败。 [D26](#task-d26)、[D30](#task-d30) |
| T31 | 部分 | [`ProductModerationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductModerationService.java#L29)、[`MerchantStoreStatusService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantStoreStatusService.java#L44) 和后台运营页面；状态不等于资质或到账。 | 管理端状态混用会开放错误操作，按真实状态及角色验证运营动作。 [D33](#task-d33)、[F11](#task-f11) |
| T32 | 待渠道 | [`PaymentGatewayService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L46)、[`WeChatPaymentChannelAdapter`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/WeChatPaymentChannelAdapter.java#L48)、[`AlipayPaymentChannelAdapter`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/AlipayPaymentChannelAdapter.java#L49)；保留账户与联调阻塞。 | 本地适配器成功不能证明真实到账，按两模式验证签名、查单和资金记录。 [F01](#task-f01) |
| T33 | 部分 | [`RefundService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L43)、[`RefundTransactionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundTransactionService.java#L48)；退款处理中收敛与渠道演练。 | 重复支付、异步退款与费用冲正存在恢复缺口，复用统一额度及流水。 [F05](#task-f05) |
| T34 | 部分 | [`MerchantAccountService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantAccountService.java#L41)、[`SettlementService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/SettlementService.java#L42)、[`ServiceFeeSnapshotService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ServiceFeeSnapshotService.java#L23)；AUD-01 及账本守恒。 | 账单可确认但审批真实性不足，验多会话、守恒和历史快照。 [AUD-01](#task-aud-01)、[F10](#task-f10) |
| T35 | 部分 | [`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68)、[`SaasFeeBillService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/SaasFeeBillService.java#L43)；F09/F10。 | 欠费和账单不完整可能误停收或误结案，验恢复与差异留痕。 [F09](#task-f09)、[F10](#task-f10) |
| T36 | 部分 | [`ClientIpResolver`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ClientIpResolver.java#L19)；入驻旁路归 AUD-04，真实代理归 D35。 | 反代后共享桶会误封，核对可信链及入驻旁路。 [AUD-04](#task-aud-04) |
| T37 | 可定位缺口 | [`JacksonConfig`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/JacksonConfig.java#L15)、[`StrictIdDeserializer`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/StrictIdDeserializer.java#L13) 和商城 `publicCatalog.mjs`；R35。 | 类目大 ID 转 Number 会丢筛选，字符串直传后做往返测试。 [D30](#task-d30) |
| T38 | 已有入口 | `.github/workflows/ci.yml`、`scripts/regression.sh`；D39。 | CI 不能证明渠道和浏览器通过，记录每项环境及跳过原因。 [D39](#task-d39) |
| T39 | 部分 | [`MerchantOnboardingService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantOnboardingService.java#L67)、[`MerchantPayoutVerificationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantPayoutVerificationService.java#L39)、[`MerchantFeePolicyService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantFeePolicyService.java#L41)；审核角色和税率是业务确认。 | 本地审核和费率并非渠道激活，审核角色、税率确认后完善。 [F02](#task-f02)、[F14](#task-f14) |
| T40 | 待渠道 | [`PlatformCollectionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PlatformCollectionService.java#L45)、[`WeChatPlatformCashierChannelAdapter`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/WeChatPlatformCashierChannelAdapter.java#L57)；F02/F03 与 AUD-02/03，渠道资质开通后验证。 | 平台支付、分账及退款契约还缺关键字段，按尝试、回执与补偿顺序落地。 [AUD-02](#task-aud-02)、[AUD-03](#task-aud-03)、[AUD-09](#task-aud-09) |

| 编号 | 当前状态 | 源码证据 | 影响、执行要求与任务归属 |
| --- | --- | --- | --- |
| G01 | 已合并同页 | T01/T28 同一 `ProductDetail.vue` 及 `productDetail.mjs`；按当前合并行为做首挂载、切换和乱序验收，非新功能。 | 首屏与迟到响应共用页面状态，按一次挂载、切换与重试验。 [D26](#task-d26) |
| G02 | 待部署 | T04/T19 同一阿里云 `04-deploy.sh`；合批验证环境透传。 | 配置模板重复修改会漏键，发布脚本同时校验数据源、商城与 Redis。 [D34](#task-d34) |
| G03 | 已有显式注入 | [`AdminBootstrap`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/AdminBootstrap.java#L29) 显式变量首建；随机生成不是安全补丁的必要条件，先验空值与默认值拒绝。 | 生产缺参或弱默认会引入弱账号，验证拒绝，随机首建另立需求。 [AUD-05](#task-aud-05) |
| G04 | 已接前端 | [`PeerPayServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L35) 与 `PeerPayCashier.vue` 已接 ownerToken，R10 回归。 | 旧凭证协议会卡在收银台，前后端合测恢复、取消和超时。 [D27](#task-d27) |
| G05 | 已接分页 | [`OrderController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L35)/[`OrderCursor`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/OrderCursor.java#L15) 与管理 `Orders.vue` 已成契约，D30 验证。 | 游标前后端不一致会漏单，合测历史格式与过滤条件。 [D30](#task-d30) |
| G06 | 部分 | [`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46) 留第二笔到账，[`RefundService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L43) 有补偿入口，真实渠道归 F05。 | 第二笔支付资金需要处置，验证事件留痕和 F05 补偿能关联。 [F05](#task-f05)、[F08](#task-f08) |
| G07 | 待角色回归 | [`OrderController.deliverOrder`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L459)；发货与推单权限分别按已确认角色测试。 | 错误角色能发货会改变他店订单，检查物理发货和推单的不同权限。 [D15](#task-d15) |
| G08 | 部分 | [`CardImportBatchService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardImportBatchService.java#L15) 以指纹去重，`schema.sql` 对照；不恢复全量明文旁路。 | 历史无指纹影响去重，先盘点再迁移，旧数据不重新走全量明文旁路。 [F12](#task-f12) |
| G09 | 可定位缺口 | [`CardCipherService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L22)，单密钥假设限制见 AUD-05。 | 旧新密钥不可辨会导致履约无法解密，按双读单写验。 [AUD-05](#task-aud-05) |
| G10 | 待边界回归 | [`PeerPayServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L35)、[`OrderController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L35) 截止与创建时间，验边界和时钟。 | 超时和预占截止口径不一致会拒绝合法或放过超时请求。 [D08](#task-d08)、[AUD-10](#task-aud-10) |
| G11 | 待发布 | `schema.sql` 与 V 系列同时存在，开发重建与存量升级分开，不能仅凭历史条目删除脚本。 | 混用基线和迁移脚本会漏表，按新库、旧库分别执行。 [AUD-06](#task-aud-06) |
| G12 | 待运维核验 | 两云 `env.sh`，脱敏核验、轮换归 AUD-05。 | 历史云环境凭据仍有效时可能被复用，核销旧值并确认新注入。 [AUD-05](#task-aud-05) |
| G13 | 待恢复演练 | `backup_mysql.sh`/`backup_mysql.ps1`，真恢复归 T21。 | 备份夹具覆盖不了真实结构和数据，做隔离恢复并验读。 [AUD-06](#task-aud-06) |
| G14 | 已有快照 | [`ServiceFeeSnapshotService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ServiceFeeSnapshotService.java#L23)/[`MerchantFeePolicyService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantFeePolicyService.java#L41)，回归政策生效时间与订单快照。 | 调费后旧订单若重算会账差，按生效时点及订单快照验。 [F10](#task-f10) |
| G15 | 已有规则 | [`RefundTransactionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundTransactionService.java#L48)，已交付卡密不当未售补回，测试覆盖全额与部分。 | 已交付卡密回补会再次售出，测部分与全额退款无回补。 [F05](#task-f05) |
| G16 | 待业务确认 | [`MerchantOnboardingController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantOnboardingController.java#L41) 与 [`MerchantPayoutVerificationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantPayoutVerificationService.java#L39)；审核权限分工待决。 | 审核职责不清会过度授权，定角色后补拒绝和合法审批。 [F02](#task-f02)、[F13](#task-f13) |
| G17 | 待业务确认 | [`MerchantFeePolicyService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantFeePolicyService.java#L41) 与 [`ServiceFeeSnapshotService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ServiceFeeSnapshotService.java#L23)；初始费率业务确认。 | 默认费率错误会影响首单应收费额，定口径后用新店与历史店回归。 [F10](#task-f10) |
| G18 | 待业务确认 | `application.yml` 税率与 [`ServiceFeeSnapshotService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ServiceFeeSnapshotService.java#L23)；政策口径由业务确认。 | 税率错会影响价税与冲正，确认后验证结算尾差。 [F10](#task-f10)、[F05](#task-f05) |
| G19 | 待渠道 | [`WeChatPlatformCashierChannelAdapter`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/WeChatPlatformCashierChannelAdapter.java#L57)、[`PlatformCollectionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PlatformCollectionService.java#L45)；联调与资质不视为通过。 | 本地已准备不等于渠道可付，按尝试和账单核对。 [AUD-02](#task-aud-02)、[AUD-09](#task-aud-09) |
| G20 | 部分 | 财务基线分项见下表，[`MerchantAccountService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantAccountService.java#L41) 是本地账本入口。 | 资金基线涉及多域，按 FB-F 各项验事件、退款、账本和权限。 [F03](#task-f03)、[F09](#task-f09) |
| G21 | 部分 | [`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68)/[`ReconciliationDailyJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReconciliationDailyJob.java#L26)；AUD-08。 | 延迟通知和未完整导入会误报，实施业务日和导入批次契约。 [AUD-08](#task-aud-08) |
| G22 | 部分 | [`PaymentEventMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentEventMapper.java#L25)、[`MerchantRechargeOrderMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/MerchantRechargeOrderMapper.java#L19)、[`ReconciliationDifferenceMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/ReconciliationDifferenceMapper.java#L19)；逐迁移核对锁/条件/幂等，按唯一写者设计。 | 事件、充值、差异并发可能覆盖终态，逐状态定义前置条件与影响行数。 [D06](#task-d06)、[AUD-01](#task-aud-01)、[AUD-08](#task-aud-08) |
| G23 | 待约束复核 | [`MerchantFeePolicyMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/MerchantFeePolicyMapper.java#L22) 与 `schema.sql`；同一时点费率并发和唯一性归账务约束。 | 同店同时刻两费率会出现选取不确定，清理后加业务键与冲突测试。 [D32](#task-d32)、[F10](#task-f10) |
| G24 | 候选 | [`MerchantAccountLedger`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/MerchantAccountLedger.java#L21)/[`MerchantAccountService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantAccountService.java#L41)；单边流水不等于复式账或可重算余额。 | 单边流水无法按借贷科目核算，先做余额重算和漂移核查，再定科目表。 [F03](#task-f03) |
| G25 | 候选 | [`PaymentEvent`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/PaymentEvent.java#L25)、[`TradeOrder`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/TradeOrder.java#L27)、[`TradeRefund`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/TradeRefund.java#L23) 字段契约；F20。 | 币种缺失会使汇总与退款错账，统一金额最小单位和币种。 [F20](#task-f20) |
| G26 | 候选 | 账务入口 [`BillingStatementService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/BillingStatementService.java#L44) 是账单，不是发票；独立候选。 | 账单不具备发票状态、凭证和红冲，独立定业务规则。 [F18](#task-f18) |
| G27 | 待渠道与业务 | [`PlatformCollectionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PlatformCollectionService.java#L45) 总闸、[`PlatformCashierProperties`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PlatformCashierProperties.java#L24)，渠道及资质条件仍由相应负责人确认。 | 权限或资质不足时启用会失败，开通证据随 F03 验。 [F03](#task-f03) |
| G28 | 待运维 | [`ReconciliationBatchService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationBatchService.java#L45)、备份脚本；运行告警与恢复证据待补。 | 未监测异常积压或缺恢复证据会延长故障，演练备份与恢复并关联告警。 [AUD-06](#task-aud-06)、[AUD-08](#task-aud-08) |
| G29 | 已有审计表 | [`PaymentCallbackAuditService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentCallbackAuditService.java#L29)、V20；审计表已存在，失败仅日志与脱敏规则需补证。 | 落审计失败需观测，验证回调 ACK 与审计异常解耦、原因脱敏。 [D24](#task-d24)、[F01](#task-f01) |
| G30 | 已有开发例外 | [`FinanceApprovalGuard`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/FinanceApprovalGuard.java#L25) 与 [`DualApprovalAccountValidator`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/DualApprovalAccountValidator.java#L32)；开发单超管例外不适用生产。 | 单超管环境可能卡审批或误开生产例外，测两种配置。 [AUD-01](#task-aud-01) |
| G31 | 可定位缺口 | [`AdminSettlementController.reviewers`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AdminSettlementController.java#L118) 已有 UI 数据源；真实批准归 AUD-01。 | 下拉可选复核人不能证明其本人审批，改为第二会话执行。 [AUD-01](#task-aud-01) |
| G32 | 待回归 | 历史失败回归以 `test-execution-log.md` 和当前测试代码为准；无本轮执行结果。 | 历史失败清单不代表当前执行结果，按原测试目标和基线复跑。 [D39](#task-d39) |
| G33 | 待端点复核 | [`ManagementGuards`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/ManagementGuards.java#L11)、[`GlobalExceptionHandler`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java#L25)；按角色、存在性和跨租户检查错误语义。 | 先报不存在可能暴露跨店对象，核对无权、跨店及不存在响应。 [D17](#task-d17)、[D24](#task-d24) |
| G34 | 待并发 | [`PaymentEventRecorder`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L30)、[`PaymentEventReconcileService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentEventReconcileService.java#L48)、[`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46)；多连接真实竞争归 AUD-03 与 R14。 | 两个事件写入可留 PROCESSING 或重复补偿，模拟占位后崩溃与并发恢复。 [D06](#task-d06)、[AUD-03](#task-aud-03) |
| G35 | 可定位缺口 | 商城 `publicCatalog.mjs` 使用 Number；R35。 | 类目大整数转 Number 丢筛选，覆盖后端字符串和前端直传。 [D30](#task-d30) |
| G36 | 配置已有 | [`FinanceApprovalConfigValidator`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/FinanceApprovalConfigValidator.java#L18)、两云生产环境注入；开关强制与批准行为分别验。 | 开关缺失或不一致可绕过生产双人控制，验证环境解析和实际第二次会话。 [AUD-01](#task-aud-01) |
| G37 | 待网关 | 两云引导/生产 Nginx 模板对照 [`ReadinessController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ReadinessController.java#L18)；逐模板验探针和不缓存。 | 缺探针代理路径可能被 SPA 响应掩盖，分别请求存活和就绪。 [D35](#task-d35)、[D37](#task-d37) |
| G38 | 可定位缺口 | [`SchemaMigrationRunner`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java#L33)、[`ReadinessService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReadinessService.java#L21)；AUD-06。 | 迁移时连通性探针可提前通过，启动状态参与 503 判断。 [AUD-06](#task-aud-06) |
| G39 | 待环境 | V1–V23 和 `schema.sql` 不可互换；全新库完整结构用后者。 | 只跑增量脚本初始化空库会缺新表，按完整 schema 建库并对比表和索引。 [AUD-06](#task-aud-06) |
| G40 | 待实库 | [`SchemaMigrationRunner`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java#L33)；两个实例读同一版本缺失时应由迁移者互斥。 | 迁移双实例会竞争，明确单迁移者并做同库启动用例。 [AUD-06](#task-aud-06) |
| G41 | 待实库 | `schema.sql`、旧库列及 V 系列外键 DDL；实际排序规则检查。 | 排序规则不同导致 FK DDL 失败，先查实际列定义并演练失败恢复。 [AUD-06](#task-aud-06) |
| G42 | 部分 | [`MerchantAccountLedger`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/MerchantAccountLedger.java#L21)、[`MerchantAccountService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantAccountService.java#L41)；人工调整、操作人和证据链需一起设计。 | 人工账本调整缺可追溯操作人，先定权限、理由和审批，再追加反向记录。 [AUD-01](#task-aud-01)、[F03](#task-f03) |
| G43 | 待运维核验 | `application.yml` 数据源占位符、两云 `env.sh`；本地注入和历史泄露是不同问题。 | 本地历史明文可能继续使用，检查环境来源和轮换记录。 [AUD-05](#task-aud-05) |
| G44 | 待 SQL 核对 | [`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46) 与 [`PaymentEventMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentEventMapper.java#L25)；显式列更新应区分保持和清空。 | 部分更新忽略 NULL 会留旧原因，显式定义保持、清空和写入条件。 [D06](#task-d06)、[D24](#task-d24) |
| G45 | 待执行计划 | [`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68)、[`PaymentEventMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentEventMapper.java#L25)、`schema.sql`；真实计划归 AUD-08。 | 按商户全历史扫会压慢对账，测数据分布和复合索引。 [AUD-08](#task-aud-08) |
| G46 | 部分 | [`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68) 非成功事件信号和部分导入；逐笔差异等待完整水位，AUD-08。 | 半批流水下差异可能提前关闭，完整水位和逐笔可重跑。 [AUD-08](#task-aud-08) |
| G47 | 待 SQL 核对 | [`PaymentEventMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentEventMapper.java#L25) 失败回写；NULL 不得覆写“已确认未收款”的 0。 | 核对 markFulfillmentFailed 的 CASE，确认未收款的 0 在后续参数未知时不应丢失证据。区分正常回写、未知回写与渠道新证据，覆盖 0、NULL、1 转换矩阵。 [D06](#task-d06)、[AUD-08](#task-aud-08) |
| G48 | 待回归 | [`PaymentEventReconcileService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentEventReconcileService.java#L48) 与 [`PaymentEventMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentEventMapper.java#L25)；回归须回读中间态及人工态。 | 只验最终状态可能漏掉中间写错，分别断言处理中、超限和人工态。 [D06](#task-d06)、[F08](#task-f08) |
| G49 | 已有回退 | 管理 `Orders.vue` 与 [`OrderCursor`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/OrderCursor.java#L15)；浏览器验旧游标退回第一页及原筛选。 | 旧游标会反复 400 或丢筛选，旧格式切回第一页且保持过滤。 [D30](#task-d30) |
| G50 | 部分 | [`BusinessException`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/BusinessException.java#L11)、[`GlobalExceptionHandler`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java#L25)；R34。 | 单参默认 500 会误报系统异常，按授权、参数和依赖分类逐个替换。 [D24](#task-d24) |
| G51 | 待浏览器 | 两端 `idempotency.mjs`、`shared/idempotency.test.mjs`；补非安全上下文与正常随机源浏览器证据。 | 不安全上下文缺 randomUUID 会阻止建单，测安全与非安全上下文及键唯一性。 [D14](#task-d14) |

| 财务基线编号（区别于主台账 F） | 当前代码与残项 |
| --- | --- |
| FB-F01、FB-F02 | `schema.sql` 账本触发器、[`MerchantAccountService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantAccountService.java#L41) 反向流水；MySQL 验账本不可覆写，冲正确认余额守恒。 |
| FB-F03 | [`RefundTransactionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundTransactionService.java#L48) 与退款尾差字段；拆多笔退款总额、服务费税额不溢出。 |
| FB-F04、FB-F05、FB-F06 | [`MerchantFeePolicyService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantFeePolicyService.java#L41)、[`SettlementService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/SettlementService.java#L42)、[`MerchantAccountService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantAccountService.java#L41) 记录人和开关；独立批准证据按 AUD-01 逐入口核对。 |
| FB-F07、FB-F08 | [`MerchantCollectionStatusAudit`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/MerchantCollectionStatusAudit.java#L18) 与 [`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68)、[`RefundService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L43)；自动停收记录、差异已限次，退款待闭环。 |
| FB-F09、FB-F10 | [`ReconciliationDailyJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReconciliationDailyJob.java#L26) 已有日跑批，[`CallbackReplayGuard`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/CallbackReplayGuard.java#L23) 已有时间窗；延迟合法重试和完整性仍需渠道测试。 |
| FB-F11、FB-F12、FB-F13 | [`MerchantAccountLedger`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/MerchantAccountLedger.java#L21) 余额重算和科目缺口归 G24；[`PaymentEvent`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/PaymentEvent.java#L25) 币种覆盖归 G25。 |
| FB-F14、FB-F15、FB-F16 | [`PlatformCollectionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PlatformCollectionService.java#L45) 总闸；[`RefundRequestService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/RefundRequestService.java#L35) 有买家申请审批但不覆盖所有人工退款；备份/告警归 G28。 |
| FB-F17、FB-F18、FB-F19 | [`PaymentEventMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentEventMapper.java#L25)/[`MerchantRechargeOrderMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/MerchantRechargeOrderMapper.java#L19)/[`MerchantFeePolicyMapper`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/MerchantFeePolicyMapper.java#L22)；归 G22/G23。 |

| 后期设计或执行分组 | 代码复核与处理方式 |
| --- | --- |
| A1/A2/A3/A4（M6/进件/分账/退款） | [`WeChatPlatformCashierChannelAdapter`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/WeChatPlatformCashierChannelAdapter.java#L57)、[`MerchantPayoutVerificationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantPayoutVerificationService.java#L39)、[`PlatformCollectionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PlatformCollectionService.java#L45)、[`RefundService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L43)：准备与渠道验收分离；账户未开通前平台总闸关闭。 |
| A5/A6/A7/A8（支付状态、渠道验证、关单错误、直收确认） | [`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46)、[`PaymentGatewayService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L46)、[`PaymentCloseRetryService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentCloseRetryService.java#L26)：可先实现尝试、回执与补偿；实际响应码、合法验签和到账归 A 类。 |
| B1/B2/B19 | [`AdminBootstrap`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/AdminBootstrap.java#L29)、[`CardCipherService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L22)、[`CardAssetService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java#L20)；AUD-05 与 F12。 |
| B4/B5/B6/D31a | [`SchemaMigrationRunner`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java#L33)、[`DualApprovalAccountValidator`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/DualApprovalAccountValidator.java#L32)、[`ReservationExpiryJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L35)、`schema.sql`；AUD-06、R14；第二超管存在校验不能替代 AUD-01。 |
| B14/B15/B16/B17 | [`SaasFeeBillService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/SaasFeeBillService.java#L43)、[`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68)、[`ManagementGuards`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/ManagementGuards.java#L11)、[`MerchantNotificationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantNotificationService.java#L29)；F10/F09/F13/F15。 |
| B18/B20/B21/B12 | [`OrderReceiptService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderReceiptService.java#L25)、[`ProductEditingService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductEditingService.java#L30)、[`PeerPayServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L35)、[`BuyerOrderQueryService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/BuyerOrderQueryService.java#L14)；F07、D13 与 AUD-07。 |
| B22/B23 | 两云 `04-deploy.sh`、Nginx 模板、[`MerchantOnboardingService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantOnboardingService.java#L67)；发布演练与角色/费率/税率决策分离。 |
| M-R3/M-R4/M-R5、S50/S52/S53/S54/S55/S56 | [`MerchantCollectionModeService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantCollectionModeService.java#L37)、[`MerchantPayoutVerificationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantPayoutVerificationService.java#L39)、[`FinanceApprovalGuard`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/FinanceApprovalGuard.java#L25)、管理端收款页；总闸、账户状态和审核角色分开，浏览器与渠道环境均未验。 |
| S40/S41/S43/S44/S45/S46 | D32/D33/D35/D37/D38/D39 原条目对应 schema、枚举、Nginx、探针、海报存储和 CI；版本、部署、磁盘故障与 CI 证据分别验证。 |
| S47/S48/S49 | [`PaymentCallbackAuditService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentCallbackAuditService.java#L29)、[`PaymentCloseRetryService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentCloseRetryService.java#L26)、[`PaymentExceptionDispositionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentExceptionDispositionService.java#L31)；验签字段、真实错误分类、人工处置与资金退款独立。 |
| S57/S58/S59、M2/M3/M4-A/M5/M6 | [`PaymentGatewayService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L46)、[`RefundService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L43)、[`PlatformCollectionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PlatformCollectionService.java#L45)；下单/回调/退分骨架与查单关单接线分开，AUD-02/03。 |
| V01–V09 | [`PaymentEventServiceImpl`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L46)、[`PaymentGatewayService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L46)；后端 Maven、shared 前端、两云构建、Nginx、Windows 备份、MySQL 竞争、渠道联调入口见第 6 节；这些是验证项。 |
| P3/P4 约束、问题 5 和文档债 | F18–F20 保持候选。以当前 Git 验证工作区，不把旧“未提交 F14”记为产品缺陷。SKIPPED 自动补关单、历史回填、支付宝平台代收与已交付卡密换卡保持原范围约束；不删除 V1–V6。 |

[后端逻辑专项](2026-11-06-backend-logic-review.md)、[并发专项](2026-11-06-backend-persistence-concurrency-review.md)、[前端部署专项](2026-11-06-frontend-deploy-review.md)、[双轨待决事项](2026-11-08-收款双轨M1-M4A问题与待决事项.md) 中的同义条目按上述归属合并。旧文档的历史计数、测试次数、待决的业务阈值和 Sprint 子编号不单独生成工单。

### 旧专项发现与修复卡对应

| 旧文档条目 | 当前源码归属与复核结论 |
| --- | --- |
| 后端逻辑 P0-1/2/3 | T05/T06/T07 已对应当前支付事件服务；新平台路径归 AUD-02/03/09。 |
| 后端逻辑 P1-1/2/3/4 | T13/T14/AUD-05/T17；角色、历史指纹、密钥和唯一性分开。 |
| 后端逻辑 P1-5/6/7/8 | T09；[`CollectionMode`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/CollectionMode.java) 与渠道枚举分离（旧订单解释不回填）；T29、T24。 |
| 并发 P1-1/2/3/4/5/6/7 | 分别归 T05/T08/T11/T36/AUD-05/T13/G34；数据库竞争不能用静态结果结案。 |
| 并发 P2-1/2/3/4/5/6/7/8 | 分别归 AUD-10/T10/T14/T26/D30/D32/T25/T27；检查 HELD 聚合索引、卡密解密输出和保留期注释。 |
| 并发 P2-9/10/11 | [`TradeCostLimiter`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/TradeCostLimiter.java) 和查单限流：固定窗口边界、跨环境 Redis 隔离、多桶预算及配置应按实际共享 Redis 部署验证，归 R07。 |
| 并发 P2-12/13/14/15 | `OrderController.checkoutRequest` 的幂等外层事务要测回滚；商家创建/账户绑定靠领域锁与唯一键做竞争回归（F02）；异常归 T18，旧库归 T12。 |
| 并发 P2-16/17/18/19 | 支付/预占事件归档见 R27；备份见 T21；过期游标见 R11；批量卡密锁与 IO 见 R25。 |
| 前端部署 P0-01/02/03/04、P1/P2 各子域 | 分别归 T01/T02/T03/T04；权限和凭据归 T13/AUD-05，管理、商城归 T24/T28–T31/T37，部署/备份归 T19–T23/T36。 |
| 双轨设计问题 1/2/3/4/5 | ① [`PaymentGatewayService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java) 平台下单已接骨架，仍缺 AUD-02/03/09；② [`MerchantCollectionModeService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantCollectionModeService.java) 已有切换校验，验证总闸关闭/恢复；③ [`MerchantPayoutVerificationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantPayoutVerificationService.java) 有本地同步，真实进件归 F02；④ [`PlatformCollectionService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PlatformCollectionService.java) 和 [`PlatformCashierProperties`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PlatformCashierProperties.java) 分别表示业务与渠道门槛；⑤ Git 工作区历史，不作为业务缺陷。 |

### T+1 专项问题（R1–R15，区别于旧复诊 R01–R35）

| 专项编号 | 源码落点与余项 |
| --- | --- |
| R1、R8、R12 | [`ReconciliationDailyJob`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReconciliationDailyJob.java#L26) 定时，[`ReconciliationBatchService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationBatchService.java#L45) 记录跑批；[`AdminSettlementController`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AdminSettlementController.java#L72) 提供查询。验证跨实例抢占、重复跑批及停机补跑。 |
| R2、R3、R4、R5 | [`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68) 已有退款、服务费、冲正、平台代收比对；仍需验证缺流水、错金额、拆多笔退款及部分结算。 |
| R6、R7 | [`ReconciliationClassifier`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationClassifier.java#L16) 与 [`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68)；业务日按领域事件时点，支付创建时间和渠道到账时间不能混用。 |
| R9、R10、R11 | [`ReconciliationDifferenceAudit`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/ReconciliationDifferenceAudit.java#L20)、[`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68) 与 [`ReconciliationBatchService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationBatchService.java#L45)；验证差异审计、升级人工、缺账单 PARTIAL。 |
| R13、R14、R15 | [`ReconciliationService`](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L68) 手工导入和本地比较入口；真实账单自动下载、分账/退款状态组合与实际退款标识仍属 F03/F05/F09。 |

## 5. 与行业做法对照：按资金和授权边界实施

| 主题 | 可采用的行业做法与本项目取舍 |
| --- | --- |
| 多租户、个人资料和公共入口 | [OWASP API Security Top 10](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) 将对象授权、字段权限、资源消耗和敏感业务流程分开。本项目沿用服务端取得的租户、按租户查询和 DTO 白名单；新接口补角色 × 对象归属 × 字段矩阵。前端菜单、难猜 ID、CORS 均不代替授权。 |
| 凭据和卡密 | [OWASP Secrets Management](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html) 建议覆盖分发、轮换和撤销。将“生产缺配置启动失败”与“历史密钥撤销”分成两项证据；加密密钥与去重标识应分别治理。 |
| 到账、通知与可靠任务 | [Stripe webhook 指南](https://docs.stripe.com/webhooks) 是处理验签、重复、乱序的行业例子，不是本项目渠道协议；[AWS transactional outbox 指南](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html) 给出本地事务与外部发送之间的持久化边界。保留一个资金事实来源，以独立任务驱动发货、退分、退款及通知；失败可查、重放不可二次扣库。 |
| 库存与迁移 | [MySQL InnoDB 死锁处理](https://dev.mysql.com/doc/refman/8.0/en/innodb-deadlocks-handling.html) 建议短事务、固定顺序和重试；[MySQL 隐式提交](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html) 明确 DDL 边界。先验原子 CAS 与唯一键、真实索引和锁顺序；不把远程支付、网络重试或文件 IO 放进锁持有段，不把 H2 当真实约束证据。 |
| 平台收付与分账 | [微信平台收付通产品模型](https://pay.wechatpay.cn/doc/v3/partner/4012086891) 区分二级商户收款、冻结、分账和解冻。[分账回退接口](https://pay.wechatpay.cn/doc/v3/partner/4012477737) 以原分账及接收方为依据。本项目应按渠道契约建模，不把内部“平台代收”命名等同于平台自持资金或渠道资质；本报告不作资质结论。 |
| 电商业务扩展 | 先把单店订单、地址/运费快照、退款可退额度、售后与商家账单闭环。跨店购物车引入父支付/子单、按子单分摊与部分退款；批发采购、多币种与发票需要独立业务决策。当前不建议为了候选功能改动单店核心契约。 |

## 6. 后续交付和人工验证

从仓库根目录 `E:\shop-b2b2c` 执行。当前只交付审计文档；下面命令供后续实现者/用户手动执行，本轮未执行。

| 验证范围 | 准确命令 | 预期 |
| --- | --- | --- |
| 后端 H2 及 Java 契约 | `mvn -f shop-b2b2c-backend/pom.xml test` | 用例通过；后续任务按其 Sprint 记录新增失败与合法路径，H2 不证 MySQL。 |
| 共享前端规则 | 先 `npm --prefix shop-storefront-vue ci`，再 `npm --prefix shop-storefront-vue test` | 规则用例及真实 PostCSS/Tailwind 规则测试通过，无缺依赖跳过。 |
| 两个前端工程 | `npm --prefix shop-admin-vue ci` 后分别执行 `npm --prefix shop-admin-vue run build` 与 `npm --prefix shop-storefront-vue run build` | 两端产生各自的 dist；另手动验证窄屏、角色、跨页迟到响应及 CORS。 |
| 网关、构建和部署隔离 | Linux/WSL（有 Nginx）：`python3 -m unittest discover -s deploy/tests -v`；整仓只读回归：`bash scripts/regression.sh` | 隔离用例通过，未修改系统站点、未连接业务库；依赖导致的跳过不算通过。 |
| Windows 备份 | `powershell -NoProfile -File deploy/tests/test_backup_windows.ps1` | 夹具验证非零退出与备份发布语义；另用隔离库做真实恢复。 |
| MySQL、浏览器、渠道 | 使用[测试执行记录](test-execution-log.md)、[开发库重建说明](开发数据库重建说明.md)和[Sprint 59 联调清单](SPRINT_59_微信电商收付通真实适配器与M6联调准备.md)；具体故障注入命令由对应实现 Sprint 提供。 | 并发资金与库存守恒、迁移失败不就绪、生产浏览器行为及渠道验签/原路退款分别留证；真实支付暂缺账户，不能标通过。 |

后续每批交付更新主台账、对应 Sprint 和文档导航；报告发现保留原编号引用，避免重复开发。本文状态为**静态审阅已记录；业务行为、环境与验收待验证**。

<a id="audit-details"></a>

## 7. AUD-01–10 开发任务明细

业务代码仍以 `5288e29` 为基线。本节每张卡先说明当前行为和触发影响，再拆分可提交的修改及验收。P0 的发布范围以卡片为准。未给定的额度、保留期和审批有效期列为配置或业务确认项。第 6 节提供执行命令，卡片中的回归场景供实现者补用例。

<a id="task-aud-01"></a>

### AUD-01 财务审批须由第二位登录用户执行

**当前状态 · 缺陷可由调用链确认。** `AdminSettlementController` 的结算、充值确认入口把当前会话身份作为操作人，从请求取得复核人 ID。结算和月费服务调用 `verify` 比较两人 ID，再调用 `resolveReviewer` 检查所选账号。满足这些条件时，当前会话即可完成操作并记 `DUAL_APPROVAL`。充值和费率调整也调用同一套校验。入驻有分阶段审核，开店动作另传 `confirmedBy`，应按动作检查真实第二次提交。店铺注销允许省略复核人是否符合业务规则，需单独确认。定位见 [FinanceApprovalGuard](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/FinanceApprovalGuard.java#L55)、[后台财务入口](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AdminSettlementController.java#L150)。

**触发与影响。** 超管 A 在确认请求填入任一有效超管 B 的 ID，B 无需登录即可产生以 B 为复核人的记录。涉及结算确认、充值入账、缴费确认和费率政策等动作，审计无法证明授权来自 B。环境中有两位超管只是必要条件之一。

1. **AUD-01.1 审批记录与原子确认。** 先列出实际写入财务或店铺状态的入口及各自待审状态。优先复用结算批次、充值单的制单字段。无待审对象的费率调整等，落持久化申请，绑定对象 ID、当前版本、金额或变更内容摘要、申请人。确认接口从当前会话获取审批人，并校验角色、申请人与审批人不同、对象版本和快照仍相符。用条件更新认领申请，审批、目标状态写入和账本变更同事务提交。
2. **AUD-01.2 管理端与历史审计。** 改成 A 提交、B 在待审列表打开并确认。旧 `confirmedBy` 参数只作接口过渡，禁止将其当作审批证据。历史记录保留原口径，新增可核对的审批事件。开发单人模式继续显式记录 `SAME_OPERATOR`，生产沿用严格配置。先交付结算、充值、月费，再按同一机制接费率和经确认须复核的店铺动作。
3. **验收。** A 伪填 B、A 审自己的申请、禁用 B、审批前金额变更都应拒绝且无账本记录。B 合法确认只入账一次，两个合格审批人并发最多一人成功，落账失败时审批认领一起回滚。扩充 `FinanceApprovalGuardTest`、`MerchantSettlementServiceTest`、`SaasFeeBillServiceTest`，补控制器双会话和数据库竞争用例。

<a id="task-aud-02"></a>

### AUD-02 支付尝试保存原收款账户，结算任务可重放

**当前状态 · 下单与回调已接通本地代码，账户快照和结算恢复缺失。** `createPayment` 从当前有效绑定拿 `sub_mchid`，并用订单号调用渠道。回调验签后再次执行 `requireBoundReceiver`。订单保存收款模式，平台分支没有持久化每次预支付的原二级商户身份。回调业务成功后才尝试写结算台账，写入异常被捕获，支付处理结果照常返回。定位见 [平台回调](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L115)、[建结算失败处理](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L176)。

**触发与影响。** 在账户 S1 发起支付，回调到达前换绑到 S2、解绑或过期，合法 S1 通知可能被拒。履约事务已提交而结算失败时，订单可已发货，但平台账缺这笔款，退款和对账取不到应有结算依据。重复回调不能作为恢复保证。

1. **AUD-02.1 持久化渠道尝试。** 在远程预下单前创建尝试，保存订单、租户、渠道产品、稳定渠道外部单号、原商户账号标识、实收预期金额/币种和截止时间。相同预下单命令复用尝试。保留订单收款模式和现有支付事件唯一性。旧单无法还原原账户时进入有证据的核查流程。回调、查单和退款都从原尝试或原渠道凭证取身份。
2. **AUD-02.2 补建结算。** 在已接受到账的本地事务中写入待建结算任务，任务唯一键绑定支付事件或订单的实际结算归属。消费者按快照幂等建账，有重试、错误码和人工终点。补一条能从已到账事件识别“缺结算”的巡检，以覆盖上线前存量。
3. **验收。** 模拟 S1 支付后换绑 S2，S1 通知可核验，S2 冒用同单应拒绝。覆盖错误币种、金额、二级商户、重复通知。模拟履约完成后结算消费者崩溃，任务恢复后只产生一套结算，发卡与扣库都保持一次。本地契约用假适配器和独立事务验证，渠道签约后验证真实报文。

<a id="task-aud-03"></a>

### AUD-03 按原支付尝试查单和关单，补齐首次关单恢复

本轮补充了[候选渠道丢失与回执序号](#close-attempt-review)的实现与验收要求。

**当前状态 · 平台预下单已有分流，主动查询和关单仍走直收。** `queryPayment`、`queryChannelPayment` 和 `closePaymentBestEffort` 查的是订单渠道或直收注册表。预下单可改写订单的 `payChannel`，因此现值也可能与仍可支付的旧二维码不同。买家取消在事务提交后调渠道，关单重试从已有回执找候选。定位见 [网关查单](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L212)、[关单](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L285)、[取消](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderCancellationService.java#L41)、[回执重试](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentCloseRetryService.java#L37)。

**触发与影响。** 平台单可能被发往直收适配器，或被记为跳过。取消成功提交后、关单请求或首个回执落库前若进程退出，定时重试缺候选。买家仍可能在有效旧页面支付，引入迟到款和客服处理。

1. **AUD-03.1 统一查单与关单路由。** 依赖 AUD-02.1，把主动查单、事件补偿、商家关单、超时/取消关单都按尝试路由，查到成功时先校验该尝试归属。取消或过期时，列出所有仍需关的尝试，历史无尝试仅走显式兼容路径。
2. **AUD-03.2 持久化关单意图。** 与本地关闭同事务创建任务，提交后由可恢复的执行器调用渠道。以尝试和动作建唯一键，使用条件认领与过期认领回收。渠道“已关闭”“已支付”“未知/超时”等分别落状态，查到到账转异常或补偿链。保持已确定的 SKIPPED 不自动补关规则，新增任务要明确记录为何没有发出。
3. **验收。** 对比两模式路由，测一单切换渠道、关闭旧尝试、事务提交后杀进程、渠道成功但响应丢失和两实例抢任务。重试不得恢复已关闭订单或重复释放库存。扩充 `PaymentCloseRetryServiceTest`、`PaymentExceptionDispositionServiceTest`、`ReservationExpiryJobTest`。

<a id="task-aud-04"></a>

### AUD-04 入驻创建、附件和协议证据的滥用边界

**当前状态 · 有申请凭证和单文件保护，缺应用层累计预算。** 创建草稿入口直接调用入驻服务。附件上传按申请凭证检查，并限制单文件大小和类型，文件读取入内存后写本地。协议留痕的 `clientIp` 取 XFF 首段，和其他入口使用的可信代理解析不一致。定位见 [公开入驻入口](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PublicOnboardingController.java#L65)、[协议 IP](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PublicOnboardingController.java#L198)、[附件存储](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OnboardingAttachmentService.java#L62)。

**触发与影响。** 同一来源可创建多个草稿并累计上传合规大小的附件。实际网关规则可能限制突发，代码仍缺按申请的持久配额。应用接收未清洗的转发头时，协议记录可能包含伪造来源。文件与库分开恢复时可能出现有记录无材料或孤儿文件。

1. **AUD-04.1 入口预算与可信代理。** 入驻控制器和引导控制器统一调用 `ClientIpResolver`。入口按来源限速，上传在验证申请凭证后按申请限制件数与总字节。按主体的限制需要已验证的主体标识，避免攻击者借他人手机号耗尽配额。并发上限用原子计数或锁保护。返回可区分的 429/业务容量拒绝，前端提示恢复条件。
2. **AUD-04.2 附件生命周期。** 提交前与补件期可写，进入审核后按状态和授权处理。数据库元信息与文件创建失败要能回收。为过期草稿和废弃附件定义清理时点并保留审核留存要求。多实例上线前验证统一私有存储和恢复能力。监控总量和清理失败。
3. **验收。** 无凭证和跨申请凭证无法上传或读他人材料。两请求同时跨额度时最多一个成功。伪造 XFF 不改变真实来源记录，可信代理仍解析正确。数据库插入失败、文件写满、合规上传和合法补件均覆盖。扩充 `ClientIpResolverTest`、`OnboardingAttachmentServiceTest` 与公开控制器集成用例。

<a id="task-aud-05"></a>

### AUD-05 生产凭据、卡密密钥和存量数据轮换

**当前状态 · 演示种子已独立，卡密仍允许缺配置回退。** `CardCipherService` 从配置或环境取密钥，缺失时使用代码内确定性回退。密文前缀 `ENC:v1:` 标记格式，未标记具体密钥，非该格式走明文兼容。指纹对明文执行稳定摘要。两个云环境文件的实际用途、历史值是否轮换，需要按部署核实。定位见 [密钥选择与密文格式](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L31)、[指纹](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L133)。

**触发与影响。** 生产遗漏密钥仍可启动并按固定回退加密。若库或备份泄露，回退加密的数据可用公开实现解密。直接替换唯一密钥会使原卡密或凭证无法解密。可枚举的低熵卡号也可能被指纹离线猜测，是否要改为带密钥指纹取决于实际卡号强度与去重兼容方案。

1. **AUD-05.1 启动门槛。** 生产明确拒绝缺失和开发默认密钥，部署先校验再切流。开发和测试显式注入独立密钥。核对真实环境文件是否进入制品和源码、核查已知历史泄露渠道，由运维轮换并核销旧凭据。
2. **AUD-05.2 双读单写。** 先盘点所有调用方和保存位置，包括发卡、已交付凭证、补发和备份。新增带密钥标识的新格式，只用当前密钥写入，按格式读历史数据。迁移按主键小批重加密，以旧密文/版本条件防覆盖，记录计数和错误，不记录明文。保留旧密钥直到在线数据与恢复要求均满足。指纹调整单独做版本兼容和跨版本去重验证。
3. **验收。** 生产缺配置启动失败，开发夹具可用。旧格式、新格式均可读取，未知 keyId 明确失败，不按明文继续。并发补发和迁移不覆盖新值，回退发布版本能读取新格式或按发布方案阻断回退。隔离库完成旧备份恢复并验读，不把密钥写进备份和日志。

<a id="task-aud-06"></a>

### AUD-06 迁移互斥、就绪门槛和失败恢复

**当前状态 · 新库基线与存量脚本已分开，迁移结果未接就绪。** `SchemaMigrationRunner` 实现 `ApplicationRunner`，按版本检查、执行、记账。 `ReadinessService` 只探测数据库连通和 Redis，成功缓存也未关联迁移。两个实例可能同时发现缺版本。已落地部分 DDL 但版本未记账时，下次启动能否继续取决于脚本幂等。定位见 [迁移入口](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java#L53)、[依赖探针](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReadinessService.java#L51)。

**触发与影响。** 双实例并发发布、旧库外键排序规则不匹配或迁移中断时，可能收到缺列错误、版本冲突或部分升级。端口可连、DB 可查都无法证明应用结构可用。

1. **AUD-06.1 单迁移者和就绪。** 在发布单步骤迁移与应用启动互斥之间选一个实现。需要多实例启动迁移时使用同一目标库的互斥，认领失败等候或退出。迁移完成且版本符合应用要求才允许就绪；迁移中或失败返回 503。网关发布等待就绪，保持存活探针可区分。
2. **AUD-06.2 版本与可恢复发布。** 发布前检查 MySQL 版本、字符集/排序规则、已有异常行、备份和可用空间。已执行版本使用可追踪的校验和。MySQL DDL 隐式提交，文档给出具体版本的重跑或前滚路径。涉及不兼容变更时定义应用回退与数据库结构的兼容窗口。
3. **验收。** 隔离 MySQL 测空库基线、旧库升级、重复启动、双实例和故意中断 DDL。检查版本只记一次、失败期间 503、数据校验通过后恢复就绪。扩充 `SchemaMigrationContractTest` 和 `ReadinessServiceTest`，发布隔离与真实 MySQL 验证分别记录。

<a id="task-aud-07"></a>

### AUD-07 买家查单的共享桶和历史问题更正

**当前状态 · 领取凭证已实现，限流误伤需验证。** `BuyerOrderQueryService.lookup` 比对独立 queryToken。 `OrderQueryLimiter.check` 在凭证验证前扣来源桶和规范化后的订单桶，Redis 故障返回 503。代付详情读入口已经与过期关闭分离。定位见 [查单授权](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/BuyerOrderQueryService.java#L19)、[查单限流](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderQueryLimiter.java#L23)。

**触发与影响。** 若攻击者知道他人的订单号，多次提交无效凭证也会消耗该订单共享配额。真实买家可能暂时得到 429。共享出口也会消耗来源桶。原 D20 的“凭证未参与校验”在当前代码不成立，原 D10 详情读写覆盖的剩余范围见 AUD-10。

1. **AUD-07.1 先验证误伤，再调整计数。** 保留认证前来源预算，增加来源与订单组合的无效尝试约束。合法查单可使用独立预算。不能简单把所有限流挪到数据库查询之后，也不能让不同错误暴露订单是否存在。Redis 不可用时沿用明确的错误策略并监控。
2. **AUD-07.2 对照文档。** 在 D10/D20 的历史修复记录下追加本轮证据和剩余子范围。已有独立领取凭证、普通未付单扫描和超时读写拆分以回归验收为主。
3. **验收。** 用错误凭证刷已知单号，验证真实买家仍有可用预算且攻击来源受限。覆盖单号大小写、跨 IP、同一出口、分享 token、Redis 不可用和合法领取。检查失败响应、日志与前端存储没有凭证或履约内容泄露。

<a id="task-aud-08"></a>

### AUD-08 对账业务日、账单完整性与查询成本

**当前状态 · 日跑批、差异与人工队列已实现。** 本地支付事件以 `created_at` 切日，渠道流水用 `trade_time`，退款用 `success_at`。当前已有缺渠道流水的 `PARTIAL`，部分导入是否完整仍缺独立批次凭证。支付异常列表使用 offset。定位见 [支付与流水比较](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L661)、[跑批入口](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationBatchService.java#L69)。

**触发与影响。** 23:59 渠道到账、次日收到通知会落到不同日，事件创建时间无法代表实际收款业务日。分段导入只包含当日部分流水时，存在误判单边或漏掉异常的风险。按商户反复回看历史会放大查询成本，具体扫描行数待 MySQL 执行计划证实。

1. **AUD-08.1 定义数据来源。** 从可信回调或查单取不可变渠道发生时间，旧记录无时间保留未知来源标记。退款 `success_at` 缺失记录按渠道凭证补，无法举证归人工。导入建立商户/渠道/业务日批次、源文件指纹、完整标记及汇总金额，区分零交易完整账单与未导入。
2. **AUD-08.2 优化读模型。** 用生产形状的隔离数据看商户+时间窗口的 `EXPLAIN`，按有效过滤字段和排序增复合索引。后台异常列表切稳定游标，重试队列用可认领批次；差异关闭必须能追溯业务状态和流水证据。
3. **验收。** 补 `ReconciliationServiceTest`、`ReconciliationBatchServiceTest`。覆盖跨午夜延迟、合法零流水、未导入、半批、同文件重放、退款未知时间、金额/币种不符。性能记录扫描行数和查询次数，指标阈值用代表性数据设定。渠道账单自动拉取随 F09 联调。

<a id="task-aud-09"></a>

### AUD-09 平台分账和回退按渠道契约建模

联调顺序见[平台支付、分账与退分前置关系](#m6-dependencies)。

**当前状态 · 有退款编排，退款回退报文和分账明细仍有缺口。** `WeChatPlatformCashierChannelAdapter.createPayment` 没传分账标识，Native 路径与服务商字段需核对目标支付产品。 `RefundService.returnSplitBeforeRefund` 取 `splits.get(0)`，用退款额减服务费冲正额作为回退额，再将该账户的 `sub_mchid` 传入 `receiverChannelId`。适配器将该值同时填为 `sub_mchid` 和 `return_mchid`，并以订单号代原分账单号。定位见 [退款编排](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L194)、[渠道回退请求](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/WeChatPlatformCashierChannelAdapter.java#L332)、[微信官方契约](https://pay.wechatpay.cn/doc/v3/partner/4012477737)。

**触发与影响。** 商户 S 出资给平台 P 时，二者是两个角色。同一值填两处会指向错误账户，渠道可能拒绝。若一订单分两笔给不同接收方，取第一笔无法定位原明细。即使当前报文被接受，按退款净额决定退分也可能超过接收方剩余可退额度。已成功退分但退款失败，需要保留两者分别可查询的结果。

1. **AUD-09.1 冻结接口和落账契约。** 依赖 AUD-02.1 确定渠道产品和原账户。分账发起按官方支持的下单/查单/关单报文和分账标识，保存真实原分账单号、出资方、接收方、金额和回执。产品权限未确认前保持总闸关闭。
2. **AUD-09.2 分配回退额度。** 按已经成功的原分账明细选择并锁定可退余额。使用 `refundId + splitDetailId` 等稳定标识生成各自回退号，保存处理中、成功、终态失败及渠道回执。只有所有本次必需回退均已确认才推进退款。渠道超时先用原回退号查单；原分账和退款凭证以业务账本为准。
3. **验收。** 用不同出资/接收方、同订单两笔分账、部分退款、并发超额退分、回退成功但退款超时、重复回放构造契约用例。扩充 `RefundServiceTest`。真实平台分账、退款和账单守恒待渠道验证。

<a id="task-aud-10"></a>

### AUD-10 预占入口拒绝过期时回滚刚完成的释放

**当前状态 · 历史缺陷在预占入口仍存在。** `preLockInventory` 带回滚事务，锁订单后调用 `expireIfNecessary`。过期分支释放预占、保存 `CLOSED/EXPIRED` 并注册提交后关单。随后外层对非 `UNPAID` 抛 `BusinessException`，本次事务的关闭与释放回滚。定时任务的 `expireReservation` 是独立事务，可兜底。定位见 [预占请求](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L412)、[过期释放](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L526)。

**触发与影响。** 已 HELD 的单过期，用户点击重新预占时收到拒绝，库存要等定时器下一次成功扫描才可用。如果任务积压，畅销 SKU 会出现已过期订单持续占库存。

1. **AUD-10.1 明确过期的事务出口。** 在同一写事务内完成关闭释放，返回可映射的过期结果，控制器在事务提交后转换既有拒绝响应。也可让请求只拒绝，由有承诺时限的任务执行释放。若选异步方案，须明确库存释放时限。两种方案共用现有过期服务和锁序。
2. **AUD-10.2 核对状态和并发。** 统一比较 `lock_expire_time` 与历史空值回退，避免截止相等的不同判断。关闭继续以未付款状态为前提，释放最多一次，待 AUD-03 接入统一关单任务。
3. **验收。** 使用真实事务从预占接口复现，收到拒绝后另开事务查订单、预占和库存。覆盖截止前、等于截止、过期、无预占、已支付、定时任务与请求并发。验证关单仅在提交后发生。扩充 `ReservationExpiryJobTest` 并补预占接口回归。

<a id="defect-details"></a>

## 8. D01–D39 逐项开发与回归要求

每张卡的源码入口见第 2 节同编号。已存在的实现先按卡片验收，只有回归失败或定位出剩余缺陷时才改代码。

<a id="task-d01"></a>

### D01 建单与实收隔离

- **当前状态** 已有实现，回归待执行。
- **缺陷与影响** 旧结账入口曾直接推进付款并交付，当前建单写未付款，正式付款由支付事件推进。
- **修改或验证落点** 在 OrderController.checkout 和 PaymentEventServiceImpl 固化职责，所有创建订单响应都用未支付视图。
- **验收** 建单后无支付流水或交付凭证，只有可信实收确认才推进，失败事件不扣库。

<a id="task-d02"></a>

### D02 生产支付入口

- **当前状态** 旧公开模拟路由已移除，配置验证待执行。
- **缺陷与影响** 历史模拟路由可绕过到账核验，新风险是部署 profile 或备用通道配置误开。
- **修改或验证落点** 检查 PeerPayController、PaymentGatewayController、支付注册表和生产配置，盘点全量可调用入口。
- **验收** 旧 URL 无效，假回调不履约，生产配置拒绝开启开发通道，合法验签回调通过。

<a id="task-d03"></a>

### D03 订单 SKU 归属

- **当前状态** 已有守卫，三入口待回归。
- **缺陷与影响** 伪造 SKU 可能把 A 商品与 B 商品或其他店铺的 SKU 组合，导致错价和错扣库存。
- **修改或验证落点** 用 TradeGuards 的同一归属规则覆盖直购、代付、商家推单，订单快照从可信 SKU 生成。
- **验收** 同店错商品与跨店 SKU 均拒绝，正确组合能建单。

<a id="task-d04"></a>

### D04 库存原子扣减

- **当前状态** 已用条件写，真实并发待验证。
- **缺陷与影响** 读后写会使并发单都看到最后一件，当前风险集中在锁序、CAS 失败后的回滚。
- **修改或验证落点** 核对 ProductSkuMapper 所有扣减、占用和释放路径，按影响行数决定成败，组合更新在同一事务。
- **验收** 最后一件并发只能成交一次，回调重放与失败回滚后库存守恒。

<a id="task-d05"></a>

### D05 卡密认领

- **当前状态** 已有条件认领，竞争待验证。
- **缺陷与影响** 两个订单可能同时读到一张可用卡，候选查询和认领之间需防并发。
- **修改或验证落点** 沿 ProductVirtualCardMapper.claimCard 与 InventoryReservationService 核对 SKU、租户和可用状态条件，认领不足整体回滚。
- **验收** 并行付款不会复用卡号，缺一张时无部分交付，合法多件数量一致。

<a id="task-d06"></a>

### D06 付款状态幂等

- **当前状态** 已有锁和状态迁移，外围状态仍需核对。
- **缺陷与影响** 回调重试、主动查单和另一笔真实到账会竞争，重复通知与重复付款业务含义不同。
- **修改或验证落点** 核对 PaymentEventServiceImpl 与 TradeOrderMapper，保留流水唯一键，迁移用前置状态，关联 G22。
- **验收** 同流水重放不重复扣库，第二笔不同流水保留差异，已发货状态不被退回。

<a id="task-d07"></a>

### D07 持久预占

- **当前状态** 已有数据库 HELD，待一致性验证。
- **缺陷与影响** 单靠 Redis 会在重启后丢失真实占用，数据库 HELD、SKU 与卡密可能出现部分更新。
- **修改或验证落点** 核对 InventoryReservationService 预占、消费、释放的同事务边界，巡检孤立状态。
- **验收** 重启可恢复；预占失败无库存或卡密残留，消费和释放不可同时生效。

<a id="task-d08"></a>

### D08 截止时间与晚到支付

- **当前状态** 入口已有状态检查，渠道补偿见 AUD-03。
- **缺陷与影响** 过期后预占或履约会消耗库存，实际到账也可能晚于本地关单。
- **修改或验证落点** 统一 PeerPayServiceImpl、PaymentEventServiceImpl 与订单截止定义，晚到资金进入 F08 处理。
- **验收** 截止前允许，等于截止及超时按约定拒绝；已到账晚到不自动发货且有处置记录。

<a id="task-d09"></a>

### D09 取消预占所有权

- **当前状态** 已有 ownerToken，恢复体验待验证。
- **缺陷与影响** 共享链接持有者不能释放他人的库存，首次响应丢失时本人也可能拿不到 ownerToken。
- **修改或验证落点** 复用 PeerPayServiceImpl.cancelPreLock 与独立过期机制，按 R10 验证有限重发规则。
- **验收** 伪造、分享链接、过期凭证无法释放，合法持有者释放一次，首响丢失最终自动释放。

<a id="task-d10"></a>

### D10 超时关单事务

- **当前状态** 详情读写已拆分，预占请求回滚仍在。
- **缺陷与影响** 预占里释放后抛异常，库存要等定时器成功处理。
- **修改或验证落点** 按 AUD-10 修改 PeerPayServiceImpl 事务出口并复用同一过期服务。
- **验收** 过期请求、定时器和支付竞争下只释放一次，详情读取无写操作。

<a id="task-d11"></a>

### D11 商品类型与履约

- **当前状态** 已有类型区分，回归待执行。
- **缺陷与影响** 把虚拟卡密库存不足当人工交付会掩盖缺卡并错误标记订单。
- **修改或验证落点** 在 TradeGuards 和支付履约统一商品类型及缺卡拒绝，类型语义由服务端决定。
- **验收** 卡密缺货走失败或异常队列，合法人工服务可按流程交付。

<a id="task-d12"></a>

### D12 商品与店铺可售性

- **当前状态** 已有交易守卫，读写并发待验证。
- **缺陷与影响** 前端缓存仍可点击已下架商品，封店和付款同时发生时要执行确认过的预占规则。
- **修改或验证落点** 以 ProductController 公开筛选和 MerchantStoreGuard 写入前校验为准，联测 ProductEditingService 下架保护。
- **验收** 停业或禁售不可新建单，有有效 HELD 时依既定规则阻止下架。

<a id="task-d13"></a>

### D13 创建、编辑与资料输入校验

- **当前状态** 各入口有部分校验，覆盖待补齐。
- **缺陷与影响** 商品创建和编辑、公开进件、费率和收款模式各自验证，缺字段与显式清空的处理可能不一致。
- **修改或验证落点** 对照 ProductController、ProductEditingService、PublicOnboardingController、MerchantCollectionModeService，先列表标注必填、范围、精度、长度、枚举和清空语义，再复用领域约束。
- **验收** 空体、负库存、无效金额、超长 JSON、未知模式拒绝；合法零值、有效日期和兼容字段正常。

<a id="task-d14"></a>

### D14 建单幂等

- **当前状态** 已有唯一键和幂等记录，重试待验证。
- **缺陷与影响** 客户端超时会重复提交，若幂等键允许换参或先写副作用，会生成额外订单。
- **修改或验证落点** 复用 OrderIdempotencyService、IdempotencyMapper，按动作和租户隔离，核对请求摘要和响应保留期。
- **验收** 同键同参只生成一个订单，同键换参冲突，首次事务回滚后可重试。

<a id="task-d15"></a>

### D15 发货租户隔离

- **当前状态** 已有角色、归属及状态校验。
- **缺陷与影响** 使用他店订单 ID 发货会污染订单状态和买家收货流程。
- **修改或验证落点** 在 OrderController.deliverOrder 验会话商户、商品类型和状态迁移条件，人工虚拟履约沿独立接口。
- **验收** 跨店、未付、错误商品类型拒绝；合法发货一次且状态可追溯。

<a id="task-d16"></a>

### D16 导卡与查看归属

- **当前状态** 已有商家和 SKU 范围，待全入口验证。
- **缺陷与影响** 伪造 SKU 或卡 ID 可能跨店写卡或泄露资产。
- **修改或验证落点** 核对 CardImportBatchService、CardAssetService 对导入、查询、导出、作废和补发逐个限制。
- **验收** 覆盖他店 SKU、同店错商品和员工权限，合法批次仅改本店 SKU。

<a id="task-d17"></a>

### D17 租户必填与平台路径

- **当前状态** 已分商家和超管入口，待权限矩阵回归。
- **缺陷与影响** 商家无租户时若退回默认店铺或全量范围，会跨店读写。
- **修改或验证落点** 在 ManagementGuards、SecurityUtils 和业务 Mapper 保持无有效租户即拒绝，全局接口独立授权。
- **验收** 缺租户、停用账号和跨店访问失败，合法超管仅在平台路径查全局。

<a id="task-d18"></a>

### D18 公开响应字段

- **当前状态** 已有专用 DTO，新增字段待约束。
- **缺陷与影响** 返回店铺实体会暴露负责人、证件和结算信息。
- **修改或验证落点** 用 PublicStoreView、PublicProductView 和 BuyerOrderView 输出白名单，对嵌套字段及错误响应加契约。
- **验收** 公开请求无个人或结算资料，仍返回合法的店铺展示字段。

<a id="task-d19"></a>

### D19 付款链接与领取凭证

- **当前状态** 已拆分，泄露路径待回归。
- **缺陷与影响** 分享链接会广泛转发，若响应含卡密或买家信息，第三人可领取。
- **修改或验证落点** 维护 PeerPayServiceImpl 付款摘要和 BuyerOrderQueryService 领取边界，检查响应缓存和日志。
- **验收** 分享链接只能付款或查看摘要，独立有效领取凭证能取本单交付结果。

<a id="task-d20"></a>

### D20 查单防滥用

- **当前状态** 独立凭证与故障关闭已有，限流误伤待验证。
- **缺陷与影响** 错误凭证可消耗已知单号共享预算，合法买家可能收到 429。
- **修改或验证落点** 按 AUD-07 验证并调整 OrderQueryLimiter 计数范围，保持错误信息一致。
- **验收** 伪造凭证、大小写和 Redis 异常符合策略，合法查单仍可用。

<a id="task-d21"></a>

### D21 应用凭据与种子

- **当前状态** 演示种子已独立，密钥回退和历史轮换仍有缺口。
- **缺陷与影响** 缺配置可落到固定密钥，历史泄露值是否有效需部署核验。
- **修改或验证落点** 按 AUD-05 整理 application-prod.yml、AdminBootstrap、云配置与卡密密钥生命周期。
- **验收** 生产无弱默认，开发显式种子可用，凭据轮换与备份恢复有证据。

<a id="task-d22"></a>

### D22 退出和撤销会话

- **当前状态** 有服务端退出，异常场景待验证。
- **缺陷与影响** 仅清浏览器状态会遗留可重放 token，离线退出和账号封禁也可能留有旧会话。
- **修改或验证落点** 核对 AdminLayout、AuthController、AuthSessionService 的登出、失效和前端错误处理。
- **验收** 登出后旧 token 被拒，封禁和权限变化刷新授权，合法新会话可用。

<a id="task-d23"></a>

### D23 CORS、登录与角色

- **当前状态** 已有基础控制，代理和员工细分待验。
- **缺陷与影响** CORS 配错可能允许非预期来源，代理 IP 配错会影响全部用户限流，员工路由不等于操作权限。
- **修改或验证落点** 核对 SaTokenConfigure、CorsOrigins、AuthLoginLimiter 与可信代理，权限拆分并入 F13。
- **验收** 合法来源预检含业务请求头，恶意来源拒绝，员工不能调用店长敏感接口。

<a id="task-d24"></a>

### D24 错误响应及日志

- **当前状态** 通用响应已收口，业务错误与回调审计需复查。
- **缺陷与影响** 内部异常原因可能携带敏感字段，业务输入错若用默认 500 会扰乱告警。
- **修改或验证落点** 逐入口审查 GlobalExceptionHandler、BusinessException 和回调审计，明确状态码、对外文案及日志结构字段。
- **验收** 参数错误、权限错误和内部失败分类正确，响应与日志均无卡密、令牌和资料。

<a id="task-d25"></a>

### D25 统计口径

- **当前状态** 已有 SQL 聚合，财务口径需验证。
- **缺陷与影响** 意向和超时单纳入成交会抬高金额，退款也可能混用毛额与净额。
- **修改或验证落点** 核对 OrderController.merchantStatistics、TradeOrderMapper，写清状态、发生时间、退款处理和展示名称。
- **验收** 构造未付、取消、已付、退款样本，金额与订单数可手算复核。

<a id="task-d26"></a>

### D26 店铺页真实资料

- **当前状态** 已接公开资料，浏览器待验证。
- **缺陷与影响** 缓存、空店与封禁场景可能仍展示虚构状态或失效内容。
- **修改或验证落点** 核对 PublicStoreController、Store.vue 和管理看板，资料缺失有明确空态，非营业状态与下单守卫一致。
- **验收** 空店、正常、封禁与接口失败都显示正确且无个人资料。

<a id="task-d27"></a>

### D27 代付预占交互

- **当前状态** 已接 ownerToken，页面生命周期待验证。
- **缺陷与影响** 多标签、路由切换和页面关闭可能遗留占用，旧请求也可能覆盖当前倒计时。
- **修改或验证落点** 核对 PeerPayCashier.vue 和 PeerPayController，过期由服务端兜底，前端只取消自己持有的预占。
- **验收** 双标签、关闭页面、首响丢失及过期恢复均不释放别人库存。

<a id="task-d28"></a>

### D28 已付页面与交付边界

- **当前状态** 已付摘要已有，待浏览器回归。
- **缺陷与影响** 付款人误把已付当可领取，或页面持续显示待付款。
- **修改或验证落点** 保持 PeerPayCashier.vue 只展示支付结果，将买家引导至独立查询，避免把 voucher 放回分享页。
- **验收** 回调前后刷新状态正确，付款人无法查看卡密，合法买家可领取。

<a id="task-d29"></a>

### D29 后台路由角色

- **当前状态** 已加路由检查和兜底，待双端回归。
- **缺陷与影响** 仅检查本地 token 可进入无权限页面，直接调用 API 也必须被拒绝。
- **修改或验证落点** 核对管理端 router 的会话角色和 SaTokenConfigure 的服务端权限。
- **验收** 手输路由、旧 token、未知 URL 及管理员正常路由均符合预期。

<a id="task-d30"></a>

### D30 分页与查询成本

- **当前状态** 订单有游标及批量明细，残余列表待测。
- **缺陷与影响** 运营队列 offset 深翻及统计重复加载随数据量增长，筛选组合可能影响索引。
- **修改或验证落点** 分别评估 ProductController、OrderController、卡密和财务队列，稳定排序加唯一键，批查关联，按 EXPLAIN 选索引。
- **验收** 同时间多记录无重漏，组合筛选正确，深页查询扫描和请求数可量化。

<a id="task-d31"></a>

### D31 结构基线和存量迁移

- **当前状态** 有迁移，仍缺就绪和真实升级证据。
- **缺陷与影响** 已有库只执行 CREATE IF NOT EXISTS 不会补列，多实例迁移也可能竞争。
- **修改或验证落点** 按 AUD-06 区分 schema 新库、V 脚本旧库及发布门槛。
- **验收** 空库、旧库、重复启动、中断及恢复真实 MySQL 验证。

<a id="task-d32"></a>

### D32 数据完整性

- **当前状态** 已增加 FK、CHECK 与索引，实库覆盖待确认。
- **缺陷与影响** 较旧 MySQL 或存量孤儿数据会让约束形同虚设或升级失败。
- **修改或验证落点** 查 schema 与真实库的外键、唯一、非负和精度约束，先清数据再加约束。
- **验收** 非法库存、重复流水及孤儿引用按预期失败，合法交易与并发可完成。

<a id="task-d33"></a>

### D33 状态与枚举契约

- **当前状态** 主路径已统一，历史值回归待做。
- **缺陷与影响** 未知或旧状态可能被 UI 当作正常，影响订单操作和服务费解释。
- **修改或验证落点** 对照 PeerStatus、ServiceFeeStatus、OrderFeeStatus、SQL 和前端映射，未知值显式处理。
- **验收** 覆盖全部合法值、NULL 与历史值，页面动作和统计分类一致。

<a id="task-d34"></a>

### D34 制品齐备

- **当前状态** 构建脚本已对齐项目目录，真实构建待验。
- **缺陷与影响** 缺某端制品仍发布会使管理或商城不可用。
- **修改或验证落点** 检查两云 03-build.sh、锁文件和制品发布，失败后保留可部署上个版本。
- **验收** 隔离测试模拟任一端失败、产物缺失和正常打包，再执行真实构建。

<a id="task-d35"></a>

### D35 网关与发布恢复

- **当前状态** 有通用及两云模板，真实拓扑待验。
- **缺陷与影响** 不同模板可能出现 API、前端路由、探针和 TLS 行为偏差。
- **修改或验证落点** 核对两云 04-deploy.sh、05-rollback.sh 与 Nginx 模板，发布按探针切流。
- **验收** 正常路由、未知路由、证书失败、探针失败和回滚均可复现。

<a id="task-d36"></a>

### D36 数据库命名

- **当前状态** 基线未写死目标库，权限演练待做。
- **缺陷与影响** 非默认库名或最小权限配置错误会建错库或启动失败。
- **修改或验证落点** 按两个 02-init-infra.sh 和 db/schema.sql 验证初始化目标及授权范围。
- **验收** 自定义库成功初始化，应用账号不可写其他库，部署过程无删库语句。

<a id="task-d37"></a>

### D37 就绪与依赖

- **当前状态** 已有 DB 和 Redis 探针，迁移门槛待做。
- **缺陷与影响** 只连通数据库不能证明版本正确，Redis 写保护触发也需按实际行为确认。
- **修改或验证落点** 按 AUD-06 接迁移状态，并验证 ReadinessService 与两云 Redis 配置。
- **验收** 启动中和依赖故障返回 503，恢复后就绪；存活探针独立。

<a id="task-d38"></a>

### D38 海报成本与文件生命周期

- **当前状态** 渲染已移出事务，旧数据和磁盘失败待验。
- **缺陷与影响** 旧 Base64、重复生成或文件写满会放大存储、带宽和建单失败概率。
- **修改或验证落点** 核对 PeerPayServiceImpl.attachPoster 和 PosterStorageService，给出旧字段清理与备份保留策略，验证降级。
- **验收** 模拟磁盘满、重复生成、旧海报和文件缺失，订单仍可查且列表无大字段。

<a id="task-d39"></a>

### D39 可重复验证

- **当前状态** 已有 CI 与隔离入口，覆盖证据待补。
- **缺陷与影响** H2 和规则测试无法覆盖真实 MySQL、浏览器、网关与渠道。
- **修改或验证落点** 扩充对应 Sprint 的测试层，记录环境、基线和跳过原因，部署用例保持隔离。
- **验收** 第 6 节命令按相关变更执行，真实环境项单独留证。

<a id="feature-details"></a>

## 9. F01–F20 逐项功能边界与交付要求

功能卡把当前已有路径与剩余工作写在一起。候选功能可用于估算和设计，开工前由业务确认范围。

<a id="task-f01"></a>

### F01 可信支付与补偿

- **当前状态** 已接事件、回调和审计，支付尝试及真实渠道未闭环。
- **缺陷与影响** 换绑和切渠道时旧单回调可能被拒，远程成功本地未见时恢复路径不足。
- **修改或验证落点** 先做 AUD-02.1 与 AUD-03.1，支付事实和订单履约继续用现有事件表，补补偿扫描、稳定幂等键和失配人工处置。
- **验收** 假适配器验证错账户、金额币种不匹配、掉响应、重复和乱序，随后验证真实签名与到账。

<a id="task-f02"></a>

### F02 二级商户进件

- **当前状态** 本地资料与审核已实现，渠道进件和签约未闭环。
- **缺陷与影响** 本地审核通过不能证明渠道可用，直接启用会让商户下单遇拒。
- **修改或验证落点** 基于 MerchantOnboardingService、MerchantPayoutVerificationService 和渠道账户表建提交、补件、签约、查询、BOUND 回写，凭证归属可追溯，入口防滥用见 AUD-04。
- **验收** 假渠道模拟待审、补件、重复通知和通过，旧商户换绑见 AUD-02，真实签约待验证。

<a id="task-f03"></a>

### F03 分账、结算与资金流水

- **当前状态** 本地台账和回退骨架已有，渠道分账和恢复待补。
- **缺陷与影响** 履约成功后可能无结算，原分账标识和接收方不全会阻断退分，账上余额难核验。
- **修改或验证落点** 按 AUD-02.2、AUD-09 建任务、分账回执、完结解冻与有限重试，结算批准接 AUD-01。
- **验收** 同一支付只入账一次，多接收方分账和重复回调可核算，失败可恢复，真实账单核对。

<a id="task-f04"></a>

### F04 商户直收确认

- **当前状态** 现有渠道查询可复用，独立确认流程未见。
- **缺陷与影响** 若按商户自报到账直接交付，缺少可信资金凭证；关单或纠纷也难追责。
- **修改或验证落点** 先确定需要自动查单还是经审批的人工核账。复用 PaymentGatewayService 与支付事件，保存证据来源和授权。
- **验收** 无证据无法确认，已验证回执仅触发一次，迟到或错账进入异常。

<a id="task-f05"></a>

### F05 退款与退货

退款失败后的同键重试另有[资金一致性缺陷 AUD-11](#task-aud-11)，优先于扩展退货流程。

- **当前状态** 申请审批、额度和费用冲正已有，异步完成及退货待补。
- **缺陷与影响** 渠道处理中可能无法及时收敛；缺退货时实物退款与库存回补口径不清晰。
- **修改或验证落点** 沿 RefundRequestService、RefundService、RefundTransactionService 补可恢复查单与异常终点，先退分接 AUD-09，实物退货另定收货质检与回补点。
- **验收** 部分/全额、超额、重复、渠道超时和已交付卡密不回补都覆盖，实物按确认规则验。

<a id="task-f06"></a>

### F06 投诉和裁决

- **当前状态** 已有受理和仲裁，外发与资金履约待做。
- **缺陷与影响** 裁决成功可能被误读为已退款，客服无法核对证据、通知和结果。
- **修改或验证落点** 在 ComplaintService 中分开裁决与退款执行状态，保留事件审计，退款复用 F05，通知复用 F15。
- **验收** 跨店证据不可读，合法裁决可追踪，退款失败可重试且不重复裁决。

<a id="task-f07"></a>

### F07 地址、发货与确认收货

- **当前状态** 已有收货地址与自动完成，改址和运费缺口待决。
- **缺陷与影响** 无改址/快照规则会导致发错地址，平台与商家展示运费也可能不一致。
- **修改或验证落点** 统一 OrderController、OrderReceiptService 和 OrderAutoCompleteJob 的实物状态。明确地址快照、可改窗口、收货时点及敏感字段。
- **验收** 发货前允许规则内改址，发货后拒绝，收货与自动完成竞争只推进一次。

<a id="task-f08"></a>

### F08 取消、超时与支付补偿

- **当前状态** 本地取消与扫描已有，首次关单恢复及晚到资金缺口。
- **缺陷与影响** 已取消页面仍可能在渠道付款，关单请求丢失会形成无法自动收敛的晚到款。
- **修改或验证落点** 做 AUD-03 与 AUD-10，复用 PaymentExceptionDispositionService 和 F05；处置结论独立于渠道退款成功。
- **验收** 已付款不可被关闭，重复取消不释放两次，首次关单丢失可恢复，晚到款有唯一处置记录。

<a id="task-f09"></a>

### F09 对账和差异治理

- **当前状态** 已做日跑批和人工队列，账单完整水位及真实拉取未闭环。
- **缺陷与影响** 跨天延迟、部分导入和查询全历史会造成误报或拖慢跑批。
- **修改或验证落点** 先做 AUD-08，再为渠道下载加可追溯导入批次，月结复用已确认流水，不直接汇总订单展示金额。
- **验收** 零流水、错币种、月末跨天、重复文件和历史退款可核查，真实账单守恒。

<a id="task-f10"></a>

### F10 SaaS 服务费账单

- **当前状态** 已出账及确认缴费，真实审批与欠费恢复待完善。
- **缺陷与影响** 单会话代填复核人可标已缴，重复批次可能导致重复账单或状态错置。
- **修改或验证落点** 在 SaasFeeBillService 和 Job 接 AUD-01。锁定按期账单唯一键、欠费停收/恢复记录及费率快照，账期规则先确认。
- **验收** 同店同期只出一单，重复支付确认不重复入账，欠费与恢复按规则生效。

<a id="task-f11"></a>

### F11 商品运营生命周期

- **当前状态** 编辑、库存调整和违规已有，批量和价格政策待拆。
- **缺陷与影响** 商品创建和编辑口径分裂会产生错价，批量下架可能碰到有效预占。
- **修改或验证落点** 先合并 D13 共享规则，批量操作按单商品返回结果和审计，费率和优惠快照只在明确业务后接。
- **验收** 合法编辑不改既有订单，坏行不能污染其他商品，违规和 HELD 的顺序有回归。

<a id="task-f12"></a>

### F12 卡密资产

- **当前状态** 导入、去重、作废、补发已有，轮换与异常处置待补。
- **缺陷与影响** 旧明文、坏指纹、密钥缺失或重复补发会损害资产与履约。
- **修改或验证落点** 先做 AUD-05，再用 CardAssetService 的同一资产事务登记异常、清点和补发原因，限制导出权限及数量。
- **验收** 混合格式、重复批次、并发补发、导出授权、历史核对结果均可追溯。

<a id="task-f13"></a>

### F13 员工、角色与审计

- **当前状态** 已有基本会话和店铺角色，员工生命周期与细权限待建。
- **缺陷与影响** 共用角色会让员工触达店长敏感动作，离职撤销和找回流程缺闭环。
- **修改或验证落点** 在 AuthController、StpInterfaceImpl、ManagementGuards 建最小权限矩阵和员工开停用，账务动作接 AUD-01，审计记录真实操作者。
- **验收** 跨店与员工越权均拒，店长合法授权有效，停用后旧会话不可继续。

<a id="task-f14"></a>

### F14 商户自助入驻与转化

- **当前状态** 有申请、协议和通知，安全预算与转化事件待完善。
- **缺陷与影响** 草稿和上传成本易被滥用，渠道激活与广告点击若混在一起会误报转化。
- **修改或验证落点** 先做 AUD-04，再基于 MerchantOnboardingService 的真实状态记录申请、提交、审核、开店事件，转化口径需确认。
- **验收** 重复提交不重复计数，过期与补件可追踪，转化统计无个人资料。

<a id="task-f15"></a>

### F15 交易通知和客服触达

- **当前状态** 已有站内 outbox，外部投递未闭环。
- **缺陷与影响** 通知失败或重复发送无法判断是否送达，客服缺稳定关联。
- **修改或验证落点** 复用 MerchantNotificationService 与 NotificationController，补渠道模板、偏好、可靠投递、有限重试及回执。
- **验收** 任务重放不重复有效送达，退订与失败升级生效，消息不带敏感凭证。

<a id="task-f16"></a>

### F16 物流与配送规则

- **当前状态** 有发货字段，轨迹和配送引擎待建。
- **缺陷与影响** 商家只能填单号，异常物流、签收变化及地区运费缺统一规则。
- **修改或验证落点** 依赖 F07 地址与运费快照，再做承运商码、查询/回调验签和状态映射；历史运费使用快照。
- **验收** 重复或乱序轨迹不回退签收，偏远地区按模板拒绝或报价，已下单价格不变。

<a id="task-f17"></a>

### F17 会员订单中心

- **当前状态** 凭证查单可用，会员身份与绑定待决。
- **缺陷与影响** 若直接按手机号关联历史订单，号码复用会暴露他人订单。
- **修改或验证落点** 先确认会员身份方案和匿名单认领，再复用 BuyerOrderQueryService 的独立凭证校验，分页按买家归属。
- **验收** 未验证手机号不能认领，旧单凭证可正常查询，已绑订单不可跨账号读取。

<a id="task-f18"></a>

### F18 跨店购物车、营销、评价与发票

- **当前状态** 扩展候选，当前按单商品 SKU 下单。
- **缺陷与影响** 直接把多个店订单绑一个订单会破坏运费、售后、结算和发票归属。
- **修改或验证落点** 先确认跨店父支付与子单、分摊和退款，再分别立项营销、评价和发票；发票由相应业务和税务负责人定规则。
- **验收** 多商户一付、多子单独立履约和部分退款能核算，营销快照及审批有回归。

<a id="task-f19"></a>

### F19 批发采购与供应链

- **当前状态** 扩展候选，现有零售库存模型可复用部分能力。
- **缺陷与影响** 零售价格和库存口径不适用询报价、采购及授信。
- **修改或验证落点** 先明确角色、报价有效期、最小采购量和履约仓，再决定采购单与零售单如何共享 SKU 库存。
- **验收** 报价不可跨组织读取，失效报价不成交，采购和零售争用库存不超卖。

<a id="task-f20"></a>

### F20 多币种

- **当前状态** 当前平台支付限制 CNY，多币种候选。
- **缺陷与影响** 不同币种的小数位、退款及汇差若共用人民币规则会造成账差。
- **修改或验证落点** 先确认目标币种、结算币与汇率来源，统一订单、事件、退款、账本最小单位和汇率快照。
- **验收** 错币种拒绝，原路退款与同币对账守恒，跨币转换保留精度和依据。

<a id="specialist-details"></a>

## 10. 专项验收与开发依赖

财务基线与 T+1 专项使用自己的编号。以下按同一编号回写专项验证记录，涉及资金状态的修改共用主任务，不另起第二套实现。

| 财务编号 | 当前状态、影响与验收落点 |
| --- | --- |
| FB-F01 | 已有账本触发器。直接 UPDATE/DELETE 若能成功会抹去资金依据，在真实 MySQL 用业务账号证明被拒，合法追加正常。 |
| FB-F02 | 已有反向流水。原借记应保留，重复冲正不重复入账，余额由正反两笔合计。 |
| FB-F03 | 已有退款尾差字段。多笔部分退款最终累计不得超过原费用，末笔吸收尾差并记录可核算依据。 |
| FB-F04 | 费率复核存在 AUD-01 风险。请求选择他人不得写新费率，审批需绑定旧版本与新费率。 |
| FB-F05 | 结算有制单字段，实际第二会话待补。并发审批只推进一个结算版本。 |
| FB-F06 | 充值有申请及确认状态，确认人真实性归 AUD-01。双会话且只写一笔入账流水。 |
| FB-F07 | 有停收与恢复审计，人工恢复审批规则待业务决定。覆盖欠费自动停收及已缴合法恢复。 |
| FB-F08 | 差异重试已限次。退款处理中若没有终点会积压，按 F05 补超限、查单与人工入口。 |
| FB-F09 | 有日跑批。跨日和不完整账单会导致误报，按 AUD-08 验业务日和导入水位。 |
| FB-F10 | 有回调时间窗。重放、时间戳偏差和渠道正常重试一起验，真实环境观察时钟偏差。 |
| FB-F11 | 余额重算与漂移告警待做。按账户重放账本，余额不符只报警和开差异，不直接覆盖。 |
| FB-F12 | 复式账候选。业务先定科目，再测试借贷平衡、追加冲正和迁移期对账。 |
| FB-F13 | 币种尚未全链绑定。按 F20 核对订单、支付、退款和账本的币种与精度。 |
| FB-F14 | 资质与发票规则待确认。保持对应总闸策略，取得产品权限和业务规则后再实装。 |
| FB-F15 | 有买家申请审批，人工退款覆盖待定。按 F05 列出直接退款、仲裁退款和补偿入口，定额度及批准条件。 |
| FB-F16 | 告警和真实恢复证据待补。测重试积压、对账异常及数据库和附件恢复。 |
| FB-F17 | 部分支付事件更新待统一。按 D06 测重放、旧事件迟到、失败标记及终态不回退。 |
| FB-F18 | 充值确认仍需证明竞争安全。保留锁或 CAS 的明确不变量，重复提交只产生一笔账。 |
| FB-F19 | 同生效时点的费率约束待核。加约束前盘点同店重复，验并发插入与合法未来费率。 |

| T+1 编号 | 当前状态、影响与验收落点 |
| --- | --- |
| R1 | 已有定时触发。停机错过一天时核对回看补跑，正常每天产出可查询批次。 |
| R2 | 已有退款对账。缺渠道退款、不同金额和重复单号要形成正确差异。 |
| R3 | 已有服务费金额比对。账本存在但金额错，仍应记录差异。 |
| R4 | 已有退款冲正检查。测退款成功且冲正缺失、少冲和重复冲正。 |
| R5 | 已有平台结算比对。输入原到账、分账、回退和结算，检查守恒与缺凭证行为。 |
| R6 | 已有分类器。用同一组真实业务样本确认长短、单边、币种、精度及跨天方向。 |
| R7 | 业务日已定义，到账时间还需可信来源。跨午夜回调按 AUD-08 验证。 |
| R8 | 跑批及重试有幂等措施。同日两实例并发及回看多日最多产生约定次数的实际重试。 |
| R9 | 有差异审计。人工操作、自动重试、关闭与再打开均可回看历史记录。 |
| R10 | 超限有升级路径。验证失败次数达到阈值时转人工且保留升级原因。 |
| R11 | 有 PARTIAL。补完完整性判定后测零流水、无文件和半批三种结果。 |
| R12 | 有批次查询。按角色、日期和商户验可见性，失败和部分完成能区分。 |
| R13 | 当前以导入为主。F09 增加账单下载、完整校验、版本及重放，真实渠道验证。 |
| R14 | 状态组合未穷举。分账成功后部分回退、退款失败与人工终点按 AUD-09 扩覆盖。 |
| R15 | 渠道退款字段待联调。先写退款号、渠道交易号区分的契约用例，真实账单复核。 |

| 开发批次 | 可开工范围 | 交付结束条件 |
| --- | --- | --- |
| 1 安全和权限 | AUD-05.1、AUD-01.1/.2、AUD-04.1；AUD-05.2 和 AUD-04.2 可各自独立交付 | 生产密钥门槛、真实双会话和入驻预算均有合法及拒绝场景；旧密钥退役和附件保留按运营要求验。 |
| 2 数据与状态 | AUD-06、AUD-10、AUD-07，随后 D04/D05/D32 的专项验证 | 就绪与迁移绑定；真实 MySQL 验证关闭、预占与支付竞态；查单共享桶影响用复现数据说明。 |
| 3 支付本地契约 | AUD-02.1 先于 AUD-03.1/.2 与 AUD-09；AUD-02.2 随结算恢复落地 | 进程中断、换绑、重复回调和退分边界用假渠道可复现并可恢复。 |
| 4 财务对账 | AUD-08.1 先于 AUD-08.2；按 F09 确认平台收付账单来源 | 账单完整性和业务日可核，执行计划与功能验收分别留证。 |
| 5 渠道联调及业务扩展 | 商户、渠道产品权限和公网通知具备后开展支付验收；F17–F20 按业务确认开工 | 对平台订单全链验证到账、分账、回退、退款与账单守恒；扩展功能按各自工单验收。 |

### 支付阻塞清单 A1–A9 与可先开发清单 B1–B23 的交付出口

| 条目 | 当前状态、影响与可交付工作 |
| --- | --- |
| A1 | 平台适配器已接入，生产开通待验证。先做 AUD-02/03/09，真实验证公网回调、旧尝试、切闸及账单。 |
| A2 | 本地进件有状态，渠道签约未闭环。按 F02 加申请号、补件与真实 BOUND 回写，失败不得开放收款。 |
| A3 | 分账台账与退分骨架已有，缺实际分账回执及恢复。按 F03 验失败后可重放、解冻和账单守恒。 |
| A4 | 直收退款有本地流程，真实原路退款待验证。按 F05 对照金额、处理中查单和费用冲正。 |
| A5 | 事件有幂等，尝试与迟到退款待补。按 AUD-02.1、AUD-03.2 持久化来源与补偿，渠道事实未知先保留。 |
| A6 | 金额及币种规则已有，真实通知样本缺失。假适配器可先验证合法和错值，签约后核真实报文。 |
| A7 | 关单回执及退避已有，错误码分类待接。区分终态、已支付、可重试与不支持，测试每类去向。 |
| A8 | 独立直收确认待定。F04 明确自动查单或人工核账，并确认凭证和权限。 |
| A9 | 退分字段角色已由微信官方契约核实。按 AUD-09 从原分账回执取得原接收方，真实账户验证资金守恒。 |
| B1 | 生产凭据部分收口，历史轮换与缺密钥风险归 AUD-05.1。 |
| B2 | 卡密单密钥和兼容读取仍在，按 AUD-05.2 验旧新密文、异常与恢复。 |
| B3 | 现有复核人选择只证明账号有效。AUD-01 用第二位登录主体批准同一申请，覆盖四条财务写入口。 |
| B4 | 迁移与恢复脚本有基础，按 AUD-06 演练重跑、排序规则和中断恢复。 |
| B5 | 启动会检查第二位超管，尚需生产配置演练；审批证据缺口归 AUD-01。 |
| B6 | 本地锁、CAS 与事件恢复已有，真实 MySQL 竞争及两实例关单归 AUD-03、D04/D05。 |
| B7 | 过期预占释放随异常回滚。AUD-10 用 H2 事务回归固定缺陷，再用 MySQL 验支付和关单竞争。 |
| B8 | AUD-02.1 保存原账户和尝试身份。B9/B10 消费快照，AUD-02.2 负责恢复已到账但缺失的结算。 |
| B9 | AUD-03 按原尝试查单、关单和认领持久化任务。需要 B8 先定义身份与快照。 |
| B10 | AUD-09 先按官方字段编写契约用例，完整退分依赖 B8 和原分账回执。 |
| B11 | AUD-08 统一可信发生时点与导入完整水位，和 B15 共用对账实现。 |
| B12 | 独立查单凭证和 Redis 失败关闭已有。AUD-07 调整共享配额，覆盖攻击来源和合法买家。 |
| B13 | AUD-04 复用可信 IP 解析并增加入驻和上传预算。附件生命周期按存储和保留策略验收。 |
| B14 | SaaS 已有出账，审批和欠费恢复按 F10，账期与试用口径需确认。 |
| B15 | 本地 T+1 已有，按 AUD-08 修支付时点、退款缺时间与导入水位。 |
| B16 | 商户角色已有，按 F13 扩员工管理、撤权和审计。 |
| B17 | 站内 outbox 已有，按 F15 交付外发的重试与回执。 |
| B18 | 地址与收货已有，改址/配送按 F07/F16 统一快照。 |
| B19 | 卡密资产功能已有，异常流程与历史清点随 F12/AUD-05。 |
| B20 | 校验分散，按 D13 列各入口字段矩阵并收口。 |
| B21 | 旧详情整行写回描述过时，与 B7 共用 AUD-10 任务，保留详情只读回归。 |
| B22 | 两云脚本、网关和探针已存在，按 D34/D35/D37 做实地演练。 |
| B23 | 有本地审核，审核角色、费率和税率仍按 G16–G18 由业务定。 |

<a id="landscape-cross-review"></a>

## 11. 全景统计、开发清单与代码架构交叉审校

复核输入为[全景统计（11-10）](2026-11-10-项目全景任务统计与处理等级.md)、[开发清单（11-11）](2026-11-11-开发清单-支付阻塞单列与非支付优先.md)、[收款双轨设计](2026-11-08-商家收款双轨与微信电商收付通进件设计.md)和 [Sprint 59 联调步骤](SPRINT_59_微信电商收付通真实适配器与M6联调准备.md)。两份清单提供优先级与历史状态，调用链、数据结构和部署配置提供当前行为证据。全景的 110 项、124 项及 18 项是该快照的统计口径，其中同一问题可能有多个别名，本节增补条目按实际修改范围归入原编号。

<a id="m6-dependencies"></a>

### 11.1 A1/A3/S59-2/S59-3 的联调依赖

**当前状态。** Sprint 59 §5.3 第 3 步要求回调只写台账，第 6 步随即要求原分账回退。代码只在支付后调用 `prepareSettlementBestEffort`，没有从这条链路发起真实分账。退款编排却直接读取预写的 `split_detail`，用订单号构造退分请求。原全景把 S59-2、S59-3 放在 M6 之后，S59-3 网关查单、关单已存在的仅是适配器方法。依照现有顺序，开发者拿到凭据后仍无法凭“预写明细”验证已分账订单退款，也无法在失联时经正确渠道主动恢复。源码见 [支付回调与结算](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L155)、[退分前置](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L194)。

**实施任务。**

1. 在 [AUD-02](#task-aud-02) 保存支付尝试后，先把 [AUD-03](#task-aud-03) 的网关主动查单与关单接线补齐，再做涉及查单和关单的联调验收。假渠道可在无凭据阶段覆盖直收/平台两条路由。
2. [AUD-09](#task-aud-09) 将预写台账与“渠道分账已成功”区分。待发起、处理中、成功及失败需要可追溯的渠道分账标识与回执。未经确认是否分账的订单先查询或留待核，不能按无分账放行。经证实尚未分账时，依据目标产品允许的未分账退款或冻结款处理流程单测和联调。有成功分账凭证时才对对应明细发起退分，再退款。D3 时点与售后窗口沿用双轨设计，并由业务确认具体规则。
3. 将 M6 验证分成“预下单、到账、错签名及重放”“无回调时主动查单与关单”“真实分账后退分及退款”三组。先证明支付与落账，再验证真实分账回执、退分金额和退款资金守恒。分账与查单接线属于相应全链验收的前置。保留直收原路退款的针对性验收，阶段一全面联调仍按原范围执行。

### 11.2 收款开关的行为与部署契约（A1、M-R3、S53-4、S54–S59）

**当前状态。** `MerchantCollectionModeService.effectiveMode` 在运营总闸关闭或应急开关打开时，将**新单**有效模式解析为直收；模式快照写入订单。随后 `modeForNewOrder` 对平台模式只检查分账开关及账户绑定，网关发起支付才使用 `PlatformCashierChannelRegistry` 检查适配器及凭据。关闭 `PLATFORM_CASHIER_ENABLED` 会同时拒绝平台发起支付、回调、退款申请及退款查单，因为这些入口共用注册表的启用校验。总闸写入以版本 CAS 更新当前值，商家切换另有审计，未看到全局总闸独立追加记录。源码见 [建单模式与应急回退](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantCollectionModeService.java#L179)、[运营总闸写入](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantCollectionModeService.java#L74)、[适配器入口保护](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PlatformCashierChannelRegistry.java#L43)、[退款查单](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L91)。

**影响。** 分账开关和 BOUND 账户就绪但适配器禁用时，平台订单可能建成后无法付款。把渠道总开关当作流量灰度回滚会拒绝已发出的二维码所产生的合法回调，也会中断存量退款。运营总闸的当前值不足以复盘何时停止收款、谁恢复以及原因。

**实施任务与验收。**

1. 新增一份开关矩阵，分别覆盖新建订单、旧单首次发起支付、已发出支付尝试的回调、查单/关单、退款与退分。按“禁止新交易”和“处理历史资金”拆能力，并明确渠道凭据失效时走有凭证的人工对账。保持原订单模式与账户快照，新单在运营总闸关闭时的直收回退沿用现有设计，须确认商户具备直收能力并在 UI 解释。
2. 在 M-R3/S53-4 把总闸变更与追加审计同事务写入，记录前后值、版本、当前会话和原因。与每商户切换历史分开查询。测试 CAS 冲突时不写成功审计、合法回滚时历史齐全。
3. 核对部署变量。Sprint 59 §5.2 的 `SHOP_SETTLEMENT_PLATFORM_COLLECT_ENABLED` 和应用显式占位变量 `PLATFORM_COLLECT_ENABLED` 名称不同，按实际绑定路径定一个部署契约并用假值验证。两云服务从 `.env` 读取环境，部署脚本中未定位到平台凭据专用注入及预检。约定独立私密配置的安装位置、换版保留、多行 PEM 转义、权限及启动前缺参检查。可在无账户时用假值验证，真实签名再按 A1 验收。配置详情见 [应用支付配置](../shop-b2b2c-backend/src/main/resources/application.yml#L113)、[阿里云服务环境](../deploy/aliyun/server/04-deploy.sh#L326)。
4. 覆盖“配置模式为平台但注册表关闭”“灰度停新单后原尝试成功回调”“关闭期间旧退款查单”“恢复后只对获准商户开新单”。独立验证未授权商户或签名错误仍拒绝。

<a id="close-attempt-review"></a>

### 11.3 关单回执的任务身份与次数限制（B6、S48-1、S48-3、S59-3）

**当前状态。** 回执写入用 `INSERT ... SELECT MAX(attempt_no)+1`，基线对 `(order_sn, channel, attempt_no)` 建普通索引。候选 SQL 按订单与渠道判断最后一条失败，但只返回 `order_sn`。任务随后重新取订单并用订单当前 `payChannel` 关单。即使串行，历史微信尝试失败、订单现值为支付宝时也可能处理错渠道。跨实例可能同时领到同一候选，普通索引不提供唯一约束，真实 MySQL 下的序号与锁等待仍需复现。见 [回执 SQL](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentCloseReceiptMapper.java#L25)、[重试服务](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentCloseRetryService.java#L37)、[基线表](../shop-b2b2c-backend/src/main/resources/db/schema.sql#L1309)。

**实施任务。** 归入 [AUD-03](#task-aud-03)，候选保留不可变尝试 ID 或至少原订单与渠道复合身份。数据库以任务唯一键和带版本的认领状态分配执行权，按认领编号或稳定的尝试序号记回执；历史重复行先盘点。新增 Redis 锁可用于减轻竞争，其失效不能破坏数据库认领。补 S48-1 的 V21 存量升级验证，以及一单两渠道、双实例同步取候选、认领后崩溃、晚到响应及达到上限的真实 MySQL 用例。验收读原渠道回执与旧单最终状态，排除执行错渠道、重复消耗重试次数和丢失回执。

### 11.4 首装双超管、迁移就绪与探针（B4/B5/B22/D31a、S50-1/2、S47-1、G37）

**当前状态。** 两云新环境写入 `FINANCE_DUAL_APPROVAL_REQUIRED=true`。 `AdminBootstrap` 只在没有超管时创建首位账号，随后 `DualApprovalAccountValidator` 要求至少两位启用超管。因此空库只执行常规首位引导会启动失败，重复替换 `BOOTSTRAP_ADMIN_*` 也不能创建第二位。校验按角色和启用状态计数，源码基线中未见与这两个列匹配的组合索引，是否有优化价值以实库规模和 `EXPLAIN` 定。两云正式和引导 Nginx 模板均有 `/healthz` 与 `/readyz`，G37 剩余项为 503 透传和实际流量切换。见 [首位超管引导](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/AdminBootstrap.java#L46)、[启动校验](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/DualApprovalAccountValidator.java#L53)、[阿里云探针](../deploy/aliyun/server/nginx-gmall.conf#L82)、[腾讯云探针](../deploy/tencent/server/nginx-shop.conf#L125)。

**实施任务。** B5 增加可审计的第二位超管首装与灾后恢复操作路径，预发布先验证 0、1、2 位启用账号及禁用第二位的行为，再纳入生产手册。审批真实性仍归 [AUD-01](#task-aud-01)。B4 依 [AUD-06](#task-aud-06) 分别验新库 schema、代表性旧库升至当前 V23，覆盖 V20 回调审计表与索引、V21 回执、V22 异常处置、V23 双轨，再验证重复执行和中断恢复。老库排序规则核对外键两端的实际列定义，隔离演练前滚与还原。B22 从 Nginx 请求就绪端点，验证迁移期/失败期 503 不被 SPA 或重定向掩盖，Redis ACL 和写保护故障按真实服务演练。D31a 单独记录 MySQL 版本和 CHECK 执行证据，应用 CAS 仍承担正常交易的并发保护。

### 11.5 清单中需要调整的状态与实施范围

| 来源 | 当前代码与架构判断 | 给开发者的任务或验收 |
| --- | --- | --- |
| B20/D13 商品、费率与模式校验 | 创建商品已经调用 `TradeGuards.requireMoney`、`requireInitialStock` 并校验规格 JSON。费率服务有 `validateRate`，模式切换有 `CollectionMode.requireKnown`。缺 Bean Validation 与业务值可入库是两个判断。见 [商品创建](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L209)、[费率规则](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/MerchantFeePolicyService.java#L253)。 | 按 [D13](#task-d13) 用请求样本核对创建、编辑、进件与费率入口，确定真实漏检后共用领域规则。优先测缺字段、类型错误、价格精度、负库存、批次含无效 SKU 后整批回滚。 |
| B21/D10、B12/D20、P2 D23/D24 | 审计已经核对详情只读、过期任务、独立查单凭证、限流规范化、Redis 503，以及 CORS 白名单和通用异常拦截。旧描述适合作为复现用例来源。 | B21 改为 [AUD-10](#task-aud-10) 的预占事务出口，B12 改为 [AUD-07](#task-aud-07) 的共享配额。D23/D24 查实际路由授权、可信代理和残余单参异常，避免重复建同类拦截器。 |
| B14/F10、B23/T39 | 月费代码当前用次月 10 日到期，TRIAL 免收；这两条和入驻审核角色、初始费率、税率均涉及业务政策。见 [账期](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/SaasFeeBillService.java#L100)、[免收判断](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/SaasFeeBillService.java#L231)。 | 先回归重复出账、缴费确认和欠费恢复审计。配置变更按生效期作用于后续账单，旧账保留快照。产品确定试用、到期、恢复及角色规则后再调整默认。 |
| G44、G47、FB-F17–19 | `PaymentEventRecorder.recordEventStatus` 用 `updateById`，成功时传入 null 也可能留下旧错误原因。 `markFulfillmentFailed` 的 CASE 只保留既有 1，当前为 0 而入参未知时 SQL 可写回 NULL，实际调用是否可达还要跟踪。见 [事件写入](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L38)、[失败迁移](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentEventMapper.java#L111)。 | 给 Mapper 显式定义清空错误、保持旧证据及接收新证据的写法，按前置状态更新。先定 0、1、未知的语义和来源，避免把未知都当成未收款。测失败后成功、晚到旧状态、并发充值和同时间费率冲突，归 D06/F09/AUD-01。 |
| B-P2 前端联调批，S49-1/3、M2/M3 | 管理端 Finance 已展示支付异常并可提交处置，列表加载直接调默认接口，未传分页参数。总闸和商家切换页面也已存在。见 [支付异常列表](../shop-admin-vue/src/views/admin/Finance.vue#L98)、[查询请求](../shop-admin-vue/src/views/admin/Finance.vue#L286)、[收款模式表单](../shop-admin-vue/src/views/admin/Merchants.vue#L829)。 | 优先补筛选、翻页、完整处置状态和角色验证。用大于接口默认页的数据确认页面不会把当前页条数标成总数。前端手验窄屏、长拒绝原因、过期会话、状态刷新，附截图和真实请求。 |
| F14 广告与导流 | 付款成功页已加载 banner 并将 `targetUrl` 绑定到链接，后端已有公开读取端点。继续做配置后台时，入口应约束链接和字段；尚无本次发现的恶意配置写入路径证据。见 [页面链接](../shop-storefront-vue/src/views/PeerPayCashier.vue#L132)、[公开广告接口](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java#L94)。 | 配置写入时限制角色、URL 协议与目标范围，公开端使用展示字段投影。点击与开店转化各有事件，按来源去重，拒绝无效链接且保留合法导航。归 [F14](#task-f14)。 |
| B-P2 文档债、V1–V6 和旧外键重名 | F11/F12 主台账“当前状态”列仍有初版描述。外键已使用 `fk_payment_channel_statement_merchant`，V1–V6 保留等待用户复核。见 [基线外键](../shop-b2b2c-backend/src/main/resources/db/schema.sql#L845)、[吸收清单](2026-11-06-schema-absorption-audit.md)。 | 回写 F11 已有编辑、SKU、运营能力，F12 已有去重、作废、批次、核对与补发，保留验收状态和密钥治理残项。旧重名按真实 MySQL 建库验收。V1–V6 是否退役须在旧库升级证据与用户决策后单独交付。 |

### 11.6 开工顺序与验收门槛

- **首批非支付。** 按开发清单 B1/B2/B19 处理凭据、卡密密钥及异常处置。D13、AUD-10、AUD-07 和 D24 可按接口独立提交。AUD-01 与 AUD-04 归原 F14/F10/F13 安全范围，AUD-06 与 B4/B5/B22/D31a 一起补生产首装和隔离库演练。
- **支付本地前置。** AUD-02.1 的支付尝试先于 AUD-03 查单/关单与回执认领、AUD-09 分账与回退身份、§11.2 开关矩阵。A5 状态迁移、迟到支付处理、A7 回执分类和假渠道用例可在现有环境开发。真实自动退款需明确何种晚到事件可以自动执行，并核对原实收与可退金额。
- **真实渠道验收。** A1–A9 的真实步骤保留账户、产品权限、回调公网、证书及相应代码前置。保留原 P4 的支付宝平台代收延后、SKIPPED 不自动补关、旧单不回填收款模式、员工敏感信息边界。退款回退用可验证的原分账成功凭证，账单验收用渠道资金记录。生产发布按用户手动验证约定执行。

<a id="local-review-findings"></a>

## 12. 本地异议复核与开发依赖修订

本轮复核了工作区两份文档的本地修订，包括全景 §12 的异议 11–20、分级表和 Phase 重排，以及开发清单新增的 A9、B3、B7–B13 等条目。§10–11 的 A/B 引用已按修订稿对齐。下表使用三种独立判断：**源码结论、可开工条件、验收门槛**。本轮逐条检查 AUD-01–10 的实现入口、关联 SQL/配置、现有测试及相关渠道契约。编号、分级统计、重复项和阶段归属只用于文档一致性，不作实现或验收证据。

| 关联任务 | 本轮源码或契约结论 | 开工顺序与验收 |
| --- | --- | --- |
| [AUD-01](#task-aud-01) · B3 | 结算、充值、月费及费率操作仍可将请求指定的复核人记为批准者。不同 ID 和账号有效性不证明由该人发起确认。入驻分阶段审批另按动作核对，G30/G31/G36 分别是单人开发例外、候选人入口与生产配置约束。见 [身份比较](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/FinanceApprovalGuard.java#L55)、[复核人资质](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/FinanceApprovalGuard.java#L112)。 | P0 安全批。第二位超管用自己的已登录身份批准持久化申请，服务端绑定对象、版本和金额。两会话和事务回滚测试可先做，生产第二位超管就绪单列 B5。 |
| [AUD-02](#task-aud-02) · B8/A5 | 发起与回调都查当前 BOUND，历史付款尝试缺原账户快照。另有独立的结算任务缺口，回调成功后结算尽力写入。见 [绑定查询](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L189)、[结算补写入口](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L176)。 | 无渠道账户即可先做尝试表、幂等契约和假回调。尝试身份先于 B9，结算任务的失败恢复单独验。MySQL 验唯一键和并发，平台开放前验真实换绑和旧回调。 |
| [AUD-03](#task-aud-03) · B9/A7 | 主动查单、关单及补偿仍以直收或订单当前渠道为输入。回执候选丢原渠道，首次任务也可能在提交后、记录前丢失。见 [查单](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/PaymentGatewayService.java#L212)、[候选 SQL](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentCloseReceiptMapper.java#L35)。 | B8 定义原尝试身份后实施查单/关单路由。错误类别、假适配器和本地状态可先做。MySQL 验任务抢占、序号和回收，渠道账户到位后验证真实错误码、关单与退款。 |
| [AUD-04](#task-aud-04) · B13 | 公开入驻协议 IP 直接取 XFF 首段，附件已有单文件限制，应用层累计预算和清理待补。代码证明解析入口不一致，实际伪造能否穿过代理取决于真实部署。见 [协议 IP](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PublicOnboardingController.java#L197)、[附件存储](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OnboardingAttachmentService.java#L62)。 | 独立开工。代理头和附件配额单测可先做，真实代理透传、并发额度、私有存储及过期清理由环境验。 |
| [AUD-05](#task-aud-05) · B1/B2/B19 | 源码可确认密钥缺失时回退，以及旧明文兼容。生产历史凭据是否仍有效、旧密文使用哪把钥匙，需要运维和备份证据。见 [密钥选择](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L31)、[旧格式读路径](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L99)。 | 生产 P0 门槛。代码可先拒绝弱默认并准备双读单写，密钥退役需盘点、轮换和恢复验读。主台账 D21 的历史文字按当前种子分离状态解释。 |
| [AUD-06](#task-aud-06) · B4/B5/B6 | 迁移执行与依赖探针没有共享完成状态，存量版本目录已到 V23。首装检查第二位超管与迁移就绪是两个独立门槛。见 [迁移执行](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SchemaMigrationRunner.java#L53)、[就绪检查](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReadinessService.java#L51)。 | 生产发布 P0 门槛。门禁代码及单测可先做，旧库 V20–V23、并发迁移、排序规则、真实恢复和 Nginx 透传须隔离 MySQL/部署环境。 |
| [AUD-07](#task-aud-07) · B12 | 查单在凭证验证前先扣 IP 桶，再扣规范化订单共享桶。已知单号的错误请求可消耗订单预算。独立 queryToken 和 Redis 故障 503 已存在。见 [限流扣减顺序](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderQueryLimiter.java#L23)、[凭证检查](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/BuyerOrderQueryService.java#L19)。 | 独立开工。模拟跨来源错凭证刷单号与合法领取，验证 Redis 原子计数、过期和异常策略。真实部署另核验共享出口误伤。 |
| [AUD-08](#task-aud-08) · B11/B15 | 事件按本地创建时点筛日，渠道用交易时点；已有跨日差异分类和退款时间缺失巡检。应持久化可信发生时间及账单来源，不宜把所有本地 `created_at` 一次性当作旧数据的到账时间。见 [事件业务日过滤](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/PaymentEventMapper.java#L151)、[退款时间巡检](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ReconciliationService.java#L195)。 | B11 与 B15 合并实现。业务日、部分导入及旧数据缺值场景先加用例，MySQL 验索引和回补，再用真实渠道账单验金额守恒。 |
| [AUD-09](#task-aud-09) · B10/A9 | 官方接口明确 `sub_mchid` 表示原分账出资方，`return_mchid` 取原分账的 `receiver_account`。若原接收方是平台才使用对应的平台账号，不能统一用 `sp_mchid`。当前代码把同一个商户号写到两处，并用订单号代分账单号，退款固定取第一条预写明细。见 [适配器](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/WeChatPlatformCashierChannelAdapter.java#L332)、[退款选明细](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L194)、[微信官方契约](https://pay.wechatpay.cn/doc/v3/partner/4012477737)。 | 字段契约已核实，可先写请求测试。完整改造要存原分账回执并按明细核余额，随后真实验证未分账、已分账、多接收方和部分退款。属于平台开启前置。 |
| [AUD-10](#task-aud-10) · B7/B21 | 过期请求经事务代理进入预占，先释放、写关闭，再由外层抛异常触发回滚。旧用例先手动跑定时任务两次，再断言无法预占，未测请求自身提交。见 [事务入口](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L411)、[现有 H2 测试](../shop-b2b2c-backend/src/test/java/com/shop/b2b2c/Sprint1SecurityIntegrationTest.java#L1777)。 | 独立开工。沿现有 H2 Spring 测试夹具补用例，先建 HELD、推过截止，直接请求预占且不先触发 job，另开事务读取订单、预占与库存。修复后仍拒绝新预占，且已有释放落库。并发付款及回执在 MySQL 验。 |

### 12.1 对异议 11–20 的独立裁决

| 异议 | 裁决 | 理由及对开发的影响 |
| --- | --- | --- |
| 11 全景漏了十条全新问题，全部证实 | **不同意整组定性，接受补索引。** | AUD-05/06 与已有凭据、迁移风险重叠，AUD-10 是历史缺陷仍存在。AUD-04 的来源伪造和 AUD-07 的限流误伤可从代码构造条件，实际网关及共享出口影响待验证。保留编号与证据等级，不能把全景 124 加 10 当作独立剩余问题数。 |
| 12 纯代码任务全部无依赖 | **不同意。** | B8 要定义原支付尝试，B9 要消费它，B10 完整退分还要原分账凭证。AUD-10 可独立修，H2 验事务边界，MySQL 验竞争。无渠道凭据可开工与无任务依赖、可上线是三个不同条件。 |
| 13 按审计专项单独聚类 | **仅同意索引。** | AUD 跨已有模块和 D/F 条目，用来标记审计来源。模块统计应按实际子任务去重，不能仅因加一行就更新全量计数。 |
| 14 AUD-10 可用 H2 复现，应归 P0 | **同意验证方式，维持 P1。** | 事务回滚可以通过 H2 Spring 代理用例验证，现有用例先跑 job 因而不能证明请求自身的释放。job 仍有独立事务处理过期，现有证据支持优先修复。若实库出现持续占用或释放停滞，再按影响上调。 |
| 15 双审批应关联 G30/G31/G36 | **同意根因，限定修法。** | 不同会话仅是入口条件。由第二位已授权超管以自己的身份批准同一份已持久化、仍有效的申请，才能形成审批证据。仅让 A 选 B、要求 B 在线或再次输入 A 的密码均无法达成。 |
| 16 查单关单代码可提前 | **同意提前，拒绝只加分布式锁。** | 重试服务只收到订单号并读取当前渠道。单实例也可能关错原尝试。先纠正任务身份和路由，再做数据库认领。Redis 锁是可选优化，不能修正错误目标或保证首次任务持久化。 |
| 17 微信回退字段待对照 | **接受核对要求，字段角色已明确。** | `return_mchid` 取原分账接收方。不能写死为服务商号。只改适配器字段也无法补足原分账单号、渠道成功凭证及并发可退余额。完整修法见 [AUD-09](#task-aud-09) 与 §12.3。 |
| 18 查单共享桶漏排 | **同意排入，限定影响。** | 扣减顺序能推导已知单号遭无效请求刷桶时合法买家可能被限流。当前证据是源码条件，实际阈值、攻击来源及正常用户影响仍要复现。保留认证前来源预算和凭证校验。 |
| 19 T+1 切日根因应明确 | **同意根因，拒绝统一用本地时间回填。** | 事件创建时间和渠道发生时间是不同事实。代码已有跨日分类，处理重点是引入可信业务发生时间和导入完整凭证，旧数据缺证据时归人工。 |
| 20 Phase 应与审计一致 | **部分同意。** | B3 应和凭据治理并行，B7/B12/B13 独立推进。B8 先定接口，B9 和 B10 按实际依赖实施。P0 数量冲突应先核对风险含义，生产安全门槛、平台上线门槛和开发先后分别记录。 |

### 12.2 优先级与放行

本地全景的逐项等级表将 AUD-05 标成 P0，合计及模块表却把它计入 P1，并把 AUD-10 计入 P0。审计采用可执行口径：AUD-01、AUD-05、AUD-06 是生产安全或发布门槛。AUD-10 放到首批独立修复，现有定时任务可兜底，暂按 P1 标注，若实库出现过期释放持续积压再上调。AUD-02/03/09 可在本地阶段开发，其对应缺口仍是平台代收上线前必须通过的门槛。

AUD-10 新增用例后，可在仓库根目录手动执行 `mvn -f shop-b2b2c-backend/pom.xml "-Dtest=Sprint1SecurityIntegrationTest,ReservationExpiryJobTest" test`，预期直接预占的过期请求被拒、库存只释放一次、定时任务仍能兜底。Redis、MySQL、浏览器、部署和真实渠道按各自验收门槛执行，通用命令见第 6 节。统计分组和排期差异只回写文档，当前业务验收保留待验证。

### 12.3 新增源码发现与对 AUD-09 修法的补充

**AUD-09 退分上限检查发生在远程调用之后。** `RefundService.returnSplitBeforeRefund` 先用退款额减服务费冲正构造退分请求，渠道成功后才调用 `recordSplitReverse`。后者此时才锁定分账明细、检查剩余可退金额。现有 `split_amount` 来自本地预写的商户应付额，初始状态为 PENDING，不能证明原接收方已拿到可回退款。即使报文字段修正，仍可能出现先改变渠道资金、随后本地拒绝记账。真实渠道是否接受具体请求待联调。见 [渠道调用](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L211)、[本地锁与上限检查](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PlatformCollectionService.java#L122)、[预写分账明细](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PlatformCollectionService.java#L225)。

**裁决与实施。** 不接受“修正 return_mchid 即可交付”。先从已确认的原分账回执计算接收方剩余可退金额，在本地短事务内锁定并预占，持久化稳定回退号，再由执行者发渠道请求。成功时只确认原预占，明确终态失败才释放。超时或结果不明时按原回退号查证，避免重新分配已在渠道处理的金额。用两个并发部分退款验证预占上限，模拟渠道已成功但本地写入失败，再验证恢复后没有重复退分。退款额减服务费冲正是当前内部公式，落地仍须按原资金流与目标渠道场景核对。

<a id="task-aud-11"></a>

### AUD-11 同键退款重试可能破坏预占与退款金额一致性

**状态与范围。** F05/T33/A4 的本地缺陷，源码和 SQL 可静态确认，复现与修复尚待执行。建议将它列为退款资金闭环的发布前置。可独立开发，无需真实支付凭据。

**根因。** `prepareOrderRefund` 先检查当前订单余额，后查旧幂等键。旧记录 FAILED 时，按**本次请求金额**重新预占，再以 `markRetry` 将旧行改回 PROCESSING。该 SQL 只更新状态、原因和更新时间。服务只在内存对象改退款额、服务费及尾差，返回后网关使用该对象。后续 `finalizeFailure` 重新从库读退款，按**旧金额**释放。另外，既有 PROCESSING 记录返回后，上层 `applyOrderRefund` 也会再次执行渠道请求，当前分支没有区分新认领和同键读回。见 [准备和幂等分支](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundTransactionService.java#L72)、[重试更新](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeRefundMapper.java#L136)、[失败释放](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundTransactionService.java#L218)、[渠道执行入口](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/payment/RefundService.java#L59)。

**复现推导。** 订单实付 100 元、服务费 0 元。用键 K 退 30 元，模拟明确失败并释放 30 元。再用 K 传 50 元，订单会预占 50 元，退款行仍是 30 元。第二次明确失败后按退款行释放 30 元，订单残留 20 元占额。实际数据断言待执行。若某次退款已经用尽余额，余额检查在幂等查询前也可能让合法重放返回 409，开发时需一起覆盖。

**实施任务。**

1. 保留订单与商户归属检查。按现有订单锁查幂等记录，再判断新申请所需的订单状态和余额。对同键校验不可变金额及已定义的业务参数。参数变化返回冲突，创建不同金额须提交新键。已有终态记录返回原结果。
2. FAILED 只有明确渠道终态且满足重试契约才可用同键重试。原退款单、已保存金额、服务费冲正和占额应一致。重试需在同一事务验证余额与更新认领，无法复用原金额时留在明确失败状态。PROCESSING 重放只读取结果或触发查单，重复请求不直接重发。渠道结果不明时保留待核状态及原额度。
3. 扩充 `RefundServiceTest` 和真实事务用例。现有同键用例用 mock 返回 SUCCESS，覆盖范围仅为同金额旧结果不再次占额。新增同键换金额拒绝、同额明确失败后重试、退款用尽余额后的原结果重放、两并发请求只有一次渠道派发、渠道超时后以原凭证查单。失败后回读订单与退款两表，校验占额及服务费恢复。见 [现有同键测试](../shop-b2b2c-backend/src/test/java/com/shop/b2b2c/RefundServiceTest.java#L172)。

执行目录为仓库根目录。实现后可手动运行 `mvn -f shop-b2b2c-backend/pom.xml "-Dtest=RefundServiceTest,Sprint1SecurityIntegrationTest" test`。预期通过上述回归，并保留原有全额、部分退款和费用冲正行为。真实 MySQL 并发及渠道未知状态仍待专项验证。
