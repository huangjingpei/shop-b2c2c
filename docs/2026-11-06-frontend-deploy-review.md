<!--
文件：2026-11-06-frontend-deploy-review.md
用途：对 shop-admin-vue、shop-storefront-vue、shared 规则用例与 deploy 脚本做只读静态审查的结论归档，为后续开发接手提供缺陷、证据与验证入口。
维护注意：本文件只记录静态阅读结论，未执行 npm/node/mvn/部署命令，也未修改任何源码；所有"确定"仅指代码与契约层面可证，"需环境验证"必须由用户在目标环境复核。行号对应本次读取的提交基线（HEAD bcb1dab，另有未提交的 deploy/scripts/backup_mysql.ps1 改动与未跟踪的 deploy/tests/test_backup_windows.ps1），后续改动后须重新定位。R 编号沿用 docs/2026-11-04-remediation-todo.md，不新增业务规则、不把技术细化冒充已确认需求。
-->

# 前端工程、共享规则用例与部署脚本静态审查（2026-11-06）

## 1. 审查范围与方法

- 只读审查：`shop-admin-vue`、`shop-storefront-vue`、`shared/*.test.mjs`、`deploy/**`。
- 后端仅做接口调用点对照（`AuthController`、`ProductController`、`OrderController`、`application.yml`、`schema.sql`），不评判后端业务实现。
- 未执行：`npm ci/build`、`node --test`、`mvn test`、任何部署/备份/恢复命令；未安装依赖；未改动源码。
- 基线：`git status` 显示 `M deploy/scripts/backup_mysql.ps1`、`M docs/README.md`、`?? deploy/tests/test_backup_windows.ps1`、`?? docs/2026-11-06-backend-*.md`、`?? merchat.md`；HEAD 为 `bcb1dab`。
- 结论口径：
  - **确定问题**：由当前代码/配置/接口契约即可证明，不依赖运行环境。
  - **需环境验证**：静态链条成立，但最终结果取决于服务器 `.env`、PowerShell 版本、Nginx 编译模块、前置代理、真实数据库等。

## 2. 结论摘要

| 级别 | 数量 | 说明 |
| --- | --- | --- |
| P0 | 4 | 首屏崩溃、功能不可用、生产默认凭据、阿里云数据源配置缺项 |
| P1 | 14 | 敏感数据、鉴权/限流失效、部署回滚边界、备份可信度、R20 同类回退 |
| P2 | 16 | 表单/体验/文案/死配置/弱断言 |

最需要优先处理的是 **P0-01（商品详情页永久加载）**、**P0-02（代客推单不可用）**、**P0-03/P0-04（默认凭据与阿里云数据源）**。

---

## 3. P0 级缺陷

### P0-01 商品详情页首屏必然抛 ReferenceError，页面永久"加载中"

- **证据**
  - [ProductDetail.vue](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L446-L474)：`loadProduct()` 第 454 行执行 `peerPayResult.value = null`。
  - 同文件第 516 行才声明 `const peerPayResult = ref(null)`；[第 470-474 行](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L470-L474)的 `watch(..., { immediate: true })` 在 `setup()` 阶段同步调用 `loadProduct()`。
  - 第 448 行已把 `loading.value = true`，异常发生在 `try`（455 行）之前，`finally`（465-467 行）不会执行。
- **触发条件**：任意用户打开 `/product/:id`（包括首次直接访问、从列表点击）。不需要特殊数据。
- **影响**：`Cannot access 'peerPayResult' before initialization`，商品详情页永远停留在加载态，下单与代付入口完全不可用。属于商城核心转化路径阻断。
- **建议方向（只写方案）**：把全部 `ref/const` 声明移到 `loadProduct` 定义与 `watch` 调用之前；或将 `watch` 从 `immediate: true` 改为 `onMounted` 后再触发；并在 `loadProduct` 内把重置逻辑放进 `try` 或独立 `try/catch`，保证 `loading` 一定复位。修复须补一条组件级回归用例（挂载即断言不再抛错且 loading 归零）。
- **类型**：确定问题。

### P0-02 商家"代客推单"商品下拉必然 TypeError，功能不可用

- **证据**
  - 前端期望 `{ product, skus }` 结构：[Orders.vue](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L519-L524) 读取 `myProducts.value[0].product.id`；[第 531-535 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L530-L540) 使用 `p.product.id`、`found.skus`。
  - 模板同样绑定 `p.product.id / p.product.title`：[Orders.vue 第 219-225 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L219-L225)。
  - 后端实际返回扁平列表：[ProductController.java 第 101-106 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L101-L106) `R<List<Product>>`，没有 `product` 包装，也没有 `skus` 字段。
- **触发条件**：商家在订单页点击"★ 代客推单"。
- **影响**：打开弹窗即抛 `Cannot read properties of undefined (reading 'product')`，代客推单整条链路不可用；同时弹窗内 SKU 下拉永远为空。
- **建议方向**：统一契约。推荐后端补一个带 `product + skus` 的商家商品 DTO（与前端已写好的结构一致），或前端改为消费扁平 `Product` 并单独拉取 SKU 列表；无论选哪种，都要在 `shared/` 增加契约用例（用真实响应样例断言字段存在），避免再次错位。
- **类型**：确定问题。

### P0-03 后台登录页预填默认超管/商家口令，且 schema 基线在每次启动写入同口令演示账号

- **证据**
  - [Login.vue 第 30-34 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/login/Login.vue#L30-L34) 一键填入 `superadmin/admin123`、`seller01/admin123`；[第 52-55 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/login/Login.vue#L52-L55) 表单默认值就是 `superadmin / admin123`。
  - [schema.sql 第 279-281 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L279-L281) 写入 `superadmin`（BCrypt 对应 `admin123`）；[第 287-289 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L287-L289)、[第 299-301 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L299-L301) 同样写入 `seller01/seller02`；均为 `INSERT IGNORE`，不会因重复启动报错。
  - [application.yml 第 30-36 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L30-L36) `sql.init.mode: always`，生产启动也会执行该脚本；`--migrate` 时 [04-deploy.sh 第 71-80 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L71-L80) 也会直接导入同一文件。
  - 文件头注释自身已写明"生产不得依赖演示账号"（[schema.sql 第 3 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L1-L3)），与实际行为矛盾。
- **触发条件**：生产部署完成、管理端 `/console/` 可被访问。
- **影响**：攻击者无需猜口令即可登录超管与商家后台（预填还把可用凭据直接显示给任何打开登录页的人）；所有租户数据、卡密、订单、商户 PII 暴露。与 R01 台账"待处理"一致。
- **建议方向**：前端删除预填与"演示视角"按钮；后端把演示种子与生产初始化分离（profile 或显式 `--seed-demo` 开关），生产只允许首次随机口令/环境变量注入，并在启动时强制校验"非默认口令"；`sql.init.mode` 生产改为 `never` 或独立迁移入口。凭据轮换必须由环境负责人执行，不在本轮操作。
- **类型**：确定问题（生产是否已暴露取决于部署环境是否沿用默认种子，须核对线上 `sys_user`）。

### P0-04 阿里云 `.env` 缺 `SPRING_DATASOURCE_USERNAME/PASSWORD`，后端回退到 `root` + 硬编码口令

- **证据**
  - [application.yml 第 19-20 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L17-L21)：`username: ${SPRING_DATASOURCE_USERNAME:root}`、`password: ${SPRING_DATASOURCE_PASSWORD:89dfu*#$ewr87l}`。
  - 阿里云 [02-init-infra.sh 第 34-55 行](file:///E:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh#L34-L55) 生成的 `.env` 只有 `DB_USER`、`DB_PASS`、`SPRING_DATASOURCE_URL`，**没有** `SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`；而第 82-90 行用随机 `DB_PASSWORD` 创建并 ALTER 的是 `DB_APP_USER`。
  - 腾讯同类脚本 [02-init-infra.sh 第 49-51 行](file:///E:/shop-b2b2c/deploy/tencent/server/02-init-infra.sh#L49-L51) 已正确写入 `SPRING_DATASOURCE_USERNAME/PASSWORD`，说明这是阿里云侧遗漏而非设计。
  - 阿里云 [04-deploy.sh 第 202 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L194-L217) 的 systemd 使用 `EnvironmentFile=.env` 后启动，没有其它注入点。
- **触发条件**：全新服务器按文档执行 `deploy.sh --infra`/`full` 后首次启动后端。
- **影响**：后端以 `root` + 仓库内默认口令连接数据库，通常直接连接失败导致部署阻断；若恰好口令与既有 root 一致，则后端以 root 全权限运行，违反最小权限。随机生成的 `DB_PASSWORD` 实际未被应用使用。
- **建议方向**：在阿里云 `02-init-infra.sh` 的 `.env` 模板补齐 `SPRING_DATASOURCE_USERNAME=${DB_APP_USER}`、`SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}`（与腾讯对齐）；删除 `application.yml` 中的明文口令默认值，改为无默认并启动失败；`.env` 已存在的服务器须人工核对是否缺项。另加一条部署脚本契约用例断言 `.env` 必含这些键。
- **类型**：确定配置缺陷；**是否已在线上阻断需核对服务器现网 `.env`**（脚本只在 `.env` 不存在时生成，历史环境可能被手工补齐）。

---

## 4. P1 级缺陷

### 4.1 凭据与权限

#### P1-01 部署脚本把真实 Redis 口令、域名、服务器信息写入受版本控制文件

- **证据**：[aliyun/deploy.sh 第 38 行](file:///E:/shop-b2b2c/deploy/aliyun/deploy.sh#L38) `REDIS_PASS="${REDIS_PASS:-3wTYvNMHNn89pZ3XmTZcQluG}"`；[aliyun/env.sh 第 38 行](file:///E:/shop-b2b2c/deploy/aliyun/env.sh#L38) 同口令；[aliyun/02-init-infra.sh 第 54 行](file:///E:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh#L51-L55) 作为默认值再次写入 `.env`；[env.sh 第 18-19 行](file:///E:/shop-b2b2c/deploy/aliyun/env.sh#L18-L19) 真实域名与公网地址。
- **触发条件**：仓库被任何有读权限的人获取（含外包、CI、误推送）。
- **影响**：Redis 口令可被用于横向攻击；公开域名+后台路径可被定向扫描。属于长期不可逆泄露（改脚本不等于轮换口令）。
- **建议方向**：从所有受控文件删除真实口令，改占位符并在部署前强制校验（缺失即 die）；由环境负责人在服务器/密钥管理系统中轮换 Redis 口令；`env.sh` 是否应入库改为 `.example`。注意本文件头已承诺"注释不复制密钥"，此处属实现与约定冲突。
- **类型**：确定问题（口令真实性以当前文件内容为准）。

#### P1-02 阿里云 Nginx 登录限流路径与实际接口不匹配，爆破防护形同虚设

- **证据**：[nginx-gmall.conf 第 100 行](file:///E:/shop-b2b2c/deploy/aliyun/server/nginx-gmall.conf#L99-L114) 限流 `location = /api/admin/auth/login`；后端真实登录入口是 [AuthController.java 第 26、52 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java#L26-L52) `/api/v1/auth/login`。腾讯配置 [nginx-shop.conf 第 92 行](file:///E:/shop-b2b2c/deploy/tencent/server/nginx-shop.conf#L91-L106) 已使用正确路径。
- **触发条件**：对阿里云站点发起口令爆破。
- **影响**：限流区 `gmall_admin_login_limit` 永不命中，任何路径 `/api/v1/auth/login` 请求不受 5r/m 限制；R06"不同真实客户端桶独立"在阿里云侧无法成立。
- **建议方向**：改为 `location = /api/v1/auth/login`；若需同时保护其它入口，用 `limit_req` 挂在 `/api/` 前缀并单独放行读接口；补一条 Nginx 文本契约用例断言限流 location 与后端 `@PostMapping` 路径一致（`deploy/tests/test_nginx_gateway.py` 已有基础设施）。
- **类型**：确定问题。

#### P1-03 后端进程以 root 运行，部署脚本强制 root 执行

- **证据**：[04-deploy.sh 第 41 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L41) 要求 `EUID -eq 0`；[第 202 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L200-L213) systemd `User=root`；腾讯同构脚本同模式。
- **触发条件**：任何生产部署。
- **影响**：应用漏洞可直接获得系统 root；`/opt/gmall`、上传目录、日志均可被应用进程改写。与 R30"非 root 正常运行"不符。
- **建议方向**：创建专用系统用户，systemd 指定 `User=shop`、`NoNewPrivileges=true`、`ProtectSystem=strict`（按上传/日志目录给 `ReadWritePaths`）；部署脚本仍可用 sudo 安装文件，但服务不以 root 启动。需在真实服务器验证权限边界。
- **类型**：确定配置问题；加固后的可运行性需环境验证。

#### P1-04 管理端"卡密库"页全量明文展示卡密正文，且接口一次返回全店卡密（含已售）

- **证据**：[Cards.vue 第 21-24 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Cards.vue#L21-L24) 直接渲染 `row.cardContent`；第 4 行文件说明却写"查询不展示卡密正文"，二者矛盾。[第 166-169 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Cards.vue#L166-L169) 调 `/merchant/cards` 无 SKU/分页参数。后端 [ProductController.java 第 252-259 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java#L226-L261) 未传 `skuId` 时 `findByMerchantId(merchantId)` 取全店并逐条解密后返回。
- **触发条件**：店长/超管打开卡密库页，或该账号会话被盗。
- **影响**：一屏即可批量复制全店未售+已售卡密；浏览器内存、截图、前端日志都可能留存明文；与 R04/R05"最小字段、按需授权"的治理方向冲突。
- **建议方向**：列表接口改为返回脱敏值+指纹（仅 `skuId` 授权时返回单 SKU 明文且限制数量），前端默认打码、按单条显式"查看"二次确认；补分页与"仅未售"过滤；同步更新该页文件说明。涉及后端 DTO，须前后端同批交付。
- **类型**：确定问题。

### 4.2 管理端功能与数据

#### P1-05 商家订单页无分页/筛选，且发货与卡密详情缺少并发保护

- **证据**：[Orders.vue 第 426-434 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L426-L434) `request.get('/merchant/orders')` 无任何分页参数；[第 442-451 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L442-L451) `handleDeliver` 无 loading、无 catch、无重复提交 guard；[第 486-502 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L486-L502) `viewOrderCards` 无请求版本号。
- **触发条件**：订单量大时打开页面；网络慢时双击"确认发货"；快速连续点击不同订单"查看卡密"。
- **影响**：只能看到接口默认返回的最近一批订单（后端上限需在接口处确认），历史订单无法检索；重复点击会发出多次发货请求（后端若未幂等则产生重复物流登记或报错）；迟到响应可能把上一单卡密显示在当前弹窗（敏感数据错配）。
- **建议方向**：接入后端游标分页（复用 R24 的 `beforeId` 模式）并加状态/订单号筛选；发货按钮加 `delivering` 状态与"请求进行中直接 return"；卡密详情用自增请求序号或 `AbortController` 丢弃旧响应，关闭弹窗即失效（可复用 Store.vue 的 `active` 模式）。
- **类型**：确定问题（"仅最近 N 条"的 N 需对照后端常量确认）。

#### P1-06 管理端多处提交缺少幂等键与异常兜底，失败时无提示或状态卡死

- **证据**
  - 商品发布 [Products.vue 第 305-319 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Products.vue#L305-L319)：无幂等键、无 `catch`，仅 `finally` 复位；成功后不重置表单（[269-283 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Products.vue#L269-L283) 固定单 SKU 与 unsplash 占位主图）。
  - 商户开通 [Merchants.vue 第 263-277 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/admin/Merchants.vue#L263-L277)：整表 `form.value` 直传，无幂等；[258-261 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/admin/Merchants.vue#L258-L261) 只重置 `storeCode`，身份证/银行卡/手机号仍留在弹窗。
  - 费率调整 [Merchants.vue 第 286-296 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/admin/Merchants.vue#L286-L296)：`.then` 无 `catch`。
- **触发条件**：网络抖动后重试；开通多个商户；费率请求失败。
- **影响**：重复创建商品/商户（后端若无唯一约束则产生脏数据，有约束则报错但前端仍显示 loading 旧态）；PII 在弹窗中被下一位操作者看到；失败静默让操作者误以为成功。
- **建议方向**：复用现有 [idempotency.mjs](file:///E:/shop-b2b2c/shop-admin-vue/src/utils/idempotency.mjs) 为发布商品/开通商户加幂等键（成功才 `complete`）；所有 `await request` 外层补 `try/catch` 提示后端 message；关闭弹窗时重置整份表单（含 PII）。服务端唯一约束是最终防线，需另行确认。
- **类型**：确定问题。

#### P1-07 商户状态与审核展示语义错误，可能误导运营决策

- **证据**：[Merchants.vue 第 82 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/admin/Merchants.vue#L82) `row.status === 1 ? '正常' : '已封禁'`，而 schema 注释定义 `2-打烊、3-已封禁`（[schema.sql 第 25 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L25)）；[Profile.vue 第 55 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Profile.vue#L55) 非 `PASSED` 一律显示"审核中"（含 `REJECTED`）。
- **触发条件**：打烊店铺、审核被拒商户。
- **影响**：运营误把打烊当封禁（或反之）而重复封禁/解封；商户看到"审核中"却实际被拒，无法按提示整改。
- **建议方向**：抽共享状态映射函数（前端 `shared/` 或页面 utils），对 `0/1/2/3` 与 `PENDING/PASSED/REJECTED` 全覆盖并补单测；未知值显示"未知状态"而非默认正常。
- **类型**：确定问题。

#### P1-08 "查看代付海报/卡密详情"存在跨订单迟到响应串数据风险（R20 同类回退点）

- **证据**：[Orders.vue 第 486-502 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L486-L502) 仅用 `cardDetailOrder` 直接覆盖，无校验响应属于当前订单；[PeerPayCashier.vue 第 441-448 行](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/PeerPayCashier.vue#L441-L460) 海报一旦缓存就不再按 token 重新校验；[第 506-511 行](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/PeerPayCashier.vue#L506-L511) 切换 token 只重载详情/广告，不清 `posterBase64`。
- **触发条件**：同一路由参数快速切换（订单弹窗连续点、代付链接切换）；网络慢。
- **影响**：A 单的卡密详情显示在 B 单；代付收银台继续展示上一账单的二维码海报，付款人可能扫错账单。R20 已标"已修复"，但这两处未采用 Store.vue 的失效模式。
- **建议方向**：所有按资源 ID 拉取的详情统一采用"请求序号/AbortController + 卸载与 key 变化失效"，token 变化时清空海报缓存；补组件级回归（先慢后快、错误、切换）。
- **类型**：确定问题（截图/实际错配需浏览器复现，但代码路径成立）。

### 4.3 商城端

#### P1-09 首页搜索完全无效，且分类切换无旧请求保护、空数据会渲染崩溃

- **证据**：[Navbar.vue 第 125-132 行](file:///E:/shop-b2b2c/shop-storefront-vue/src/components/Navbar.vue#L125-L132) 搜索 `router.push({ path: '/', query: { q: ... } })`；[Home.vue 第 282-307 行](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/Home.vue#L282-L307) 只读 `selectedCat`，**从不读 `route.query.q/searchType`**；且 `products.value = pRes.data.data` 不校验 `code`，失败时保持 `[]`、成功但 `data` 为 `null` 时模板 `products.length` 会报错。
- **触发条件**：用户在导航栏输入关键词搜索；快速切换类目；公开接口返回业务错误。
- **影响**：搜索按钮没有任何效果（用户以为搜到了全部商品）；快速切类目可能显示上一次类目结果；业务错误被当作"空列表"，无错误态。
- **建议方向**：Home 读取并监听 `route.query`，把 `q/searchType` 作为请求参数（后端若无搜索接口须先补契约，不能前端假过滤）；为 `loadData` 加请求序号；校验 `res.data.code === 200`，失败显示错误/重试；`products` 初始化为 `[]` 且模板判空。
- **类型**：确定问题（搜索后端能力需接口对照）。

#### P1-10 商品详情错误态与剪贴板在非安全上下文下失效

- **证据**：[ProductDetail.vue 第 463-465 行](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L455-L467) 只 `console.error`，不设置错误提示；[第 506-510 行](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L506-L511) `navigator.clipboard.writeText` 无 `isSecureContext` 判断、无 `await`、无 `catch`；[第 424 行](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L424) 默认联系人 `13912345678`；[第 194-200 行](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L194-L201) 依据 `successResult.cards` 文案"已自动秒发虚拟卡密"，而后端下单响应并不返回卡密正文。
- **触发条件**：商品已下架/接口 500；HTTP 访问或浏览器未授权剪贴板；用户未修改默认手机号就下单。
- **影响**：加载失败时用户只看到空白页；点击"复制卡密"抛异常但仍提示已复制；默认手机号会把真实买家的联系信息写成假号，售后无法触达；"已秒发卡密"与真实交付状态不符（R22 类误导）。
- **建议方向**：错误态展示+重试；剪贴板封装成 `shared/` 工具（安全上下文降级到 `document.execCommand`，失败提示手动复制）；移除默认手机号改空值必填；按后端实际返回字段调整成功文案（无卡密时只显示"订单已创建，待发货/待发卡"）。
- **类型**：确定问题。

#### P1-11 代付收银台海报跨账单残留（见 P1-08 证据）——单列为上线阻断项

- **证据**：同上 [PeerPayCashier.vue 第 441-460、506-511 行](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/PeerPayCashier.vue#L441-L460)。
- **影响**：代付是资金场景，扫错海报二维码属于资金错付风险。**建议**：token 变化时 `posterBase64.value = ''` 并重新拉取；海报请求也加请求序号；打开海报弹窗时校验 `detail.token === route.params.token`。
- **类型**：确定问题（实际资金损失取决于海报二维码内容是否含账单参数，需环境核对）。

### 4.4 部署、网关与备份

#### P1-12 首次部署 Nginx 校验顺序错误，失败回滚不会撤销已启用的坏配置

- **证据**：[04-deploy.sh 第 238-249 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L238-L251) `safe_nginx_switch` 先 `cp` 到 `sites-available` 并 `ln -sfn` 到 `sites-enabled`，之后才 `nginx -t`；失败即 `die`。[第 126-163 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L126-L163) 回滚仅在 `HAD_NGINX=yes` 时恢复配置；首次部署没有旧配置，坏文件仍留在 `sites-enabled`。
- **触发条件**：首次部署时模板/证书路径写错；或任何一次 `nginx -t` 失败。
- **影响**：虽然当次 `reload` 未执行，但下次 `systemctl reload/restart nginx` 会因坏配置失败，可能让网关整体不可用；且 `systemctl reload nginx || systemctl restart nginx`（[249 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L249)）在 reload 失败时会尝试 restart，坏配置下 restart 会直接失败并停服。
- **建议方向**：先在临时路径渲染并 `nginx -t -c` 校验（或把候选配置放到独立 `conf.d` 片段做 `nginx -t`），通过后再原子替换并 `reload`；失败分支恢复旧软链或删除新软链，并显式 `nginx -t` 复核后才 `die`。补一条夹具用例覆盖"首次部署 + 语法错误"。
- **类型**：确定问题（是否已造成实际停服需环境核对）。

#### P1-13 `--migrate` 前无数据库备份，回滚也不恢复数据库

- **证据**：[04-deploy.sh 第 68-83 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L65-L83) 迁移发生在"备份当前线上状态"（第 85-123 行）之前；[第 126-173 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L126-L173) 回滚只恢复 jar/前端/Nginx，无任何 SQL 恢复；schema 本身含 `INSERT IGNORE` 演示种子（见 P0-03）。
- **触发条件**：执行 `--migrate` 或空表首次部署后应用启动失败。
- **影响**：结构变更或种子写入不可逆；失败回滚后代码回到旧版但数据库已是新结构/含演示账号，可能直接不可用或暴露默认口令。R29"失败无整套恢复"在 DB 维度仍未闭环。
- **建议方向**：迁移前强制 `mysqldump`（含 routines/triggers）到带时间戳目录并在失败时提示恢复命令；迁移与应用启动合并为"要么全成功要么回滚 DB"的边界；把演示种子从 `schema.sql` 拆到显式 seed 脚本。
- **类型**：确定问题。

#### P1-14 阿里云未注入前台地址，代付短链/海报指向 `localhost:3002`

- **证据**：[application.yml 第 90 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L90) `storefront-url: ${APP_FRONTEND_STOREFRONT_URL:http://localhost:3002}`；阿里云 [02-init-infra.sh 第 34-55 行](file:///E:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh#L34-L55) 未写该键；腾讯 [02-init-infra.sh 第 63-64 行](file:///E:/shop-b2b2c/deploy/tencent/server/02-init-infra.sh#L63-L64) 已写。
- **触发条件**：阿里云上创建代付账单/生成海报/分享链接。
- **影响**：生成的链接与海报二维码指向 `http://localhost:3002`，付款人无法访问；代付核心链路在生产不可用（P1，若线上确认为 localhost 则升 P0）。
- **建议方向**：阿里云 `.env` 补 `APP_FRONTEND_STOREFRONT_URL=https://${GMALL_SERVER_NAME}/m/`（当前商城挂在 `/m/`，与 03-build 的 base 一致）；测试断言该键存在且带 `/m` 基路径。
- **类型**：确定配置缺陷；线上实际值需核对服务器 `.env`。

#### P1-15 证书申请失败被 `tail` 吞掉，HTTP 降级被当作正常完成

- **证据**：[04-deploy.sh 第 263-274 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L263-L274) `certbot ... 2>&1 | tail -20` 位于 `if` 条件；管道退出码取 `tail`（恒 0），else 分支不可达。随后 [277-285 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L277-L285) 在无证书时启用 HTTP 引导配置并继续部署。
- **触发条件**：DNS 未解析、certbot 失败、证书目录缺失。
- **影响**：生产以明文 HTTP 提供管理端登录与订单（口令、卡密、PII 可被中间人截获），且部署脚本报告成功，运维不会察觉。R30"证书失败 HTTP 降级未区分生产"仍成立；同时 `CERTBOT_EMAIL` 默认拼写错误（见 P2）。
- **建议方向**：去掉管道或用 `PIPESTATUS[0]` 判断；生产 profile 下无证书应 `die` 而非降级（或显式 `--allow-http` 才允许）；将"HTTPS 可用"纳入部署烟测（curl 443）。
- **类型**：确定代码逻辑；生产是否已长期 HTTP 运行需环境核对。

#### P1-16 Windows 备份把 stderr 写入 SQL 且带 BOM，恢复可能失败或"假成功"

- **证据**（含未提交改动，当前工作区版本）
  - [backup_mysql.ps1 第 94 行](file:///E:/shop-b2b2c/deploy/scripts/backup_mysql.ps1#L94) `& mysqldump @dumpArgs 2>&1 | Out-File -Encoding utf8`；[第 103 行](file:///E:/shop-b2b2c/deploy/scripts/backup_mysql.ps1#L98-L110) Docker 分支同样 `2>&1 | Out-File -Encoding utf8`。
  - 文件首行在第 1 行前新增 UTF-8 BOM（见 `git diff` `-#` → `+﻿#`），PowerShell 5.1 的 `Out-File -Encoding utf8` 也会写 BOM。
  - 密码以 `--password=$DbPass` 置于命令行（[86 行](file:///E:/shop-b2b2c/deploy/scripts/backup_mysql.ps1#L82-L93)）；压缩用 `ReadAllBytes` 全量入内存（[118-123 行](file:///E:/shop-b2b2c/deploy/scripts/backup_mysql.ps1#L116-L126)）。
  - 未加 `--no-tablespaces`；MySQL 8 在缺少 PROCESS 权限时 mysqldump 会失败。
- **触发条件**：Windows 服务器用 PowerShell 5.1 执行备份并以 `mysql < file.sql` 恢复；mysqldump 输出任何 warning（如"Using a password on the command line..."）。
- **影响**：warning 文本混入 SQL 文件中间，恢复在第 N 行报语法错误；BOM 在文件头导致首条语句解析失败；两者都可能让备份"存在且非空"但不可恢复（R31 核心）。大库时内存峰值可能 OOM；口令出现在进程列表/日志。
- **建议方向**：stderr 与 stdout 分离（`-RedirectStandardError` 或写临时 err 文件并在非空时告警）；改用无 BOM 的 `[IO.File]::WriteAllLines`/`Out-File -Encoding utf8NoBOM`（PS5.1 需用 `[Text.UTF8Encoding]::new($false)`）；加 `--no-tablespaces`；密码改用 `MYSQL_PWD` 或 `--defaults-extra-file` 临时权限文件；压缩改流式。
- **类型**：机制确定；最终是否失败**需按用户指定 PS 5.1/7 矩阵在隔离环境验证**（本项即 R31 待专项验证）。

#### P1-17 回滚入口参数未加引号且不传导环境变量，可注入或回滚到错误域名/路径

- **证据**：[aliyun/deploy.sh 第 164-165 行](file:///E:/shop-b2b2c/deploy/aliyun/deploy.sh#L164-L166) `run_remote "05-rollback.sh ${ARG_VALUE:-}"`，`ARG_VALUE` 直接取自 CLI（[第 53-60 行](file:///E:/shop-b2b2c/deploy/aliyun/deploy.sh#L53-L60)），未加引号；`run_remote`（[112-124 行](file:///E:/shop-b2b2c/deploy/aliyun/deploy.sh#L112-L124)）拼接成 `ssh host "... bash script"`；回滚分支未传 `COMMON_ENVS`（对比 deploy 分支 [153-157 行](file:///E:/shop-b2b2c/deploy/aliyun/deploy.sh#L153-L157)）。
- **触发条件**：操作者误输入含空格/分号的版本号；或被诱导执行恶意参数。
- **影响**：`ARG_VALUE` 可拼出额外 shell 命令，以 root 在远端执行（命令注入）；回滚时 `GMALL_SERVER_NAME`/`ADMIN_PORTAL_PATH` 缺失，04-deploy 的默认值（`mall.graddu.com`、`/console`）会重写网关，定制部署回滚后域名/后台路径错误。
- **建议方向**：参数用白名单校验（只允许版本目录名正则）并 `printf %q`/单引号转义；回滚同样传 `COMMON_ENVS`；`run_remote` 改为 `ssh host -- env KEY=... bash -s < script` 或使用 `shlex.quote` 等价机制。腾讯同构脚本同样处理。
- **类型**：确定问题（实际被利用需威胁模型，但代码路径成立）。

---

## 5. P2 级问题

| 编号 | 证据 | 触发/影响 | 建议方向 |
| --- | --- | --- | --- |
| P2-01 | [Merchants.vue 第 137、226 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/admin/Merchants.vue#L137) 初始密码默认 `admin123` | 新商户全部同口令，且明文显示在表单 | 改为随机生成/必填强口令，提交后只显示一次 |
| P2-02 | [Orders.vue 第 438 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L436-L440) 发货单号预填随机 `SF...` | 操作者忘改即登记假运单，买家查不到物流 | 置空并强校验格式/长度 |
| P2-03 | [Merchants.vue 第 279-284 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/admin/Merchants.vue#L279-L284) `toggleStatus` 无确认、无 loading | 双击把店铺"打烊→开业→打烊"来回翻转 | 二次确认 + 行级 loading + 成功后按服务端状态刷新 |
| P2-04 | [Profile.vue 第 112-120 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Profile.vue#L112-L120) 整份 `store` PUT | 把身份证/银行卡等 PII 回传，后端白名单外字段被忽略但存在日志/审计面风险 | 只提交可编辑字段白名单 |
| P2-05 | [Profile.vue 第 97-100 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Profile.vue#L97-L100) 长度<10 直接返回原值 | 短卡号/异常值明文展示 | 统一脱敏函数，异常值显示"格式异常" |
| P2-06 | [Products.vue 第 269-283 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Products.vue#L269-L283) 默认 unsplash 外链主图、固定单 SKU | 生产商品引用第三方图床，离线/被墙时裂图；多规格无法发布 | 改本地占位图，补 SKU 增删行 |
| P2-07 | [serviceFee.mjs 第 27-35 行](file:///E:/shop-b2b2c/shop-admin-vue/src/utils/serviceFee.mjs#L27-L36) 未校验 `monthlyFee` 数值 | 传入 `NaN/负数字符串` 时原样提交，后端才报错 | 增加有限数值/非负校验并补 `serviceFee.test.mjs` 反例 |
| P2-08 | [serviceFee.test.mjs](file:///E:/shop-b2b2c/shared/serviceFee.test.mjs#L10-L42) 只覆盖合法编码/日期 | 无 `monthlyFee` 负数、`NaN`、超大值用例 | 补非法输入断言 |
| P2-09 | [application.yml 第 56-59 行](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L56-L59) `timeout: 30天`、`active-timeout: -1` | 令牌长期有效且无空闲过期；配合 [request.js 第 17 行](file:///E:/shop-b2b2c/shop-admin-vue/src/api/request.js#L17) 的 localStorage 存储，XSS/共享电脑风险放大（R08 证据缺口） | 缩短为小时级并设活跃超时；令牌改 httpOnly Cookie 或内存+刷新策略（需架构决策） |
| P2-10 | [authSession.mjs 第 74-77 行](file:///E:/shop-b2b2c/shop-admin-vue/src/utils/authSession.mjs#L72-L81) `ownsSession` 对 `null===null` 成立 | 登录接口返回 401 时清会话并 `window.location.assign('/login')`，整页刷新、输入丢失 | 登录请求标记 `authAttempt`，401 只提示不重定向 |
| P2-11 | [router/index.js 第 76-80 行](file:///E:/shop-b2b2c/shop-admin-vue/src/router/index.js#L76-L88) `/merchant/cards` 仅 `requiresMerchant` | 员工可进入页面但接口 403，页面空+报错 | 路由 meta 加 `role: ['SUPERADMIN','MERCHANT_ADMIN']`，导航同源过滤 |
| P2-12 | [Login.vue 第 30-34 行](file:///E:/shop-b2b2c/shop-admin-vue/src/views/login/Login.vue#L30-L34) 演示视角按钮直接给出可用凭据 | 与 P0-03 同源，删除即可 | 删除 |
| P2-13 | [QuickQuery.vue 第 165-168 行](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/QuickQuery.vue#L165-L168) `copyText` 无 await/catch | HTTP 环境点击复制抛错却提示成功 | 复用统一剪贴板工具 |
| P2-14 | [nginx-gmall.conf 第 88-92、152-159 行](file:///E:/shop-b2b2c/deploy/aliyun/server/nginx-gmall.conf#L87-L92) `/m` 静态目录 + 根路径 302 到 `/m/` | 与 03-build 的 `/m/` base 一致，但与"根路径商城"文档描述冲突，易被后续改动破坏 | 明确阿里云/腾讯两种挂载约定，写入部署 README 与测试断言 |
| P2-15 | [tencent/nginx-shop.conf 第 16-20 行](file:///E:/shop-b2b2c/deploy/tencent/server/nginx-shop.conf#L16-L20) 定义 `$is_malicious_bot` 但全文未使用 | 死配置，给人"已防爬"错觉 | 要么挂到 `/api/`，要么删除并在文档说明 |
| P2-16 | [aliyun/02-init-infra.sh 第 49 行](file:///E:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh#L49) `useUNICODE=true`；[第 55 行](file:///E:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh#L55) `SPRING_DATA_REDIS_DATABASE` 与后端 `REDIS_DB` 不匹配 | 参数拼写无效；Redis 库号实际回退 0 而非 1 | 统一为 `useUnicode`、写 `REDIS_DB`（腾讯已正确，可对齐） |
| P2-17 | [aliyun/env.sh 第 24 行](file:///E:/shop-b2b2c/deploy/aliyun/env.sh#L24) `hjp9221@63.com`；[deploy.sh 第 29 行](file:///E:/shop-b2b2c/deploy/aliyun/deploy.sh#L29) 真实域名默认值 | 证书到期通知收不到 | 修正邮箱并以环境变量必填 |
| P2-18 | [04-deploy.sh 第 294-316 行](file:///E:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh#L294-L316) 健康检查只 curl 后端 | 前端 404/白屏仍判成功；`--no-healthcheck` 可跳过 | 增补对 `/console/index.html` 与 `/m/` 的 200 校验 |
| P2-19 | [test_backup_windows.ps1 第 77-91 行](file:///E:/shop-b2b2c/deploy/tests/test_backup_windows.ps1#L76-L91) 只断言 gz 存在且非空 | mock 输出满足即通过，捕获不到 BOM/stderr 混入（P1-16） | 解压后断言首字节非 BOM、SQL 中不含 `Warning`/`mysqldump:`；补非零退出+部分输出用例 |
| P2-20 | [test_deploy_lifecycle.py 第 359-361 行](file:///E:/shop-b2b2c/deploy/tests/test_deploy_lifecycle.py#L350-L361) 用 `result.stderr` 过滤 `log` 行，断言恒为空；[第 363-377 行](file:///E:/shop-b2b2c/deploy/tests/test_deploy_lifecycle.py#L363-L377) 在 Python 里复刻过滤逻辑而非执行真实脚本；[第 451-458 行](file:///E:/shop-b2b2c/deploy/tests/test_deploy_lifecycle.py#L451-L458) 名为"端口默认 8080"却无端口断言 | 三条用例均可在错误实现下通过（R33"持续回归不足"） | 修断言源数据、直接执行 `05-rollback.sh list`、断言渲染后的 Nginx/systemd 文本含 8080 |
| P2-21 | [nginx-gmall.conf](file:///E:/shop-b2b2c/deploy/aliyun/server/nginx-gmall.conf) 无 CSP/X-Frame-Options/Referrer-Policy；腾讯仅 2 条 | 后台页面可被点击劫持/外链泄露 Referer（令牌在 localStorage 时 XSS 危害更大） | 统一安全头模板，后台加 `frame-ancestors 'none'` |

> 说明：P2 编号在本文内唯一，便于后续在台账中引用；不与 11-04 的 R 编号混用。

---

## 6. R 编号现状核对（不重复旧结论）

| R | 台账状态 | 本次静态核对结果 | 关键证据 |
| --- | --- | --- | --- |
| R01 | 待处理 | **仍存在**。默认超管/商家凭据写入 seed，登录页预填，且生产启动默认执行种子；`application.yml` 还有硬编码数据库口令回退 | [Login.vue 52-55](file:///E:/shop-b2b2c/shop-admin-vue/src/views/login/Login.vue#L52-L55)、[schema.sql 279-301](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/db/schema.sql#L279-L301)、[application.yml 20](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L19-L21) |
| R06 | 待专项验证 | **部分失效（新证据）**。阿里云限流 location 与真实登录路径不一致；两套模板均无 `set_real_ip_from`，若前置 CDN/LB 则 `$binary_remote_addr` 会退化为代理 IP，桶合并 | [nginx-gmall.conf 100](file:///E:/shop-b2b2c/deploy/aliyun/server/nginx-gmall.conf#L99-L114)、[AuthController.java 26/52](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java#L26-L52) |
| R08 | 待专项验证 | **无完整证据**。令牌 30 天、无活跃超时、localStorage 明文；未发现后端记录口令的日志（grep 无匹配）；仓库内无依赖审计/漏洞扫描产物 | [application.yml 56-59](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/resources/application.yml#L56-L59)、[request.js 17](file:///E:/shop-b2b2c/shop-admin-vue/src/api/request.js#L15-L22) |
| R14 | 待专项验证 | **静态无法确认**。库存守恒/锁序需真实 MySQL 并发矩阵；H2 与本文结论均不能替代 | 无（超出本次只读范围） |
| R31 | 待专项验证 | **未解决（新证据）**。stderr 混入 + BOM + 无 `--no-tablespaces`；配套 Windows 用例只断言文件大小 | [backup_mysql.ps1 94/103](file:///E:/shop-b2b2c/deploy/scripts/backup_mysql.ps1#L82-L110)、[test_backup_windows.ps1 77-91](file:///E:/shop-b2b2c/deploy/tests/test_backup_windows.ps1#L76-L91) |
| R04 | 已修复（抽查） | **仍有效**：订单列表只回摘要文本（"已自动发放(共N张)"），员工看到无明文占位；唯独卡密库存页是另一数据面，仍全量明文（见 P1-04，不推翻 R04 结论） | [OrderController.java 237/280](file:///E:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java#L237)、[Orders.vue 52-54](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L52-L54) |
| R20 | 已修复（抽查） | **覆盖不全/存在回退点**：Store.vue 已用 `active` 失效旧请求（正确范例），但 ProductDetail 的 route watch 与代付海报缓存未采用（见 P1-08） | [Store.vue 94-104](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/Store.vue#L94-L104)、[ProductDetail.vue 470-474](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L470-L474) |
| R23 | 已修复（抽查） | **仍有效**：空截止日显式 `null`，不会覆盖月费/状态 | [serviceFee.mjs 31-35](file:///E:/shop-b2b2c/shop-admin-vue/src/utils/serviceFee.mjs#L27-L36)、[serviceFee.test.mjs 36-40](file:///E:/shop-b2b2c/shared/serviceFee.test.mjs#L36-L42) |
| R35 | 已修复（抽查） | **未能静态确认**：需核对全局 Long→String 序列化与所有 `Number()` 消费点；前端金额多处直接 `Number(...)` 展示，ID 比较有 `===` 混用 | [ProductDetail.vue 427-434](file:///E:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue#L427-L435)、[Orders.vue 531-535](file:///E:/shop-b2b2c/shop-admin-vue/src/views/merchant/Orders.vue#L530-L540) |

---

## 7. shared 规则用例覆盖评估

| 用例 | 已覆盖 | 主要缺口 / 误通过风险 |
| --- | --- | --- |
| [authSession.test.mjs](file:///E:/shop-b2b2c/shared/authSession.test.mjs) | 会话键清理、base 感知登录路径、角色覆盖伪造缓存、401/网络失败分支、旧响应不覆盖新登录、退出撤销顺序、HTTP/业务 401 | 未覆盖"登录请求本身 401"（P2-10 的真实缺陷路径）；未与真实 router meta 表联动（路由新增后用例不会失败） |
| [idempotency.test.mjs](file:///E:/shop-b2b2c/shared/idempotency.test.mjs) | 同参数复用、参数变化换键、请求体快照、仅成功 `complete` | 未覆盖并发双击（两次 `prepare` 交错）、`crypto.randomUUID` 缺失（非安全上下文/旧 WebView）、跨页面保留键 |
| [buyerReceipt.test.mjs](file:///E:/shop-b2b2c/shared/buyerReceipt.test.mjs) | URL 敏感参数剔除、凭据不自动使用 | 未覆盖部署 base（`/m/`、`/console/`）下 `receiptPath` 的实际前缀；未覆盖 `<router-link>` 与 `location` 两种消费方式 |
| [goodsType.test.mjs](file:///E:/shop-b2b2c/shared/goodsType.test.mjs) | 类型标签/可用履约动作映射 | 未知类型默认值未用反例覆盖（易默认成实物） |
| [productEditing.test.mjs](file:///E:/shop-b2b2c/shared/productEditing.test.mjs) | 编辑 payload 含 revision、固定字段不可改 | 未覆盖"创建商品"路径（P1-06 的幂等/默认图问题不在规则层）；未覆盖 SKU 空数组 |
| [inventoryAdjustment.test.mjs](file:///E:/shop-b2b2c/shared/inventoryAdjustment.test.mjs) | 调整数量/原因必填、非法值拒绝 | 较强；缺"并发两次调整"契约（后端职责，需集成测试） |
| [orderStatistics.test.mjs](file:///E:/shop-b2b2c/shared/orderStatistics.test.mjs) | 口径聚合、空数据 | 未覆盖字符串金额边界（超大/小数） |
| [serviceFee.test.mjs](file:///E:/shop-b2b2c/shared/serviceFee.test.mjs) | 5 种编码、未知不回退、日期保留、null 清除 | 缺 `monthlyFee` 负数/`NaN`/字符串（见 P2-07/P2-08） |
| [storePage.test.mjs](file:///E:/shop-b2b2c/shared/storePage.test.mjs) | 分页/筛选参数、DTO 解析 | 只测纯函数；Store.vue 的旧请求失效属组件行为，无用例（但组件已正确实现） |
| [storedPoster.test.mjs](file:///E:/shop-b2b2c/shared/storedPoster.test.mjs) | 版本号防迟到响应、缓存键 | 正面范例；未覆盖"切换账单必须清空旧海报"（P1-11 的规则尚未抽到 shared） |
| [cardAssets.test.mjs](file:///E:/shop-b2b2c/shared/cardAssets.test.mjs) | 卡密状态/批次参数 | 未覆盖"列表不得返回明文"的契约（P1-04） |
| [storefrontStyles.test.mjs](file:///E:/shop-b2b2c/shared/storefrontStyles.test.mjs) | 真实 PostCSS/Tailwind 编译 | 依赖 `npm ci`；用户未安装时跳过不等于通过（文件内已有说明） |

**系统性缺口**：没有组件级/契约级用例，因此 P0-01（挂载即崩溃）、P0-02（接口结构错位）、P1-08/P1-11（迟到响应错配）这四类**只能靠人眼发现**；建议后续引入最小 Vue Test Utils 冒烟（挂载各页面 + mock axios）与后端响应样例夹具，不必一步到位做 E2E。

---

## 8. 待专项验证清单

> 以下条目**不能由静态审查判定通过**，须由用户在隔离/目标环境执行；本轮未运行任何命令。

1. **R06 真实客户端桶**：在阿里云/腾讯目标机用 `curl` 伪造 `X-Forwarded-For`，确认限流是否按真实 IP 分桶；若前置 CDN，核对 `set_real_ip_from`/`real_ip_header` 与可信代理拓扑。同时确认修正后的 `location = /api/v1/auth/login` 生效（P1-02）。
2. **R08 会话/日志/依赖**：核对 Sa-Token 30 天/无活跃超时是否符合业务预期；抽查生产日志无口令/卡密；补依赖扫描（`npm audit`/OWASP Dependency-Check）并记录可达性例外。**本项需专项授权，不在本轮执行。**
3. **R14 库存守恒**：真实 MySQL 并发矩阵（预占/支付/释放/关闭交错），断言"可售非负、可售+HELD 不溢出、未售=可售+HELD"；H2 结果不算。
4. **R31 Windows 备份/恢复**：PowerShell 5.1 与 7 各跑一次，解压后检查首字节无 BOM、SQL 无 warning 文本；用隔离库做一次真实恢复并对比表/行数。配套修 `test_backup_windows.ps1` 断言。
5. **P0-04 阿里云数据源**：登录目标服务器 `cat /opt/gmall/.env`，确认是否存在 `SPRING_DATASOURCE_USERNAME/PASSWORD`；若缺失，先补齐再重启并观察后端日志。
6. **P1-14 前台地址**：确认 `.env` 中 `APP_FRONTEND_STOREFRONT_URL` 实际值，创建一张代付单检查返回短链/海报是否为公网 `/m/` 地址。
7. **P1-12 Nginx 顺序**：在隔离容器（或 `nginx -t -c` 指定临时 conf）验证"首次部署 + 错误配置"的回滚行为；不要在生产直接试。
8. **P1-15 证书降级**：确认线上是否 443 可用、HTTP 是否仍可登录后台；检查 certbot 最近一次退出码。
9. **R20/P1-08 浏览器链路**：用浏览器复现"慢网切商品/切账单/连续点查看卡密"，确认是否出现旧数据；顺带验证 P0-01 修复后加载态。
10. **P0-03 线上种子**：查生产 `sys_user` 是否存在 `superadmin/seller01/seller02`；若有，按环境负责人流程轮换口令并评估是否已泄露。
11. **R35 ID/金额契约**：构造 `2^53` 边界、Long 最大值、负数/小数金额，确认前后端一致；核对序列化配置而非只看页面显示。
12. **storefrontStyles**：用户先执行 `npm --prefix shop-storefront-vue ci`，再跑 `node --test shared/storefrontStyles.test.mjs`；缺依赖跳过不算通过。
13. **部署脚本隔离用例**：按 AGENTS.md 在 Linux/WSL 运行 `test_aliyun_build.py`、`test_tencent_build.py`、`test_nginx_gateway.py`；Windows 原生跳过不代表通过。
14. **P1-13 迁移回滚**：在隔离库演练 `--migrate` 失败后的恢复路径，确认是否需要人工 dump；不要对生产库做破坏性验证。

---

## 9. 建议优先处理顺序

1. **P0-01 商品详情 TDZ**（一行级修复、影响全站转化）：先修，后补组件冒烟。
2. **P0-04 阿里云 `.env` 数据源缺项 + 默认口令回退**（部署阻断/root 连接）：与腾讯对齐，再核对线上 `.env`。
3. **P0-03 默认凭据与生产种子**（安全最高危）：删预填、拆 seed、生产禁默认口令；凭据轮换由环境负责人执行。
4. **P0-02 代客推单契约**（功能不可用）：前后端同批修，补契约用例。
5. **P1-02 阿里云限流路径**（一行级安全修复）：与 R06 验证同批。
6. **P1-16 + R31 Windows 备份可信度**（当前未提交改动未解决根因）：先修 stderr/BOM，再增强用例，再做隔离恢复演练。
7. **P1-12/P1-13/P1-15 部署安全边界**（Nginx 顺序、DB 备份、证书降级）：一起改 04-deploy，补 lifecycle 断言。
8. **P1-17 回滚参数注入 + 环境传导**（连同腾讯同构脚本）。
9. **P1-04 卡密明文批量展示**（敏感数据最小化）：后端 DTO + 前端打码。
10. **P1-08/P1-11 迟到响应与海报残留**（资金/隐私错配）：抽统一失效模式，替换所有按 ID 拉详情处。
11. **P1-09 首页搜索与错误态**（用户可见功能缺失）：先确认后端是否已有搜索能力。
12. **P1-05/P1-06/P1-07 管理端分页、幂等与状态语义**（运营效率）。
13. **P2 批次**：优先 P2-10（登录失败整页刷新）、P2-09（会话时长）、P2-19/P2-20（弱断言），其余随页面改动顺带修。

**跨项约束**：P0-03/P0-04/P1-12/P1-13 都涉及部署与凭据，改动须写明"需环境负责人执行/待验证"，不要宣称已修复；P1-04/P1-05 涉及后端 DTO，须与前端同批交付，避免再次出现 P0-02 式结构错位。

---

## 10. 审查声明

- 本文所有"证据"均为本次只读阅读所见的文件与行号；未编译、未跑测试、未连接数据库、未执行部署/备份命令，未安装依赖。
- "确定问题"不等于已修复或已验证；"需环境验证"不等于已确认故障。验收由用户管理，本文不催验收、不宣称通过。
- 未修改任何源码；仅新增本审查文档。
