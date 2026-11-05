<!--
  文件：2026-11-06-backend-persistence-concurrency-review.md
  用途：对 shop-b2b2c-backend 的持久化、事务边界、锁与并发、数据一致性不变量、Redis 用法、定时任务与备份迁移做逐层静态审查，形成可执行的修复清单，供后续开发主力接手。
  维护注意：
  1) 本轮为只读静态审查：未修改任何源码或 schema，未运行 mvn/编译/测试/构建，未连接真实 MySQL/Redis。文中"确定存在"仅指代码路径可静态证实，不等于已运行复现；"理论风险"需专项验证或压测确认。
  2) R 系列条目对应 docs/2026-11-04-project-diagnostic-report.md 与 docs/2026-11-04-remediation-todo.md，本文件只做现状核对，不复述旧结论；未在清单中重列的历史项视为未复核，不视为已确认修复。
  3) 行号基于 2026-11-06 HEAD 静态阅读，后续提交可能位移；引用前请按符号重新定位。修复建议只写方向，未附代码。
  4) 本文不构成验收结论。任何修复完成后仍需按 AGENTS.md 由用户手动执行编译/测试/构建与真实 MySQL、Redis 联调。
-->
# 后端持久化与并发一致性静态审查（2026-11-06）

## 0. 审查边界与结论摘要

审查对象：`shop-b2b2c-backend` 主源码、`src/main/resources/db/schema.sql`、`deploy/nginx/nginx.prod.conf`、`deploy/scripts/backup_mysql.sh`、`application.yml`。

审查方式：只读静态阅读（含注解 SQL 与 MyBatis-Plus Lambda 条件构造），不连接数据库、不连接 Redis、不执行任何构建或测试命令。

结论计数：

| 级别 | 数量 | 说明 |
| --- | --- | --- |
| P0 | 0 | 未发现"常见并发路径下必然造成不可恢复数据损坏"的缺陷 |
| P1 | 7 | 确定存在的代码路径缺陷或部署前提缺陷，后果为资金/订单不可自愈、越权、规则失效或全站容量塌陷 |
| P2 | 19 | 规模相关、半修、健壮性或文档/运维前提问题 |
| 待专项验证 | 8 | 静态阅读无法定论，需真实 MySQL/Redis 或并发压测 |

最重要结论：**整套"下单—预占—支付—发卡—释放"的CAS/行锁骨架是健全的，不会超卖、不会重复发卡**；真正的风险集中在两类：一是**支付事件审计与业务事务的提交顺序**（P1-1），二是**时间规则在数据库层缺少统一截止条件、且缺少支撑索引**（P1-2/P1-3）。此外，Nginx 反代下后端只用 `getRemoteAddr()` 导致限流按全平台单桶计数（P1-4），属于部署形态确定缺陷。

### 0.1 与同日业务逻辑审查的衔接（避免重复定性）

- 同日已有 [后端业务逻辑静态审查（2026-11-06）](2026-11-06-backend-logic-review.md)，对支付事件、金额自证、员工 PII、默认加密密钥、事务内海报、统计全量、HTTP 状态、`ownerToken` 等给出 P0/P1/P2 定性。本文件**不重新定性**这些条目，只从持久化、索引、锁与不变量角度补充证据、触发时序和后果；两文件结论一致处以后者为准，本文件不重复展开的部分视为沿用其结论。
- **级别口径差异（有意保留，不矛盾）**：该文件把"支付事件先记 SUCCESS"定为 **P0**，理由是"接入真实渠道后必然成立"（潜伏缺陷）；本文件在"当前 HEAD 无渠道回调入口、`payment.mock: false`"前提下定为 **P1**。**上线真实渠道前必须按 P0 处理**，本文件 P1-1 即按此执行。
- 该文件的 P0-2（代付金额自证）与本文件 R13 现状核对为同一证据；本文件补充"当前无外部调用方，金额核对为空转"，因此不单独升级，但随 P1-1 一并修复。

---

## 1. 级别与状态定义

- **P0**：常见流量与正常运维下必然发生、且不可通过重试自愈的数据损坏或资金损失。
- **P1**：代码路径确定存在；触发可能依赖特定故障、特定部署形态或达到一定数据量，但后果严重（订单永久不履约、越权读取、规则整体失效、全站限流塌陷）。
- **P2**：规模放大后明显、半修、健壮性不足、注释/文档与实现不一致、运维前提缺失。
- **性质-确定**：不依赖运行时才能确认的分支，静态即可断定会发生。
- **性质-理论**：需要特定交错、故障注入、数据分布或真实中间件行为才能确认，落入"待专项验证"的则单列。

---

## 2. P1 发现（7 项）

### P1-1 支付事件 SUCCESS 审计先于业务事务提交，提交失败后订单永久不履约

**级别**：P1（本文件口径）；接入真实支付渠道后按 P0 处理，见 0.1　**性质**：代码路径确定存在（触发依赖提交阶段故障）

**证据**

- [PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L231-L232)：业务写入完成后，立即以独立事务写 SUCCESS：

```java
order = orderMapper.save(order);
eventRecorder.recordEventStatus(pe.getId(), "SUCCESS", null);
```

- [PaymentEventRecorder.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L32-L33)：`recordEventStatus` 为 `Propagation.REQUIRES_NEW`，走独立连接、独立提交。
- [PaymentEventServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L58-L61)：重试入口只按 `eventStatus == SUCCESS` 短路，不核对订单真实状态。
- [PeerPayServiceImpl.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L505-L506)：代付路径在外层事务内再调一次 `findById`，异常会使外层回滚。

**触发时序**

1. 渠道回调 → `processPaymentEvent` 在调用方事务中执行：`markOrderPaidIfUnpaid` 成功、库存预占 C态/卡密 claim、`orderMapper.save` 全部完成，但这些都未提交。
2. 执行到 L232，`REQUIRES_NEW` 用另一条连接把 `payment_event.event_status` 置为 `SUCCESS` 并独立提交。
3. 外层事务在提交阶段失败：进程被 kill、Hikari `connection-timeout` 30s 超时、提交时死锁/连接断、或代付路径 L506 抛错。
4. 外层回滚：订单回到 `UNPAID`，预占回到 `HELD`（或回滚为未扣减），卡密 `claim` 回滚，库存回滚；但事件仍是 `SUCCESS`。
5. 渠道重试同流水号 → L58 命中 `SUCCESS` 直接返回 `success=true`，**不再重新履约**；后续所有重试同样短路。

**影响**：买家资金已到账，订单永久 `UNPAID`，卡密不发放，系统无自愈路径，只能人工改库；同时订单与支付事件的事实不一致，破坏 R13 的可追溯性。属于资金类 P1。

**修法方向**

- 最小改动：`SUCCESS` 不再由独立事务提前写，改为外层事务提交后写（`TransactionSynchronization.afterCommit`）或同连接写；提交失败时事件保持 `PROCESSING`，重试可重新履约。
- 结构改动（推荐）：引入 outbox——支付事件只记录 `RECEIVED/PROCESSING`，`SUCCESS` 以订单状态为唯一事实来源；履约改为幂等状态机，重试可从任意中间点继续。
- 无论采用哪种，**重试短路条件必须加"订单确为 PAID/COMPLETED/SHIPPED"**，否则不得返回成功。

---

### P1-2 普通订单在数据库层没有 15 分钟支付截止校验

**级别**：P1　**性质**：代码路径确定存在

**证据**

- [TradeOrderMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L54-L60)：普通订单落账条件允许 `lock_expire_time IS NULL`：

```sql
WHERE id = #{id} AND order_status = 'UNPAID'
  AND (lock_expire_time IS NULL OR lock_expire_time > CURRENT_TIMESTAMP)
```

- 普通建单路径从不写 `lock_expire_time`：全代码库 `setLockExpireTime` 仅出现在代付预占与清空处（[PeerPayServiceImpl.java#L423](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L423)、[#L448](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L448)），普通 `checkout` 不设置。
- 代付分支则严格要求有效期：[TradeOrderMapper.java#L45-L48](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L45-L48) 要求 `peer_status='RESERVED_LOCK' AND lock_expire_time > CURRENT_TIMESTAMP`。
- `expireIfNecessary` 使用的是内存计算 `createdAt.plusMinutes(15)`：[PeerPayServiceImpl.java#L461-L471](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L461-L471)。

**触发时序**

1. 12:00 建普通单（`UNPAID`，`lock_expire_time = NULL`）。
2. 12:20 渠道回调到账；此时若调度器尚未关单（服务停机、任务积压、过期扫描慢，见 P1-3），`markOrderPaidIfUnpaid` 命中 `lock_expire_time IS NULL` → 直接 `PAID` 并完成履约。
3. 同一时刻另一笔代付单在 12:20 回调，则因 `lock_expire_time > now` 不成立而返回 `ORDER_STATUS_INVALID`，资金进入人工/退款语义。

**影响**：同一条"15 分钟未支付即关闭"的业务规则，**因订单是否带 `peer_pay_token` 而在数据库层表现不一致**；普通订单是否被履约取决于调度器是否先跑到，规则时好时坏。窗口≈调度周期 + 积压时长（见 P1-3，积压可无限增长）。与 `LATE_PAYMENT` 判定形成竞态解释困难。

**修法方向**

- 建普通单时即写入 `lock_expire_time = created_at + 15min`，`markOrderPaidIfUnpaid` 与代付分支统一使用 `lock_expire_time > CURRENT_TIMESTAMP`（去掉 `IS NULL` 放行）。
- 或把条件改为显式时间参数（由服务传入 `now - 15min`），避免 SQL 内 `CURRENT_TIMESTAMP` 与调度器时间源不一致。
- 存量数据：对历史 `UNPAID` 单补写截止时间或统一按 `created_at` 判定，需在迁移方案中说明（见 P2-15）。

---

### P1-3 `trade_order` 缺过期扫描与分页排序索引，30 秒任务随数据量线性退化

**级别**：P1　**性质**：确定存在（影响随数据量增长）

**证据**

- [schema.sql#L162-L169](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L162-L169)：`trade_order` 只有 `uk_order_sn`、`uk_peer_pay_token`、`idx_merchant_order(merchant_id, order_status)`、`idx_contact`、`idx_query_token`，**没有 `created_at`、`lock_expire_time` 相关索引**。
- [TradeOrderMapper.java#L62-L69](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L62-L69)：过期扫描条件为 `order_status='UNPAID' AND (lock_expire_time<=now OR (lock_expire_time IS NULL AND created_at<=cutoff)) AND id>cursor ORDER BY id LIMIT n`。`id` 有序可用主键，但状态与时间列无索引，**每轮需逐行过滤，UNPAID 存量越大扫描越慢**，最坏退化为全表扫描。
- [ReservationExpiryJob.java#L32-L36、L61-L82](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L32-L36)：每 30 秒执行一次，每批 100 条。
- [TradeOrderMapper.java#L110-L135](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L110-L135)：列表分页 `ORDER BY created_at DESC LIMIT/OFFSET`，无 `created_at` 索引 → filesort；**且无 `id` 兜底排序**，`created_at` 同秒（种子数据、批量迁移、并发建单）时结果不稳定。

**触发时序**

1. 大促/批量导入后 `UNPAID`（含历史脏单）累积到数万级，30 秒任务单轮只能处理 100 条，扫描却要遍历大量行。
2. 过期订单产生速度持续 >100/30s 时，任务游标持续前移、积压单调增长；越积压，单轮扫描越慢，形成正反馈。
3. 用户浏览订单列表：`created_at` 相同的一批订单在翻页时重复出现或整页漏掉（同一排序键无 tiebreaker）。

**影响**：P1-2 的"15 分钟"窗口被放大到不可控；同时数据库 CPU 随订单量线性上升，列表分页出现重复/漏行。R24 的"万级订单每页查询有界"未被满足。

**修法方向**

- 新增索引：`KEY idx_order_expiry (order_status, lock_expire_time, created_at, id)`；列表用 `KEY idx_merchant_created (merchant_id, created_at, id)` 与 `(created_at, id)`（按实际查询面拆分）。
- 分页统一改为 keyset（`created_at + id` 复合游标），禁止 `OFFSET` 深翻。
- 过期扫描改为"按 `lock_expire_time` 索引范围 + `id` 游标"，并监控积压量（见 P2-18）。
- 索引上线需配套存量迁移方案（`CREATE TABLE IF NOT EXISTS` 不会补索引，见 P2-15）。

---

### P1-4 Nginx 反代后，后端仅用 `getRemoteAddr()`，限流退化为全平台单桶

**级别**：P1　**性质**：部署形态确定存在（直连部署不触发）

**证据**

- [nginx.prod.conf#L42-L52](file:///e:/shop-b2b2c/deploy/nginx/nginx.prod.conf#L42-L52)：反代设置 `X-Real-IP $remote_addr`、`X-Forwarded-For`，上游为 `127.0.0.1:8080`。
- [OrderController.java#L69](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L69)：`request.getRemoteAddr()` 作为 IP 归因。
- [TradeCostLimiter.java#L73-L102](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/TradeCostLimiter.java#L73-L102)：`order:ip:`、`prelock:ip:`、`poster:ip:` 三个桶全部基于该地址。
- 同样问题存在于登录与查单限流（`AuthLoginLimiter`、`OrderQueryLimiter` 的 IP 桶）。

**触发时序**

1. 两个不同买家经同一 Nginx 访问后端，`remoteAddr` 都是 `127.0.0.1`。
2. 两者落同一个 `order:ip:127.0.0.1` 桶；第 61 个买家在 5 分钟窗口内被 429，**请求被拒绝且不写单**。
3. 攻击者与正常用户共享同一桶，无法区分；同时 `X-Forwarded-For` 完全未被使用。

**影响**：建单 60/5min、预占、海报、登录的"每客户端"限流在反代部署下退化为**全站总量上限**，正常业务被误杀或整体被轻易打满。R06"不同真实客户端桶独立、伪造 XFF 不能绕过"仍未闭环。

**修法方向**

- 引入受信代理模式：仅当直连来源在配置的反代白名单内，才解析 `X-Real-IP`（或取 `X-Forwarded-For` 最右可信跳），否则一律用 `remoteAddr`。
- 反代侧注入不可伪造的身份头（如带签名的 `X-Client-IP` + 共享密钥/HMAC），后端校验后才信任。
- 桶 key 增加环境前缀（见 P2-10），避免多环境串桶。
- 补充回归：直连伪造 XFF 仍按直连 IP 计数；反代下不同真实 IP 独立计数。

---

### P1-5 卡密加密密钥缺省回退到源码内置常量；种子卡密为明文且无指纹

**级别**：P1　**性质**：确定存在

**证据**

- [CardCipherService.java#L39-L68](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L39-L68)：配置项为空、环境变量 `CARD_ENCRYPTION_KEY` 为空时，使用硬编码种子 `shopizer-b2b2c-card-default-secret-seed-2026` 派生密钥。
- [application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml)：未配置 `shop.security.card-secret-key`。
- [schema.sql#L326-L334](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L326-L334)：种子 `card_content` 为明文 `TXVIP-2026-...`，且未写 `card_fingerprint`（列为 NULL）。
- [CardCipherService.java#L97-L103](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardCipherService.java#L97-L103)：无 `ENC:v1:` 前缀时透明返回明文，意味着库内明文卡密"可直接使用"。

**触发时序**（确定性，无需并发）

1. 生产按默认配置启动 → 所有新导入卡密用公开常量派生密钥加密。
2. 任何能读仓库/镜像的人取得同一常量 → 用同一算法解密生产库与备份中的 `ENC:v1:` 卡密。
3. 种子明文卡密即使在未拿到密钥时也可直接使用；`card_fingerprint = NULL` 使这些卡不参与唯一指纹去重（MySQL 允许多个 NULL）。

**影响**：R05"库与备份不出现直接可用卡密"不成立。加密提供的是**混淆**而非机密性。属于安全/持久化 P1。

**修法方向**

- 生产环境强制显式配置密钥；缺失时启动失败（接入 `ReadinessService` 或 `@PostConstruct` 校验），不再回退默认常量。
- 种子卡密改为无效占位（或仅在 dev profile 注入），并回填 `card_fingerprint`。
- 提供密钥轮换与重加密作业（旧 key 解密 → 新 key 加密），并在备份恢复文档中明确"密钥必须与备份同时可恢复"。
- 注意：`decrypt` 的明文兼容分支会长期削弱"库内无明文"结论，迁移完成后应收紧。

---

### P1-6 商家员工（MERCHANT_STAFF）可读取身份证号与银行账号

**级别**：P1　**性质**：确定存在（越权读取）

**证据**

- [SaTokenConfigure.java#L31-L33](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaTokenConfigure.java#L31-L33)：`/api/v1/merchant/**` 放行 `MERCHANT_STAFF`。
- [MerchantController.java#L200-L212](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L200-L212)：`getMerchantProfile` 直接返回完整 `MerchantStore` 与 `MerchantPaymentAccount`，未按角色裁剪。

**触发时序**（确定性）

1. 店长创建一名 `MERCHANT_STAFF` 账号。
2. 员工登录后 `GET /api/v1/merchant/profile` → 响应包含 `id_card_number`、`bank_account_number`、`bank_account_name`。
3. 该数据可被员工导出，且同类字段在其他接口可能同样暴露（需逐接口按角色裁剪）。

**影响**：个人敏感信息与结算账户越权可见，违反最小权限。R02"员工未授权操作拒绝"在资料读取面未覆盖。

**修法方向**

- 拆分端点：`/merchant/profile`（店长/超管，含敏感字段）与 `/merchant/profile/basic`（员工可用，脱敏 `accountNo` 仅后四位、隐藏身份证）。
- 或在同一端点按角色返回白名单字段，禁止直接序列化整个实体。
- 审计日志记录敏感字段读取（不含明文）。

---

### P1-7 `REQUIRES_NEW` 嵌套在 Hikari 20 连接下形成连接池自锁

**级别**：P1　**性质**：容量型确定（并发接近池上限时必然发生）；需压测确认阈值

**证据**

- [application.yml#L24-L28](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L24-L28)：`maximum-pool-size: 20`、`connection-timeout: 30000`。
- [PaymentEventRecorder.java#L22-L33](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PaymentEventRecorder.java#L22-L33)：两个方法均为 `REQUIRES_NEW`。
- [PaymentEventServiceImpl.java#L94、L101、L106、L111、L119、L125、L148、L161、L169、L181、L189、L206、L222、L232](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PaymentEventServiceImpl.java#L94)：一次成功回调最多会多次调用独立事务记录器。

**触发时序**

1. 20 个支付回调并发进入（每个请求先开外层事务，各占 1 条连接，池 20/20）。
2. 每个请求在核验/失败/成功节点调用 `recordEventStatus`（`REQUIRES_NEW`），需要第 2 条连接。
3. 池已满，全部线程等待；外层事务同时持有订单行锁与连接。
4. 30 秒后 `connection-timeout` 触发，回调批量失败；若渠道重试窗口重叠，失败会叠加。

**影响**：支付回调在并发达到池上限附近时**批量超时**，叠加 P1-1 造成"事件状态与订单状态分裂"，运维表现为渠道重试风暴。属于容量 P1，需压测确认实际拐点。

**修法方向**

- 审计事件改为同事务写入（与 P1-1 的 outbox/afterCommit 一起做），彻底去掉 `REQUIRES_NEW`。
- 若必须独立事务，使用独立的小连接池或预留连接，不计入主池；并为该路径设置更短超时与降级策略。
- 压测目标：20/40/80 并发回调下 P99 与失败率，确认池大小与超时（列入待专项验证 V-03）。

---

## 3. P2 发现（19 项）

### P2-1 过期关单在预占入口内被抛异常回滚

**证据**：[PeerPayServiceImpl.java#L357-L370](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L357-L370) 先调 `expireIfNecessary(order)`（内部 `release` + 置 `CLOSED/EXPIRED` + save），随后在 L363 对"已过期/状态不符"抛 `BusinessException`，整个 `@Transactional` 回滚，刚完成的关单与释放被撤销。

**触发时序**：买家在预占入口点击时恰好已过期 → 关单写入被回滚 → 下一次任意入口或 30s 任务才真正关闭。反复点击可反复"假关闭"。

**影响**：仅浪费一次事务与一次库存释放计算，不会双加（整事务回滚），故 P2。但会产生"接口返回失败、数据库仍 UNPAID"的观测不一致。

**修法方向**：过期分支不要抛异常回滚，改为返回值/错误码并让事务提交；或把过期处置拆到独立事务（与 P1-7 的独立连接问题一并权衡）。

### P2-2 `ownerToken` 由客户端 `clientKey` 决定，恢复分支为弱授权

**证据**：[PeerPayServiceImpl.java#L357-L394](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L357-L394)：`ownerToken` 取客户端提交的 `clientKey`；恢复仅比较 `clientKey == existing.ownerToken`。

**触发时序**：攻击者获得 `peer_pay_token`（分享链接/海报可见）后，若同时猜中或复用 `clientKey`（取决于前端生成熵），可认领已有预占。属**理论**风险，需确认前端 `clientKey` 生成方式（是否 `crypto.randomUUID` 级熵、是否可预测/复用）。

**修法方向**：服务端生成高熵 `ownerToken` 返回客户端；恢复仅接受服务端签发的 token（可加 HMAC 绑定订单）；限制恢复次数与时间窗。

### P2-3 卡密导入在无指纹命中时执行两次全店历史全量读取（R25 半修）

**证据**：[CardAssetService.java#L64-L77](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java#L64-L77)：`assets.findExistingFingerprint(...)` 未命中后进入 `assets.historyContents(merchant) != null`——该方法返回 `List`，**永不为 null**，条件恒真；随后循环里又调用一次 `assets.historyContents(merchant)`，即**两次全店卡密行（含密文、已售、作废）读取**。

**触发时序**：任一"库里没有该批指纹"的正常导入（最常见路径）都会触发两次全量读取；大商户百万级卡密时单次导入可造成长时间锁商户行 + 内存尖峰 + GC。

**影响**：导入耗时为 O(全店卡密)，与批大小无关；R25"大历史不读全部明文"仅半修（不读明文，但仍全量读密文/行）。另外历史遗留"加密但无指纹"的卡以密文摘要比对，导致**重复判断漏判**。

**修法方向**：删除 `!= null` 恒真判断，只查一次并复用结果；对无指纹存量做一次性回填迁移，迁移后删除兼容分支；仍保留的行按 `(merchant_id, card_fingerprint)` 唯一索引查询。

### P2-4 海报渲染仍在事务内（R26 半修）

**证据**：[PeerPayServiceImpl.java#L138-L141](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L138-L141) 与 [#L238-L241](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L238-L241)：`PosterUtils.generatePeerPayPoster` 在 `@Transactional` 方法内执行。

**影响**：CPU 渲染（可能数十到数百毫秒）期间持有连接与商品/订单相关锁，放大 P1-7 的池占用；`regeneratePoster` 已改为事务外（正面），但建单路径未改。

**修法方向**：建单事务只落库并返回 token，海报改为异步生成 + 存储；首次请求按需生成（事务外），失败不影响建单。

### P2-5 `listCards` 无分页、全量解密返回

**证据**：[ProductController.java#L222-L260](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L222-L260)：按商户或 SKU 取全部卡密，循环 `decrypt` 后整体返回。

**影响**：大商户（万级卡密）单请求可造成大响应、内存尖峰与长占用；虽有角色限制（仅店长/超管），仍是规模 P2。

**修法方向**：游标分页（`id` 游标，`limit<=200`），列表默认不返回卡密正文，仅详情/导出按需解密；导出走异步任务。

### P2-6 `heldQuantity` 缺组合索引

**证据**：[schema.sql#L251-L262](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L251-L262)：仅 `idx_reservation_expiry(status, expires_at)`；[InventoryAdjustmentMapper.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/InventoryAdjustmentMapper.java) 的 `heldQuantity` 按 `sku_id + merchant_id + status='HELD'` 求和。

**影响**：每次卡密导入、作废、库存调整都会扫描大量预占行；预占表随时间增长。属规模 P2。

**修法方向**：新增 `KEY idx_reservation_sku_status (merchant_id, sku_id, status)`。

### P2-7 统计接口每请求全量投影，分页无 tiebreaker（R24 半修）

**证据**：[TradeOrderMapper.java#L137-L148](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java#L137-L148)：`findStatisticsProjectionAll()` 无条件全表读取；`findStatisticsProjectionByMerchantId` 按商户全量读取。[OrderController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java) 的 `listAdminOrders` / `merchantStatistics` 每请求调用。

**影响**：管理端/商家看板刷新频率高时，订单增长直接线性放大 DB 负载；万级订单已可观测，百万级不可用。

**修法方向**：改为聚合 SQL（`GROUP BY order_status, peer_status` 在库内求和）或按日预聚合表；加时间范围参数限制扫描面；分页改 keyset。

### P2-8 幂等记录事实上受阻于清理策略，但头注释仍写"不自动过期"

**证据**：[OrderIdempotencyService.java#L4](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderIdempotencyService.java#L4) 头注释"记录不自动过期，清理前需另立保留策略"；[IdempotencyPurgeJob.java#L44-L52](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/IdempotencyPurgeJob.java#L44-L52) 已按 72h 批删；[IdempotencyMapper.java#L26-L27](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/IdempotencyMapper.java#L26-L27) 只删 `response_json IS NOT NULL AND created_at < cutoff`。

**影响**：注释与实现矛盾（R27 的维护误导）；另 `while (deleted >= batchSize)` 无轮次上限，首次上线清理历史洪峰时会长时间占用单实例。

**修法方向**：更新注释为"已完成记录按 72h 清理，未完成占位保留"；给清理加最大轮次/时间预算，避免长跑。

### P2-9 固定窗口边界双倍突发；多桶检查非原子

**证据**：[TradeCostLimiter.java#L104-L120](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/TradeCostLimiter.java#L104-L120)：单键 `INCR + EXPIRE` Lua 原子（正面），但固定窗口在窗口切换瞬间可放行 2×limit。[#L73-L82](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/TradeCostLimiter.java#L73-L82)：IP 桶与 contact 桶顺序独立检查，**非原子**——IP 计数已消耗后 contact 超限拒绝，合法用户被额外计数，反之亦然。

**影响**：限流精度与公平性下降；边界突发对建单成本敏感。理论/规模问题，P2。

**修法方向**：改滑动窗口（ZSET 或令牌桶 Lua）；多桶合并为一次 Lua（同 key 前缀 + 多 key 原子判断），先判定全部通过再统一扣减。

### P2-10 Redis key 无环境/租户命名空间，摘要无盐

**证据**：[TradeCostLimiter.java#L104-L109](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/TradeCostLimiter.java#L104-L109)：`rate:cost:{sha256(identifier)}`；[application.yml#L43](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L43)：默认 `REDIS_DB:0`。

**影响**：多环境共享 Redis 时（默认 db0）限流桶互相污染；手机号/账号空间小，无盐 SHA-256 可离线枚举匹配（低价值但可判定某号码是否被限流）。

**修法方向**：key 增加 `{app}:{env}:` 前缀；对隐私标识加服务端盐（HMAC）；文档明确各环境 Redis DB 隔离要求。

### P2-11 `OrderQueryLimiter` 阈值硬编码，与其他限流不一致

**证据**：[OrderQueryLimiter.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderQueryLimiter.java)：IP 300/300s、订单 60/300s 写死在代码中，而 `TradeCostLimiter` / `AuthLoginLimiter` 走配置。

**影响**：运维无法按环境调参；与其他模块策略不一致易误判。P2。

**修法方向**：统一改为 `@Value`/配置项，并纳入配置校验（范围与默认值）。

### P2-12 `checkout` 的 `@Transactional` 自调用失效，靠外层 TransactionTemplate 兜底

**证据**：[OrderController.java#L71-L75](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L71-L75)：`checkoutRequest` 通过 lambda 调同类 `checkout(dto)`，`@Transactional` 不生效；实际事务来自 `idempotency.execute` 的 `TransactionTemplate`。

**影响**：当前行为正确（事务存在），但注解是"假的"，后续若有人绕过幂等直接调 `checkout` 会**无事务写入**。P2 脆弱设计。

**修法方向**：移除该注解并把事务责任显式交给幂等服务的 TransactionTemplate（文档说明），或把建单下沉到独立 `@Service` 方法由代理调用。

### P2-13 建商户先查后插竞态；`subMchid` 同毫秒可重复且无唯一约束

**证据**：[MerchantController.java#L89-L94](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L89-L94) 先 `findByStoreCode` / `findByUsername` 再插入；[#L124](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java#L124) `setSubMchid("SUB" + System.currentTimeMillis())`；[schema.sql#L51](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L51) 支付账户唯一键是 `(merchant_id, channel)`，不含 `sub_mchid`。

**触发时序**：两个超管并发审批同一 `storeCode` → 唯一键冲突 → `DuplicateKeyException` 落入通用 500（见 P2-14）；同一毫秒创建两商户 → `sub_mchid` 相同，无约束拦截。

**影响**：并发审批出现 500 而非明确业务错误；分账子商户号重复可能在下游渠道侧才暴露。P2。

**修法方向**：`DuplicateKeyException` 映射为 409 业务错误；`sub_mchid` 用雪花/UUID 后缀并加唯一索引；保留先查仅为友好提示，正确性依赖唯一键。

### P2-14 `BusinessException` 仍返回 HTTP 200（R34 未闭环）

**证据**：[GlobalExceptionHandler.java#L81-L85](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java#L81-L85)：仅有 `@ExceptionHandler`，**无 `@ResponseStatus`**，业务错误统一 HTTP 200 + body 业务码；[#L111-L116](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java#L111-L116) 通用异常 500；`DuplicateKeyException` 未单独映射。

**影响**：网关/监控/前端拦截器无法按状态码区分失败；限流 429、未登录 401 走的是 `BusinessException(429/401)` 还是独立异常需逐路径核对——若走 `BusinessException` 则状态码仍是 200。与文档"R34 已修复"不一致。

**修法方向**：给 `BusinessException` 以 `@ResponseStatus` 动态映射（或返回 `ResponseEntity`），按 code 段映射 400/401/403/404/409/429/503；`DuplicateKeyException` 单独映射 409；补充契约测试。

### P2-15 schema 只 `CREATE TABLE IF NOT EXISTS`，不升级存量结构与索引

**证据**：[schema.sql#L3](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L3) 维护注释明确不升级旧结构；[application.yml](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml) `spring.sql.init.mode=always`；全文件无 `ALTER TABLE`。

**影响**：本文件建议新增的任何索引（P1-3、P2-6）在**已存在的库上不会生效**；删改列同理。运维容易误以为"启动即迁移"。

**修法方向**：建立版本化迁移（Flyway/Liquibase 或受控 `Vxxx__*.sql`），或提供幂等的 `ALTER`/索引存在性校验脚本；发布清单中明确"新库靠 schema，存量库靠迁移"。

### P2-16 `payment_event`、`inventory_reservation` 无保留/归档策略

**证据**：[schema.sql#L449-L467](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L449-L467)（支付事件表）与 [#L251-L262](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L251-L262)（预占表）；代码中仅幂等表有清理任务（[IdempotencyPurgeJob.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/IdempotencyPurgeJob.java)）。

**影响**：两张表随业务单调增长；结合 P2-6 的扫描放大，长期影响查询与备份体积。

**修法方向**：定义保留期（支付事件按合规要求，预占按已终态时间），归档到历史表或分区；清理任务复用游标分批模式。

### P2-17 备份脚本：密钥不在备份内、密码走命令行、无恢复演练

**证据**：[backup_mysql.sh](file:///e:/shop-b2b2c/deploy/scripts/backup_mysql.sh)：`mysqldump --single-transaction --quick` 与 30 天轮转（正面）；`-p${DB_PASS}` 置于命令行；脚本不含卡密密钥；仓库无恢复演练记录。

**影响**：跨环境恢复若缺 `card-secret-key`，`ENC:v1:` 卡密永久不可解密（与 P1-5 联动）；密码在 `ps`/审计日志可见；备份"能导出但未验证能恢复"。

**修法方向**：密码改 `MYSQL_PWD` 或 `--defaults-extra-file`；备份清单包含密钥的安全交付流程（不写入同一备份包）；增加定期恢复演练（隔离库）与记录；恢复后校验订单/预占/卡密/库存守恒（见 V-08）。

### P2-18 过期任务失败即前移游标，持续积压时可能长期跳过同一批

**证据**：[ReservationExpiryJob.java#L45-L56、L69-L80](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L45-L80)：异常被捕获后 `cursor = max(cursor, id)` 继续前移。

**触发时序**：某批前 100 条持续失败（如固定数据异常、锁冲突），游标越过；由于每轮从游标之后取，失败行要等游标归零（`ids.size() < 100`）才重试。当持续产生 >100 过期项时可能长时间不归零 → **同一批失败行长期得不到重试**。

**影响**：R11 的"100 条失败加第 101 条正常"目标达成，但"失败可追溯"仅有日志，无重试计数/告警。P2。

**修法方向**：为失败行记录重试计数与下次重试时间（或独立失败表），超过阈值告警并转人工；游标归零逻辑保留，但增加"按到期时间补偿扫描"的第二通道。

### P2-19 卡密导入按商户串行、持锁时长与批量线性

**证据**：[CardAssetService.java#L40-L100](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java#L40-L100)：事务内 `lockMerchant` → `lockProduct` → `lockSku`，随后循环插入最多 1000 张卡、逐条 `batch.link`，再 `addStock`。作废、库存调整同样先锁商户。

**影响**：单店并发导入/作废全部串行；批量 1000 时锁持有时间可达秒级，叠加 P2-3 的全店扫描更甚。P2 规模。

**修法方向**：缩短临界区（先校验与去重、再短事务批量插入）；卡密插入改批量 `INSERT`；`batch.link` 批量；导入去重放库内唯一键而非全量扫描。

---

## 4. 待专项验证（8 项，静态阅读无法定论）

| 编号 | 事项 | 为什么不能静态定论 | 建议验证方式 |
| --- | --- | --- | --- |
| V-01 | P1-1 的提交阶段失败窗口是否真实可达 | 需要注入提交阶段失败（进程 kill、连接断、死锁） | 故障注入：在 `recordEventStatus(SUCCESS)` 后强杀进程/断连接，重启后重放同流水号，观察订单与事件状态；隔离测试库 |
| V-02 | P1-2 的超时窗口实际宽度 | 取决于调度器积压与扫描耗时 | 构造 1 万/10 万 `UNPAID`，观测单轮扫描耗时与积压曲线；记录"过期到关单"的最大延迟 |
| V-03 | P1-7 连接池拐点 | 需并发压测 | 20/40/80 并发回调，测 P99 与 `connection-timeout` 触发率；同时观察行锁等待 |
| V-04 | P2-2 `clientKey` 熵与复用 | 需看前端实际生成方式 | 审查 storefront 的 `clientKey` 生成与存储；做一次"仅持分享链接"的接管尝试 |
| V-05 | P1-3 索引收益与执行计划 | 需真实 MySQL 优化器与数据分布 | `EXPLAIN` 过期扫描与两种分页 SQL；对比加索引前后 `rows_examined` |
| V-06 | P2-9 固定窗口边界与多桶非原子的实际影响 | 需真实 Redis 时序 | 边界秒并发 2×limit 请求，统计放行数；模拟 contact 桶拒绝观察 IP 计数偏差 |
| V-07 | Redis 故障降级是否一致 | 需停 Redis | 停 Redis 后逐接口验证：支付/预占/建单/查单/登录应 503 失败关闭而非静默放行；检查 Sa-Token 会话行为 |
| V-08 | 备份恢复后的守恒性 | 需真实备份与恢复库 | 恢复后校验：卡密未售数 = `stock_quantity` + HELD；订单状态与预占状态一致；`ENC:v1:` 可解密 |

---

## 5. R 系列现状核对（只列有变化或需注意的项）

| 编号 | 原条目要点 | 当前 HEAD 现状 | 结论 |
| --- | --- | --- | --- |
| R02 | 员工未授权操作拒绝、角色变化撤销会话 | 路由级角色校验在 [SaTokenConfigure.java#L31-L33](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaTokenConfigure.java#L31-L33)；`AuthSessionService` 每请求 DB 校验角色/状态与 TokenSession 快照 | 会话与操作授权有效；但**资料读取面存在 P1-6 越权**，R02 不能整体标为已闭环 |
| R03 | 正确凭证释放、错误凭证不释放 | `cancelPreLock` 锁单 + 校验 ownerToken 后释放 | 释放路径正确；恢复路径弱绑定见 P2-2 |
| R05 | 库与备份不出现直接可用卡密、密钥轮换 | 已实现 AES-GCM 加密 + 指纹；但**默认密钥回退**、seed 明文无指纹、无轮换作业 | **半修**（P1-5），文档口径需修正 |
| R06 | 真实客户端桶独立、伪造 XFF 不能绕过 | 后端仍只用 `getRemoteAddr()`，反代下共享单桶 | **未闭环**（P1-4） |
| R07 | 超限不写单、不扣库存 | 限流在控制器入口（[OrderController.java#L69](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L69)），先于事务；失败关闭 | 逻辑有效，但归因错误导致误伤（P1-4） |
| R09 | 店铺配置交错更新不覆盖 | [MerchantController.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java) 使用白名单 `COALESCE` 定向更新，未整实体回写 | **有效**（正面） |
| R10 | 丢响应可恢复且只扣一次 | `clientKey` 恢复分支 + 预占唯一键/`deleteReleased` | 只扣一次成立；授权强度见 P2-2 |
| R11 | 100 条失败 + 第 101 条正常仍被处理 | 游标前移已实现（[ReservationExpiryJob.java#L45-L80](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java#L45-L80)） | 目标达成；持续积压下的重试饥饿见 P2-18 |
| R12 | 无人访问也按规则关闭 | 30s 任务存在；但普通订单无 DB 级截止（P1-2），且任务依赖扫描索引（P1-3） | **半修**：规则存在但不可靠 |
| R13 | 重复/乱序/错金额/到账后崩溃均可追溯、不双履约不漏到账 | 事件唯一键、状态机、履约校验已实现；但 **SUCCESS 先提交**（P1-1）、重试短路不核对订单；`notifyPaymentSuccess` 用订单自身金额自证（[PeerPayServiceImpl.java#L500](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java#L500)），当前无外部渠道适配器（`PeerPayService` 注释明确"仅供未来适配器调用"） | **半修**：金额核对当前为空转；崩溃路径不满足"不漏到账" |
| R14 | 可售非负、可售+HELD 不溢出、回滚无半单 | 预占 CAS、`transition` CAS、库存 `deductStock/addStock` CAS、卡密 `FOR UPDATE + claimCard` CAS 均在 | **有效**（正面） |
| R15 | 非法直写拒绝、存量升级验证、SQL 失败阻断 | `continue-on-error=false` 已配置；但无 `ALTER` 迁移 | 启动阻断有效；**存量升级未覆盖**（P2-15） |
| R24 | 万级订单每页查询有界、无全表明细扫描 | 列表已排除 poster 大字段（正面）；但统计投影全表（P2-7）、`OFFSET` 分页无 tiebreaker、过期扫描无索引（P1-3） | **半修** |
| R25 | 大历史不读全部明文、并发重复只一批成功 | 指纹索引查询已加；但恒真判断导致两次全量读（P2-3），存量无指纹漏判 | **半修** |
| R26 | 慢渲染不阻塞付款/释放、重试不重复生成 | `regeneratePoster` 与列表减载已改；**建单路径海报仍在事务内**（P2-4） | **半修** |
| R27 | 窗口内重放一致、窗口外按契约处理 | purge 任务与行锁保护已实现（正面）；头注释过期（P2-8） | 实现有效，文档需更新 |
| R34 | 错误分类正确、敏感输入不回显 | 敏感输入不回显已做；**业务异常仍 HTTP 200**、唯一键冲突落 500 | **未闭环**（P2-14、P2-13） |
| R35 | Long/2^53/溢出精确或明确拒绝 | 本轮未逐项复核金额序列化与前端精度 | 未复核，不视为结论 |

---

## 6. 正面结论（经核对健全的路径）

1. **不超卖、不重复发卡**：预占 `deductStock` 条件更新、`inventory_reservation` 以 `order_id` 为主键、`transition` CAS（`HELD→CONSUMED/RELEASED` 条件更新）、卡密 `lockAvailableCards(... ORDER BY id LIMIT ... FOR UPDATE)` + `claimCard` CAS，构成"行锁 + 条件更新"双重保护；`claimCard` 失败即整单回滚。
2. **锁顺序总体无环**：订单 → SKU → 卡密（支付/履约），商户 → 商品 → SKU → 卡（卡密资产），商品 → 按 `id` 排序 SKU（商品编辑）。未发现跨域反向加锁形成的环形等待。
3. **多实例定时任务幂等**：过期关单/释放以订单行锁 + 状态 CAS 保证重复执行不双加；幂等清理与重放由 `FOR UPDATE` 串行。
4. **幂等键并发正确**：`claim` upsert（`ON DUPLICATE KEY UPDATE request_key=request_key`）+ `lock FOR UPDATE` + 条件 `complete`，同键并发只有一次执行业务，其余重放快照；唯一键冲突回滚重试最多 3 次，不在失败事务内重试。
5. **Redis 限流原子性与失败关闭**：单桶 `INCR+EXPIRE` 为单条 Lua 原子；Redis 异常/空结果统一 `503` 失败关闭，不静默放行（`TradeCostLimiter`、`AuthLoginLimiter`、`OrderQueryLimiter`）。
6. **会话与失效**：Sa-Token 会话存 Redis，`is-share=false` 每次登录独立 Token；`AuthSessionService` 每请求以 DB 角色/状态比对 TokenSession 快照，变更即失效。
7. **商品编辑不覆盖库存**：`ProductEditingMapper` 仅白名单更新元数据/售价，**不写 `stock_quantity`**；SKU 按 `id` 排序行锁；修订摘要（内容哈希）做版本校验。
8. **R09 定向更新已生效**：店铺封禁/公告、费率/电话、收费/通道使用 `COALESCE` 单项更新，不整实体回写。
9. **买家查单隐私**：固定错误文案 + Redis 限流 + 一次性 queryToken 语义；列表脱敏 `cardDetails` 并清空 `queryToken`。
10. **备份导出一致性**：`mysqldump --single-transaction --quick`、空文件删除、30 天轮转，避免半份备份覆盖有效备份。
11. **`cancelPreLock` 置空 `lock_expire_time` 可正确落库**：实体字段使用 `FieldStrategy.ALWAYS`，MyBatis-Plus 不会因 NOT_NULL 策略跳过 NULL 更新（此前疑点已排除）。
12. **代付/普通订单均只允许单 SKU 单条目**：`checkout` 与代付建单都限制条目，不存在"多 SKU 数字订单只发首条卡密"的路径（此前疑点已排除）。

---

## 7. 建议优先处理顺序

**第 1 批（资金与不可自愈，建议先做）**

1. P1-1 支付事件 SUCCESS 提交顺序 + 重试短路条件（与 P1-7 共用一次重构）。
2. P1-2 普通订单统一 `lock_expire_time` 截止校验。
3. P1-3 过期扫描/分页索引 + keyset 分页（含 P2-7 的统计聚合改造）。
4. P1-7 去掉 `REQUIRES_NEW` 嵌套或隔离连接池。

**第 2 批（越权与密钥，安全面）**

5. P1-5 密钥强制配置、seed 清理、轮换方案。
6. P1-6 员工资料字段裁剪。
7. P2-14 错误码与 HTTP 状态映射（含 P2-13 唯一键 409）。
8. P2-17 备份密钥与恢复演练。

**第 3 批（部署与限流正确性）**

9. P1-4 受信代理 IP 归因 + P2-10 key 命名空间 + P2-11 配置化。
10. P2-9 多桶原子化与滑动窗口。

**第 4 批（规模与半修收尾）**

11. P2-3 卡密导入双次全量扫描 + 存量指纹回填。
12. P2-4 海报移出事务；P2-5 卡密列表分页。
13. P2-6 预占组合索引；P2-15 版本化迁移落地（索引类修复的前置）。
14. P2-1、P2-2、P2-8、P2-12、P2-16、P2-18、P2-19 按迭代逐步处理。

**每批的共同前置**：先落地 P2-15 的迁移机制，否则任何索引/列变更在存量环境不会生效；修复需配套回归用例（含非法操作拒绝与合法操作通过），并按 AGENTS.md 由用户手动跑 `mvn -f shop-b2b2c-backend/pom.xml test` 与真实 MySQL/Redis 联调。

---

## 8. 本轮验证状态

- 未修改任何源码、schema、配置或脚本。
- 未运行 `mvn`、编译、测试、构建；未运行任何前端命令。
- 未连接真实 MySQL/Redis，无压测。
- 上述 P1/P2 均为静态阅读结论；标注"理论"或列入"待专项验证"的项目在获得运行验证前不得记为已确认缺陷或已修复。
