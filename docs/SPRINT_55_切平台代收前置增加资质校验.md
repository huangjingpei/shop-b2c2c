<!--
文件：SPRINT_55_切平台代收前置增加资质校验.md
用途：记录“切换到平台代收前置增加资质硬闸校验”（2026-11-08 问题汇总之问题 2，方案 A）的实际范围、规则、验证命令与未验证项。
维护注意：
  1. 本轮只改切换前置：在 M1 绑定三要素之前增加 PlatformCollectionService.enabled() 校验；不改下单解析
     （M4-A 已有资质校验）、不改支付发起、回调、台账与退款。
  2. 拒绝文案必须保留“分账通道未启用”关键词，与 M4-A 下单拒绝口径一致，前端直接展示后端 message。
  3. 代理未运行 mvn/node/npm，验证标记待人工执行。
-->
# Sprint 55：切平台代收前置增加资质校验（问题 2 方案 A）

- 日期：2026-11-09
- 主台账：F02（支付渠道与分账）
- 设计来源：[2026-11-08-商家收款双轨与微信电商收付通进件设计](2026-11-08-商家收款双轨与微信电商收付通进件设计.md)、[收款双轨 M1–M4A 问题与待决事项](2026-11-08-收款双轨M1-M4A问题与待决事项.md) 问题 2
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：代理已运行相关用例并通过（2026-11-09，`mvn -Dtest=MerchantCollectionModeServiceTest,MerchantCollectionModeIntegrationTest test` → 24 项，0 失败）；真实渠道联调与 MySQL 行为仍待人工验证

## 1. 背景与决策

M1 允许在资质硬闸（`shop.settlement.platform-collect-enabled`）关闭时把商家切到 `PLATFORM_COLLECT`，
而 M4-A 下单会因此 409，导致该商家新订单全部阻断（停售）。用户已确认按问题 2 方案 A 处理：
把“资质开关已启用”加入切换前置校验，未启用时禁止切平台代收，并返回明确原因。

## 2. 本轮实际子范围

| 项 | 已做 | 未做 |
| --- | --- | --- |
| 切换前置 | `switchMode` 切 `PLATFORM_COLLECT` 时先校验 `PlatformCollectionService.enabled()`，未启用 409“无法切换到平台代收：分账通道未启用（需分账资质与通道联调，shop.settlement.platform-collect-enabled=true）” | 环境硬闸的运行时变更接口（仍只能改环境变量重启） |
| 校验顺序 | 非法模式 400 → 店铺不存在 404 → 重复切换 409 → **资质硬闸 409** → 绑定三要素 409 → CAS 更新 | 调整既有顺序（保持不变） |
| 切回直收 | 不设任何前置，始终允许（回滚通道不被资质闸阻断） | — |
| 下单解析 | 不改：M4-A `modeForNewOrder` 的资质校验保持双保险 | — |

## 3. 规则

1. 资质硬闸校验先于绑定三要素：硬闸关闭时不再查询 `merchant_payment_account`，直接 409，不写审计、不改店铺。
2. 拒绝文案保留“分账通道未启用”关键词，与 M4-A 下单拒绝口径一致；管理端切换弹窗原样展示后端 message，无需前端改动。
3. 资质硬闸是进程级配置（`@Value` 注入），运行期不可变；测试通过反射置位，生产只能改环境变量重启。
4. 审计语义不变：只有切换成功才写 `merchant_collection_switch_audit`，所有 409 分支均不落审计。

## 4. 文件清单

| 文件 | 变化 |
| --- | --- |
| `shop-b2b2c-backend/.../service/MerchantCollectionModeService.java` | `switchMode` 增加资质硬闸校验；类注释第 2 条更新为“资质硬闸 + 三要素” |
| `shop-b2b2c-backend/src/test/java/com/shop/b2b2c/MerchantCollectionModeServiceTest.java` | 新增资质关闭 409 用例（不查账户/不更新/不审计）；既有切平台用例补 `platforms.enabled()=true` stub；注释同步 |
| `shop-b2b2c-backend/src/test/java/com/shop/b2b2c/MerchantCollectionModeIntegrationTest.java` | 上下文开启 `shop.settlement.platform-collect-enabled=true`；`@BeforeEach` 反射重置；新增资质关闭 409 集成用例（绑定齐全仍拒绝、店铺不变、无审计） |

## 5. 验证命令与预期（用户手动执行）

```bash
mvn -f shop-b2b2c-backend/pom.xml -Dtest=MerchantCollectionModeServiceTest,MerchantCollectionModeIntegrationTest test
mvn -f shop-b2b2c-backend/pom.xml test
```

预期：

- 单元用例：资质关闭时切平台代收 409 且消息含“分账通道未启用”，不查询绑定账户、不更新店铺、不写审计；资质开启后仍按绑定三要素逐项拒绝；三要素齐全时切换成功并写一条审计。
- H2 集成用例：资质硬闸关闭时即使绑定齐全也 409（`$.message` 含“分账通道未启用”），`collection_mode` 保持 `MERCHANT_DIRECT`、审计表 0 行；其余 M1 用例行为不变。
- 以上不覆盖真实渠道联调与 MySQL 行为。

## 6. 未验证项与已知问题

1. **验证范围**：已运行本 Sprint 相关用例与后端全量测试并通过；未覆盖真实渠道联调、真实 MySQL 结构与并发。
2. **平台代收仍不可支付**：本 Sprint 只收紧切换入口；`PaymentGatewayService` 对 `PLATFORM_COLLECT` 订单仍拒绝发起支付，平台收银台配置与适配器由问题 1（Sprint 57）补齐骨架，真实联调属 M6。
3. **已切商家不受追溯**：本规则只约束新切换；若此前已存在“资质关闭但已切平台代收”的商家数据，其下单仍按 M4-A 规则 409，需运营切回直收或开启资质。
4. **管理端文案**：切换弹窗未就绪原因由后端 message 展示；如需更醒目的运营提示（如资质闸状态徽标），属 M3 页面增强，未在本轮范围。

## 7. 运行验证时发现并修复的既有用例缺陷（非业务逻辑）

此前 M1–M4-A 从未实际执行过 `mvn`，首次运行暴露出测试代码自身的缺陷，均只改测试，业务行为未变：

| 缺陷 | 位置 | 修复 |
| --- | --- | --- |
| 8 处 `mvc.perform(...)` 链少一个闭合括号，无法编译 | `MerchantCollectionModeIntegrationTest` | 补齐 `)` |
| `MerchantController` 构造调用缺 M2 新增的 `collectionModeService` 参数，共 8 处 | `MerchantStoreUpdateTest`、`ManagementSecurityTest`、`ServiceFeeConfigTest` | 插入 `mock(MerchantCollectionModeService.class)` |
| 集成测试未 stub `AuthLoginLimiter` 的 Redis 脚本（mock 返回 null），登录 503 | `MerchantCollectionModeIntegrationTest`、`SettlementAccountPaymentSyncIntegrationTest` | `@BeforeEach` stub 返回 1L，与 `Sprint1SecurityIntegrationTest` 一致 |
| `SchemaMigrationContractTest` 用全文 `contains("trade_order")` 断言，命中 V23 文件头注释，导致 M1 交付的用例必然失败 | `SchemaMigrationContractTest` | 负向断言先剥离 `--` 行注释与块注释，只检查可执行语句 |

修复后后端全量 `1169` 项 0 失败、0 错误、1 跳过。
