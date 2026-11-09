<!--
文件：SPRINT_57_平台收银台骨架与支付拒绝细化.md
用途：记录“平台收银台配置键 + 适配器接口 + mock 骨架 + 支付拒绝原因细化”（2026-11-08 问题汇总之问题 1，方案 A）及问题 4 双闸口径固化的实际范围、规则、验证命令与未验证项。
维护注意：
  1. 本轮只建立配置契约与骨架入口：不接真实资金、不产生任何渠道调用；mock 适配器永远 enabled=false，
     不得被当成联调通过，也不得返回伪造成功。
  2. 支付拒绝文案分两级：“平台收银台未配置”（凭据缺失）与“已配置但未完成通道联调”（payment.platform.enabled=false），
     前端/排障依赖关键词，修改须同步用例。
  3. 代理未运行 mvn/node/npm，验证标记待人工执行。
-->
# Sprint 57：平台收银台骨架与支付拒绝细化（问题 1 方案 A + 问题 4 口径）

- 日期：2026-11-09
- 主台账：F02（支付渠道与分账）
- 设计来源：[2026-11-08-商家收款双轨与微信电商收付通进件设计](2026-11-08-商家收款双轨与微信电商收付通进件设计.md)、[收款双轨 M1–M4A 问题与待决事项](2026-11-08-收款双轨M1-M4A问题与待决事项.md) 问题 1/问题 4
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：代理已运行相关用例并通过（2026-11-09，`mvn -Dtest=PaymentChannelSecurityTest test` → 18 项，0 失败；后端全量 1169 项 0 失败 1 跳过）；真实渠道联调仍待人工验证

## 1. 背景与决策

平台代收此前“可配置、可切模式（Sprint 55 已收紧），但无法支付”：`PaymentGatewayService` 对
`PLATFORM_COLLECT` 订单只给阶段一通用拒绝文案，仓库没有平台级服务商凭据配置。用户已确认按
问题 1 方案 A 处理：先补“平台收银台配置键 + 适配器接口 + mock 实现 + 支付拒绝原因细化”，
不接真实资金；等凭据后只换适配器实现。同时按问题 4 默认建议把双闸口径固化进设计文档。

## 2. 本轮实际子范围

| 项 | 已做 | 未做 |
| --- | --- | --- |
| 配置契约 | `payment.platform.*`：enabled/sp-mchid/serial-no/api-v3-key/private-key/platform-public-key/notify-path，全部环境变量默认空=未配置；`PlatformCashierProperties.configured()` 判定凭据是否配齐 | 真实凭据下发与密钥托管（部署侧） |
| 适配器骨架 | `PlatformCashierChannelAdapter` 接口（下单/回调解析/查单/关单，签名对齐 PaymentChannelAdapter）+ `MockPlatformCashierChannelAdapter`（enabled 固定 false，方法全抛 501）+ `PlatformCashierChannelRegistry`（路由+细化拒绝） | 真实微信电商收付通实现（M6）、平台代收 refund 契约（M4-B） |
| 支付发起 | `PaymentGatewayService.createPayment` 对 PLATFORM_COLLECT 改走骨架入口：凭据缺失 409“平台收银台未配置：缺少平台服务商商户号/证书/APIv3 密钥…”；配齐未联调 409“平台收银台已配置但未完成通道联调”；不再触碰商家直收适配器 | 平台收银台真实下单返回支付参数（M6） |
| 支付回调 | 平台代收订单在商家直收回调入口 409“平台代收订单支付回调须由平台收银台独立入口处理”，防止渠道归属错配 | 平台回调独立入口 controller 与平台商户号验签（M6） |
| 双闸口径 | 设计文档 4.1 固化：环境变量=资质硬闸（仅真实联调后可开）、数据库总闸=运营灰度、管理端只运营数据库总闸且硬闸只读呈现；支付侧 `payment.platform.*` 为第三层独立配置 | — |

## 3. 规则

1. 凭据只来自环境变量，默认空=未配置，仓库不落任何密钥；`configured()=true` 不代表联调完成。
2. mock 适配器 `enabled()` 固定 false，任何直接调用抛 501，不得返回伪造支付参数/成功回调。
3. 拒绝语义三级区分：渠道未接入 → “平台收银台未接入渠道”；凭据缺失 → “平台收银台未配置”；凭据配齐但 `enabled=false` → “已配置但未完成通道联调”。全部 409，不回退商家直收。
4. 平台代收退款仍由 `RefundTransactionService` 阶段一守卫拒绝（未在本轮范围，随 M4-B“先退分后退款”一并处理）。
5. 商家直收链路（下单/回调/查单/关单/退款）行为不变；`CollectionMode.requireStageOne` 保留供退款守卫使用。

## 4. 文件清单

| 文件 | 变化 |
| --- | --- |
| `service/payment/PlatformCashierProperties.java` | 新增：`payment.platform` 配置契约与 configured() 判定 |
| `service/payment/PlatformCashierChannelAdapter.java` | 新增：平台收银台适配接口（骨架） |
| `service/payment/MockPlatformCashierChannelAdapter.java` | 新增：占位实现，enabled=false、全方法 501 |
| `service/payment/PlatformCashierChannelRegistry.java` | 新增：路由 + 未接入/未配置/未联调细化 409 |
| `service/payment/PaymentGatewayService.java` | 新增 registry 依赖；PLATFORM_COLLECT 发起支付走骨架入口；回调入口细化拒绝；类注释更新 |
| `application.yml` | 新增 `payment.platform` 段（环境变量占位、默认空/false） |
| `PaymentChannelSecurityTest.java` | 构造调用补第 7 参；平台代收用例改真实 registry 断言“未配置”；新增未配置/未联调/未接入/占位拒绝用例 |
| `2026-11-08-商家收款双轨与微信电商收付通进件设计.md` | 4.1 双闸口径固化（问题 4）；4.2 切换前置补资质硬闸（Sprint 55） |

## 5. 验证命令与预期（用户手动执行）

```bash
mvn -f shop-b2b2c-backend/pom.xml -Dtest=PaymentChannelSecurityTest test
mvn -f shop-b2b2c-backend/pom.xml test
```

预期：

- 平台代收订单发起支付：默认配置下 409 且消息含“平台收银台未配置”；商家直收适配器零交互。
- registry 用例：凭据缺失“未配置”、凭据配齐+enabled=false“已配置但未完成通道联调”、ALIPAY“未接入渠道”、占位适配器直接调用抛 501。
- 既有 T32 渠道安全用例（验签/归属/关单/审计）全部保持通过。
- 以上不覆盖真实渠道联调。

## 6. 未验证项与已知问题

1. **验证范围**：已运行本 Sprint 相关用例与后端全量测试并通过；未覆盖真实渠道联调。
2. **平台代收仍不可支付**：本轮是骨架；真实下单/回调/台账/分账/退分属 M4-B/M6，需平台服务商凭据。
3. **M3 管理端未呈现收银台配置状态**：`payment.platform.*` 是进程级配置，管理端只读标识本轮未加；如需运营可见性属页面增强。
4. **支付宝平台收银台**：骨架只注册 WECHAT 占位；支付宝直付通视渠道选型再接，registry 已按“未接入渠道”拒绝。
5. **平台代收退款**：入口仍被阶段一守卫 409，文案未细化（M4-B 处理）。
