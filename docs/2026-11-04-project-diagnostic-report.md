<!-- 文件说明：2026-11-04当前工作区B2B2C静态诊断，记录代码证据、风险、实现缺口和验证边界。
维护注意：不包含凭据；R为本次复查索引，D/F沿用主台账，不替代历史记录。静态确认不等于动态复现或验收；行号属于本次快照，维护时按符号定位。 -->
# B2B2C 项目诊断报告（diagnostic）

日期：2026-11-04（Asia/Shanghai）。范围：`E:\shop-b2b2c` 当前工作区。执行清单见 [todo](2026-11-04-remediation-todo.md)，历史范围见 [D/F主台账](B2B2C_项目缺陷与未实现功能清单_2026-11-03.md)，规则依赖见 [问题依赖与修复边界](问题依赖与修复边界.md)。

提交补记：审查时尚未提交的Sprint22代码及用例现已补交为 `5686f95`；本报告保留审查时点的基线描述，不代表重新执行了测试或验收。

## 1. 结论

当前项目具备多商户商品、匿名建单/领取、数据库库存预占、卡密认领、商品维护和部分运营追溯能力，基础安全边界较最初MVP有明显补强；仍不具备真实支付、资金结算、退款和实物交易的完整经营闭环。不能作为“资金交易全闭环已验收”的生产系统交付。

本次登记 **35项技术/逻辑/工程诊断**，另复核 **20项原F业务能力**。这些条目存在依赖与交叉，不能相加估算工期，也不代表发现35个可远程利用的安全漏洞。最优先的是生产凭据/种子隔离、店铺配置并发覆盖、计价不一致、预占恢复与关闭，以及真实支付接入前的事件/补偿模型。

### 审查基线与限制

- Git基线：`e40e1cf`（Sprint21）。同时审阅了尚未提交的Sprint22海报减载、商户按需读取、前端预览和异常处理改动及其用例；这些均视为**工作区实现、待人工验证**。已有未提交改动保留，本轮没有改业务代码。
- 范围覆盖后端Controller/Service/Mapper/DTO/Entity及SQL/config，两个Vue工程的路由/页面/请求/规则工具，shared用例，云构建/部署/网关/备份与既有交付文档；以交易、权限、资产和发布路径为重点追踪调用链。依赖目录、构建产物、图片及二进制设计文档不作自研代码证据。
- 采用文件清点、源码阅读、模式检索、SQL/接口对照和局部Git历史溯源。不是逐行形式化证明；报告不能承诺已发现全部问题。
- 按用户约定，**没有执行编译、单元/集成/E2E测试、构建、部署、数据库操作、压测或依赖漏洞扫描**；未连接远端。未判断云主机真实状态或外部服务能力；没有进行法律合规鉴定。
- “确认”指源码存在相应路径/缺口，仍须回归或环境验证；“待专项验证”不应擅自升级为已复现漏洞。
- 历史遗留=旧实现仍存在；近期实现关联=Sprint整改带来的新接口/规则交接或扩展瓶颈，**不当然意味着该提交引入回归**；只对有Git证据的条目标注明确来源。其余首次发现时间为本次审查，首次引入时间未全面追溯。
- 优先级：P0为真实交易/生产接入阻断，P1为关键安全或经营正确性，P2为容量/契约/治理，P3为需确认扩展。功能缺失与可利用漏洞分开标记。

## 2. 已有整改应保留，不重复判为未实现

| 领域 | 当前源码已具备 | 本次仍保留的边界 |
| --- | --- | --- |
| Mock/未付款交付 D01–D02 | checkout只建UNPAID；公开Mock付款已移除 | F01真实支付仍未实现，不恢复Mock冒充支付 |
| 库存/卡密 D03–D09 | 归属校验、条件扣减、订单锁、预占流水、HELD迁移、FOR UPDATE卡密候选及CAS认领 | 恢复、毒记录、MySQL并发、账本及渠道一致性仍需补 |
| 类型/资格/租户 D11–D19 | 三商品类型、店铺/上下架资格、对象归属、公开店铺白名单、付款与领取token分离 | 同店员工权限、管理列表最小化与配置并发覆盖仍有问题 |
| 会话/跨域 D20/D22/D23/D29 | 服务端退出、账号/授权状态核验、前端守卫、来源白名单、Redis原子限流 | CORS漏头、代理IP、权限矩阵、强认证仍需处理 |
| 幂等/商品/资产 D14/F11/F12 | 三建单事务幂等、编辑版本、库存调整流水、卡密去重作废与批次游标 | 不等于支付幂等/加密/全部库存流水/完整员工系统 |
| 数据库 D31/D36 | 唯一schema入口、不强制切库、初始化失败阻断；新表部分CHECK与外键 | 旧列迁移、生产种子隔离与核心约束仍缺 |
| 看板/店铺/查询 D25/D26/D30/D33 | 状态统计、账本不可用明确、空店资料、公开商品组合聚合、状态编码统一 | 看板不是财务账本；商品详情/收银宣传与其他查询残留 |
| 部署/探针/样式 D34–D38 | 阿里构建隔离、通用网关、ready探针、Redis写保护、Tailwind本地构建 | 云部署消费者尚未统一；ready不证明Schema/写权限/支付可用 |
| 工作区Sprint22 | 管理列表SQL剔除poster、租户限定按需读取、预览乱序/关闭保护、路径类型400及用例 | 尚未编译/测试；未解决公开渲染/大字段存储/分页 |

旧台账D27关于executePay跳过预占的描述已被支付关闭改变；D28关于付款页不显示交付凭证，现在属于D19隐私边界的有意行为，**不得恢复分享页卡密**。当前剩余收银缺陷以R10/R20/R21/R22重述。旧D32“未见外键”和D39“无测试”也不能直接作为现状引用。

## 3. 诊断索引

| 编号 | 优先级 | 证据类别 | 历史/近期归类 | 原台账 | 当前问题 |
| --- | --- | --- | --- | --- | --- |
| R01 | P0 | 确认配置风险 | 历史遗留 | D21/D31 | [固定凭据回退及生产种子隔离未完成](#r01) |
| R02 | P1 | 确认权限缺口 | 历史遗留 | D23/F13 | [员工与店长共用敏感操作权限](#r02) |
| R03 | P1 | 确认接口缺陷 | 近期实现关联 | D09/D23/D27 | [CORS遗漏预占所有者请求头](#r03) |
| R04 | P1 | 确认暴露面 | 历史遗留；Sprint22仅减载 | D18/D23/D30/F12/F13 | [管理列表批量返回领取凭证及卡密正文](#r04) |
| R05 | P1 | 确认存储风险 | 历史遗留 | D21/F12/F13 | [卡密多份明文与身份结算资料缺分级保护](#r05) |
| R06 | P1 | 部署相关风险 | 近期限流关联 | D20/D23/D35 | [代理部署下限流可能共享回环IP桶](#r06) |
| R07 | P1 | 确认防滥用缺口 | 历史遗留及新增接口共同边界 | D38/D30/F01 | [公开建单、预占和海报无应用成本预算](#r07) |
| R08 | P2 | 待专项验证 | 历史遗留 | D21/D23/D24/F13 | [长期会话、日志和依赖安全缺完整证据](#r08) |
| R09 | P1 | 确认并发缺陷 | 历史遗留（Git可追溯） | D12/D13/F13 | [整实体保存可能恢复旧封禁状态或费率](#r09) |
| R10 | P1 | 确认恢复缺口 | 近期预占关联 | D09/D27/F08 | [预占提交后丢响应无法恢复ownerToken](#r10) |
| R11 | P1 | 确认恢复缺陷 | 近期释放任务关联 | D10/F08 | [持续失败头100条可饿死后续到期预占](#r11) |
| R12 | P1 | 确认功能缺口 | 历史遗留；部分修复 | D10/F08 | [无预占意向单缺统一后台关闭](#r12) |
| R13 | P0 | 支付接入阻断缺口 | 历史遗留；非公开支付漏洞 | F01/F03/F05/F08/F09/D06/D32 | [到账事实与本地履约未解耦，支付事件未建模](#r13) |
| R14 | P2 | 待专项验证 | 近期资产维护关联 | D04/D05/D32/F12 | [锁序与库存守恒缺完整MySQL并发证据](#r14) |
| R15 | P2 | 确认数据库缺口 | 历史遗留；新表部分补强 | D31/D32/F01/F12 | [主表约束、版本迁移与历史修复工具不全](#r15) |
| R16 | P1 | 确认计价缺陷 | 历史遗留（初始提交） | D13/D26/F19 | [批量折扣只在前端，展示价与订单价不同](#r16) |
| R17 | P1 | 确认输入缺陷 | 历史遗留；新编辑较完整 | D13/F11 | [创建与编辑商品校验及最低价语义分裂](#r17) |
| R18 | P1 | 确认基线缺陷 | 历史遗留 | D11/D31/F12 | [年卡SKU库存为3却无对应种子卡密](#r18) |
| R19 | P2 | 确认接口缺陷 | 历史遗留 | D13/F11 | [新增类目接口携ID可修改既有类目](#r19) |
| R20 | P1 | 确认页面缺陷 | 历史遗留；Store已修 | D26/D28/F17 | [同组件切路由可能展示旧商品或旧账单](#r20) |
| R21 | P2 | 确认契约缺陷 | 历史遗留 | D26/D28 | [自己付款跳转依赖响应不存在的productId](#r21) |
| R22 | P1 | 确认真实性缺口 | 历史遗留；部分页面已改 | D26/F02/F03/F05/F19 | [担保认证、虚构原价、隐藏商品与开通提示不真实](#r22) |
| R23 | P2 | 确认配置缺口 | 近期服务费契约关联 | D33/F10 | [服务费免收截止日不能清空](#r23) |
| R24 | P2 | 确认查询缺口 | 历史遗留；近期局部优化 | D30/D32/D38 | [全量列表、N+1明细及内存统计残留](#r24) |
| R25 | P2 | 确认瓶颈 | 近期卡密去重关联 | F12/D30 | [导入每次全扫全店历史明文且锁商户](#r25) |
| R26 | P2 | 确认资源缺口 | 历史遗留；Sprint22未覆盖 | D38/D30 | [海报事务渲染、公开大字段与幂等副本仍在](#r26) |
| R27 | P2 | 确认治理缺口 | 近期幂等和追溯关联 | D30/D32/F12/F13 | [私密幂等快照与历史数据缺保留策略](#r27) |
| R28 | P1 | 确认发布缺陷 | 历史遗留；阿里云已局部修 | D34/D35 | [腾讯构建仍可缺端成功、依赖与latest不稳](#r28) |
| R29 | P1 | 确认发布缺陷 | 历史遗留；新旧消费者衔接未完 | D35/D37 | [部署先覆盖线上且吞SQL错误，失败无整套恢复](#r29) |
| R30 | P1 | 确认防护缺口 | 历史遗留 | D21/D35 | [root应用与证书失败HTTP降级未区分生产](#r30) |
| R31 | P1 | 确认实现风险（版本相关） | 历史遗留 | D35/D39 | [Windows备份可能假成功或产生错误编码](#r31) |
| R32 | P2 | 确认治理缺口 | 历史遗留 | D21/D35/D39 | [备份旧默认目标、参数拆分及恢复证据不足](#r32) |
| R33 | P2 | 确认验证缺口 | 近期交付累计 | D39 | [测试已增加但执行状态和持续回归不足](#r33) |
| R34 | P2 | 确认契约缺口 | 历史遗留；Sprint22类型错误已单独处理 | D13/D24/D37 | [业务失败常返回HTTP200，缺参/错误方法分类不全](#r34) |
| R35 | P2 | 待专项验证 | 历史遗留；新分页/海报同受影响 | D13/D30/D32 | [BIGINT/Long和JS安全整数契约未统一](#r35) |

## 4. 技术与逻辑证据

### 安全与隐私

<a id="r01"></a>

#### R01 · 固定凭据回退及生产种子隔离未完成

**P0 · 确认配置风险 · 历史遗留 · D21/D31 · 待处理/待验证。**

证据：[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L270（`-- 1. 初始超管账号`）；[application.yml](../shop-b2b2c-backend/src/main/resources/application.yml) L33（`mode: always`）。

数据源存在口令字面回退，云环境文件已被Git跟踪；启动schema含固定演示用户与模拟数据。删除演示账号后再次启动还可能按固定ID重新插入。源码不能证明这些值用于线上，但不能作为生产默认值；不在报告复制口令。

整改方向：强制受控注入，缺值阻断；隔离生产结构和开发种子；轮换实际使用过的凭据，核查Git历史。新增.gitignore不能清除已跟踪文件。

回归重点：缺凭据不能生产启动；生产安装/重启不生成演示用户；旧凭据轮换后失效。

<a id="r02"></a>

#### R02 · 员工与店长共用敏感操作权限

**P1 · 确认权限缺口 · 历史遗留 · D23/F13 · 待处理/待验证。**

证据：[SaTokenConfigure.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaTokenConfigure.java) L33（`StpUtil.checkRoleOr`）；[ProductController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java) L175（`listCards(`）。

MERCHANT_STAFF可访问整个商家路由，能够读取卡密、导入/作废、改价、履约及更改自定义收款码。已有租户隔离不等于同店最小权限。

整改方向：明确操作矩阵，卡密明文、收款资料和资产操作独立授权并审计；后端强制，前端隐藏只改善体验。

回归重点：合法授权成功；员工未授权操作拒绝且数据库不变；角色变化撤销旧会话。

<a id="r03"></a>

#### R03 · CORS遗漏预占所有者请求头

**P1 · 确认接口缺陷 · 近期实现关联 · D09/D23/D27 · 待处理/待验证。**

证据：[SaTokenConfigure.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/SaTokenConfigure.java) L52（`.allowedHeaders(`）；[PeerPayController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java) L56（`@RequestHeader("X-Reservation-Token")`）。

取消预占必需X-Reservation-Token，CORS仅允许Authorization、Content-Type、Idempotency-Key。允许来源的跨域浏览器取消请求仍会预检失败。同源不受影响，当前付款按钮未调用预占。属于Sprint2与Sprint8契约衔接遗漏，不宣称本轮代码引入。

整改方向：精确允许所有者头，保持来源白名单和禁跨域Cookie；补OPTIONS契约。

回归重点：合法来源预检成功；非法来源/头拒绝；正确凭证释放成功，错误凭证不释放。

<a id="r04"></a>

#### R04 · 管理列表批量返回领取凭证及卡密正文

**P1 · 确认暴露面 · 历史遗留；Sprint22仅减载 · D18/D23/D30/F12/F13 · 待处理/待验证。**

证据：[OrderController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java) L178（`map.put("order", o)`）；[OrderController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java) L271（`res.put("orders", orders)`）；[ProductController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java) L185（`cardMapper.findByMerchantId`）。

列表返回实体和全量明细，包括queryToken、联系方式、服务凭证/卡密快照；卡密接口返回完整实体。Sprint22只排除posterUrl。不是匿名或已确认跨店泄露，但结合员工权限扩大批量泄露面；部分敏感GET也未设no-store。

整改方向：列表/详情/卡密查看拆最小DTO，明文单独授权和审计，敏感响应no-store；保留买家领取白名单。

回归重点：普通列表不含领取token/正文等非必要字段；授权查看可用，跨店拒绝，缓存头正确。

<a id="r05"></a>

#### R05 · 卡密多份明文与身份结算资料缺分级保护

**P1 · 确认存储风险 · 历史遗留 · D21/F12/F13 · 待处理/待验证。**

证据：[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L121（``card_content` TEXT`）；[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L45（``bank_account_number``）；[PeerPayServiceImpl.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java) L489（`firstItem.setCardDetails`）；[PeerPayServiceImpl.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java) L494（`order.setServiceVoucher("系统卡密`）。

卡密库存、订单明细与服务凭证重复存正文，扩大数据库和备份泄露面。银行卡/身份证未见字段加密、脱敏存取服务，SQL注释“脱敏”不等于处理实现。密码已有BCrypt，不应把密码保护与卡密保护混为一谈。

整改方向：设计加密及密钥版本/轮换，减少正文副本，保护备份与日志，迁移兼容已售/作废历史。

回归重点：库与备份不出现直接可用卡密；授权解密可用，缺密钥拒绝，轮换和旧订单读取覆盖。

<a id="r06"></a>

#### R06 · 代理部署下限流可能共享回环IP桶

**P1 · 部署相关风险 · 近期限流关联 · D20/D23/D35 · 待处理/待验证。**

证据：[AuthController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/AuthController.java) L54（`request.getRemoteAddr()`）；[OrderController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java) L162（`servletRequest.getRemoteAddr()`）；[nginx.prod.conf](../deploy/nginx/nginx.prod.conf) L50（`proxy_set_header X-Forwarded-For`）。

应用按连接IP限流，Nginx回环连接后端，当前未见可信转发配置，默认拓扑可能让所有用户共用登录/查单IP桶。攻击或集中访问会挤占其他人限额。实际效果需按部署核实；拒绝任意XFF本身正确，不能直接取可伪造头值。

整改方向：明确可信代理并剥离外部伪造头，限制后端直连；保留账号/订单与总量限额。

回归重点：不同真实客户端桶独立；伪造XFF不能绕过；直连不获得可信转发身份。

<a id="r07"></a>

#### R07 · 公开建单、预占和海报无应用成本预算

**P1 · 确认防滥用缺口 · 历史遗留及新增接口共同边界 · D38/D30/F01 · 待处理/待验证。**

证据：[PeerPayController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java) L32（`/buyer/create`）；[PeerPayController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java) L47（`/pre-lock/{token}`）；[PeerPayController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/PeerPayController.java) L69（`/poster/{token}`）；[nginx.prod.conf](../deploy/nginx/nginx.prod.conf) L20（`client_max_body_size 50m`）。

更换幂等键可以持续建意向单；持分享链接能占用真实可售数，当前又无真实付款渠道；渲染可消耗CPU和连接。登录/查单专有限流不能保护这些入口，通用20r/s也不能约束累计存储成本或网关旁路。

整改方向：按接口成本限制大小、速率、并发及累计配额；预占绑定支付尝试和恢复策略。匿名交易限额需确认业务影响。

回归重点：正常请求成功；新键洪泛/重复渲染受限；超限不写单、不扣库存。

<a id="r08"></a>

#### R08 · 长期会话、日志和依赖安全缺完整证据

**P2 · 待专项验证 · 历史遗留 · D21/D23/D24/F13 · 待处理/待验证。**

证据：[request.js](../shop-admin-vue/src/api/request.js) L17（`localStorage.getItem('token')`）；[application.yml](../shop-b2b2c-backend/src/main/resources/application.yml) L58（`timeout: 2592000`）；[GlobalExceptionHandler.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java) L79（`log.error("系统未知异常", e)`）。

Bearer存localStorage，30天会话无闲置过期，通用异常堆栈可能含SQL参数。未见完整CSP、敏感操作再认证、traceId/脱敏体系。不是已证实XSS；未查询漏洞库或运行SCA，不能据版本号宣称存在或不存在特定CVE。

整改方向：按威胁模型收紧会话，补强认证、CSP和日志治理；建立SBOM/SCA/凭据扫描。若改Cookie，须同时设计CSRF。

回归重点：日志无凭据；会话撤销/过期可回归；依赖扫描列出可达性与例外。

### 原子性与生命周期

<a id="r09"></a>

#### R09 · 整实体保存可能恢复旧封禁状态或费率

**P1 · 确认并发缺陷 · 历史遗留（Git可追溯） · D12/D13/F13 · 待处理/待验证。**

证据：[MerchantController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java) L137（`updateStatus(`）；[MerchantController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java) L222（`updateMerchantProfile(`）；[MerchantStoreMapper.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/MerchantStoreMapper.java) L26（`updateById(entity)`）。

配置接口先读MerchantStore再save整实体，没有版本CAS或统一行锁。商家先读营业中，平台封禁提交，商家仅改公告却可能将旧status=1一并写回；费率/收费/通道也会丢更新。请求赋值白名单不能防保存旧实体其他列。Git-S显示整实体保存自7d04707已存在。

整改方向：各操作只UPDATE授权列，必要时版本CAS/行锁，集中店铺写入口并审计敏感变化。

回归重点：屏障控制封禁+公告、费率+电话、收费+通道交错，各自修改不覆盖；合法单项更新成功。

<a id="r10"></a>

#### R10 · 预占提交后丢响应无法恢复ownerToken

**P1 · 确认恢复缺口 · 近期预占关联 · D09/D27/F08 · 待处理/待验证。**

证据：[PeerPayServiceImpl.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java) L334（`public PeerPayDTOs.PreLockResult preLockInventory`）；[PeerPayServiceImpl.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java) L368（`result.setOwnerToken`）；[PeerPayServiceImpl.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java) L379（`public void cancelPreLock`）。

首次预占已提交但响应丢失，重试因RESERVED_LOCK被拒；没拿到ownerToken的客户端不能主动取消，只能等到期。不能为恢复方便把owner返回任何分享链接访问者。

整改方向：用可验证的客户端支付尝试/预占幂等凭证，同事务重放原所有者结果。

回归重点：丢响应同尝试可恢复且只扣一次；其他尝试不能拿owner；取消/超时重试不双加。

<a id="r11"></a>

#### R11 · 持续失败头100条可饿死后续到期预占

**P1 · 确认恢复缺陷 · 近期释放任务关联 · D10/F08 · 待处理/待验证。**

证据：[InventoryReservationMapper.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/InventoryReservationMapper.java) L27（`ORDER BY order_id LIMIT 100`）；[ReservationExpiryJob.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java) L28（`下一轮重试`）。

每轮取最小100条HELD到期记录；失败没有退避时间或游标。若头100条因坏SKU/溢出等持续失败，后面合法到期单永久进不了批次；更少失败也持续占容量。

整改方向：重试时间/次数和异常队列，或有界游标覆盖扫描；保留订单锁+状态CAS+事务恢复，不能直接忽略资产错误。

回归重点：100条失败加第101条正常，正常记录仍在有限轮次释放；失败可追溯，多实例不双加。

<a id="r12"></a>

#### R12 · 无预占意向单缺统一后台关闭

**P1 · 确认功能缺口 · 历史遗留；部分修复 · D10/F08 · 待处理/待验证。**

证据：[ReservationExpiryJob.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/ReservationExpiryJob.java) L26（`reservations.expiredIds()`）；[PeerPayServiceImpl.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java) L401（`private void expireIfNecessary`）。

任务只扫HELD，普通checkout、未预占代付和取消预占回INIT的订单不在扫描内；代付只能靠访问详情补关闭。无人访问可能长期UNPAID。页面“自动15分钟取消”不适用于所有路径。

整改方向：统一关闭与渠道关单/迟到款/释放规则；普通checkout有效期须确认，不能擅自全部设15分钟。

回归重点：无人访问也按规则关闭；已支付不关闭；取消后到期可关闭，释放只一次。

<a id="r13"></a>

#### R13 · 到账事实与本地履约未解耦，支付事件未建模

**P0 · 支付接入阻断缺口 · 历史遗留；非公开支付漏洞 · F01/F03/F05/F08/F09/D06/D32 · 待处理/待验证。**

证据：[PeerPayServiceImpl.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java) L422（`public TradeOrder notifyPaymentSuccess`）；[TradeOrderMapper.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderMapper.java) L39（`markPaidIfUnpaid`）；[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L144（``pay_trade_no``）。

内部方法只收token/payerId/tradeNo/channel，无验签/金额/币种/商户核验事件模型。支付CAS、资格检查与发卡同事务，缺货/过期/下架可回滚本地，但不会撤销未来真实渠道扣款。无渠道事件唯一约束；目前无公开Mock落账入口，不能写成“外部任意付款成功”。

整改方向：接真实支付前先持久化验签核对后的唯一到账事件，再幂等履约；失败交付/迟到款进补偿或退款，渠道号不能落多个订单。

回归重点：重复/乱序/错金额/错商户、到账后崩溃、缺货/过期后到账均可追溯，不双履约不漏到账。

<a id="r14"></a>

#### R14 · 锁序与库存守恒缺完整MySQL并发证据

**P2 · 待专项验证 · 近期资产维护关联 · D04/D05/D32/F12 · 待处理/待验证。**

证据：[CardAssetService.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java) L31（`ISOLATION_READ_COMMITTED`）；[InventoryAdjustmentService.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/InventoryAdjustmentService.java) L35（`ISOLATION_READ_COMMITTED`）；[SchemaMySqlTest.java](../shop-b2b2c-backend/src/test/java/com/shop/b2b2c/SchemaMySqlTest.java) L24（`fullBaselineExecutesTwice`）。

资产锁商户→商品→SKU→卡，交易锁订单→SKU/卡，资格含非锁定读取。未确认必然死锁或超卖，不能只因锁序不同断言漏洞。H2已有并发用例；MySQL基线/SQL与回滚测试不能替代多连接InnoDB并发。

整改方向：列写者锁图，补预占/释放/导入/作废/手调/付款交错矩阵和死锁重试边界。

回归重点：可售非负；可售+HELD不溢出；干净卡密库存未售数=可售+HELD；回滚无半单。

<a id="r15"></a>

#### R15 · 主表约束、版本迁移与历史修复工具不全

**P2 · 确认数据库缺口 · 历史遗留；新表部分补强 · D31/D32/F01/F12 · 待处理/待验证。**

证据：[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L108（``stock_quantity` INT`）；[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L244（`CREATE TABLE IF NOT EXISTS inventory_reservation`）；[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L432（`CONSTRAINT fk_card_import_item_batch`）。

核心SKU非负、金额、预占数量缺CHECK，核心归属链依赖应用。新库存调整/作废/批次已有CHECK且成员有外键，不能再说全库没有约束。开发schema不升级旧列，开发重建不等于保留数据迁移。

整改方向：先查脏数据再加必要约束/事件唯一键；设计历史快照/删除语义后决定外键；开发唯一schema之外补版本升级方案。

回归重点：非法直写拒绝，合法快照保留；空库/存量升级均验证，SQL失败阻断。

### 业务逻辑与前端

<a id="r16"></a>

#### R16 · 批量折扣只在前端，展示价与订单价不同

**P1 · 确认计价缺陷 · 历史遗留（初始提交） · D13/D26/F19 · 待处理/待验证。**

证据：[ProductDetail.vue](../shop-storefront-vue/src/views/ProductDetail.vue) L434（`if (qty >= 50)`）；[ProductDetail.vue](../shop-storefront-vue/src/views/ProductDetail.vue) L442（`if (quantity.value >= 50)`）；[OrderController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java) L98（`sku.getPrice().multiply`）。

10件92折、50件85折仅由页面计算，普通/买家代付后端按原SKU价×数量。单价100买10件显示920，建单1000。git log -S将折扣追溯至f674e1e；实际金额不一致必须修，不能当未来批发候选搁置。

整改方向：短期取消无服务支持的折扣；确认阶梯价后后端统一报价与快照，三条建单复用。

回归重点：1/9/10/49/50件、SKU切换、普通/代付/议价金额一致；不信任前端价格确认到账。

<a id="r17"></a>

#### R17 · 创建与编辑商品校验及最低价语义分裂

**P1 · 确认输入缺陷 · 历史遗留；新编辑较完整 · D13/F11 · 待处理/待验证。**

证据：[ProductController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java) L106（`public R<Product> createProduct`）；[ProductController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/ProductController.java) L136（`product.setMinPrice(dto.getMinPrice())`）；[ProductEditingService.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/ProductEditingService.java) L52（`categories.findById`）；[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L176（``spec_info` VARCHAR(255)`）。

创建不核实类目存在，minPrice独立收取，SKU数无编辑接口100条上限；subTitle/specData缺存储长度一致性。长JSON可进SKU JSON列却无法进入订单VARCHAR(255)规格快照，出现能发布不能建单。

整改方向：共享创建/编辑校验、最低价派生及规格快照契约，处理既有异常数据。

回归重点：错类目/长规格/长副标题/超量SKU明确拒绝或兼容；合法边界发布后能建单，无部分写入。

<a id="r18"></a>

#### R18 · 年卡SKU库存为3却无对应种子卡密

**P1 · 确认基线缺陷 · 历史遗留 · D11/D31/F12 · 待处理/待验证。**

证据：[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L314（`'TX-VIP-1Y'`）；[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L316（`-- 注入 5 张月卡卡密库存`）。

年卡SKU2设置库存3，卡密种子只有月卡SKU1的5张。空库重建仍展示不存在的年卡资产，预占扣数后发卡会因缺卡回滚。重建解决结构差异，不能消除种子自己的业务错误。

整改方向：演示卡密可售数按实际种子卡数生成，无卡年卡设0；生产不自动注入可交易演示卡。

回归重点：空库各卡密SKU未售数等于可售数；重复初始化不重置消耗、不增卡。

<a id="r19"></a>

#### R19 · 新增类目接口携ID可修改既有类目

**P2 · 确认接口缺陷 · 历史遗留 · D13/F11 · 待处理/待验证。**

证据：[CategoryController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/CategoryController.java) L33（`createCategory(`）；[CategoryMapper.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/CategoryMapper.java) L27（`updateById(entity)`）。

创建直接绑定实体，save见ID即update；新增可变成覆盖。缺名称长度、父级存在与循环校验。是超管数据完整性问题，不是匿名越权。

整改方向：Create DTO强制insert，编辑独立，校验层级/引用影响并审计。

回归重点：新增携ID不覆盖；空名/长名/无父级/循环拒绝；合法根子类目成功。

<a id="r20"></a>

#### R20 · 同组件切路由可能展示旧商品或旧账单

**P1 · 确认页面缺陷 · 历史遗留；Store已修 · D26/D28/F17 · 待处理/待验证。**

证据：[ProductDetail.vue](../shop-storefront-vue/src/views/ProductDetail.vue) L610（`onMounted(() =>`）；[PeerPayCashier.vue](../shop-storefront-vue/src/views/PeerPayCashier.vue) L327（`const token = route.params.token`）；[Store.vue](../shop-storefront-vue/src/views/Store.vue) L94（`watch([() => route.params.id`）。

商品只onMounted加载；收银token捕获常量无watch。Vue复用组件时A→B可能仍展示A，商品提交沿用旧product/currentSku。Store已有旧响应失效机制，这两页未适配。

整改方向：监听参数、清理结果/表单/倒计时，取消或版本化请求；加载失败不可操作旧数据。

回归重点：A慢B快、错误恢复、卸载和无效ID，页面与建单始终对应当前资源。

<a id="r21"></a>

#### R21 · 自己付款跳转依赖响应不存在的productId

**P2 · 确认契约缺陷 · 历史遗留 · D26/D28 · 待处理/待验证。**

证据：[PeerPayCashier.vue](../shop-storefront-vue/src/views/PeerPayCashier.vue) L179（`detail.productId || 1`）；[PeerPayDTOs.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/dto/PeerPayDTOs.java) L36（`class DetailResponse`）。

响应未定义或赋productId，页面回退/product/1；其他商品会跳到商品1。

整改方向：明确回原商品还是同账单付款；需要商品ID则加可信白名单字段，并处理下架；不恢复Mock。

回归重点：非1商品不误跳1；缺失/下架提示准确，付款未开放状态保持真实。

<a id="r22"></a>

#### R22 · 担保认证、虚构原价、隐藏商品与开通提示不真实

**P1 · 确认真实性缺口 · 历史遗留；部分页面已改 · D26/F02/F03/F05/F19 · 待处理/待验证。**

证据：[Home.vue](../shop-storefront-vue/src/views/Home.vue) L46（`阶梯批发价`）；[ProductDetail.vue](../shop-storefront-vue/src/views/ProductDetail.vue) L52（`官方合规认证`）；[PeerPayCashier.vue](../shop-storefront-vue/src/views/PeerPayCashier.vue) L246（`1.38`）；[PeerPayCashier.vue](../shop-storefront-vue/src/views/PeerPayCashier.vue) L199（`hideProductInfo = !hideProductInfo`）；[Merchants.vue](../shop-admin-vue/src/views/admin/Merchants.vue) L271（`二级商户号已分配`）。

仍有未接入担保/专户/退款承诺，划线价=应付×1.38；隐藏商品只是本地ref，分享API/海报仍含商品。本地SUB号+PASSED提示官方开通。属于产品真实性诊断，不作法律结论。

整改方向：去掉未证实承诺，参考价有真实来源；隐藏规则须服务端、分享与海报一致；模拟进件和官方状态分离。

回归重点：未开放能力不显示已生效；隐藏跨浏览器/分享一致；无真实参考价不显示划线价。

<a id="r23"></a>

#### R23 · 服务费免收截止日不能清空

**P2 · 确认配置缺口 · 近期服务费契约关联 · D33/F10 · 待处理/待验证。**

证据：[MerchantController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java) L173（`if (dto.getFeeFreeUntil() != null)`）；[serviceFee.mjs](../shop-admin-vue/src/utils/serviceFee.mjs) L4（`null`）。

null定义保留，前端清空也传null，旧日期无法撤销。不是扣费漏洞（扣费未实现）；Sprint17保留语义明确，不能直接将所有null改删除。

整改方向：显式clear动作或区分未传/清空的PATCH，兼容旧调用；收费政策另确认。

回归重点：省略保留、显式清除、新值设置成功；月费/状态不被覆盖。

### 性能与容量

<a id="r24"></a>

#### R24 · 全量列表、N+1明细及内存统计残留

**P2 · 确认查询缺口 · 历史遗留；近期局部优化 · D30/D32/D38 · 待处理/待验证。**

证据：[OrderController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java) L179（`itemMapper.findByOrderSn(o.getOrderSn())`）；[OrderController.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/OrderController.java) L190（`OrderStatistics.from(orderMapper`）；[TradeOrderItemMapper.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/TradeOrderItemMapper.java) L39（`.eq(TradeOrderItem::getOrderSn`）；[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L182（`KEY `idx_order_id` (`order_id`)`）。

订单全量+逐单明细，平台全量统计，卡密/商品/商户无通用分页；明细按order_sn读但仅有order_id索引。Sprint15公开商品单SQL与Sprint22图片排除不能消除剩余内存/查询线性成本。

整改方向：稳定有界分页、批量明细、匹配索引；统计SQL聚合保留D25分类/精度/账本不可用语义，先EXPLAIN与基线。

回归重点：万级订单每页查询有界；无全表明细扫描，金额口径不变，分页边界明确。

<a id="r25"></a>

#### R25 · 导入每次全扫全店历史明文且锁商户

**P2 · 确认瓶颈 · 近期卡密去重关联 · F12/D30 · 待处理/待验证。**

证据：[CardAssetService.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java) L55（`assets.historyContents(merchant)`）；[CardAssetMapper.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/mapper/CardAssetMapper.java) L14（`SELECT card_content`）；[CardAssetService.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/CardAssetService.java) L113（`assets.lockMerchant(merchant)`）。

CPU/网络/内存随历史增长，商户排他锁串行化不同SKU导入/作废。当前同路径互斥有效，但无数据库唯一摘要，历史删除或新旁路会破坏前提。

整改方向：迁移规范摘要+商户范围唯一键，冲突整批回滚；确定统一锁图后缩锁，不能先去商户锁。

回归重点：同店跨SKU/已售/作废重复拒绝；跨店按批准规则；大历史不读全部明文，并发重复只一批成功。

<a id="r26"></a>

#### R26 · 海报事务渲染、公开大字段与幂等副本仍在

**P2 · 确认资源缺口 · 历史遗留；Sprint22未覆盖 · D38/D30 · 待处理/待验证。**

证据：[PeerPayServiceImpl.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java) L551（`public String regeneratePoster`）；[PeerPayServiceImpl.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java) L270（`resp.setPosterUrl(order.getPosterUrl())`）；[OrderIdempotencyService.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderIdempotencyService.java) L55（`response = encode(action.get())`）。

公开GET重生成持订单锁并写回；建单事务同步渲染；图片在订单和幂等原响应重复保存。公开详情、买家查单底层整实体仍读大字段。Sprint22只处理管理列表，不等于全部海报减载。

整改方向：成本限流/缓存后将渲染移出交易事务，以版本控制写回；对象存储权限与幂等业务引用保持一致。

回归重点：慢渲染不阻塞付款/释放；重试不重复生成；失败无半单，旧渲染不覆盖新版本。

<a id="r27"></a>

#### R27 · 私密幂等快照与历史数据缺保留策略

**P2 · 确认治理缺口 · 近期幂等和追溯关联 · D30/D32/F12/F13 · 待处理/待验证。**

证据：[OrderIdempotencyService.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/OrderIdempotencyService.java) L4（`记录不自动过期`）；[schema.sql](../shop-b2b2c-backend/src/main/resources/db/schema.sql) L260（`response_json LONGTEXT`）；[PeerPayServiceImpl.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java) L142（`result.put("queryToken"`）。

幂等原响应含queryToken/海报；调整/批次/作废等无分层保留、归档及容量指标。不能直接TTL删除键，否则晚重试可重建单/重复调整；缺的是生命周期而非当前幂等事务无效。

整改方向：明确重放窗口、永久业务去重凭据/归档索引与敏感快照保护，制定保留和清理。

回归重点：窗口内重放一致，窗口外按契约拒绝或处理但不双执行；追溯完整且无批量凭证泄露。

### 部署与恢复

<a id="r28"></a>

#### R28 · 腾讯构建仍可缺端成功、依赖与latest不稳

**P1 · 确认发布缺陷 · 历史遗留；阿里云已局部修 · D34/D35 · 待处理/待验证。**

证据：[03-build.sh](../deploy/tencent/server/03-build.sh) L61（`npm install --no-audit`）；[03-build.sh](../deploy/tencent/server/03-build.sh) L57（`if [[ -d "${SRC_DIR}/shop-admin-vue"`）；[03-build.sh](../deploy/tencent/server/03-build.sh) L82（`rm -f "${SHOP_ROOT}/releases/latest"`）。

前端目录可选，npm install代替锁定ci，JAR取首个，秒级版本目录可能复用，latest先删后建。没有阿里Sprint18完整产物和失败隔离契约。

整改方向：复用三端必需产物与锁定构建，唯一目录、完整清单、成功后原子切换。测试执行仍按用户约定。

回归重点：缺工程/锁/产物或失败非零，旧latest保留；并发构建隔离，完整三端才发布。

<a id="r29"></a>

#### R29 · 部署先覆盖线上且吞SQL错误，失败无整套恢复

**P1 · 确认发布缺陷 · 历史遗留；新旧消费者衔接未完 · D35/D37 · 待处理/待验证。**

证据：[04-deploy.sh](../deploy/tencent/server/04-deploy.sh) L59（`install -m 644`）；[04-deploy.sh](../deploy/tencent/server/04-deploy.sh) L85（`< "${RELEASE_DIR}/schema.sql" || true`）；[04-deploy.sh](../deploy/aliyun/server/04-deploy.sh) L78（`schema-mysql.sql`）；[04-deploy.sh](../deploy/aliyun/server/04-deploy.sh) L134（`SERVER_PORT:-8081`）。

完整校验前覆盖JAR并清前端；SQL ||true仍报完成；失败不恢复全套版本/站点。阿里运行消费者仍旧schema名/端口/路径/探针，云脚本不会自动采用通用网关。腾讯ready修复只覆盖探针。

整改方向：统一三端/base/配置/迁移/探针契约，预检→兼容迁移→切换→就绪烟测，失败恢复完整版本。开发schema不是存量升级工具。

回归重点：缺端/SQL失败/nginx失败/ready失败阻断且旧站可用，三端版本一致，无虚假成功。

<a id="r30"></a>

#### R30 · root应用与证书失败HTTP降级未区分生产

**P1 · 确认防护缺口 · 历史遗留 · D21/D35 · 待处理/待验证。**

证据：[04-deploy.sh](../deploy/tencent/server/04-deploy.sh) L100（`User=root`）；[04-deploy.sh](../deploy/tencent/server/04-deploy.sh) L172（`将以 HTTP 模式运行`）；[nginx.prod.conf](../deploy/nginx/nginx.prod.conf) L16（`listen 80;`）。

云服务以root跑，证书失败继续HTTP；通用参考配置也仅HTTP。开发引导与生产无强制区分，真实令牌/领取凭证可能经HTTP，应用漏洞权限扩大。仓库不能证明实际安全组/TLS现状。

整改方向：受限用户与目录、回环后端，生产TLS失败阻断，HTTP仅挑战/重定向；检查续期与真实入口。

回归重点：非root正常运行且不能改系统目录；生产无证书不发布交易；HTTPS与后端隔离验证。

<a id="r31"></a>

#### R31 · Windows备份可能假成功或产生错误编码

**P1 · 确认实现风险（版本相关） · 历史遗留 · D35/D39 · 待处理/待验证。**

证据：[backup_mysql.ps1](../deploy/scripts/backup_mysql.ps1) L76（`& mysqldump @dumpArgs > $BackupSqlFile`）；[backup_mysql.ps1](../deploy/scripts/backup_mysql.ps1) L94（`$inputBytes = [System.IO.File]::ReadAllBytes`）。

未查原生命令LASTEXITCODE，仅判非空，部分输出后失败也可能压缩报成功。PowerShell5.1重定向可能生成UTF16而非可直接导入UTF8 SQL；整个备份ReadAllBytes还耗全量内存。未执行脚本，编码影响需目标版本验证。

整改方向：原生result-file/字节流导出，查退出码、流式压缩、临时文件成功后原子改名，隔离恢复。

回归重点：部分输出+非零不算成功；中文/emoji/二进制恢复一致；大备份内存有界，不清最后有效备份。

<a id="r32"></a>

#### R32 · 备份旧默认目标、参数拆分及恢复证据不足

**P2 · 确认治理缺口 · 历史遗留 · D21/D35/D39 · 待处理/待验证。**

证据：[backup_mysql.sh](../deploy/scripts/backup_mysql.sh) L13（`DB_NAME=`）；[backup_mysql.sh](../deploy/scripts/backup_mysql.sh) L48（`DUMP_CMD=`）；[backup_mysql.sh](../deploy/scripts/backup_mysql.sh) L75（`find "${BACKUP_DIR}"`）。

默认库/目录仍旧工程；Bash字符串命令会拆带空格参数，口令在进程参数；无异地加密/不可变副本与RPO/RTO演练证据。Bash已有pipefail，不应误报完全不检查管道失败。

整改方向：目标显式配置、参数数组/受控凭据文件、严格目录权限，隔离恢复和异地保留策略。

回归重点：特殊字符不拆参，错库拒绝，恢复后表/资产/预占一致，记录恢复耗时与时间点。

### 质量与契约

<a id="r33"></a>

#### R33 · 测试已增加但执行状态和持续回归不足

**P2 · 确认验证缺口 · 近期交付累计 · D39 · 待处理/待验证。**

证据：[Sprint1SecurityIntegrationTest.java](../shop-b2b2c-backend/src/test/java/com/shop/b2b2c/Sprint1SecurityIntegrationTest.java) L1252（`concurrentOrdersCannotOversellLastUnit`）；[SchemaMySqlTest.java](../shop-b2b2c-backend/src/test/java/com/shop/b2b2c/SchemaMySqlTest.java) L4（`schema.mysql.bin`）；[storedPoster.test.mjs](../shared/storedPoster.test.mjs) L8（`createStoredPosterPreview`）。

已有Java/H2并发、Node规则、MySQL基线、Linux部署隔离用例；旧报告“无自动化测试”已过时。本轮遵约未运行，工作区Sprint22也待执行。未发现仓库CI、浏览器E2E与完整MySQL交易并发矩阵；写了不等于通过。

整改方向：保持手动验证，交付环境/命令/结果矩阵；后续授权再接CI；行为变化补合法/非法/回滚用例及页面联动。

回归重点：跳过不算通过，结果可追溯；不以Sprint11/12验收阻塞独立修复。

<a id="r34"></a>

#### R34 · 业务失败常返回HTTP200，缺参/错误方法分类不全

**P2 · 确认契约缺口 · 历史遗留；Sprint22类型错误已单独处理 · D13/D24/D37 · 待处理/待验证。**

证据：[GlobalExceptionHandler.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java) L52（`handleBusinessException(`）；[GlobalExceptionHandler.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/GlobalExceptionHandler.java) L77（`@ExceptionHandler(Exception.class)`）。

业务429/503/404及通用code500常HTTP200，网关/外部客户端会误判。缺必需query参数/不支持方法未独立处理，可能走通用500。路径类型错误已有400，但不是全部参数验证已完成；管理请求层兼容业务code。

整改方向：盘点消费者后统一HTTP/业务码及兼容方案，覆盖缺参/方法/格式/未知异常；探针维持明确503。

回归重点：错误分类正确，敏感输入不回显；旧前端迁移兼容，监控可识别失败。

<a id="r35"></a>

#### R35 · BIGINT/Long和JS安全整数契约未统一

**P2 · 待专项验证 · 历史遗留；新分页/海报同受影响 · D13/D30/D32 · 待处理/待验证。**

证据：[TradeOrder.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/TradeOrder.java) L20（`private Long id;`）；[storedPoster.mjs](../shop-admin-vue/src/utils/storedPoster.mjs) L12（`Number.isSafeInteger(value)`）；[CardImportDTOs.java](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/dto/CardImportDTOs.java) L10（`record Item(Long cardId`）。

MySQL多表BIGINT UNSIGNED、Java有符号Long、浏览器数字解析，超过2^53-1先丢精度。海报拒绝不安全数字是正确防误操作，但后端不统一字符串输出时合法高ID仍不能用。未确认线上达到此范围。

整改方向：统一ID范围及字符串JSON，列表/表单/游标/路径全链路精确，兼容旧数字，不单改组件。

回归重点：2^53边界/Long最大/溢出/负数/小数均精确或明确拒绝，不误命中别的对象。

## 5. 商业功能完成度与剩余工作

下表为仓库实现状态的本次复核，不覆盖主台账历史验收。仅模型/标签/页面不算端到端实现；部分交付无人工反馈仍待验证。F17–F20仍是候选，未因本次诊断转为用户已确认需求。

业务证据：本轮已核对全部Controller映射和Service，未见支付网关、退款、投诉、账单生成、通知或地址收货服务；[PeerPayServiceImpl](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/impl/PeerPayServiceImpl.java)与[MerchantController](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/controller/MerchantController.java)保留内部落账/模拟进件；[schema](../shop-b2b2c-backend/src/main/resources/db/schema.sql)、[Complaint](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/Complaint.java)、[BillingStatement](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/entity/BillingStatement.java)可证明部分领域停留在模型。[PeerPayDTOs](../shop-b2b2c-backend/src/main/java/com/shop/b2b2c/dto/PeerPayDTOs.java)的地址字段未被交易实现保存，不能因DTO出现字段判定地址功能完成。

| 原编号 | 建议优先级 | 源码状态 | 业务域 | 当前缺口 | 最小闭环 |
| --- | --- | --- | --- | --- | --- |
| F01 | P0 | 未实现 | 真实支付与可信通知 | 无渠道网关/验签通知/主动查单闭环；Mock已移除，当前不是可真实付款的商城。 | 唯一支付尝试、商户/金额/币种核验、事件持久化、幂等履约、关单/补偿。 |
| F02 | P1 | 模拟实现 | 正式进件 | 本地SUB号、PASSED不是官方开通。 | 申请、渠道提交、待审/补件/签约、状态同步、敏感资料保护。 |
| F03 | P1 | 未实现 | 分账与结算 | sharingStatus和预计金额不是实际到账流水。 | 渠道分账/查询/失败重试、资金事件、结算与退款回退。 |
| F04 | P1/按模式 | 未实现 | 商户直收确认 | 有收款码配置，未见可靠对单核账。 | 先确认A/B经营模式；人工核账须权限/凭证/审计，不能展示二维码即到账。 |
| F05 | P1 | 未实现 | 退款退货 | REFUNDED状态不构成退款服务。 | 原付款人、部分/全额、额度CAS、渠道重试、库存/佣金回退、退货。 |
| F06 | P1 | 仅模型 | 投诉与仲裁 | Complaint实体/Mapper/表，无服务/接口/三端闭环。 | 买家独立凭证举证、商家答辩、裁决、退款联动、不可覆盖时间线。 |
| F07 | P1 | 未实现 | 实物地址与收货 | BuyerCreateRequest有recipientAddress但交易实现未保存，普通checkout无地址快照。 | 收件人/地址快照、运费、发货、确认收货/自动完成及售后期。 |
| F08 | P1 | 部分实现 | 关闭取消与补偿 | HELD超时释放已有；无统一所有意向关闭/真实渠道关单补偿。 | 复用R10–R13，定义状态转移和迟到支付。 |
| F09 | P1 | 仅模型 | 对账账单与差异 | BillingStatement只有模型/Mapper/表，无账本生成服务。 | 支付/退款/分账事件对账、日月账单、差异处理、重算/导出。 |
| F10 | P1/按收费策略 | 配置实现 | SaaS账单与缴费 | 五种服务费标签已统一，无扣费/停复服引擎。 | 账期、试用/豁免、缴费、提醒、审批与停复服规则先确认。 |
| F11 | P1 | 部分实现待验证 | 商品运营 | 已有编辑/改价/上下架及人工库存记录。 | 创建规则统一、违规审核、SKU增删/停售、图文与媒体、售罄策略。 |
| F12 | P1 | 部分实现待验证 | 卡密资产 | 已有去重/作废/批次；全量历史扫描仍在。 | 加密/唯一摘要、分配事件、库存核对、授权补发、异常修复；补发不得重复售卡。 |
| F13 | P1 | 部分实现待验证 | 账号与审计 | 已有会话退出/状态核验及部分资产记录。 | 员工授权、修改/找回密码、会话管理、敏感动作审计、强认证策略。 |
| F14 | P2 | 未闭环 | 入驻与广告 | 商户超管创建、广告只读；种子跳转注册页无对应商城路由。 | 申请/审核/补件、广告后台、安全目标链接与转化；复用正式进件。 |
| F15 | P2 | 未实现 | 通知与客服 | 未见短信/邮件/站内信发送服务。 | 支付/履约/售后事件通知、发送幂等/状态/重试、脱敏与退订规则。 |
| F16 | P2 | 录单基础 | 物流配送 | 仅公司/运单号，不含轨迹与完整配送模型。 | 轨迹/异常/时限/运费；拆包部分发货先业务确认。 |
| F17 | P2 | 待业务确认 | 会员订单中心与搜索 | 现有免登录单订单领取，不应被强制会员替换。 | 安全凭证恢复/账号绑定、订单归属、搜索排序。 |
| F18 | P3 | 待业务确认 | 购物车/跨店/营销/评价/发票 | 未见端到端领域实现。 | 先确认经营范围，再设计拆单、优惠分摊、开票红冲与评价治理。 |
| F19 | P3 | 待业务确认 | 批发采购供应链 | 没有阶梯价/起订量模型；页面假折扣R16必须先修。 | 确认后做MOQ、服务端报价、阶梯价、询报价及采购关系。 |
| F20 | P3 | 待业务确认 | 多币种 | 示意SVG不等于支付/订单币种支持。 | 明确跨境需求后做原币/汇率/精度快照、渠道及退款换汇。 |

## 6. 资金与库存专业评估

现有条件扣减与行锁是必要且应继续复用的资产底座：预占扣可售、付款核销不再重复扣、释放HELD后回加、卡密认领失败回滚。不要退回读改写库存，也不要再建第二套Redis权威库存。

但“单数据库事务原子”不等于“支付渠道、数据库、通知、对象存储跨系统原子”。应分别保障：

1. 订单/预占/卡密/本地事件在MySQL事务内满足不变量，校验条件SQL影响行数。
2. 渠道到账事件先可靠入库，事件唯一键包含渠道及必要商户维度；付款事实不被库存缺货回滚抹掉。
3. 履约/退款/分账独立幂等，事务消息或outbox及重试队列须避免提交成功但消息丢失；具体技术选型待实现设计，不要求为了本次审查引入消息中间件。
4. 全部金额用明确币种及精度，订单快照不覆盖历史成交；退款累计额度需原子限制，账本与D25展示统计分开。
5. 库存对账区分可售、HELD、已售、作废及损益；人工调整日志不是完整库存事件账本，批次成员也不是订单分配账本。

新账本/支付/退款能力应接入既有状态规则及D/F依赖，不各自维护一套订单金额和库存含义。真实渠道未接入前，R13属于接入门槛，不能宣称已形成现实资金损失。

## 7. 最近实现代码的复核结果

| 范围 | 静态复核结论 | 不能据此声称 |
| --- | --- | --- |
| Sprint9–12 编辑/手调/去重/批次 | 白名单SQL、版本摘要、独立事务、租户游标、关联再次校验与资产记录已有；R14/R17/R25/R27仍需处理 | 全量测试通过、加密/唯一指纹/完整审计已完成 |
| Sprint13–17 统计/店铺/商品聚合/状态 | 状态金额与资金账本已分开；店铺独立白名单、组合筛选、五状态兼容可见；R23/R24保留 | 真实MySQL性能已验证，服务费扣缴已生效 |
| Sprint18–20 构建/网关/就绪 | 阿里完整产物、网关路径和有界依赖探针已有；R06/R28–R32是剩余运行边界 | 所有云脚本统一、生产就绪/恢复可用 |
| Sprint21 样式 | 运行时Tailwind CDN移除，本地依赖/扫描配置及真实PostCSS用例存在 | 构建已执行、全部外链图片可离线 |
| 工作区Sprint22 | SQL列表不读poster；新GET按订单+会话商户限定且no-store；前端版本防乱序、PNG形状检查与图片错误处理；路径类型400不回显输入 | 测试/构建通过、所有查询大字段已移除、PNG字节有效性已校验 |

本次没有从这些新代码确认新的公开跨租户读取或库存读改写超卖路径。此表是有限静态结论，不是安全保证；已有未提交用例的执行状况不能从历史提交推定。

## 8. 未发现证据与专项核查边界

没有把未搜索到的功能当作外部系统必然没有。也没有仅凭Mapper存在BaseMapper方法就声称它可被匿名调用。当前已审阅SQL的业务参数主要采用绑定参数，未确认可由外部控制的字符串拼接SQL注入路径；未发现凭据HTML直出型v-html链路。但SSRF、XSS、依赖供应链漏洞、云权限、防火墙、历史泄露及线上配置仍需要专门验证，静态未发现不等于不存在。

待补证据包括：真实MySQL事务并发与EXPLAIN/容量基线，允许/拒绝来源的浏览器CORS联调，生产TLS与后端隔离，渠道签名/重放/乱序沙箱，备份隔离恢复，以及依赖/凭据扫描。具体安全工具和版本应在专项审查时查官方公告，不能用本报告推断当前CVE清单。

## 9. 本轮交付与验证状态

本轮仅新增诊断/执行文档并补导航与追踪，不实施上述修复；原D/F状态不因写报告变为已修复。代理自行细化的是R索引、风险分类、建议顺序与回归场景，不是用户已批准的权限/收费/交易政策。

静态交付核对：相对文件链接、R编号与todo对应、文件用途说明和Git空白格式检查。编译、所有测试、生产构建、MySQL/Nginx/云部署、依赖漏洞审计均**未执行，待用户按需要运行**。准确目录/命令及待新增测试见[todo验证入口](2026-11-04-remediation-todo.md#manual-validation)。报告本身不要求运行部署或删除数据库。
