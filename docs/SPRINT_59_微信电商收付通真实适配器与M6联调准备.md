<!--
文件：SPRINT_59_微信电商收付通真实适配器与M6联调准备.md
用途：M6 联调准备交付说明——真实微信电商收付通平台收银台适配器、平台代收回调独立入口与台账接线、
     退款/退分报文 sub_mchid 接线，以及凭据到位后的 M6 联调执行清单。
维护注意：
  1. 本文件记录 Sprint 59 实际代码范围与验证边界；未接真实资金，不得宣称“联调通过”。
  2. 2026-11-10 用户授权代理执行 mvn：定向 70 项与全量 1196 项均通过（0 失败，1 跳过）；
     真实微信支付渠道联调、真实 MySQL、回调公网可达性均未验证，单测通过不代表生产可用。
  3. Sprint 57/58 文档中的“占位 mock”描述为历史记录；MockPlatformCashierChannelAdapter 已在本 Sprint 删除。
-->
# Sprint 59：微信电商收付通真实适配器与 M6 联调准备

- 日期：2026-11-10
- 执行方：代码代理（实现、用例、静态审阅）；mvn 编译与测试经用户授权（2026-11-10 会话内“好的，执行吧”授权延续）由代理执行
- 验证状态：H2/单测已实测通过（定向 70 项 + 全量 1196 项，0 失败，1 跳过）；**真实微信渠道联调未开始**（等待真实服务商凭据）
- 关联台账：F02/F03；设计：[收款双轨设计](2026-11-08-商家收款双轨与微信电商收付通进件设计.md) 3.2/D3/D4；前置：[SPRINT_58](SPRINT_58_平台代收退款先退分后退款骨架.md)

## 1. 范围与不做事项

本轮把平台收银台从“占位骨架”推进到“凭据到位即可联调”：

- 真实 `WeChatPlatformCashierChannelAdapter`（电商收付通服务商模式）：下单/回调验签解密/查单/关单/退款/退款查单/退分/退分查单八个契约方法全部落地；
- 平台代收回调独立入口 `POST /api/v1/payment/callback/platform`：验签→订单快照校验→二级商户号核对→幂等履约→生成结算台账（D3 只建台账不分账）；
- 平台代收发起支付接线：补订单状态/支付截止校验，命令携带商家二级商户号；
- 平台退款/退分报文 `sub_mchid` 接线（分账台账接收方 / 当前有效绑定）。

不做（M5/M6 真实联调）：真实凭据联调、分账发起（订单完成+售后期后）、分账失败人工队列、对账任务、平台订单的网关查单/关单路由、灰度名单。

## 2. 关键设计定案（本轮新增约定）

| 编号 | 约定 | 理由 |
| --- | --- | --- |
| M6-1 | 删除 `MockPlatformCashierChannelAdapter`；registry 每渠道只注册真实适配器 | 真实适配器 `enabled()`=配置开关，registry 三态 409（未接入/未配置/未联调）语义不变，mock 已成死代码；同 channel 键唯一，路由确定 |
| M6-2 | 分账发起必须以 `orderSn` 为商户分账单号 `out_order_no` | 退分（profitsharing returnorders）凭 `out_order_no` 关联原分账单；约定先行，未来分账实现不得偏离 |
| M6-3 | 渠道退款/退分报文 `sub_mchid` = 分账接收方二级商户号：source=ORDER 取台账接收方（与退分同源），其余取商家当前有效 `BOUND` 接收方；发起商户号固定 `sp_mchid` | 电商收付通报文必填 sub_mchid；`RefundCommand.merchantChannelId` 语义明确为“二级商户号”，与商家直收账户无关 |
| M6-4 | 台账生成失败不阻断回调 ACK：履约成功后 `prepareSettlement` 异常仅记审计 reason + ERROR 日志 | 支付已履约不可回滚；渠道对持久性失败重试 15 次后也停止，毒消息循环无收益；由 M5 对账按“事件 SUCCESS 且无台账”兜底补建 |
| M6-5 | 平台代收发起与商家直收同一前置校验（UNPAID 状态、支付截止、渠道切换 CAS） | Sprint 57 骨架在模式分流前未做该校验，属潜伏缺陷，本轮修复 |
| M6-6 | `payment.platform.*` 新增 `sp-appid`（服务商模式下单必填）、`notify-base-url`、`api-base`；`configured()` 纳入 AppID | 凭据完整性以电商收付通下单最小集合为准 |

## 3. 改动清单

| 文件 | 变化 |
| --- | --- |
| `service/payment/WeChatPlatformCashierChannelAdapter.java` | **新增真实适配器**：Native 下单（sp_appid+sp_mchid+sub_mchid）、平台公钥验签+AES-GCM 解密回调（sp_mchid 匹配校验、sub_mchid 入 channelMerchantId）、查单（404=NOT_EXIST）、关单（404 视为已关）、`/v3/ecommerce/refunds/*` 退款与查单、`/v3/ecommerce/profitsharing/returnorders*` 退分与查单；签名与直收同口径（RSA-SHA256、±300s 防重放）；未启用直接调用 503、缺 sub_mchid 409、缺 notify-base-url 503 |
| `service/payment/MockPlatformCashierChannelAdapter.java` | **删除**（M6-1） |
| `service/payment/PlatformCashierChannelAdapter.java` | 契约注释更新：真实实现已存在、merchantChannelId=sub_mchid 语义、out_order_no=orderSn 约定 |
| `service/payment/PlatformCashierChannelRegistry.java` | 注释更新；“未配置”文案补 AppID |
| `service/payment/PlatformCashierProperties.java` | 新增 `spAppid`/`notifyBaseUrl`/`apiBase`；`configured()` 纳入 AppID |
| `application.yml` | `payment.platform` 补 `sp-appid`/`notify-base-url`/`api-base` 三个环境变量映射 |
| `service/payment/PaymentGatewayService.java` | `createPayment` 平台分支补状态/截止校验并携带二级商户号；新增 `handlePlatformCallback`（验签后校验 PLATFORM_COLLECT 快照、接收方比对、幂等履约、台账尽力生成）+ 依赖 `PlatformCollectionService` |
| `controller/PaymentGatewayController.java` | 新增 `POST /callback/platform` 独立入口（ACK/FAIL 按微信协议） |
| `service/payment/RefundService.java` | 平台退款/查单命令携带 sub_mchid（台账接收方或当前有效绑定）；`returnSplitBeforeRefund` 返回接收方号复用 |
| `test/.../service/payment/WeChatPlatformCashierChannelAdapterTest.java` | **新增 16 例**：回调真实 RSA 验签/AES-GCM 解密/sp_mchid 匹配/篡改/过期/非成功事件；下单/退款/退分报文拼装与 Authorization 头断言（MockRestServiceServer）；404→NOT_EXIST、网络错误→502、缺 sub_mchid→409、缺 notify-base-url→503、禁用→503 |
| `test/PaymentChannelSecurityTest.java` | mock 引用全部移除；registry 三态用例改用真实适配器并补 enabled=true 放行断言；新增平台回调 5 例（履约+台账、直收订单 409、sub_mchid 不匹配 401、台账失败仍 ACK、收银台未就绪 409） |
| `test/RefundServiceTest.java` | 平台用例补 `findEffectiveBound` 桩与 merchantChannelId=sub_mchid 断言；新增“接收方解绑时重复付款补偿 409”用例 |

## 4. 验证命令与结果（2026-11-10 用户授权代理执行）

```bash
mvn -f shop-b2b2c-backend/pom.xml "-Dtest=WeChatPlatformCashierChannelAdapterTest,PaymentChannelSecurityTest,RefundServiceTest,PlatformCollectionServiceTest" test
mvn -f shop-b2b2c-backend/pom.xml test
```

实测结果：

- 定向：70 项全部通过，0 失败 0 跳过。
- 全量：1196 项，0 失败 0 错误，1 跳过，BUILD SUCCESS（较 Sprint 58 基线 1174 +22：新适配器 16 例 + 平台回调网关 5 例 + 退款 1 例）。
- 中途发现并修复：适配器双构造器导致 Spring 上下文加载失败（`No default constructor`），已用显式 `@Autowired` 指定生产构造器，`MerchantCollectionModeIntegrationTest`/`OrderCollectionModeSnapshotIntegrationTest` 复测通过。

## 5. M6 联调准备清单（凭据到位后执行）

### 5.1 凭据与环境变量映射（只进环境变量/部署密钥，禁止入库入日志入仓库）

| 环境变量 | 来源（微信侧动作） | 对应配置 |
| --- | --- | --- |
| `PLATFORM_CASHIER_SP_MCHID` | 微信支付服务商平台 → 服务商商户号 | `payment.platform.sp-mchid` |
| `PLATFORM_CASHIER_SP_APPID` | 服务商 AppID（服务商平台 → 产品中心） | `payment.platform.sp-appid` |
| `PLATFORM_CASHIER_SERIAL_NO` | API 证书序列号（API 安全） | `payment.platform.serial-no` |
| `PLATFORM_CASHIER_PRIVATE_KEY` | API 商户私钥 PEM（apiclient_key.pem 全文） | `payment.platform.private-key` |
| `PLATFORM_CASHIER_API_V3_KEY` | APIv3 密钥（32 字节，API 安全设置） | `payment.platform.api-v3-key` |
| `PLATFORM_CASHIER_PLATFORM_PUBLIC_KEY` | 微信支付平台公钥（API 安全 → 平台公钥） | `payment.platform.platform-public-key` |
| `PLATFORM_CASHIER_NOTIFY_BASE_URL` | 部署公网域名（通常与 `PAY_NOTIFY_BASE_URL` 同域） | `payment.platform.notify-base-url` |
| `PLATFORM_CASHIER_API_BASE` | 默认生产 `https://api.mch.weixin.qq.com`，仅微信指定联调环境覆盖 | `payment.platform.api-base` |
| `PLATFORM_CASHIER_ENABLED` | **联调全部用例通过后才置 true** | `payment.platform.enabled` |

### 5.2 微信侧配置动作

1. 服务商平台开通“电商收付通”产品，确认商户号具备分账权限；
2. API 安全：申请 API 证书（取得序列号）、设置 APIv3 密钥、下载/复制微信支付平台公钥；
3. 将 `https://<域名>/api/v1/payment/callback/platform` 配置为电商收付通支付回调地址（路径对应 `payment.platform.notify-path`）；
4. 商家二级商户进件（D1 平台代进件）取得 `sub_mchid`，回写 `merchant_payment_account` 并确认 `binding_status=BOUND`；
5. 资质硬闸 `SHOP_SETTLEMENT_PLATFORM_COLLECT_ENABLED=true` 与 DB 运营总闸 `platform_collection_config.enabled=1`。

### 5.3 联调执行步骤（建议顺序）

1. 凭据注入测试环境，`enabled` 仍 `false` 启动 → 发起平台代收支付应 409“已配置但未完成通道联调”；
2. `enabled=true` 后：平台代收下单 → Native code_url 出码 → 真实扫码支付 0.01 元；
3. 观察 `/callback/platform`：验签解密 → 履约 → `platform_collection_settlement`/`split_detail` 台账生成（**只建台账，不应发生真实分账**）；
4. 重复回调验证幂等（同一 transaction_id 重放不重复履约、台账不重复）；
5. 商家直收订单回调误投平台入口 → 409“非平台代收订单”；伪造 sub_mchid → 401；
6. 退款：平台代收订单发起退款 → 先退分（`out_order_no=orderSn`、`out_return_no=refundNo`）→ 后退款，渠道侧核对金额守恒；
7. 退款 PROCESSING 时 `syncRefund` 查单收敛；
8. 关单/查单路径抽查（网关层平台订单查单路由仍属 M6 剩余项，本轮仅在适配器层就绪）。

### 5.4 联调验收用例（全部通过才允许生产打开开关）

- [ ] 真实下单出码、支付成功回调履约、台账金额=实收−留成
- [ ] 回调重放/乱序幂等；伪造签名/篡改报文/过期时间戳被拒
- [ ] 退款全链路金额守恒（退分额=退款额−服务费冲正）
- [ ] 台账失败场景审计留痕（`payment_callback_audit.reason` 含“结算台账生成失败”）
- [ ] 灰度口径：按商家名单切 PLATFORM_COLLECT，可单商家回滚（沿 Q09-A/D7）

## 6. 未验证项与风险

1. 真实微信支付报文字段以联调返回为准；适配器单测基于公开文档契约，若电商收付通实际拓扑不同（如退分 direction），仅需调整适配器内部报文映射，契约不变。
2. 分账发起未实现（D3：订单完成+售后期后，属 M5/M6）；`split_detail` 当前由台账生成时按留成口径预写。
3. 平台代收订单的网关查单/关单/超时关单路由未接线（适配器方法已就绪），联调时按需补。
4. H2 单测不替代真实 MySQL 行锁与渠道联调；回调公网可达性、HTTPS 证书链未验证。
