<!--
文件：2026-11-06-backend-logic-review.md
用途：对 shop-b2b2c-backend 的 controller/service/dto/entity/校验器/异常处理/过滤器/定时任务做逐层静态审查，聚焦业务状态机、资金与库存、卡密、代付、权限隔离、幂等与接口契约；对 2026-11-04 诊断报告的 35 项已修复条目只做 HEAD 现状核对，不重复旧结论。
维护注意：
  1. 本轮为只读静态分析，未修改任何源码，未执行 mvn/编译/测试/构建/部署；所有“已修复/仍存在”结论均指当前工作区静态代码，不代表运行验证通过。
  2. 行号以本文件生成时的 HEAD 工作区为准，后续提交会使行号漂移；引用时请同时核对符号名。
  3. P0/P1/P2 是本次审查建议的分级，不是用户已确认的排期；标注“需业务确认”的条目必须先确认规则，不能直接按缺陷改写业务。
  4. 修复时须复用既有 TradeGuards、AuthSessionService、OrderIdempotencyService、支付事件与预占状态，不得另建第二套金额/库存/幂等权威。
  5. 验证由用户手动执行：后端 `mvn -f shop-b2b2c-backend/pom.xml test`；前端规则与生产构建按 AGENTS.md 与对应 Sprint 文档。本文不替代验收记录。
-->
# B2B2C 后端业务逻辑静态审查（2026-11-06）

## 0. 审查范围、方法与声明

- 范围：`shop-b2b2c-backend` 的 controller、service/impl、dto、entity、common 校验器、异常处理、Sa-Token 过滤器/拦截器、定时任务；只读对照 `shop-admin-vue`、`shop-storefront-vue` 的调用点。
- 重点：订单与库存预占状态机、卡密导入/去重/作废/分配、代付发起与领取、服务费与金额精度、商品/SKU 上下架与改价、权限与租户隔离、幂等、错误码/HTTP 分类、前后端契约。
- 基线：`docs/2026-11-04-project-diagnostic-report.md` 的 R01–R35。对“已修复”条目只核对现状；R01/R06/R08/R14/R30/R31 等原开放或需专项验证条目不在本轮重新定性。
- 声明：**未运行编译、测试、构建或部署**。下文是静态推断，H2/MySQL 并发与真实渠道行为均未验证；`PaymentEventService` 与 `notifyPaymentSuccess` 当前没有 HTTP 入口，相关 P0 属“接入真实渠道前必须修复”的潜伏缺陷。

## 1. 结论摘要

本轮共确认 **3 项 P0、8 项 P1、12 项 P2**，另有若干“需业务确认”事项。P0 均集中在尚未接通的支付到账/履约接缝；当前因 `payment.mock: false` 且无渠道回调入口，不会立即造成现实资金损失，但一旦接入微信/支付宝即会直接成立。

| 级别 | 数量 | 主题 |
| --- | --- | --- |
| P0 | 3 | 支付事件先记成功后提交、金额校验自证、重复支付被提前返回吞掉 |
| P1 | 8 | 员工读取经营者 PII、卡密去重旁路、默认加密密钥、SKU 编码跨店唯一、下架与预占竞态、代付通道语义、代付收银台未接通、订单列表静默截断 |
| P2 | 12 | 公开接口限流/锁、脱敏强度、客户端 ownerToken、预占有效期、HTTP 状态、并发幂等 500、统计全量、长字段 500、事务内海报、公开实体直出等 |

**最重要的正面结论**：拦截器层的角色门禁、`AuthSessionService` 每次请求的数据库态复核、订单/海报/卡密/库存调整的租户归属校验、预占 CAS 与行锁、`markPaidIfUnpaid` 的预占有效性约束、幂等的请求哈希冲突 409、金额位数守卫、公开店铺白名单，均经核对成立；未发现跨店读取订单、跨店操作卡密或跨店改库存的直接越权路径。

## 2. P0 详细发现

### P0-1 支付事件在订单事务提交前记为 SUCCESS，重放不再补履约

**证据**
- `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java:231-232`：先 `orderMapper.save(order)`，紧接着 `eventRecorder.recordEventStatus(pe.getId(), "SUCCESS", null)`。
- `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java:32-41`：`recordEventStatus` 使用 `Propagation.REQUIRES_NEW`，独立事务立即提交。
- `PaymentEventServiceImpl.java:58-69`：后续同一渠道流水号再次到达时，只要事件已是 `SUCCESS`，直接返回“已成功履约”，不检查订单是否真的已支付/已交付。

**触发条件**
1. 渠道回调进入 `processPaymentEvent`，订单行、卡密、库存更新都在外层事务内完成；
2. 在 `L232` 之后、外层事务真正提交之前发生提交失败、连接中断、进程崩溃或提交阶段异常；
3. 渠道重试同一 `payTradeNo`。

**影响**
支付事件永久为 `SUCCESS`，订单仍是 `UNPAID`，卡密未分配；重试被 `L58-69` 短路，永远不会自动补履约。若渠道已扣款，则形成“资金已收、订单未付、资产未交付”的持久不一致。**P0（资金/资产）**。

**建议修法方向**
- 不要在业务事务内用 REQUIRES_NEW 写 `SUCCESS`；改为 `TransactionSynchronizationManager.registerSynchronization(...afterCommit)`，或把事件状态推进到“履约中”，由提交后的独立步骤确认 `order.order_status` 与交付结果后再记 `SUCCESS`。
- 重放分支不能只看事件状态：`SUCCESS` 时必须回查订单状态与交付结果；若订单未支付，应以同一事件重新驱动履约，或至少转为 `FULFILLMENT_FAILED` 待人工补偿，不能直接返回成功。
- 补一条“事件 SUCCESS 但订单 UNPAID”的补偿扫描，作为最后兜底。

### P0-2 代付到账金额校验使用订单自身金额，金额核对恒真

**证据**
- `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java:495-505`：构造事件时 `.payAmount(order.getPayAmount())`。
- `PaymentEventServiceImpl.java:105-109`：`if (event.getPayAmount() != null && order.getPayAmount().compareTo(event.getPayAmount()) != 0)` 才拒绝。

**触发条件**
任何未来渠道适配器调用 `notifyPaymentSuccess(token, payerId, payTradeNo, channel)` 时。适配器没有传入“渠道实收金额”，服务端拿订单应付金额填进事件，于是少付、部分退款后补付、优惠错算都必然通过校验。

**影响**
金额核对形同虚设；一旦接入真实渠道，少付也会被标记 `PAID` 并交付卡密/服务，直接形成资金损失。**P0（资金）**。若渠道适配器自行在调用前核对金额，可降级，但当前接口签名没有金额参数，无法从契约上保证。

**建议修法方向**
- 把 `notifyPaymentSuccess` 的签名改为显式接收渠道实收金额（以及币种、商户号），或以“渠道原始回调对象”为入参，禁止服务层从订单反填。
- `processPaymentEvent` 中金额缺失时应拒绝或标记 `AMOUNT_UNVERIFIED`，不得默认取订单金额（`L87` 的默认回填同样应删除或仅用于测试夹具）。
- 增加“应付≠实收”的回归用例（少付、多付、币种不符）。

### P0-3 已支付订单在记录支付事件之前提前返回，第二笔付款无审计与退款触发

**证据**
- `PeerPayServiceImpl.java:486-488`：`if (Set.of("PAID","COMPLETED","SHIPPED").contains(order.getOrderStatus())) { return order; }`，该判断位于参数校验与事件构造之前。

**触发条件**
1. 买家重复支付（同一订单第二次扫码/第二个渠道流水号）；
2. 两个付款人几乎同时为同一代付账单付款；
3. 渠道重试时使用新的 `payTradeNo`。

**影响**
第二笔资金没有任何 `payment_event` 记录，既不进入对账，也没有退款/补偿线索；`payTradeNo` 唯一性排重也失去作用。对账时会出现“渠道有款、平台无流水”。**P0（资金/审计）**。

**建议修法方向**
- 删除该提前返回，或改为“记录事件 + 标记 `DUPLICATE_PAYMENT`/`OVERPAYMENT` + 返回已支付结果”，事件必须先落库；
- 对“订单已支付但流水号不同”的情况建立重复支付补偿队列（人工或自动退款）；
- 同时校验 `payerId/payTradeNo/channel` 的合法性，不能因订单已支付而跳过。

## 3. P1 详细发现

### P1-1 商家员工可读取经营者身份证/银行卡，且可发起代客推单

**证据**
- `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaTokenConfigure.java:33`：`/api/v1/merchant/**` 对 `MERCHANT_STAFF` 放行。
- `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java:124-128`：`/auth/me` 把完整 `MerchantStore` 放进响应。
- `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java:200-216`：`/merchant/profile` 无角色判断，直接返回完整 `MerchantStore` 与 `MerchantPaymentAccount`。
- `MerchantStore` 含 `idCardNumber`；`MerchantPaymentAccount` 含 `bankAccountNumber`，两个实体头部均声明公开接口必须走白名单/脱敏。
- `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java:351-362`：`push-pay` 无角色校验，员工可用任意 `negotiatedPrice` 建单。

**触发条件**
`MERCHANT_STAFF` 登录后调用 `/api/v1/auth/me`、`/api/v1/merchant/profile`，或调用 `/api/v1/merchant/orders/push-pay`。

**影响**
员工可读取店主身份证号、银行卡号等强 PII；可创建大幅折价订单，影响抽佣与结算。与 R02“员工与店长权限分离”的已修复状态不符，属于修复未覆盖的边界。**P1（隐私/资金配置）**。

**建议修法方向**
- `/auth/me` 与 `/merchant/profile` 对员工只返回展示白名单（店名、店铺编码、公告、Logo、联系电话），身份证/银行卡仅对 `MERCHANT_ADMIN`/`SUPERADMIN` 返回，且银行卡默认脱敏。
- 明确员工操作矩阵：推单、改价、履约、发货是否允许员工执行；至少 `push-pay` 应限制为店长以上或增加二次授权。
- 复用 `AuthSessionService` 与既有角色常量，不在控制器里散落 `isLogin()` 条件判断。

> 需业务确认：员工是否应具备推单/改价权限；身份证与银行卡对店长是否必须明文展示。

### P1-2 卡密去重对“已加密但无指纹”的历史数据存在旁路

**证据**
- `shop-b2b2c-backend/src/main/resources/db/schema.sql:123`：`card_fingerprint` 允许 NULL；`:129` 唯一键为 `(merchant_id, card_fingerprint)`，MySQL 允许多个 NULL。
- `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java:64-72`：先按指纹查；未命中时 `historyContents(merchant)` 回退，对 `card_content` 原文计算指纹。
- `CardCipherService.java:133-140`：`fingerprint` 对传入文本直接 SHA-256。

**触发条件**
存量卡密已是 `ENC:v1:...` 密文但 `card_fingerprint IS NULL`（历史迁移未回填）；再导入相同明文卡密。回退路径对密文做哈希，永远匹配不到明文指纹。

**影响**
重复卡密被当作新卡导入，`product_sku.stock_quantity` 被虚增；后续分配时可能出现“同一卡密卖两次”或库存与实物不一致。**P1（资产/资金）**。当前 seeds 为明文卡密且指纹为 NULL，回退能命中；真正风险在“加密后未回填指纹”的存量。

**建议修法方向**
- 一次性迁移：对 `card_fingerprint IS NULL` 的行解密后计算明文指纹并回填，再补唯一约束/非空约束。
- 回退路径应识别 `ENC:v1:` 前缀并先解密再哈希；迁移完成后删除全表回退，避免每次全量读。
- 增加“加密存量 + 重复导入”回归用例。

### P1-3 卡密加密缺省使用源码内置密钥，且无密钥版本/轮换

**证据**
- `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java:63-68`：未配置 `shop.security.card-secret-key` 且无 `CARD_ENCRYPTION_KEY` 时，回退到字符串 `shopizer-b2b2c-card-default-secret-seed-2026` 的 SHA-256。
- `shop-b2b2c-backend/src/main/resources/application.yml` 未提供 `shop.security.card-secret-key`。
- `schema.sql:326-330` 的种子卡密为明文，`card_fingerprint` 为 NULL。
- `BuyerOrderView.java:20` 与 `ProductController` 的兼容构造器还会 `new CardCipherService()`，绕过 Spring 注入。

**触发条件**
生产/预发未注入密钥环境变量，或某个组件走了无参/兼容构造器。

**影响**
任何拿到源码或默认种子的人都能解密库内卡密；同一密钥无版本号，无法轮换而不破坏历史订单读取。**P1（资产机密性）**。R05 虽标已修复，但“缺密钥拒绝启动”与“密钥轮换”未落地。

**建议修法方向**
- 生产环境缺少显式密钥时**启动失败**，不回退默认值；开发环境可显式配置 dev key。
- 密文增加密钥版本（如 `ENC:v2:<keyId>:`），支持按版本解密与灰度轮换。
- 所有组件只使用注入的单例 `CardCipherService`，删除无参/兼容构造器的生产可用性。
- 种子卡密改为加密值或明确标注仅开发夹具，并在启动时校验指纹回填。

### P1-4 `product_sku.sku_code` 全局唯一导致跨店编码冲突与不透明 500

**证据**
- `schema.sql:111`：`UNIQUE KEY uk_sku_code (sku_code)`，没有 `merchant_id` 维度。
- `ProductController.java:183-193`：创建 SKU 直接 `skuMapper.save(sku)`，未捕获唯一键冲突；全局异常处理器把 `DataIntegrityViolationException` 归入通用 `Exception`，返回 500「系统繁忙」。

**触发条件**
商户 B 使用与商户 A 相同的 `skuCode`（常见如 `DEFAULT`、`SKU-001`、`年卡`）。

**影响**
后创建方发布失败且只看到 500；同一编码被先占后，其他商户无法使用，形成跨租户的资源抢占与存在性侧信道。**P1（租户隔离/可用性）**。若业务确认 SKU 编码本就要求全平台唯一，则应改为明确的 409 文案并保留唯一约束。

**建议修法方向**
- 首选改为 `(merchant_id, sku_code)` 唯一，符合多租户语义；
- 若必须全局唯一，则在创建/编辑时预检并返回 409「SKU 编码已被使用」，同时避免泄露对方店铺信息；
- 补充并发创建同编码的回归用例。

> 需业务确认：SKU 编码是否承担跨店统一条码职责。

### P1-5 商品下架不校验有效预占，支付可在 CAS 为 PAID 后失败

**证据**
- `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductEditingService.java:71-93`：`changeStatus` 只校验店铺状态、SKU 与金额，未检查 `HELD` 预占。
- `PaymentEventServiceImpl.java:165-171`：先 `markPaidIfUnpaid` 把订单 CAS 为 `PAID`（`:139-150`），随后 `TradeGuards.requirePurchasable` 因商品已下架抛异常；该处没有 `recordEventStatus`，事件停留在 `PROCESSING`。

**触发条件**
买家已 `pre-lock` 成功（库存已扣、订单 `RESERVED_LOCK`），商家此时下架商品，买家完成支付。

**影响**
订单已置 `PAID`（资金已收），但商品不可购买，交付被拒；事务回滚后订单仍是 `UNPAID`，可售库存已被预占扣减，事件为 `PROCESSING`。若无补偿任务，会形成“钱收了、货没发、库存锁着”。**P1（资金/资产）**，接入真实渠道后接近 P0。

**建议修法方向**
- 下架前检查是否存在该商品/SKU 的 `HELD` 预占；有则拒绝或要求先释放/等待到期。
- 支付侧把 `requirePurchasable` 前移到 CAS 之前，或对已支付但不可履约的订单建立自动退款/人工补偿。
- 该异常必须写 `FULFILLMENT_FAILED` 事件状态，不能留在 `PROCESSING`。

> 需业务确认：已预占订单遇到下架时，是允许完成本单还是强制退款。

### P1-6 代付/推单把店铺收款模式写入 `payment_channel`，与渠道枚举语义冲突

**证据**
- `PeerPayServiceImpl.java:108`：`order.setPaymentChannel(store.getPaymentChannelType() != null ? store.getPaymentChannelType() : "WECHAT")`，而 `MerchantStore.paymentChannelType` 的取值是 `A/B/C`（平台收付通/商家直收/测试桩，见 `MerchantController.java:180-190`）。
- `PeerPayServiceImpl.java:212` 推单同样写入店铺模式。
- `PaymentEventServiceImpl.java:48` 与 `PeerPayServiceImpl.java:490` 又把 `payment_channel` 当作 `WECHAT/ALIPAY` 使用。

**触发条件**
任何代付/推单创建；后续统计、对账、渠道筛选读取 `payment_channel`。

**影响**
同一列混用“收款模式”和“支付渠道”两套枚举：`A/B/C` 订单进入渠道统计会被判为未知；退款/对账按渠道筛选会漏单。**P1（数据语义/对账）**。

**建议修法方向**
- 拆分字段：`collection_mode`（A/B/C）与 `payment_channel`（WECHAT/ALIPAY/…）分别存储；若暂不能加列，至少支付渠道保持 `WECHAT/ALIPAY`，收款模式另存。
- 历史数据做枚举清洗，统计口径固定为支付渠道。

> 需业务确认：A/B/C 是否就是实际支付渠道，还是仅代表收款结算模式。

### P1-7 代付收银台前端未调用预占/取消接口，付款链路不可用

**证据**
- `shop-storefront-vue/src/views/PeerPayCashier.vue:471-494` 只调用 `detail`、`poster`、`success-ad`；文件内没有 `/pre-lock/` 或 `/cancel-lock/` 调用。
- 同文件 `quickPay` 为占位提示（约 `:502-504`），没有实际支付动作。
- 后端 `PeerPayController.java:59-78` 已提供预占与取消；`PaymentEventServiceImpl.java:142` 的代付支付严格要求 `RESERVED_LOCK + lock_expire_time > now()`。

**触发条件**
买家打开代付收银台尝试付款。

**影响**
即使未来接入渠道，前端不预占则代付支付必然被 `markPaidIfUnpaid` 拒绝；预占/取消能力也没有用户入口。**P1（功能可用性）**。当前支付未开放，属“接通前必须补齐”的契约缺口。

**建议修法方向**
- 收银台进入付款步骤前调用 `pre-lock` 并保存 `ownerToken`（仅内存/会话），离开/超时调用 `cancel-lock`；
- 预占失败（已被他人占用、过期）要有明确 UI 状态与刷新路径；
- 前端 `idempotency.mjs` 的键要与预占 ownerToken 区分，避免复用。

> 需业务确认：代付是否必须“先预占后付款”，还是允许无预占直接付款。

### P1-8 订单列表前端不分页、后端静默截断 200 条

**证据**
- `shop-admin-vue/src/views/merchant/Orders.vue:429` 与 `shop-admin-vue/src/views/admin/Orders.vue:172` 调用 `/merchant/orders`、`/admin/orders` 均不带 `page/pageSize`。
- `OrderController.java:204-210`：无参数时固定 `findByMerchantIdPaged(merchantId, 1, 200)`；`:412-418` 管理员同样固定 200。
- `TradeOrderMapper.java:110-118,127-135`：pageSize 上限 200，超出被截断。

**触发条件**
单店或平台订单超过 200 条。

**影响**
第 201 条以前的订单在页面上完全不可见，商家无法发货/履约、平台无法审计；没有任何“已截断”提示，属于静默数据丢失式可用性问题。**P1（运营可用性/数据可见性）**。

**建议修法方向**
- 前端增加分页/游标加载并展示总数；后端返回 `hasMore/total`；
- 或在无分页参数时返回明确错误，禁止静默截断；
- 统计接口继续使用轻量投影，但列表必须可分页。

## 4. P2 详细发现

| 编号 | 发现 | 证据 | 触发/影响 | 建议方向 |
| --- | --- | --- | --- | --- |
| P2-1 | 公开代付详情、取消预占、成功页广告无限流 | `PeerPayController.java:50-56,73-78,81-85` | 匿名高频请求可压 DB；`cancel-lock` 还会取 `FOR UPDATE` 行锁 | 复用 `TradeCostLimiter`/`OrderQueryLimiter`，对 token+IP 限流；取消接口先做轻量存在性检查再取锁 |
| P2-2 | 手机号脱敏泄露 7/11 位 | `PeerPayServiceImpl.java:311-315` | 公开分享页任何人可见前 3 后 4 位，配合店铺联系方式可定位买家 | 手机号只保留后 4 位或中间 4 位；邮箱只保留首字符与域名；按业务确认展示粒度 |
| P2-3 | 客户端 `Idempotency-Key` 被当作预占 ownerToken | `PeerPayServiceImpl.java:368-374,411` | 知道/复用同一键的客户端可取消他人预占；键不是身份凭证 | 服务端生成随机 ownerToken 并仅在响应返回；幂等键与所有权凭证分离 |
| P2-4 | 预占到期时间可晚于订单 15 分钟总有效期 | `PeerPayServiceImpl.java:363-366,410,463-464` | 第 14 分钟预占，锁到第 29 分钟；详情页按 `createdAt+15` 却显示过期，口径不一致 | 统一订单生命周期：预占不得超过 `createdAt+15`，或明确订单有效期随预占延长 |
| P2-5 | `RESERVED_LOCK` 且 `lockExpireTime` 为空时永不释放 | `PeerPayServiceImpl.java:463-466` | 历史/异常数据可永久占用库存 | 定时任务对 `RESERVED_LOCK + NULL` 设定兜底截止时间；迁移补数据 |
| P2-6 | 业务异常仍返回 HTTP 200 | `GlobalExceptionHandler.java:81-85` | 监控/网关无法按状态码识别失败；R34 的“HTTP 分类”未真正落地 | 明确契约：若坚持 body code，需在网关/监控按 body 统计；若要 HTTP 语义，则 4xx/409/423 分类并同步前端 |
| P2-7 | 金额 `scale > 2` 直接拒绝，`18.800` 这类合法数值被拒 | `TradeGuards.java:43-48` | JSON 传入三位小数即 400，前端/第三方易踩 | 先 `stripTrailingZeros` 再判位数，或按“最多两位有效小数”判定 |
| P2-8 | 同一幂等键并发请求重试 3 次后仍可能 500 | `OrderIdempotencyService.java:89-91` | 高并发同键（客户端重试风暴）返回 500 而非稳定重放 | 冲突时短暂退避后读取已完成快照；仍冲突返回 409/429，不抛通用异常 |
| P2-9 | 统计接口把全量订单载入内存 | `TradeOrderMapper.java:137-148` | 万级订单每次统计全表扫描，虽只取 4 列 | 改 SQL `GROUP BY` 聚合；保留 `OrderStatistics` 输出口径 |
| P2-10 | 发货/履约/推单长字段无长度校验，超长直接 DB 500 | `OrderController.java:333-346`（物流公司/运单号）；`PeerPayServiceImpl.java:527-531`（serviceVoucher）；推单的 `customerContact/sellerNote` | 超列宽触发 `DataIntegrityViolationException`，返回通用 500 | 统一用 `ManagementGuards.text` 按列宽校验，返回 400 |
| P2-11 | 代付创建在数据库事务内渲染海报 | `PeerPayServiceImpl.java:138-141` | 750×1334 PNG 光栅化耗时占用连接/事务，放大锁等待 | 先落订单并提交，再在事务外生成海报并单字段回写；失败可重试 |
| P2-12 | 公开商品详情直接序列化 `Product`/`ProductSku` 实体 | `ProductController.java:89-94` | 暴露 `merchantId`、库存、成本价（若实体含）等非展示字段；未来加字段会被动泄露 | 使用 `PublicProductView`/专用详情 DTO 白名单 |
| P2-13 | 迟到支付提示“已进入自动退款与补偿流程”，但无退款实现 | `PaymentEventServiceImpl.java:116-121` | 运营/买家误以为已退款；实际只有 `LATE_PAYMENT` 事件 | 文案改为“已记录，待人工处理”，或补齐退款补偿任务后再宣称 |
| P2-14 | 幂等快照 72h 清理后同键重试会重新建单 | `IdempotencyPurgeJob.java:11-12,46-52` | 超期重试产生重复订单 | 保留键→订单号的轻量映射（不含敏感响应），或延长保留并明确客户端重试窗口 |

> 表中 P2-13、P2-14 涉及对外承诺与保留策略，前端/运营文案需同步，属“需业务确认”。

## 5. 经核对健全的关键路径（正面结论）

1. **路由级权限门禁成立**：`SaTokenConfigure.java:31-44` 对 `/api/v1/admin/**` 强制 `SUPERADMIN`，对 `/api/v1/merchant/**` 只放行平台管理员与商家角色；`/api/v1/public/**`、`/peer-pay/**` 为匿名入口，未混入可信落账路由。
2. **会话与数据库态复核成立**：`AuthSessionService.requireCurrentUser()` 在每次受保护请求中校验用户仍激活、角色与 token 快照一致、商户绑定未变，避免“改角色后旧会话继续生效”。
3. **租户隔离成立**：订单详情按 `merchantId` 校验（`OrderController.java:260-265`）；海报接口跨店 404 且 `Cache-Control: no-store`；卡密导入/作废/批次/库存调整均先锁商户/商品/SKU 再操作；`findStoredPoster` 带 `merchantId`。
4. **预占状态机成立**：`preLockInventory` 先锁订单行、校验商品可购与 SKU 归属，再 `deductStock`，最后插入 `HELD`；`transition(orderId, "RELEASED"/"CONSUMED")` 为 CAS，重复释放/核销不会双加或双扣；`release` 后 `addStock` 校验影响行数。
5. **支付预占约束成立**：代付单走 `markPaidIfUnpaid`，要求 `peer_status='RESERVED_LOCK'` 且 `lock_expire_time > NOW()`；普通单走 `markOrderPaidIfUnpaid`；CAS 失败即拒绝，避免无预占代付被直接置 `PAID`。
6. **幂等语义成立**：`OrderIdempotencyService` 强制 16–128 位键、拒绝嵌套事务、在 `TransactionTemplate` 内 claim/lock/action/complete；请求哈希不同返回 409；响应快照剥离海报并在重放时从订单补齐，契约字段顺序保持不变。
7. **金额与数量边界成立**：`TradeGuards.requireMoney` 拒绝负值/零/超上限/超过两位小数；`requireQuantity` 限制 1–10000；`requireSkuOwnership` 校验商品-SKU-商户三元归属；`requireInitialStock` 禁止自动卡密初始库存非 0。
8. **卡密分配原子性成立**：`lockAvailableCards(... ORDER BY id LIMIT qty FOR UPDATE)` + `claimCard` 要求 `status=0` 且商户/SKU 匹配，抢卡失败即回滚；订单明细卡密快照加密存储，商家列表脱敏，详情按角色解密。
9. **幂等/限流失败关闭成立**：Redis 不可用时 `AuthLoginLimiter`/`TradeCostLimiter`/`OrderQueryLimiter` 返回 503，不退化为匿名放行；查询接口失败消息统一，避免枚举订单。
10. **定时任务游标扫描成立**：`ReservationExpiryJob` 对 HELD 预占与超时未付订单分别游标分页，批次不足 100 重置游标，单条异常不阻塞后续；`IdempotencyPurgeJob` 只删已完成且超保留期记录，未完成占位保留。

## 6. R01–R35 现状核对（只核对，不复述旧结论）

| 编号 | 原状态 | HEAD 现状 | 核对说明 |
| --- | --- | --- | --- |
| R01 | 待处理 | **仍存在** | `application.yml:20` 默认数据库口令、`:33` `sql.init.mode=always`；`schema.sql:279-330` 演示账号/完整身份证/银行卡/明文卡密。属环境与种子策略，需环境负责人处理。 |
| R02 | 已修复 | **部分保留** | 拦截器仍对 `MERCHANT_STAFF` 放行全部 `/merchant/**`；`/merchant/profile` 与 `push-pay` 无角色限制，员工可读 PII、可推单（P1-1）。 |
| R03 | 已修复 | 成立 | `SaTokenConfigure.java:52` CORS 已包含 `X-Reservation-Token`，来源仍为精确白名单。 |
| R04 | 已修复 | 成立 | 商家列表剥离 `queryToken/posterUrl` 与卡密正文；详情仅店长/超管解密；管理员列表不返回明细。 |
| R05 | 已修复 | **部分保留** | 导入/订单快照已加密；但默认密钥回退、种子明文、无密钥版本（P1-3），且 `BuyerOrderView` 兼容构造器绕过注入（P1-3/P0-4 相关）。 |
| R06 | 待专项验证 | 未复核 | 代理拓扑/真实 IP 需部署环境证据，本轮静态无法判定。 |
| R07 | 已修复 | **部分保留** | 建单/预占/海报有限流；`detail`、`cancel-lock`、`success-ad` 无限流（P2-1）。 |
| R08 | 待专项验证 | 未复核 | 会话/日志/依赖审计需运行与扫描证据。 |
| R09 | 已实现·待人工验证 | **未全面复核** | 店铺配置已改定向 SQL；本轮未逐一验证所有店铺写者与并发交错，需按 Sprint23 用例人工执行。 |
| R10 | 已修复 | 成立（有边界） | 同尝试凭证可恢复现有预占且不重复扣库存；但 ownerToken 直接取客户端幂等键（P2-3）。 |
| R11 | 已修复 | 成立 | 游标前移 + 批次不足重置，失败记录不会永久堵住后续。 |
| R12 | 已修复 | 成立 | 定时任务统一扫描无预占超时意向单并调用 `expireIfNecessary` 关单；已支付单不受影响。 |
| R13 | 已修复 | **不成立/需重修** | 支付事件已建模，但 `SUCCESS` 先提交（P0-1）、金额自证（P0-2）、重复支付吞掉（P0-3），核心目标“到账与履约解耦且可追溯”未达。 |
| R14 | 待专项验证 | 未复核 | 真实 MySQL 锁序/库存守恒需用户执行并发测试。 |
| R15 | 已修复 | **部分保留** | 主要约束已有；`uk_sku_code` 全局唯一是跨租户缺陷（P1-4），指纹 NULL 唯一键可绕过去重（P1-2）。 |
| R16 | 已修复 | 成立 | 建单/代付/推单均服务端按 SKU 价计算，前端折扣未进入订单金额。 |
| R17 | 已修复 | 基本成立 | 创建/编辑共享 `TradeGuards`；但创建商品直接 `status=1` 且未校验店铺营业状态，与编辑上架条件不一致（P2 级边界）。 |
| R18 | 已修复 | 未逐项复核 | 种子卡密已存在；本轮未逐 SKU 核对“未售数=可售数”。 |
| R19 | 已修复 | 成立 | `CategoryController.java:41` 显式 `setId(null)`，新增不覆盖既有类目。 |
| R20/R21/R22 | 已修复 | 未复核（前端范围） | 前端路由/文案本轮只读对照调用点，未展开页面状态与文案审查。 |
| R23 | 后续修订待人工验证 | 基本成立 | `MerchantController.java:166-175` 支持 `clearFeeFreeUntil` 与 null 保留；需人工验证日期清空与费用专用 SQL。 |
| R24 | 已修复 | **部分保留** | 列表已分页且消除 N+1，但前端不分页导致 200 条截断（P1-8）；统计仍全量载入（P2-9）。 |
| R25 | 已修复 | **部分保留** | 指纹索引查询已优先；全表回退仍存在且对密文失效（P1-2）。 |
| R26 | 已修复 | **部分保留** | 详情/重生成/幂等副本已减载；创建代付仍在事务内渲染海报（P2-11）。 |
| R27 | 已修复 | 成立（有契约边界） | 清理任务与保留期已实现；超期同键重试会重建单（P2-14）。 |
| R28–R32 | 已修复/待验证 | 未复核 | 阿里云/腾讯构建、部署、TLS、备份恢复属部署范围，本轮不运行、不判定。 |
| R33 | 已修复 | 未复核 | 测试执行状态需用户手动运行；本轮未执行。 |
| R34 | 已修复 | **部分保留** | 缺参/方法/媒体类型已分类；`BusinessException` 仍 HTTP 200（P2-6），若“HTTP 分类”是目标则未完成。 |
| R35 | 已修复 | **部分保留** | 入参有 `StrictIdDeserializer` 等严格解析；但出参 `PublicProductView.id` 等仍为 JSON number，未见全局 Long→String 序列化。 |

## 7. 待确认问题（业务规则，非纯技术缺陷）

1. **代付是否强制“先预占后付款”**：若强制，前端收银台必须补 `pre-lock/cancel-lock`（P1-7）；若允许无预占，`markPaidIfUnpaid` 的代付约束需重新定义。
2. **`payment_channel` 与店铺收款模式 A/B/C 的关系**：A/B/C 是支付渠道还是结算模式？决定 P1-6 是拆列还是改枚举。
3. **SKU 编码是否要求全平台唯一**：决定 P1-4 改为租户维度唯一，还是保留全局唯一但返回明确 409。
4. **已预占订单遇到下架的处理**：允许完成本单，还是强制退款？决定 P1-5 的校验位置与补偿方案。
5. **员工操作矩阵**：员工是否可推单、改价、发货、履约、查看卡密明文？决定 P1-1 的角色拆分。
6. **身份证/银行卡对店长的展示粒度**：明文、脱敏还是仅超管可见。
7. **订单 15 分钟有效期与预占 15 分钟的关系**：预占是否可延长订单总有效期（P2-4）。
8. **幂等键保留期与客户端重试窗口**：72h 后同键重试是否必须继续幂等（P2-14）；若必须，需保留轻量键→单号映射。
9. **迟到支付的对外承诺**：是否已具备自动退款能力；没有则文案不得宣称（P2-13）。
10. **统计口径**：`REFUNDED` 计入历史支付、佣金只计 PAID/SHIPPED/COMPLETED 是文档化口径；确认是否作为对外报表口径。

## 8. 建议优先处理顺序

**第一批（接入真实支付前，阻断级）**
1. P0-1：事件 `SUCCESS` 改为提交后确认；重放必须回查订单并补履约。
2. P0-2：`notifyPaymentSuccess` 显式传入渠道实收金额，删除默认回填。
3. P0-3：删除已支付提前返回，重复支付必须先落事件并转补偿。
4. P1-5：下架与预占互斥；支付侧 `requirePurchasable` 前移或补偿。
5. P1-7：收银台补预占/取消与付款状态机（与业务确认后实施）。

**第二批（隐私与资产）**
6. P1-1：员工 PII 与推单权限收口，`/auth/me`、`/merchant/profile` 白名单化。
7. P1-3：缺密钥启动失败 + 密钥版本/轮换；清理兼容构造器。
8. P1-2：指纹迁移与回退解密，补唯一/非空约束。
9. P1-4：SKU 编码唯一维度按业务确认调整，错误改 409。

**第三批（可用性与数据语义）**
10. P1-6：拆分公司收款模式与支付渠道，清洗历史枚举。
11. P1-8：订单列表分页与截断提示。
12. P2-9/P2-11：统计 SQL 聚合、海报移出事务。
13. P2-6/P2-7/P2-8：错误码与金额/幂等边界统一。

**第四批（治理与长期）**
14. P2-1/P2-2/P2-3/P2-4/P2-5：公开接口限流、脱敏、ownerToken、预占有效期兜底。
15. P2-10/P2-12/P2-13/P2-14：字段校验、公开 DTO、迟到支付文案、幂等保留策略。
16. R01/R06/R08/R14/R28–R33：按原环境/部署轨道由用户手动验证，静态审查不替代。

## 9. 验证状态与未验证项

- 本轮**未执行** `mvn test`、编译、前端构建、部署脚本或数据库连接；所有结论来自静态阅读。
- **未验证**：真实 MySQL 下的锁等待/死锁与库存守恒（R14）、Redis 限流在代理拓扑下的真实 IP（R06）、支付渠道联调（P0 系列在接入后才可复现）、前端页面交互（R20–R22）、构建与备份恢复（R28–R32）。
- 建议在修复 P0-1/P0-2/P0-3 时同步补充：重复 `payTradeNo`、事务提交失败、少付/多付、重复支付、事件 SUCCESS 但订单 UNPAID 的回归用例；修复 P1-5 时补充“预占中下架+支付”的交错用例；修复 P1-2 时补充“加密无指纹存量+重复导入”用例。
