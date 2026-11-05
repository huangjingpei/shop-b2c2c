<!--
文件：2026-11-06-remediation-plan.md
用途：合并 2026-11-06 后端逻辑、后端持久化并发、前端部署三份审查与两份诊断纠错/基线文档，按“修复单元”重组为唯一的排期与状态入口；保留 D/F/R 编号用于追溯，补充本次合并编号 T01–T40（T36–T38 为合并后发现无法归入既有卡片的补充项；T39/T40 为 2026-11-06 商家结算设计重构 S5 时新增，编号不重排）。
维护注意：
 1. 本文件是后续开发的唯一排期与状态入口；状态只能按代码现状与用户反馈更新，代理不得自行填写“已验证”。
 2. 依据为 HEAD bcb1dab 的静态代码核对；未修改源码，未运行 mvn/编译/测试/构建/部署，未连接 MySQL/Redis。“待人工验证”不等于通过。
 3. 行号会随提交漂移，修复时以符号名定位；新增/调整任务须同步本表与本文件 §2 阶段判据。
 4. 同一根因已合并为一个修复单元，不得按文档来源重复建项；若需拆分，先在 §6 说明取代关系并保留原编号。
 5. P0/P1/P2 是“后果严重度 × 触发概率 × 改动风险”的建议排序，不是用户已批准的排期；业务规则以 §5 已确认项为准；Q01–Q21 与 S01–S12 已全部确认或撤回，不再有未确认业务规则。
 6. 修复必须复用既有 TradeGuards、AuthSessionService、OrderIdempotencyService、支付事件与预占状态机、idempotency.mjs/storedPoster.mjs，禁止另建第二套金额/库存/幂等/请求失效权威。
-->
# B2B2C 修复清单与排期入口（2026-11-06）

复核基线：`bcb1dab`。
合并输入（冲突处一律以当前代码事实为准，不照抄旧状态）：
- [后端业务逻辑审查](2026-11-06-backend-logic-review.md)
- [后端持久化与并发审查](2026-11-06-backend-persistence-concurrency-review.md)
- [前端与部署审查](2026-11-06-frontend-deploy-review.md)
- [诊断文档纠错](2026-11-06-diagnostic-docs-errata.md)
- [纠正后基线](2026-11-06-diagnostic-baseline-revised.md)
- 原编号体系：[主台账 D/F](B2B2C_项目缺陷与未实现功能清单_2026-11-03.md)、[11-04 R 索引](2026-11-04-project-diagnostic-report.md)

---

## 1. 状态口径

| 状态 | 含义 | 谁能填写 |
| --- | --- | --- |
| 待修复 | 代码现状仍存在该问题，或尚未开始 | 代理按代码核对填写 |
| 修复中 | 已开始改代码/补用例，尚未交付完整验证入口 | 代理 |
| 已实现·待人工验证 | 代码、用例与说明齐备，等待用户执行验证 | 代理（须附准确命令与预期结果） |
| 已验证 | 用户已执行验证并明确反馈通过 | **仅用户反馈；代理不得自行标通过** |

补充规则：
- 部分完成不得写“已验证”，只能保留“修复中”或“已实现·待人工验证”，并在条目标明残留子项。
- 本文件所有条目当前一律按代码现状填“待修复”或“已实现·待人工验证”；没有任何条目可填“已验证”。
- 状态变更须写明依据（提交、文件、验证命令与用户反馈时点），不得用文档描述替代代码事实。

---

## 2. 阶段划分

任务总数：**40 个修复单元**（T01–T40；其中 T36–T38 为合并后发现无法归入既有卡片而补充；T39/T40 为商家结算设计重构 S5 时新增，编号不重排），另有 9 项验证项（V01–V09，见 §7），不占修复单元编号。
分布：S0 4 项、S1 8 项、S2 6 项、S3 6 项、S4 10 项、S5 6 项 = 40 项。
阶段总数：**6 个**（S0–S5）。

| 阶段 | 主题 | 包含任务 | 进入下一阶段判据 | 技术依赖 |
| --- | --- | --- | --- | --- |
| S0 生产阻断 | 让明细页、代客推单、后台登录、阿里云启动先可用且不泄露默认凭据 | T01、T02、T03、T04 | 四项代码完成且各自最小回归通过；生产 `.env` 与种子账号核对结论已记录（可为“需环境负责人确认”）。 | 无前置；T03/T04 涉及部署配置，须与 S3 的备份/回滚约定一致，但不阻塞 S1。 |
| S1 资金与订单生命周期 | 支付事件顺序、实收金额、重复支付、普通订单截止、下架竞态、预占所有权、到期释放 | T05、T06、T07、T08、T09、T10、T11、T12 | P0 三连（T05–T07）同批完成并通过“提交失败/重放/少付/重复付款”用例；T08 截止条件统一；T09 下架与支付交错用例通过；T11 的 V01/V02 故障注入结论已记录。 | 依赖 S0 无；T05–T07 必须同批；T08 与 T11 共用 SQL/索引改造，须同批评审；T10 前端改动依赖 T05 的事件契约稳定。 |
| S2 权限、隐私与资产安全 | 员工 PII/推单权限、库内卡密、备份密钥、默认凭据、跨店 SKU、HTTP 状态 | T13、T14、T15、T16、T17、T18 | T13 权限矩阵按业务确认结果落地；T14 存量指纹迁移在隔离库演练通过；T15 的密钥缺失启动失败验证通过；T18 的状态码契约测试通过。 | T14 依赖 T12 的 schema 迁移机制；T14 与 T15 同属卡密资产，须同批设计密钥版本与迁移顺序；T18 会被 S1 的支付错误码复用。 |
| S3 部署、备份与网关 | 阿里云 `.env` 与默认口令、Nginx 顺序/路径、迁移备份与回滚、证书降级、可信代理 IP、进程权限与备份可信度 | T19、T20、T21、T22、T23、T36 | 部署脚本改动均有隔离用例；T21 的“迁移前备份”在隔离库演练通过；T22 证书失败在生产 profile 必须失败；T23 的 Bash/PowerShell 备份恢复矩阵由用户执行；T36 的可信代理解析在网关隔离用例中通过（伪造头不改变限流桶）。 | 依赖 S2 的凭据策略（T15）；T19 与 T23 同属 `.env`/备份文件，改动须一次评审；T21 依赖 T12 迁移机制。 |
| S4 契约、规模与运营 | 订单分页、海报移出事务、统计聚合、幂等与错误映射、Long ID 序列化、CI、搜索与错误态、迟到响应、运营功能 | T24、T25、T26、T27、T28、T29、T30、T31、T37、T38 | 每项前后端同批交付并有契约/组件用例；T24/T26/T27 的性能改造有可复现的基准记录（用户执行）；T29/T30 的主链路浏览器回归通过。 | 依赖 S1 支付契约稳定；T27 依赖 T12 迁移机制；T29 依赖 T10 的所有权凭证契约；T30 若后端无搜索能力，须先确认范围再实现。T31 可独立推进，但不得挤占 S0–S2。 |
| S5 商业闭环（阶段一商家结算 + 阶段二预留） | 商家入驻/实名/收款账户/费率政策、阶段一商家直收、退款与费用冲正、服务费账本与结算单、欠费与对账、阶段二分账预留 | T39、T32、T33、T34、T35、T40 | 阶段一：实名/账户审核与费率政策可用、T05–T07 已修、真实渠道在商家直收模式下完成联调、结算单与对账样例一致；T40 仅资质取得且阶段一稳定后进入下一判据。 | 强依赖 S1（T05–T12）与 S2（T15）；T39/M1 为 T32/M2 前置，T34 依赖 T32/T33，T35 依赖 T32–T34；T40 依赖阶段一全部完成与渠道分账资质。 |

阶段独立性说明：
- S0 可独立交付、独立验证，不与 S1 共用必须同批的代码。
- S1 是一个整体交付单元：T05–T07 分开改会留下“事件已成功但订单未履约”的中间态，禁止拆批上线。
- S2 的 T14/T16 可独立验证，但迁移顺序必须与 T12 的迁移机制一起设计。
- S3 与 S4 可在 S1 完成后并行（不同工程/脚本），但 T19/T23 与 T21 涉及同一部署脚本，需串行。
- S5 不是当前 MVP 的“修 bug”，而是能力建设；不得用它替代 S1 的资金一致性修复。

---


## 3. 修复单元

字段说明：编号为本次合并编号；D/F/R 为追溯编号；状态一律按当前代码现状。

### S0 生产阻断

#### T01 商品详情页 TDZ 崩溃

| 项 | 内容 |
| --- | --- |
| 优先级 | **P0**（可用性/转化阻断：任意商品详情页打开即抛 ReferenceError，下单与代付入口不可用） |
| 阶段 | S0 |
| D/F/R | R20；关联 N4 |
| 涉及文件 | [ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L446-L474)、[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L516) |
| 问题本质 | `watch(..., { immediate: true })` 在 setup 阶段同步执行 `loadProduct`，访问尚未初始化的 `const peerPayResult`，触发 TDZ；异常发生在 `try` 之前，`loading` 永不复位。 |
| 修复要点 | 把 ref/const 声明全部前移到 `loadProduct` 与 watch 之前，或改用 `onMounted` 触发首次加载；重置逻辑放入 `try/finally`，保证 loading 一定复位。 |
| 依赖/冲突 | 与 S4 的 T28（迟到响应）同文件，须同批改，避免两次改同一处状态机；不影响后端。 |
| 回归要点 | 合法：打开任意商品详情页正常加载、可下单/代付。非法：接口 404/500 时显示错误态且 loading 归零。失败后数据不变：加载失败不产生订单或预占。 |
| 状态 | 已实现·待人工验证（提交 7a1db09；同文件 T28 仍待 S4，见开发缺口G01） |

#### T02 代客推单商品下拉契约错位

| 项 | 内容 |
| --- | --- |
| 优先级 | **P0**（功能不可用：商家打开推单弹窗即 TypeError，SKU 下拉恒空） |
| 阶段 | S0 |
| D/F/R | R02（推单权限同域）；关联 N5 |
| 涉及文件 | [Orders.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L219-L225)、[Orders.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L519-L540)、[ProductController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L101-L106) |
| 问题本质 | 前端消费 `{product, skus}`，后端返回扁平 `List<Product>`；接口契约从未对齐。 |
| 修复要点 | 二选一并固化：后端提供带 `product + skus` 的商家商品 DTO（推荐，前端已按此写），或前端改为消费扁平 Product 并单独拉 SKU；在 `shared/` 增加真实响应样例契约用例。 |
| 依赖/冲突 | 与 T13（推单角色限制）同入口：T02 在 S0 只修契约并保证店长路径，员工拒绝行为由 T13 在 S2 落地；DTO 改动会触及其他商品列表消费方，需同批核对。 |
| 回归要点 | 合法：店长打开弹窗能看到商品与 SKU，并可创建推单。非法：员工无权推单时被拒且无订单副作用。失败后数据不变：拉取商品失败不产生订单。 |
| 状态 | 已实现·待人工验证（提交 fc119bf） |

#### T03 默认凭据与演示种子进入生产

| 项 | 内容 |
| --- | --- |
| 优先级 | **P0**（越权/凭据泄露：登录页直接展示可用超管与商家口令；生产启动写入同口令账号） |
| 阶段 | S0 |
| D/F/R | R01、D21；关联 N5 |
| 涉及文件 | [Login.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/login/Login.vue#L30-L55)、[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L279-L301)、[application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L30-L36)、[04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L71-L80) |
| 问题本质 | 演示数据（固定账号/口令/身份证/银行卡/明文卡密）与生产初始化未分离，且 `sql.init.mode=always` 使生产每次启动都执行。 |
| 修复要点 | 前端删除预填与“演示视角”；后端把演示种子拆到显式 dev/seed 开关，生产只允许首次随机口令或环境变量注入并校验非默认口令；生产 `sql.init.mode` 改 `never` 或独立迁移入口；环境负责人轮换已暴露凭据（代理不执行）。 |
| 依赖/冲突 | 与 T04（数据源默认口令）、T19（阿里云 `.env`）同源凭据治理，须按同一策略设计；与 T16（卡密密钥）分开，避免把种子清理混进加密迁移。 |
| 回归要点 | 合法：生产首次部署可按受控流程创建管理员并登录。非法：默认口令登录被拒；重复启动不重建演示账号。失败后数据不变：种子开关关闭时不写任何演示行。 |
| 状态 | 已实现·待人工验证（提交 8c5a32a + 2660bc9；随机口令生成未实现，见开发缺口G03） |

#### T04 阿里云 `.env` 缺数据源凭据与默认口令回退

| 项 | 内容 |
| --- | --- |
| 优先级 | **P0**（部署阻断/最小权限：缺键时回退 `root` + 仓库内口令） |
| 阶段 | S0 |
| D/F/R | R01、R29；关联 N9 |
| 涉及文件 | [application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L17-L21)、[02-init-infra.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh#L33-L55)、[04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L194-L217) |
| 问题本质 | 阿里云 `.env` 只写 `DB_USER/DB_PASS/SPRING_DATASOURCE_URL`，未写 `SPRING_DATASOURCE_USERNAME/PASSWORD`；应用配置带明文默认口令。 |
| 修复要点 | 补齐 `SPRING_DATASOURCE_USERNAME=${DB_APP_USER}`、`SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}` 并与腾讯对齐；删除 `application.yml` 明文口令默认值，改为缺失即启动失败；增加部署脚本契约用例断言 `.env` 必含键。 |
| 依赖/冲突 | 与 T03 同属默认凭据治理，须同批确定“应用账号 vs root”边界；与 T19（前台地址）同文件，合并为一次 `.env` 模板修改。 |
| 回归要点 | 合法：全新阿里云按脚本部署，后端以应用账号连接成功。非法：缺 `SPRING_DATASOURCE_PASSWORD` 时启动失败而非回退 root。失败后数据不变：启动失败不执行 schema 写入。 |
| 状态 | 已实现·待人工验证（提交 f0fb273；与 T19 同文件，见开发缺口G02） |

### S1 资金与订单生命周期

#### T05 支付事件 SUCCESS 先于业务事务提交

| 项 | 内容 |
| --- | --- |
| 优先级 | **P0**（资损/不可自愈：资金已收、订单永久 UNPAID、卡密未交付；当前无 HTTP 入口，属接入渠道前潜伏缺陷） |
| 阶段 | S1 |
| D/F/R | R13、D06、F01；关联 N1、N2、N3 |
| 涉及文件 | [PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L232-L234)、[PaymentEventRecorder.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L32-L41)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L58-L69) |
| 问题本质 | 业务写入未提交时用 `REQUIRES_NEW` 独立事务记 `SUCCESS`；外层回滚后事件仍成功，重放只按事件状态短路，不补履约。 |
| 修复要点 | 取消业务事务内的 `REQUIRES_NEW` 成功写入；改为外层提交后确认（afterCommit/outbox）或“履约中”中间态；重放短路必须回查订单真实状态与交付结果，未履约则以同一事件重新驱动或转 `FULFILLMENT_FAILED`；增加“事件 SUCCESS 但订单 UNPAID”的补偿扫描。 |
| 依赖/冲突 | **必须与 T06、T07 同批**：三者共用 `processPaymentEvent` 与事件状态机；与 T11（连接池自锁）同源，建议一次去掉 `REQUIRES_NEW`。禁止另建第二套支付事件表。 |
| 回归要点 | 合法：正常回调一次成功、订单 PAID、卡密/服务交付。非法：重复 `payTradeNo` 不双履约；错商户/错状态拒绝。并发交错：业务写入后、提交前强杀/断连，重启后重放同流水号必须补履约或转人工失败态，事件与订单一致。失败后数据不变：外层回滚时事件不得为 SUCCESS，订单/库存/卡密回到原状。 |
| 状态 | 已实现·待人工验证（提交 751397d，与 T06/T07 同批；重复支付退款执行见缺口 G06） |

#### T06 到账金额自证

| 项 | 内容 |
| --- | --- |
| 优先级 | **P0**（资损：少付/错币种/部分退款后补付均可标 PAID 并交付） |
| 阶段 | S1 |
| D/F/R | R13、F01；关联 N2、F04 |
| 涉及文件 | [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L495-L505)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L87)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L105-L109) |
| 问题本质 | `notifyPaymentSuccess` 不接收渠道实收金额，服务层用 `order.getPayAmount()` 回填事件，金额比较恒真。 |
| 修复要点 | 接口签名显式接收渠道实收金额/币种/商户号，或直接以渠道原始回调对象为入参；禁止从订单反填；缺失金额拒绝或标 `AMOUNT_UNVERIFIED`，不得默认取订单金额；补“应付≠实收”用例。 |
| 依赖/冲突 | 与 T05、T07 同批；签名变更会触达所有未来适配器与测试夹具，须一次改完；与 T32（真实渠道）共享契约，不得先上线旧签名。 |
| 回归要点 | 合法：实收=应付时标 PAID 并交付。非法：少付、多付、币种不符、金额缺失均拒绝或进入待核验，不交付。失败后数据不变：拒绝时订单仍 UNPAID、库存/卡密不消耗。 |
| 状态 | 已实现·待人工验证（提交 751397d，与 T05/T07 同批；真实渠道契约见 T32） |

#### T07 已支付订单提前返回吞掉第二笔付款

| 项 | 内容 |
| --- | --- |
| 优先级 | **P0**（资损/审计：第二笔资金无事件、无对账、无退款线索） |
| 阶段 | S1 |
| D/F/R | R13、D06；关联 N3、F05 |
| 涉及文件 | [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L486-L488) |
| 问题本质 | 订单已 `PAID/COMPLETED/SHIPPED` 时在构造事件前直接返回，重复付款与不同流水号重试被静默吞掉。 |
| 修复要点 | 删除提前返回；先落事件并标记 `DUPLICATE_PAYMENT/OVERPAYMENT`，再返回已支付结果；建立重复支付补偿队列（人工/自动退款）并在对账中可见；同时校验 `payerId/payTradeNo/channel`。 |
| 依赖/冲突 | 与 T05、T06 同批；与 T33（退款）有接口预留，但退款实现可后置；不得因订单已支付跳过参数校验。 |
| 回归要点 | 合法：首次支付正常履约。非法：同流水号重放不双履约；不同流水号第二笔必须产生事件并进入补偿，不得静默成功。并发交错：两个付款人同时付款，两笔事件均落库，仅一笔履约。失败后数据不变：第二笔不得重复发卡/重复扣库存。 |
| 状态 | 已实现·待人工验证（提交 751397d，与 T05/T06 同批；退款执行见缺口 G06） |

#### T08 普通订单数据库层缺 15 分钟截止

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（数据错误/资金语义不一致：同一条 15 分钟规则因是否带代付 token 而表现不同） |
| 阶段 | S1 |
| D/F/R | R12、F08；关联 N7 |
| 涉及文件 | [TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L54-L60)、[OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L71-L75)、[PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L461-L471) |
| 问题本质 | 普通建单从不写 `lock_expire_time`，落账 SQL 允许 `IS NULL`，迟到支付是否成功取决于定时任务是否先跑到。 |
| 修复要点 | **已确认 Q15-A**：所有订单统一下单即写 `lock_expire_time = created_at + 15min`；`markOrderPaidIfUnpaid` 去掉 `IS NULL` 放行，与代付分支统一；存量 `UNPAID` 单补写或按 `created_at` 判定，在迁移方案说明。超时支付拒绝并记录，不静默 PAID。 |
| 依赖/冲突 | 与 T11（过期扫描索引）同批设计 SQL；与 T06/T07 同属支付落账条件，须一起评审；依赖 T12 迁移机制处理存量。 |
| 回归要点 | 合法：15 分钟内支付成功。非法：超过 15 分钟支付被拒或进入迟到支付处理，不再静默 PAID。失败后数据不变：拒绝时订单保持 UNPAID，不消耗库存/卡密。 |
| 状态 | 已实现·待人工验证（提交 19c70e0；存量 NULL 回填见 T12 提交 22d0bc4） |

#### T09 下架与 HELD 预占竞态

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（资金/资产：已预占订单付款后履约被拒，钱收、货不发、库存锁着；接入渠道后接近 P0） |
| 阶段 | S1 |
| D/F/R | R12、D12、F08、F11；关联 N7 |
| 涉及文件 | [ProductEditingService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductEditingService.java#L72-L93)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L165-L171) |
| 问题本质 | 下架只校验店铺/SKU/金额，不检查 HELD 预占；支付侧先 CAS 为 PAID 再校验可购，失败后事件停在 PROCESSING。 |
| 修复要点 | 下架前检查该商品/SKU 是否存在 HELD 预占，有则拒绝或要求先释放/等待到期；支付侧把 `requirePurchasable` 前移到 CAS 之前，或对“已支付不可履约”建立补偿/退款；异常必须写 `FULFILLMENT_FAILED`，不得停在 PROCESSING。 |
| 依赖/冲突 | 与 T08 共用支付落账顺序评审；与 T32/T33 的补偿语义相关；**已确认 Q16-A**：存在 HELD 预占时禁止下架，待预占结束/释放后才可下架（不采用“允许下架保留订单”或“强制取消退款”）。 |
| 回归要点 | 合法：无 HELD 预占时下架成功，新订单不可购。非法：有 HELD 预占时下架被拒（或按确认规则进入补偿）。并发交错：预占成功→下架→支付，三序交错下不得出现“PAID 但永久不履约且无事件”。失败后数据不变：拒绝下架时商品状态不变；支付失败时订单/库存可回滚且事件有失败态。 |
| 状态 | 已实现·待人工验证（提交 f9d2d30；补偿/退款语义见 T33） |

#### T10 预占所有权凭证可由客户端决定

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（越权/资金：知道分享 token 且复用/猜中 clientKey 可接管或取消他人预占；前端当前尚未调用接口，属接入前必修） |
| 阶段 | S1 |
| D/F/R | R10、D09、F08；关联 N8 |
| 涉及文件 | [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L357-L394)、[PeerPayController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java#L61-L75) |
| 问题本质 | `ownerToken` 直接取客户端 `clientKey`，恢复/取消仅比较该值；键不是身份凭证。 |
| 修复要点 | 服务端生成高熵 `ownerToken` 并仅随响应返回；恢复/取消只接受服务端签发值（可 HMAC 绑定订单）；**已确认 Q17-A**：预占 15 分钟、最多重发 3 次；幂等键与所有权凭证分离。 |
| 依赖/冲突 | 与 T29（前端接入 pre-lock/cancel-lock）同批，否则契约无处消费；与 T05 的状态机共用订单锁；不得复用 `Idempotency-Key`。 |
| 回归要点 | 合法：同一付款人可恢复/取消自己的预占。非法：仅持分享链接或错误 ownerToken 不能取消/接管。并发交错：两客户端同时恢复，仅一个成功；到期与取消交错不双释放。失败后数据不变：拒绝时预占不被释放、库存不减。 |
| 状态 | 已实现·待人工验证（提交 8874b6a；前端消费 T29 未在 S1，见缺口 G04） |

#### T11 过期扫描与分页无支撑索引 / 连接池自锁

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（容量/数据可见性：30 秒任务随 UNPAID 线性退化，分页无 tiebreaker 会重复/漏行；`REQUIRES_NEW` 在池上限时批量超时） |
| 阶段 | S1 |
| D/F/R | R11、R24；关联 N1、N7 |
| 涉及文件 | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L162-L169)、[TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L62-L69)、[TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L110-L135)、[ReservationExpiryJob.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L38-L58)、[PaymentEventRecorder.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L22-L33) |
| 问题本质 | `trade_order` 无 `created_at/lock_expire_time` 索引；分页 `created_at DESC` 无 id tiebreaker；支付回调内嵌 `REQUIRES_NEW` 在主连接池满时自锁。 |
| 修复要点 | 增加过期扫描与商家/全平台分页组合索引；分页改 keyset（`created_at + id`）；去掉支付路径的 `REQUIRES_NEW`（与 T05 一次重构），或隔离独立小连接池；任务增加积压监控。 |
| 依赖/冲突 | 索引改动依赖 T12 迁移机制；与 T08 同批改 SQL；与 T24（前端分页）前后端同批；与 T05 同批去 `REQUIRES_NEW`。 |
| 回归要点 | 合法：万级订单下分页无重复/漏行，过期单按规则关闭。非法：非法游标/超限参数被拒。并发交错：30 秒任务与支付/取消同时执行，不双释放、不误关已支付单。失败后数据不变：任务单条失败不阻塞后续，且失败行可追溯。 |
| 状态 | 已实现·待人工验证（提交 33c055e；keyset API 与前端 T24 未在 S1，见缺口 G05） |

#### T12 存量结构/索引无版本化迁移

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（工程性但为前置：所有索引/列/约束修复在存量库不会生效，运维误以为“启动即迁移”） |
| 阶段 | S1（机制先行，随 S1 索引落地） |
| D/F/R | R15、R29、D31；关联 N6 |
| 涉及文件 | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L1-L5)、[application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L30-L36) |
| 问题本质 | schema 只有 `CREATE TABLE IF NOT EXISTS`，无 `ALTER`/版本记录；存量环境无法获得新增约束与索引。 |
| 修复要点 | 建立版本化迁移（Flyway/Liquibase 或受控 `Vxxx__*.sql`），或提供幂等 `ALTER`/索引存在性校验脚本；发布清单明确“新库靠 schema，存量库靠迁移”；迁移前强制备份（与 T21 联动）。 |
| 依赖/冲突 | 是 T08/T11/T14/T17/T27 索引或列变更的前置；与 T21（迁移前备份）同批设计；不得把删库写入启动脚本。 |
| 回归要点 | 合法：新库由 schema 建齐；存量库执行迁移后结构与新库一致。非法：迁移失败阻断启动，不回退为“忽略错误继续”。失败后数据不变：失败迁移不留下半套结构（事务/回滚或幂等重试）。 |
| 状态 | 已实现·待人工验证（提交 22d0bc4；需用户在独立 MySQL 连续执行两次验证幂等；T21 迁移前备份未在 S1） |

---


### S2 权限、隐私与资产安全

#### T13 员工可读店主 PII / 可推单

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（越权/隐私：员工可读身份证、银行卡、结算账户，并可以任意议价推单） |
| 阶段 | S2 |
| D/F/R | R02、D23、F13；关联 N5 |
| 涉及文件 | [SaTokenConfigure.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaTokenConfigure.java#L33)、[AuthController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java#L124-L128)、[MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L200-L216)、[OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L318-L362) |
| 问题本质 | `/merchant/**` 对员工整体放行；`/auth/me`、`/merchant/profile` 直接序列化完整实体；发货与推单只校验商户归属、不校验角色。 |
| 修复要点 | **已确认 Q13-A 员工最小权限**：资料读取按角色白名单裁剪（员工仅店名/编码/公告/Logo/电话），身份证与银行卡仅店长/超管且默认脱敏；推单/发货/资金/卡密仅店长；复用 `AuthSessionService` 与既有角色常量，禁止控制器散落角色判断。 |
| 依赖/冲突 | 权限矩阵已确认（Q13-A）；与 T02（推单契约）同入口；与 T18（错误码）共用 403 语义；前端路由/导航需同批过滤（员工不可见卡密页）。 |
| 回归要点 | 合法：店长可读完整资料并推单/发货；员工可读展示白名单并执行被允许的操作。非法：员工读身份证/银行卡被拒或脱敏；员工推单被拒。失败后数据不变：被拒请求不修改订单/店铺。 |
| 状态 | 待修复 |

#### T14 卡密指纹历史旁路与全量回退扫描

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（资产/资金：加密但无指纹存量重复导入会被判新卡，库存虚增，可能同一卡密卖两次） |
| 阶段 | S2 |
| D/F/R | R25、R05、F12；关联 N11 |
| 涉及文件 | [CardAssetService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java#L63-L76)、[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L123-L129)、[CardCipherService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L133-L140) |
| 问题本质 | `card_fingerprint` 允许 NULL 且唯一键对 NULL 不生效；回退路径对密文做 SHA-256，匹配不到明文指纹，并两次全店全量读取（`historyContents(...) != null` 恒真）。 |
| 修复要点 | 一次性迁移：`card_fingerprint IS NULL` 的行解密后回填明文指纹，再收紧非空/唯一；回退识别 `ENC:v1:` 先解密再哈希；迁移完成后删除全表回退并只查一次；列表默认不返回正文（与 T16/N11 联动）。 |
| 依赖/冲突 | 依赖 T12 迁移机制；与 T15（密钥版本/轮换）同批设计迁移顺序（先有稳定密钥再回填）；与 T16（卡密库明文展示）同批交付 DTO。 |
| 回归要点 | 合法：同一明文重复导入被拒，库存不增。非法：跨商户同卡密按规则处理（不串店）。并发交错：两批相同卡密并发导入，仅一批成功。失败后数据不变：去重失败不写卡、不加库存。 |
| 状态 | 待修复 |

#### T15 卡密加密默认密钥回退与无轮换

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（资产机密性：源码内置密钥可解密生产库与备份；种子明文无指纹） |
| 阶段 | S2 |
| D/F/R | R05、F12；关联 N11 |
| 涉及文件 | [CardCipherService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L63-L68)、[application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml)、[schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L326-L334)、[BuyerOrderView.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/dto/BuyerOrderView.java#L20) |
| 问题本质 | 未配置密钥时回退到硬编码种子；密文无 keyId，无法轮换；兼容构造器 `new CardCipherService()` 绕过 Spring 注入。 |
| 修复要点 | 生产缺显式密钥即启动失败；密文增加密钥版本（`ENC:v2:<keyId>:`）支持灰度轮换；所有组件只用注入单例，删除生产可用兼容构造器；种子改无效占位或仅 dev profile 注入，并回填指纹。 |
| 依赖/冲突 | 与 T14 同批（迁移顺序：密钥稳定→指纹回填→删回退）；与 T03（种子清理）分工：T03 管账号，T15 管卡密密钥；与 T19/T23（`.env`/备份密钥）同源。 |
| 回归要点 | 合法：配置密钥后可导入/读取/作废卡密。非法：缺密钥启动失败；错误 keyId 拒绝解密并告警。失败后数据不变：启动失败不写库；解密失败不返回明文。 |
| 状态 | 待修复 |

#### T16 卡密库明文批量展示

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（隐私/资产：一屏批量复制全店未售+已售卡密；接口一次返回全店并逐条解密） |
| 阶段 | S2 |
| D/F/R | R04、R05、F12；关联 N11 |
| 涉及文件 | [Cards.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Cards.vue#L21-L24)、[Cards.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Cards.vue#L166-L169)、[ProductController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L222-L260) |
| 问题本质 | 列表接口默认返回全店卡密并逐条解密，前端直接渲染正文，与文件说明“查询不展示正文”矛盾。 |
| 修复要点 | **已确认 Q21-A**：列表返回脱敏值+指纹，仅店长按 SKU 授权时返回单 SKU 明文且限量；前端默认打码、单条“查看”二次确认；导出必须二次确认并审计（操作人、数量、时间）；补分页与“仅未售”过滤；前后端 DTO 同批。 |
| 依赖/冲突 | 与 T14/T15 同属卡密资产，须同批确定“列表/详情/导出”三种权限；与 T24（列表分页）共用分页模式；影响 `cardAssets.test.mjs` 契约。 |
| 回归要点 | 合法：店长按 SKU 查看明文有数量上限并可审计。非法：员工/跨店请求被拒；列表不含正文。失败后数据不变：查看不修改卡状态/库存。 |
| 状态 | 待修复 |

#### T17 跨商户 SKU 编码全局唯一

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（租户隔离/可用性：后建商户 500，编码被先占形成资源抢占与存在性侧信道） |
| 阶段 | S2 |
| D/F/R | R15、D13；关联 N6 |
| 涉及文件 | [schema.sql](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L111)、[ProductController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L183-L193)、[GlobalExceptionHandler.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java) |
| 问题本质 | `uk_sku_code(sku_code)` 无 `merchant_id` 维度；唯一键冲突落到通用 500。 |
| 修复要点 | **已确认 Q14-A**：改为 `(merchant_id, sku_code)` 唯一；同店同码冲突返回 409 明确文案，不泄露其他店铺；补并发同码用例。 |
| 依赖/冲突 | 唯一域已确认（Q14-A 店内唯一）；与 T18（409 映射）同批；索引与迁移由开发决定并依赖 T12。 |
| 回归要点 | 合法：同店同码被拒；跨店同码按确认规则成功或明确 409。非法：空/超长/非法字符被拒 400。并发交错：两请求同时创建同店同码，仅一个成功且另一个稳定 409。失败后数据不变：失败不写商品/SKU。 |
| 状态 | 待修复 |

#### T18 业务异常 HTTP 200 / 唯一键冲突 500

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（工程性/可观测性：网关、监控、前端拦截器无法按状态码识别失败；并发幂等冲突返回 500） |
| 阶段 | S2 |
| D/F/R | R34、D24；关联 N12 |
| 涉及文件 | [GlobalExceptionHandler.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java#L81-L85)（无 `DuplicateKeyException` 处理器）、[OrderIdempotencyService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderIdempotencyService.java) |
| 问题本质 | `BusinessException` 无 `@ResponseStatus`，业务失败统一 200；`DuplicateKeyException` 未单独映射。 |
| 修复要点 | 按业务码段映射 400/401/403/404/409/429/503（返回 `ResponseEntity` 或动态状态）；`DuplicateKeyException` 单独 409；幂等同键并发冲突退避后读快照，仍冲突返回 409/429 而非通用异常；补契约测试。 |
| 依赖/冲突 | 前端 `request.js` 的 401/403 分支须同批核对；与 T03/T04 的启动失败无关；与 T17 的 409 共用错误体结构。 |
| 回归要点 | 合法：成功请求仍 200 且 body 不变。非法：参数/权限/冲突/限流分别返回对应状态码且不回显敏感输入。失败后数据不变：错误响应不产生订单/库存副作用。 |
| 状态 | 待修复 |

### S3 部署、备份与网关

#### T19 阿里云 `.env` 缺商城地址与 Redis 参数、默认口令

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（可用性/资金链路：代付短链与海报指向 `localhost:3002`；Redis 库号回退 0；与 T04 同源） |
| 阶段 | S3 |
| D/F/R | R01、R28、R29；关联 N9 |
| 涉及文件 | [application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L90)、[02-init-infra.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh#L34-L55)、[02-init-infra.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh#L49) |
| 问题本质 | 阿里云 `.env` 未写 `APP_FRONTEND_STOREFRONT_URL`；`useUNICODE` 拼写错误；`SPRING_DATA_REDIS_DATABASE` 与后端 `REDIS_DB` 不匹配；Redis 口令有硬编码默认值。 |
| 修复要点 | 补齐 `APP_FRONTEND_STOREFRONT_URL=https://${GMALL_SERVER_NAME}/m/`；统一 `useUnicode` 与 `REDIS_DB`；删除真实 Redis 口令默认值，缺失即失败；与腾讯模板对齐并加契约断言。 |
| 依赖/冲突 | 与 T04 同文件、同一次模板修改；与 T03 的凭据治理同批；不得只改阿里云而留下腾讯漂移。 |
| 回归要点 | 合法：阿里云部署后代付短链/海报指向公网 `/m/`，Redis 使用预期库。非法：缺必填键时部署脚本失败而非静默回退。失败后数据不变：部署失败不覆盖现有发布。 |
| 状态 | 待修复 |

#### T20 Nginx 首次部署校验顺序与回滚边界

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（可用性：首次部署坏配置留在 `sites-enabled`，下次 reload/restart 可能整体不可用） |
| 阶段 | S3 |
| D/F/R | R29、R30、D35 |
| 涉及文件 | [04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L238-L251)、[04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L126-L173) |
| 问题本质 | `safe_nginx_switch` 先启用再 `nginx -t`；失败即 `die`，首次部署无旧配置可恢复。 |
| 修复要点 | 先在临时路径/独立片段 `nginx -t`，通过后再原子替换；失败恢复旧软链或删除新软链并复核后再 `die`；补“首次部署+语法错误”夹具用例。 |
| 依赖/冲突 | 与 T21（部署回滚）同一脚本；与 T22（证书降级）同批改 04-deploy；不得只修阿里云而漏腾讯同构脚本。 |
| 回归要点 | 合法：首次部署成功且 `nginx -t` 通过后 reload。非法：坏配置被拒且不进入 `sites-enabled`；reload 失败不 restart 到坏配置。失败后数据不变：失败部署保留旧版本与旧网关。 |
| 状态 | 待修复 |

#### T21 迁移前无数据库备份 / 回滚不恢复数据库

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（数据不可逆：`--migrate` 先于备份执行，失败回滚只恢复代码） |
| 阶段 | S3 |
| D/F/R | R29、D31；关联 N9 |
| 涉及文件 | [04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L65-L123)、[04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L126-L173) |
| 问题本质 | 迁移发生在“备份当前线上状态”之前；回滚无 SQL 恢复，演示种子可能已写入。 |
| 修复要点 | 迁移前强制带时间戳 `mysqldump`（含 routines/triggers）；迁移与应用启动合并为“全成功或回滚 DB”边界；失败时输出恢复命令；演示种子从 schema 拆到显式 seed。 |
| 依赖/冲突 | 依赖 T12 迁移机制；与 T20/T22 同脚本，须串行；与 T23（备份可信度）共享 dump 参数与密钥交付约定。 |
| 回归要点 | 合法：迁移前备份存在且可恢复；成功路径应用启动。非法：备份失败即中止迁移。失败后数据不变：迁移失败可恢复到迁移前结构与数据。 |
| 状态 | 待修复 |

#### T22 证书失败被吞 / HTTP 降级当成功

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（安全：生产以明文 HTTP 提供登录、订单、卡密、PII，脚本仍报成功） |
| 阶段 | S3 |
| D/F/R | R30 |
| 涉及文件 | [04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L263-L285) |
| 问题本质 | `certbot ... 2>&1 \| tail -20` 位于 `if` 条件，管道退出码取 `tail` 恒 0，失败分支不可达；无证书仍启用 HTTP 引导配置并继续。 |
| 修复要点 | 用 `PIPESTATUS[0]` 或去掉管道判断；生产 profile 无证书即 `die`，仅显式 `--allow-http` 才降级；把“HTTPS 可用”纳入部署烟测（curl 443）。 |
| 依赖/冲突 | 与 T20/T21 同脚本；与 T30（健康检查只测后端）同批补烟测；证书操作由环境负责人执行。 |
| 回归要点 | 合法：证书申请成功，443 可访问。非法：DNS/certbot 失败时生产部署失败，不启用明文。失败后数据不变：失败部署不切换代码/配置。 |
| 状态 | 待修复 |

#### T23 进程 root 运行 / 部署脚本 root 强制 / 备份可信度

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（安全/运维：应用漏洞即系统 root；Bash 备份拼命令、口令进程可见；PowerShell stderr 混入 SQL、BOM、无 `--no-tablespaces`） |
| 阶段 | S3 |
| D/F/R | R30、R31、R32 |
| 涉及文件 | [04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L41)、[04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L200-L213)、[backup_mysql.sh](file:///e:/shop-b2b2c/deploy/scripts/backup_mysql.sh#L56-L86)、[backup_mysql.ps1](file:///e:/shop-b2b2c/deploy/scripts/backup_mysql.ps1#L79-L110)、[test_backup_windows.ps1](file:///e:/shop-b2b2c/deploy/tests/test_backup_windows.ps1#L76-L91) |
| 问题本质 | systemd `User=root`；Bash 备份用字符串拼接命令；PowerShell `2>&1 \| Out-File` 把 warning 与 BOM 写入 SQL；用例只断言文件非空。 |
| 修复要点 | 专用系统用户 + `NoNewPrivileges/ProtectSystem`；备份改参数数组/`MYSQL_PWD`/`--defaults-extra-file`/`--no-tablespaces`；stderr 分离、无 BOM 写文件、流式压缩；用例断言首字节非 BOM、SQL 无 warning、非零退出；隔离库恢复演练。 |
| 依赖/冲突 | 与 T15（密钥随备份可恢复）联动；与 T21 共享 dump 约定；root 改用户需在真实服务器验证权限，代理不执行。 |
| 回归要点 | 合法：Bash/PowerShell 备份可恢复，恢复后订单/预占/卡密守恒。非法：dump 失败不生成“看似成功”的文件；超长/空格路径不拆参。失败后数据不变：备份失败不删除上一份有效备份。 |
| 状态 | 待修复 |

#### T36 限流取 `getRemoteAddr`，Nginx 回环后真实客户端共享 IP 桶

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（可用性/安全：所有经 Nginx 的匿名请求共享一个回环 IP，限流要么误伤全站、要么形同虚设） |
| 阶段 | S3 |
| D/F/R | R06、R07 |
| 涉及文件 | [TradeCostLimiter.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/TradeCostLimiter.java)、[OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java)、[PeerPayController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java) |
| 问题本质 | 代码直接 `request.getRemoteAddr()` 建限流键；生产经 Nginx 反代后该值是回环地址，与真实客户端无关。 |
| 修复要点 | 启用受控的 `ForwardedHeaderFilter`/server 级 real-IP，并只在可信代理网段使用 `X-Forwarded-For`；或由 Nginx 注入不可伪造的内部头；禁止无条件信任客户端自报头；统一客户端标识解析供建单/预占/海报使用。 |
| 依赖/冲突 | 与 T19（网关/`.env`）同批配置；与 T11（限流存储容量）联动；不得用前端传参替代服务端解析。 |
| 回归要点 | 合法：不同真实客户端命中各自桶；同客户端超限 429。非法：伪造 `X-Forwarded-For` 不改变桶。失败后数据不变：限流拒绝不建单/不预占。 |
| 状态 | 待修复 |

---


### S4 契约、规模与运营

#### T24 订单列表不分页、后端静默截断

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（运营可用性/数据可见性：第 201 条以前订单不可见，无截断提示） |
| 阶段 | S4 |
| D/F/R | R24、D30 |
| 涉及文件 | [Orders.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L426-L434)、[Orders.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/admin/Orders.vue#L172)、[OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L204-L210)、[TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L110-L135) |
| 问题本质 | 前端不带分页参数；后端无参时固定 200 条并静默截断；分页 `OFFSET` 无 tiebreaker。 |
| 修复要点 | 前端接游标分页并展示总数/`hasMore`；后端返回分页元数据，非法参数 400，禁止静默截断；分页改 keyset（与 T11 同批）。 |
| 依赖/冲突 | 依赖 T11 索引与游标；与 T31（订单页筛选/发货防重）同页面，须同批改；与 T16 共用 `beforeId` 模式。 |
| 回归要点 | 合法：>200 条订单可翻页且无重复/漏行；筛选与分页组合正确。非法：非法 page/pageSize 返回 400 而非截断。失败后数据不变：分页查询不修改订单。 |
| 状态 | 待修复 |

#### T25 统计接口全量投影 / 看板口径

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（规模/数据真实性：万级订单每请求全表投影；看板可能把意向单混入成交） |
| 阶段 | S4 |
| D/F/R | R24、D25、D30 |
| 涉及文件 | [TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L137-L139)、[OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java)、[Dashboard.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Dashboard.vue) |
| 问题本质 | 统计投影无条件下全量读取；按商户也全量；口径与“支付 GMV/预计佣金/已结算”未分离。 |
| 修复要点 | 改库内 `GROUP BY` 聚合或按日预聚合；加时间范围参数限制扫描面；保留 `OrderStatistics` 输出字段与字符串金额；明确退款/结算不可用字段。 |
| 依赖/冲突 | 依赖 T12 索引；与 T24 同批做分页/聚合；与 T35（对账）区分：统计不是账本，不得宣称收入。 |
| 回归要点 | 合法：统计与逐单求和一致，空数据为 0。非法：非法时间范围 400。失败后数据不变：统计只读。 |
| 状态 | 待修复 |

#### T26 建单事务内渲染海报

| 项 | 内容 |
| --- | --- |
| 优先级 | **P2**（工程性/容量：PNG 光栅化占连接与事务，放大锁等待与连接池占用） |
| 阶段 | S4 |
| D/F/R | R26、D38 |
| 涉及文件 | [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L138-L141)、[PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L238-L241) |
| 问题本质 | `PosterUtils.generatePeerPayPoster` 在 `@Transactional` 建单方法内同步执行；`regeneratePoster` 已改事务外，建单路径未改。 |
| 修复要点 | 建单事务只落库并返回 token；海报异步生成+存储或首次请求按需生成（事务外）；失败可重试且不影响建单。 |
| 依赖/冲突 | 与 T11（连接池）有收益叠加；与 T28（海报缓存/切换）同属海报链路，须同批核对；不改变公开响应字段。 |
| 回归要点 | 合法：建单成功，海报稍后可取且内容对应当前 token。非法：海报生成失败时建单仍成功并可重试。失败后数据不变：海报失败不回滚订单/库存。 |
| 状态 | 待修复 |

#### T27 幂等保留期与清理任务

| 项 | 内容 |
| --- | --- |
| 优先级 | **P2**（契约/运维：72h 后同键重试会重建单；清理任务无轮次上限；头注释与实现矛盾） |
| 阶段 | S4 |
| D/F/R | R27、D14 |
| 涉及文件 | [IdempotencyPurgeJob.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/IdempotencyPurgeJob.java#L32-L56)、[OrderIdempotencyService.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderIdempotencyService.java#L4)、[IdempotencyMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/IdempotencyMapper.java#L26-L27) |
| 问题本质 | 完成快照 72h 后删除，超期同键重试无映射；清理 `while` 无最大轮次；注释仍写“不自动过期”。 |
| 修复要点 | **已确认 Q18-B**：保留轻量“键→订单号”映射 30 天，不保留敏感响应；清理加最大轮次/时间预算；更新注释；快照不得保存完整敏感响应。映射表结构由开发决定。 |
| 依赖/冲突 | 保留期已确认（Q18-B）；与 T21（备份/保留）无直接代码冲突；与 T24 的分页无共享状态。 |
| 回归要点 | 合法：窗口内同键重放返回原结果。非法：不同请求体同键 409 不回显旧响应。失败后数据不变：清理只删已完成且超期记录，未完成占位保留。 |
| 状态 | 待修复 |

#### T28 迟到响应错配与详情错误态/剪贴板

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（隐私/资金：A 单卡密显示在 B 单；代付海报切换账单不清空可能扫错码；详情失败无错误态） |
| 阶段 | S4 |
| D/F/R | R20、R22、D28 |
| 涉及文件 | [Orders.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L486-L502)、[PeerPayCashier.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/PeerPayCashier.vue#L441-L460)、[PeerPayCashier.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/PeerPayCashier.vue#L506-L511)、[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L455-L467)、[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L506-L511) |
| 问题本质 | 详情请求无请求序号/失效控制；海报缓存不随 token 清空；详情错误只 `console.error`；剪贴板无安全上下文判断。 |
| 修复要点 | 统一“请求序号/AbortController + key 变化失效”模式（复用 Store.vue 与 storedPoster.mjs）；token 变化清空海报并重拉；错误态+重试；剪贴板抽到 `shared/` 并降级；移除默认手机号。 |
| 依赖/冲突 | 与 T01 同文件，须同批；与 T26 海报链路同批核对；与 T30 的首页错误态可共用请求失效模式。 |
| 回归要点 | 合法：快速切换订单/账单只显示当前资源；详情失败可见错误并可重试。非法：旧响应不得覆盖新资源；剪贴板失败提示手动复制。失败后数据不变：前端失效控制不触发额外写请求。 |
| 状态 | 待修复 |

#### T29 代付收银台未接预占/取消接口

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（功能可用性/资金契约：真实收银台无预占则支付必被拒；预占/取消能力无 UI 入口） |
| 阶段 | S4 |
| D/F/R | R10、D27、F08；关联 N8 |
| 涉及文件 | [PeerPayCashier.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/PeerPayCashier.vue#L471-L494)、[PeerPayController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java#L61-L75) |
| 问题本质 | 前端只调 `detail/poster/success-ad`，无 `/pre-lock`、`/cancel-lock`；`quickPay` 仍是占位提示。 |
| 修复要点 | 进入付款步骤前 `pre-lock` 并保存服务端 ownerToken（内存/会话），离开/超时 `cancel-lock`；**已确认 Q17-A**：预占 15 分钟、最多重发 3 次；预占失败（被占/过期）给明确 UI 与刷新路径；幂等键与 ownerToken 分离。 |
| 依赖/冲突 | **必须与 T10 同批**（所有权凭证契约）；预占规则已确认（Q17-A）；与 T28 同文件状态机，须一次改。 |
| 回归要点 | 合法：预占成功后可进入付款，取消后库存释放。非法：重复预占/过期预占返回明确状态，不重复扣库存。并发交错：两付款人抢同一 SKU，一个预占成功、另一个明确失败。失败后数据不变：预占失败不建单/不扣库存。 |
| 状态 | 待修复 |

#### T30 公开商品搜索、首页错误态与未验证承诺

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1**（用户可见功能缺失/合规：搜索按钮完全无效；业务错误被当空列表；首页仍有未验证的资金承诺） |
| 阶段 | S4 |
| D/F/R | R22、F17、D30 |
| 涉及文件 | [Navbar.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/components/Navbar.vue#L125-L132)、[Home.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/Home.vue#L99)、[Home.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/Home.vue#L141)、[Home.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/Home.vue#L282-L307)、[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L149-L150)、[App.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/App.vue#L17) |
| 问题本质 | 导航把 `q` 放入 URL，Home 从不读取 `route.query.q/searchType`；`code` 未校验；`products` 可能为 null；页面仍展示“持牌官方专户存管/官方二级直结/持牌专户担保/已自动秒发”等未验证承诺。 |
| 修复要点 | Home 读取并监听 `route.query`，把 `q/searchType` 作为请求参数（后端无搜索能力则先补契约，不能前端假过滤）；**已确认 Q19-A**：店内 + 全平台双搜索入口（关键词/类目），默认范围与排序在实现中固定；**已确认 Q20-A**：免登录下单保留，联系方式必填 + 现有 IP/联系人限流；加请求序号；校验 `code`，失败显示错误/重试；模板判空；未开通能力对应的承诺文案移除或改为真实状态说明。 |
| 依赖/冲突 | 搜索范围已确认（Q19-A）；匿名下单限制已确认（Q20-A）；与 T28 共用请求失效模式；与 F17 的会员/搜索能力候选区分。 |
| 回归要点 | 合法：关键词/类目组合返回正确结果。非法：后端业务错误显示错误态而非空列表。失败后数据不变：搜索只读。 |
| 状态 | 待修复 |

#### T31 管理端运营功能与状态语义

| 项 | 内容 |
| --- | --- |
| 优先级 | **P2**（运营效率/数据真实性：发货重复提交、商户开通/费率无幂等与异常兜底、状态映射错误、演示默认值） |
| 阶段 | S4 |
| D/F/R | D26、D29、F13、F14 |
| 涉及文件 | [Orders.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L438-L451)、[Products.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Products.vue#L269-L319)、[Merchants.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/admin/Merchants.vue#L82)、[Merchants.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/admin/Merchants.vue#L258-L296)、[Profile.vue](file:///e:/shop-b2b2c/shop-admin-vue/src/views/merchant/Profile.vue#L55) |
| 问题本质 | 发货无 loading/防重；商品发布与商户开通无幂等/异常兜底；店铺 `2/3` 与审核 `REJECTED` 映射错误；开通表单默认 `admin123`、随机运单号、unsplash 外链。 |
| 修复要点 | 复用 `idempotency.mjs` 为发布/开通加键（成功才 complete）；所有提交补 `try/catch` 与提示；弹窗关闭重置整份表单（含 PII）；抽共享状态映射并补单测；默认值改空/随机强口令/本地占位图。 |
| 依赖/冲突 | 与 T02/T13 的商家权限/推单入口同批；与 T24 订单页同批；与 T18 的错误体契约同批；不涉及后端结构。 |
| 回归要点 | 合法：正常发布/开通/发货成功且表单复位。非法：重复提交被幂等拦截；失败显示后端 message 且不残留 loading。失败后数据不变：失败不新建商品/商户/运单。 |
| 状态 | 待修复 |

#### T37 公开 DTO 的 Long ID 仍按 JSON number 输出

| 项 | 内容 |
| --- | --- |
| 优先级 | **P2**（契约/前端精度：超过 2^53 的 ID 在 JS 端丢精度，详情/跳转可能指向错误对象） |
| 阶段 | S4 |
| D/F/R | R35 |
| 涉及文件 | [StrictIdDeserializer.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/StrictIdDeserializer.java)、[PublicProductView.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/dto/PublicProductView.java#L13-L19)、`config/`（无 Jackson 配置类） |
| 问题本质 | 只有入参反序列化约束；没有全局 `Long→String` 序列化，公开视图 ID 仍是 number。 |
| 修复要点 | 增加全局 Jackson 配置把 `Long/long` 序列化为字符串（或在公开 DTO 显式 `@JsonSerialize`）；同步调整前端比较/路由参数；补 2^53 边界契约用例。 |
| 依赖/冲突 | 与 T24（游标 ID）、T17（ID 冲突提示）共用契约；改动会影响所有前端把 ID 当 number 的比较，须前后端同批；不改变入库类型。 |
| 回归要点 | 合法：2^53 以上 ID 原样往返，详情/下单可命中。非法：超范围入参仍被 `StrictIdDeserializer` 拒绝。失败后数据不变：序列化只影响响应。 |
| 状态 | 待修复 |

#### T38 无 CI 与可重跑回归入口

| 项 | 内容 |
| --- | --- |
| 优先级 | **P2**（工程性/可维护性：并发、权限、部署类修复无持续回归，历史“通过”不可重跑） |
| 阶段 | S4 |
| D/F/R | R33 |
| 涉及文件 | 仓库根（无 CI 工作流）；`shop-b2b2c-backend/src/test/java/**`；`shared/*.test.mjs`；`deploy/tests/**` |
| 问题本质 | 有后端测试类与前端规则用例，但仓库无 CI 工作流，无执行记录/环境矩阵，无法阻止回归。 |
| 修复要点 | 增加最小 CI（后端单测 + 前端 `node --test` + 部署隔离用例），明确 H2/无 Nginx 的跳过口径；记录每次执行结果；不在 CI 中连接生产库或执行真实构建。 |
| 依赖/冲突 | 依赖 T12/T21（隔离库与迁移用例）；与 V03–V07 的用户手动入口并行，不替代人工验收；不得把“CI 绿”当成“已验证”。 |
| 回归要点 | 合法：提交可自动跑通现有用例。非法：用例失败阻断合并；环境缺依赖显式标记 skipped。失败后数据不变：CI 只读运行，不写业务库。 |
| 状态 | 待修复 |

### S5 商业闭环（阶段一商家结算 + 阶段二预留）

> 本节已按 [商家结算设计](2026-11-06-merchant-settlement-design.md) 与 [分期实施](2026-11-06-merchant-settlement-implementation-plan.md) 重构：阶段一商家直收货款、平台只收技术服务费；阶段二平台代收分账，资质后实施。T32–T35 编号保留但范围重写，新增 T39（M1）与 T40（M5）。

#### T39 商家入驻、实名、收款账户与费率政策（M1）

| 项 | 内容 |
| --- | --- |
| 优先级 | **P0（能力前置）**（无实名/账户/费率政策，后续计费与结算没有合法来源） |
| 阶段 | S5 |
| D/F/R | F02、F13、F14、R02；关联 N5 |
| 涉及文件 | **计划新增** `merchant_onboarding_application/audit`、`merchant_settlement_account`、`merchant_fee_policy/audit`（现有代码中均不存在）；[MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L68-L133)、[MerchantStore.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/MerchantStore.java#L28-L38)、[ManagementGuards.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/ManagementGuards.java#L20) |
| 问题本质 | 超管直接建店即营业；无申请/实名/审核/驳回；费率 0–100 无政策与审计；收款账户是 `SUB+时间戳`、默认 `PASSED` 的模拟值。 |
| 修复要点 | 申请单与审核状态机（个人身份证/企业执照两条路径）；通过后才建店；账户登记+人工审核+脱敏；费率政策正常 `rate_bps 100–1000`（1%–10%），推广 `rate_type=PROMOTION + rate_bps=0` 为独立状态；调价原因/操作人/前后值/确认人/生效时间（**已确认 S07-B**：默认次日 00:00、按自然日一天一套；降费可即时、调高必须次日）；0 费率仍生成计费记录并区分应收/免收；停写假进件值。**实名与账户审核按已确认（用户 2026-11-06）的口径实现**：Q11 分阶段（阶段一人工审核；`verification_method/verification_source` 字段预留第三方；不得把人工审核标成第三方实名）；Q12 账户登记+人工核对户名与账号，**“户名必须等于实名主体”为硬校验**，不接第三方账户验证。详见设计 §3–§5、§8.2。 |
| 依赖/冲突 | 与 T13（PII/权限）同批；依赖 T12 迁移机制、T03 演示种子清理；与 T15 的 PII/密钥模式复用；**T32/M2 不得先于本项上线真实计费**；Q11/Q12 已确认，可进入实现；第三方实名与账户验证作为后续增强，不在阶段一范围。 |
| 回归要点 | 合法：个人/企业申请→人工审核通过→建店/店长；管理员调价 1%–10% 留痕；推广 0 费率生成应收/免收记录；户名等于实名主体时账户审核通过。非法：超范围费率、把 0 混入正常区间、重复主体、员工读完整 PII 均拒绝；**户名与实名主体不一致必须拒绝/补件，不允许人工放行**；未审核账户不得展示“已开通”；无第三方核验结果不得标注“第三方认证”。失败后数据不变：审核失败不改店铺状态；调价失败不产生新政策。 |
| 状态 | 待修复（设计已出；Q01–Q21 与 S01–S12 已确认/撤回，代码待实现） |

#### T32 阶段一真实支付渠道接入（商家直收，M2）

| 项 | 内容 |
| --- | --- |
| 优先级 | **P0（能力）**（无真实支付则交易闭环不成立；阶段一只接商家直收，不做平台代收） |
| 阶段 | S5 |
| D/F/R | F01、D01、D02、D06 |
| 涉及文件 | 新增渠道适配器；[PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java)、[PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java)、schema 订单/事件列 |
| 问题本质 | 只有本地事件骨架与模拟付款；无渠道网关、验签回调、主动查单、关单与异常补偿；A/B/C 与渠道语义混用。 |
| 修复要点 | 阶段一按**计划新增的** `collection_mode=MERCHANT_DIRECT` 接商家商户号/收款码；统一支付尝试/回调/查单/关单；验签与实收金额/商户/币种核验；回调幂等；**计划新增** `pay_channel` 列并与 `collection_mode` 拆开（现有 `trade_order.payment_channel` / `merchant_store.payment_channel_type` 保留为迁移来源）；生产禁用 mock。支付成功后按**已确认的订单级费用快照**（Q01-A）触发扣费/欠费，不在结算时重算费率。设计见 §2、§5.4、§11。 |
| 依赖/冲突 | **强依赖 T05–T07、T10、T15、T39**；不得在事件三连与实名/费率前接入真实资金；Q02-A 即时扣费已确认，与 T34 账本同批；Q10 已撤回，旧字段按“保留只读、新语义另起列、历史值不重算”由开发执行，阶段一只接 `MERCHANT_DIRECT`。 |
| 回归要点 | 合法：真实支付成功→订单 PAID→资产交付→按快照生成费用并扣费/转欠费。非法：验签失败/金额不符/商户不符/重复回调均拒绝且不交付。并发交错：回调重试、查单与回调同时到达，只履约一次。失败后数据不变：渠道失败不标记 PAID、不发卡、不产生应收。 |
| 状态 | 待修复（设计已出，代码待实现） |

#### T33 退款、重复付款补偿与费用冲正（M3/M4）

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1（能力）**（有 `REFUNDED` 状态无退款服务；重复付款无退款线索；服务费无冲正） |
| 阶段 | S5 |
| D/F/R | F05、F03；关联 N3 |
| 涉及文件 | 新增退款服务/接口/页面；[TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java)、[OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java)、订单 `fee_refunded_amount/fee_status` |
| 问题本质 | 退款状态存在但无申请/审批/原路退回/库存与费用冲正/失败重试；阶段一平台不代收货款，责任需明确。 |
| 修复要点 | 退款接口按**计划新增的** `collection_mode` 分叉（**已确认 Q03-C**）：阶段一 `MERCHANT_DIRECT` 由商家原路退款，平台只按原快照冲正服务费、不垫资退货款；阶段二 `PLATFORM_COLLECT` 由平台按渠道规则退款。支持部分/全额退款、渠道结果、库存回退、服务费按原快照比例冲正（全额/部分/结算后红字）；重复付款进入补偿队列。设计 §6.2、§9.5。 |
| 依赖/冲突 | 依赖 T32（渠道退款能力）与 T07（重复付款事件）；与 T34 的服务费账本同批；Q03-C 已确认，阶段一只实现商家退款路径，但接口与状态字段必须同时容纳两种模式。 |
| 回归要点 | 合法：全额/部分退款成功，订单/库存/费用一致。非法：超退、重复退款、非原付款人退款被拒。并发交错：退款与发货/回调/结算交错不产生负库存、双退款或重复冲正。失败后数据不变：退款失败保持原支付状态并记录可重试；不修改已确认账单。 |
| 状态 | 待修复（设计已出，代码待实现） |

#### T34 技术服务费计费、商家账户与结算单（M3）

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1（能力）**（当前只有下单佣金数字，无账户、无扣费、无结算明细） |
| 阶段 | S5 |
| D/F/R | F03、F10、F09 |
| 涉及文件 | 新增 `merchant_account/ledger`、`merchant_settlement_batch/item`；[OrderStatistics.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/OrderStatistics.java#L17-L47)、`billing_statement` |
| 问题本质 | 服务费只写订单 `commission_amount`；无应收/已收/冲正状态，无余额/欠费/账本，无结算批次与明细；`billing_statement` 空壳默认 `SETTLED`。 |
| 修复要点 | 订单级费用快照（费率/基数/金额/政策版本/状态，**Q01-A 已确认**）；计费基数按商品成交额，剔除运费/平台优惠与补贴/税费，必须新增字段，不用实付额过渡（**Q04-B 已确认**）；余额充足时支付成功即扣，不足转欠费并按 7/21/30 天账期宽限（**Q02-A + 补充规则已确认**）；推广 0 费率仍生成应收/免收记录；含税扣款、内部价税分离记账（**Q05-D 已确认**）；按周期生成服务费结算批次与明细（**Q06-C 月结已确认**）；**后台必须同时提供逐笔明细查询，结算单是汇总、明细是证据，明细可追到 `payment_event_id/refund_id` 与订单快照费率**；充值按 **Q08-A 线下转账 + 人工确认**，充值单带唯一凭证号并幂等入账（详见设计 §8.4）；所有余额变更走账本+幂等键；阶段一结算单是服务费账单，不是货款应付。设计 §5、§6、§7、§8、§9。 |
| 依赖/冲突 | 依赖 T39 费率政策、T32 支付事件、T33 退款冲正；**T34 不得在 T05–T07 未修时扣费**；与 T35 对账同批；Q06/Q07/Q08 已确认，周期按自然月、信用额度初始 500 元可配、充值走线下+人工确认，不再以“未确认”为由只做空结构。 |
| 回归要点 | 合法：支付成功即扣、余额足够时账本与余额一致；推广 0 费率生成应收/免收记录；含税金额与净收入/税额可分；月结账单与逐笔明细合计一致且明细可追溯到事件与退款。非法：余额不足未转欠费、0 费率漏记、重复结算/重复回调重复扣费、同一凭证号重复入账。并发交错：退款与扣费同时发生，冲正不重复、不出现负费用。失败后数据不变：扣费失败不改订单费用状态、不重复生成明细。 |
| 状态 | 待修复（设计已出，代码待实现） |

#### T35 欠费、对账与差异处理（M4）

| 项 | 内容 |
| --- | --- |
| 优先级 | **P1（能力）**（无欠费控制、无渠道对账、无差异处理） |
| 阶段 | S5 |
| D/F/R | F09、F04 |
| 涉及文件 | 新增对账任务/差异队列/导出；`merchant_account.arrears/credit_limit`；`merchant_settlement_batch` |
| 问题本质 | 无欠费额度/停单/恢复；无渠道流水与订单/事件核对；差异无记录；`billing_statement` 不能承担账本。 |
| 修复要点 | 欠费宽限期按店铺配置 7/21/30 天，管理员确认后生效；系统可给出建议值但不得自动生效，高频商家建议更短（**Q02 补充规则已确认**）。**欠费与停收款按 Q07-B 已确认口径**：宽限期内允许欠费；欠费累计达到信用额度上限（**初始 500 元、后台可配置**）或账期到期，任一触发即停收款；补齐后自动恢复并记审计。**充值按 Q08-A**：线下转账 + 人工确认，充值单唯一凭证号、幂等入账，防重复确认。**结算按 Q06-C**：月结生成结算单，同时提供逐笔明细查询。对账覆盖订单、支付事件、渠道流水、账本与结算单；差异进入可重试/人工队列；退款冲正入账；账单导出。设计 §8.3、§8.4、§9.5。 |
| 依赖/冲突 | 依赖 T32/T33/T34；Q06/Q07/Q08 已确认，可启用周期任务、欠费停收款与充值入口；停收款只停新单收款，不影响已支付订单履约与退款；与 T25 统计口径分开，统计不得替代账本；与 T36 的客户端 IP 限流无直接冲突。 |
| 回归要点 | 合法：账单与流水一致时确认；余额不足按已确认的 7/21/30 天账期进入欠费宽限；欠费达到 500 元（或配置值）或账期到期即停收款，补齐后自动恢复；退款冲正入账；月结账单与逐笔明细合计一致。非法：缺流水/金额不符标差异而非静默平衡；欠费未达阈值且未到期不得提前停收款；同一凭证号重复确认被拒。并发交错：停单与支付成功同时发生，已支付订单仍履约；并发双确认只入账一次。失败后数据不变：对账失败不覆盖已确认账单；停收款失败不影响订单状态。 |
| 状态 | 待修复（设计已出，代码待实现） |

#### T40 阶段二平台代收与分账演进（M5，资质后）

| 项 | 内容 |
| --- | --- |
| 优先级 | **P2（能力/资质前置）**（阶段一不依赖分账资质；阶段二未取得资质前不得开工） |
| 阶段 | S5（延后，不阻塞阶段一） |
| D/F/R | F01、F03、F05；关联 N3 |
| 涉及文件 | 新增 `merchant_channel_binding/split_receiver/split_detail/split_reverse`；订单**计划新增** `collection_mode/platform_trade_no`；支付事件扩展 |
| 问题本质 | 当前无平台商户号、无分账接收方/明细/冻结/退分；`sharing_status` 只是订单字段，不能视为真实分账结果。 |
| 修复要点 | **已确认 Q09-A**：资质取得后按灰度名单切换，支持按渠道/按商家回滚；平台代收订单写 `PLATFORM_COLLECT`；分账接收方进件与绑定；分账/冻结/退分/退款联动；平台留成与服务费复用原快照；历史阶段一订单不回填。**Q10 撤回后的技术约束**：旧 `payment_channel_type`/`payment_channel` 保留只读、新语义另起列、历史值不重算。设计 §10、§13.2。 |
| 依赖/冲突 | 依赖 T39、T32–T35 全部完成、T15、支付资质/协议/沙箱；切换条件已确认（Q09-A）；**禁止在阶段一用平台商户号代收货款再手工转账**。 |
| 回归要点 | 合法：实收=分账+平台留成；退款先退分再退款。非法：未绑定接收方/超分账比例/重复分账拒绝。并发交错：支付回调、分账、退款交错，金额守恒。失败后数据不变：分账失败可重试，订单支付状态不回退。 |
| 状态 | 待修复（设计已出，资质前不排期） |

### 未单列修复单元的商业能力候选

以下能力缺口不占 40 个修复单元，避免把“能力建设”混入当前缺陷排期；需要时由用户确认范围后另立任务：
- F02 商户正式进件与渠道开通（当前 `SUB+时间戳`、`PASSED` 模拟）。
- F06 投诉协商与平台仲裁（仅模型）。
- F07 实物收货地址与确认收货（地址未落库）。
- F14 商户自助申请与广告转化（仅超管创建、广告只读）。
- F15 短信/邮件/站内信通知（无发送服务）。
- F16 物流轨迹与配送规则（仅公司/运单号）。
- F17–F20 会员/搜索扩展、购物车跨店/营销/评价、批发、多币种为候选功能，未列入本轮 21 项决策，不属于待确认项；发票能力阶段一已确认不做（S12-A），后续另立。

---


## 4. 排序原则

优先级**不是**按文档顺序或 R 编号排，而是按：

```
后果严重度（资损/越权/数据不可逆 > 可用性 > 合规/隐私 > 工程性）
  × 触发概率（默认路径是否必然触发 > 特定条件 > 接入真实渠道后才触发）
  × 改动风险（改动面越大、跨模块越多，越需要前置设计与同批评审）
```

具体判定：

| 判定 | 任务 | 理由 |
| --- | --- | --- |
| 先做 | T01、T02 | 默认路径必然触发，改动面小，先恢复主链路。 |
| 先做 | T03、T04 | 凭据泄露与生产启动阻断，触发概率高、后果为越权/部署失败。 |
| 同批 P0 | T05、T06、T07 | 三者共用支付事件状态机，分拆会留下“事件成功但未履约”的中间态。 |
| P1 但必须随 P0 同批 | T10、T11、T12 | T10 与 T29 的所有权契约、T11 的索引与连接池、T12 的迁移机制都会改动 T05–T09 的同一批 SQL/事务；若后补会二次改支付代码。 |
| P1 可并行 | T13–T18 | 与支付状态机无共享写路径，可在 S1 评审期并行准备，但 T14/T16 依赖 T15 的密钥稳定。 |
| P1 部署/网关 | T19–T23、T36 | 与代码发布绑定，适合在 S1 完成后集中做；T20/T21/T22/T36 同属网关与脚本，禁止拆给不同人同时改。 |
| P2 后置 | T26、T27、T31、T37、T38 | 不阻断交易正确性，但 T26 与 T11 有叠加收益，T31 与 T24 同页面，T37 与 T24 共用 ID 契约，应跟随对应批次。 |
| 能力建设 | T39、T32–T35、T40 | 不是“修 bug”，排期必须在事件三连与权限/密钥修复之后，否则真实资金会放大现有缺陷；T39/M1 先于 T32/M2，T40/M5 在资质与阶段一稳定后启动。 |

必须同批的硬约束：
- **T05 + T06 + T07**：支付事件写入、金额来源、重复支付处理。
- **T10 + T29**：预占所有权凭证与前端调用契约。
- **T14 + T15**：卡密指纹迁移与密钥版本/回退删除。
- **T08 + T11 + T12**：订单截止、索引、迁移机制。
- **T20 + T21 + T22**：同一个 `04-deploy.sh` 的网关、备份、证书逻辑。
- **T01 + T28**：同一个 `ProductDetail.vue` 的初始化与迟到响应。
- **T02 + T13**：同一个推单入口的契约与权限。
- **T24 + T31**：同一个管理端订单页的分页、筛选、发货防重。

允许独立推进、不阻塞主线：
- T19（阿里云 `.env` 中非凭据部分）、T23（备份脚本）、T25（统计聚合）、T26（海报异步）、T27（幂等保留）、T30（搜索/错误态/承诺文案）、T31（运营功能）、T36（可信代理）、T37（ID 序列化）、T38（CI）可在对应阶段内独立交付。
- 商业能力候选 F02/F06/F07/F14/F15/F16 不阻塞任何 S0–S4 任务。

---

## 5. 业务确认清单（已确认 / 已撤回）

21 项决策已于 2026-11-06 全部确认或撤回；以下为回填状态，所有条目均已有明确结论。

| 编号 | 问题 | 阻塞任务 | 不阻塞任务 | 确认状态（用户 2026-11-06） |
| --- | --- | --- | --- | --- |
| B01 | 员工权限矩阵：员工能否推单、发货、看商品成本、看卡密、看店主 PII | T13、T02、T31 | T01、T03、T04、T05–T12、T14–T23 | **已确认 Q13-A**：员工最小权限；只读展示资料、处理被指派订单，推单/发货/资金/卡密/完整 PII 仅店长。 |
| B02 | SKU 编码是“店内唯一”还是“全平台唯一” | T17、T18 | T01–T16、T19–T35 | **已确认 Q14-A**：店内唯一（`merchant_id + sku_code`）；索引与迁移由开发决定。 |
| B03 | 普通订单是否也强制 15 分钟截止；超时支付如何处理 | T08、T09 | T05–T07 的代码顺序、T10–T12 | **已确认 Q15-A**：所有订单 15 分钟截止；超时支付拒绝并记录，不静默 PAID。 |
| B04 | 下架时存在 HELD 预占：允许完成本单、强制取消并退款、还是禁止下架 | T09、T33 | T05–T08、T10–T12 | **已确认 Q16-A**：禁止下架直至预占结束/释放，避免“钱收货不发”。 |
| B05 | 代付是否强制“先预占后付款”，预占有效期与恢复次数 | T10、T29 | T05–T09 的事件修复 | **已确认 Q17-A**：强制预占，15 分钟，最多重发 3 次。 |
| B06 | 幂等键保留期与超期重试语义（72h 是否足够、是否保留键→订单号映射） | T27 | T24、T25、T26、T28–T31 | **已确认 Q18-B**：保留轻量“键→订单号”映射 30 天，不保留敏感响应快照。 |
| B07 | 商品搜索范围：全平台、本店、类目；是否需要会员/搜索扩展 | T30、F17 | T24–T29、T31 | **已确认 Q19-A**：店内 + 全平台双搜索入口（关键词/类目）；会员能力另立。 |
| B08 | 收费政策：服务费/抽佣比例、免收期、结算周期、退款时费用回退 | T39、T34、T35、F10 | T01–T33（除 T32 的费率字段） | **已确认（用户 2026-11-06）**：Q01-A 订单级快照、Q02-A 余额充足支付即扣 + 7/21/30 天欠费宽限、Q03-C 按收款模式退款、Q04-B 商品成交额基数、Q05-D 含税展示+内部价税分离、Q06-C 月结 + 逐笔明细查询、Q07-B 宽限期+固定信用额度（初始 500 元可配）、Q08-A 线下转账+人工确认。仍待：S07 调价生效时点。S12 已确认 A（阶段一不做开票，只留价税数据）。 |
| B09 | 支付业务模式：A 代付分享、B 商户直收、C 混合，各模式的资金流与对账主体 | T32、T33、T34、T35、T39、T40 | T05–T31 | **已确认**：阶段一商家直收；Q03-C 要求退款接口按**计划新增的** `collection_mode` 分叉（现有代码只有 `payment_channel_type` / `payment_channel`）。Q10 撤回：旧字段处理由开发决定，固定“旧列保留只读、新语义另起列、历史值不重算”。 |
| B10 | 匿名下单限制：联系方式必填、频率限制、是否需要验证码/实名 | T30 相关限制、T11 限流 | T05–T10、T12–T29 | **已确认 Q20-A**：免登录 + 联系方式必填 + 现有 IP/联系人限流；验证码/登录/实名阶段一不采用。 |
| B11 | 卡密查看/导出权限与审计：谁能看明文、是否允许批量导出、保留多久 | T16、T14 | T15、T17、T18 | **已确认 Q21-A**：店长按 SKU 限量查看明文；导出二次确认并审计（操作人、数量、时间）。 |
| B12 | 商户进件与开通：是否需要真实资质审核、谁能开通、费率谁定 | T39、F02、F14 | T01–T31 | **已确认（用户 2026-11-06）**：费率 1%–10% 与推广 0% 已定（Q01/Q05）；Q11 分阶段实名（阶段一人工审核、预留 `verification_method/verification_source`，第三方后续增强）；Q12 账户登记+人工核对、**户名必须等于实名主体**硬校验、不接第三方账户验证。阶段一做申请/实名/账户审核，真实渠道进件另立。 |

### 5.1 已确认决策 → 受影响任务编号（开工前对照入口）

| 决策 | 已确认口径 | 受影响任务 |
| --- | --- | --- |
| S07 | B 次日 00:00 生效，按自然日一天一套；降费即时、调高次日 | T39、T32、T34 |
| Q01 | A 订单级费率快照 | T32、T34、T39 |
| Q02 | A 余额充足即扣；7/21/30 天可配账期；0% 推广独立；费率变更审计；免费仍记费 | T34、T35、T39 |
| Q03 | C 按收款模式分流退款 | T32、T33、T34、T35 |
| Q04 | B 商品成交额基数，剔除运费/平台优惠与补贴/税费 | T32、T34、T39 |
| Q05 | D 对外含税、内部价税分离 | T34、T39 |
| Q06 | C 月结 + 逐笔明细查询 | T34、T35 |
| Q07 | 宽限期 + 500 元上限（可配）；超限或到期停收款；补齐自动恢复 | T34、T35 |
| Q08 | A 线下转账 + 人工确认；充值单/唯一凭证号/幂等入账 | T34、T35 |
| Q09 | A 阶段二灰度名单切换 | T40、T32、T34 |
| Q10 | 撤回；旧列只读、新语义另起列、历史值不重算（开发执行） | T32、T39、T40 |
| Q11 | 人工审核起步；预留认证方式/核验来源；第三方后续 | T39、T13 |
| Q12 | 登记 + 人工核对；户名=实名主体硬校验；不接第三方验证 | T39、T32、T40 |
| Q13 | A 员工最小权限 | T13、T02、T31 |
| Q14 | A SKU 店内唯一 | T17、T18 |
| Q15 | A 所有订单 15 分钟截止 | T08、T09 |
| Q16 | A 有预占时禁止下架 | T09、T33 |
| Q17 | A 代付强制预占 15 分钟、最多重发 3 次 | T10、T29 |
| Q18 | B 幂等键轻量映射保留 30 天 | T27 |
| Q19 | A 店内 + 全平台双搜索入口 | T30、F17 |
| Q20 | A 免登录 + 联系方式必填 + 现有 IP/联系人限流 | T30、T11 |
| Q21 | A 店长按 SKU 限量查看；导出二次确认并审计 | T16、T14 |
| S07 | B 次日 00:00 生效，按自然日一天一套；降费即时、调高次日 | T39、T32、T34 |
| S12 | A 阶段一不做开票，只留价税分离数据 | T34、T39 |

### 5.2 开发自行决定的技术项（不属业务决策）

以下事项由开发决定并写入实现/迁移说明，不再要求用户选择：

- `collection_mode` / `pay_channel` 的具体列名、类型、默认值；旧 `payment_channel_type` / `payment_channel` 的只读与迁移映射。
- 新表 `merchant_onboarding_application`、`merchant_settlement_account`、`merchant_fee_policy`、`merchant_recharge_order`、`merchant_account_ledger`、`settlement_batch/item` 的字段细节、主键与索引命名。
- 唯一索引 DDL 与存量数据迁移脚本（含 `(merchant_id, sku_code)` 改造、历史值不重算）。
- 幂等映射表结构、清理任务批次与最大轮次、导出接口实现、审计表结构。
- 错误码文案、日志字段、分页参数命名。

---

## 6. 与旧文档的关系

### 6.1 取代关系

| 文档 | 定位 | 本文件如何使用它 |
| --- | --- | --- |
| [2026-11-04 diagnostic](2026-11-04-project-diagnostic-report.md) | **历史时点的背景证据**：问题域、审查方法、§4 回归场景 | 只引用问题背景与回归思路；其“当前状态/当前事实”已被本文件取代，不得再当进度。 |
| [2026-11-04 remediation todo](2026-11-04-remediation-todo.md) | **历史时点的待办模板**：批次依赖与最小回归场景 | 只引用回归场景；其 §3 状态列不可信（27 项“已修复”中 14 项不成立）。 |
| [2026-11-06 backend-logic-review](2026-11-06-backend-logic-review.md) | **当前代码的详细证据**（P0/P1/P2 分级与行号） | 作为 T05–T18、T24–T27、T32–T40 的证据来源；冲突时回源码复核。 |
| [2026-11-06 商家结算设计](2026-11-06-merchant-settlement-design.md) | **S5 阶段一/阶段二的设计与数据模型**（非现状） | T39/T32–T35/T40 的详细依据；与代码冲突时以代码现状为准并回写本表。 |
| [2026-11-06 商家结算分期实施](2026-11-06-merchant-settlement-implementation-plan.md) | **S5 现状差距与 M1–M5 拆分**（非现状） | 用于核对 S5 交付范围、依赖与回归要点；状态仍只以本文件为准。 |
| [2026-11-06 backend-persistence-concurrency-review](2026-11-06-backend-persistence-concurrency-review.md) | **当前代码的并发/持久化旁证** | 作为 T08、T11、T12、T14、T25 的并发证据；不单独决定业务规则。 |
| [2026-11-06 frontend-deploy-review](2026-11-06-frontend-deploy-review.md) | **当前前端与部署证据** | 作为 T01、T02、T19–T31、T36 的证据来源。 |
| [2026-11-06 diagnostic-docs-errata](2026-11-06-diagnostic-docs-errata.md) | **纠错记录**：旧文档哪些条目错、为什么错 | 追溯用；不承担排期。 |
| [2026-11-06 diagnostic-baseline-revised](2026-11-06-diagnostic-baseline-revised.md) | **R01–R35 的纠正后状态** | 与本文件的 D/F/R 追溯列一一对应；若两者状态不一致，以本文件为准并回写 baseline。 |
| [主台账 D/F](B2B2C_项目缺陷与未实现功能清单_2026-11-03.md) | **原始编号体系** | 只用于追溯；D/F 的原始状态不再代表当前进度。 |

### 6.2 明确不再作为进度依据的内容

- 11-04 报告 §3 索引与 §4 逐节的“待处理/待验证”状态。
- 11-04 todo §3 的“已修复”列，以及任何未附代码证据的完成声明。
- 11-06 errata/baseline 中已注明“本次修正”的旧判断（它们本身是纠错记录，不是执行清单）。
- 任何“文档说已修复”但本文件仍标“待修复”的条目，一律以本文件与代码现状为准。

### 6.3 更新规则

- 每完成一个修复单元，更新本文件对应卡片的“状态”和“依据”，并在提交说明中写“执行方/验证状态”。
- 用户反馈通过后，才能把状态改为“已验证”；代理只能改到“已实现·待人工验证”。
- 新增发现先以 `T39+` 追加，不得重排既有编号；需要并入主台账时由用户在 D/F 台账登记映射。

---

## 7. 验证项与验证边界

### 7.1 只缺用户执行的验证项

| 编号 | 对应任务 | 验证内容 | 准确入口 | 预期 |
| --- | --- | --- | --- | --- |
| V01 | T05/T07/T11 | 支付事件提交失败/重放/重复付款 | 由用户按 V03 后端测试入口执行，并补充故障注入（杀进程/断连） | 事件与订单一致、不双履约、第二笔有记录 |
| V02 | T05/T06 | 渠道回调金额/币种校验 | 由用户在渠道沙箱或本地桩执行 | 少付/错币种拒绝，订单保持 UNPAID |
| V03 | 全后端 | 后端单测 | `mvn -f shop-b2b2c-backend/pom.xml test` | 用户执行并反馈；H2 不等于 MySQL 并发 |
| V04 | T01/T02/T24/T28–T31 | 前端规则用例 | `node --test shared/goodsType.test.mjs shared/idempotency.test.mjs shared/buyerReceipt.test.mjs shared/authSession.test.mjs shared/productEditing.test.mjs shared/inventoryAdjustment.test.mjs shared/cardAssets.test.mjs shared/orderStatistics.test.mjs shared/storePage.test.mjs shared/serviceFee.test.mjs shared/storefrontStyles.test.mjs shared/storedPoster.test.mjs`（先 `npm --prefix shop-storefront-vue ci`） | 用户执行；缺依赖跳过不算通过 |
| V05 | T20/T21/T22 | 阿里云构建隔离 | Linux/WSL：`python3 -m unittest discover -s deploy/tests -p 'test_aliyun_build.py' -v` | 用户执行；假工具不编译业务 |
| V06 | T20/T22/T36 | Nginx 网关隔离与真实客户端 IP | Linux/WSL（已装 Nginx）：`python3 -m unittest discover -s deploy/tests -p 'test_nginx_gateway.py' -v` | 用户执行；缺 Nginx 跳过不算通过 |
| V07 | T23 | Windows 备份编码/退出码 | 用户按 `deploy/tests/test_backup_windows.ps1` 在 Windows 执行，并在隔离库恢复 | 首字节非 BOM、SQL 无 warning、失败非零退出 |
| V08 | T10/T14/T17 | MySQL 并发交错 | 用户在真实 MySQL 执行并发用例（预占/导入/建码） | 不双释放、不双导入、冲突稳定 409 |
| V09 | T32–T35、T40 | 真实渠道联调与对账 | 渠道沙箱 + 对账样例，由用户/财务执行 | 金额、状态、账单三方一致；T40 另需分账金额守恒 |

### 7.2 本轮未覆盖

本轮**没有执行**编译、测试、构建、部署、数据库操作或压测，也没有连接任何远端环境。因此：
- 本文件所有“待修复”均来自静态代码核对，不代表运行时复现。
- T01 的 TDZ 影响需实际打开详情页确认；T05–T07 当前无 HTTP 入口，属接入渠道前潜伏缺陷。
- H2/静态推断不能替代 MySQL 并发、真实代理拓扑（R06，非 T06）与真实渠道联调。
- R08（依赖漏洞与日志审计）、R14（MySQL 锁序/库存守恒）仍缺工具或环境证据，未单列修复单元；需在 S1/S4 验证时补记录，不能以静态推断判通过。
- 部署类任务（T20–T23）的 root/证书/备份恢复必须由环境负责人在真实 Linux 主机验证。

---

## 8. 交付说明

- 本轮只写文档：未修改源码、未修改两份 11-04 原文档、未运行 mvn/编译/测试/构建/部署。
- 交付文件：[2026-11-06-remediation-plan.md](2026-11-06-remediation-plan.md)。
- 导航更新：[docs/README.md](README.md) 已将本文件标为后续排期与状态的唯一入口。
- 未完成事项：40 个修复单元均未开始；9 个验证项均待用户执行或环境负责人确认；§5 业务确认项中 Q01–Q21 与 S01–S12 已于 2026-11-06 全部确认或撤回（S07 确认 B：次日 00:00 生效，降费可即时、调高必须次日），无残留待确认项。
- S5 重构依据：[商家结算设计](2026-11-06-merchant-settlement-design.md) 与 [分期实施](2026-11-06-merchant-settlement-implementation-plan.md)；两者只作设计依据，不代表已实现。

