<!--
文件：SPRINT_54_M4下单收款模式快照与平台代收就绪校验.md
用途：记录 M4-A（普通下单、买家代付、卖家推单三条建单入口按生效收款模式写订单快照，平台代收未就绪时拒绝建单）的实际范围、规则、验证命令与未验证项。
维护注意：
  1. 本轮只改“建单写什么快照”和“平台代收未就绪是否允许建单”，不改支付发起、回调验签、分账台账与退款；这些属于 M4-B/M6，无服务商凭据前不得宣称可用。
  2. 生效模式优先级固定为：环境应急关闭 > 平台代收总闸 > 商家配置；与 M1 完全一致，禁止在建单处另写一套。
  3. 平台代收不满足条件时必须 409 且不落订单，不得静默降级为商家直收，也不允许先建单再补绑定。
  4. 代理未运行 mvn/node/npm，验证标记待人工执行。
-->
# Sprint 54：下单收款模式快照与平台代收就绪校验（M4-A）

- 日期：2026-11-08
- 主台账：F02（支付渠道与分账）
- 设计来源：[2026-11-08-商家收款双轨与微信电商收付通进件设计](2026-11-08-商家收款双轨与微信电商收付通进件设计.md)
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：待人工验证（代理未执行 mvn）

## 1. 本轮实际子范围

| 项 | 已做 | 未做 |
| --- | --- | --- |
| 订单快照 | 普通下单、买家代付、卖家推单建单时按生效模式写 `trade_order.collection_mode` | 历史订单回填（旧单保持原值） |
| 模式解析 | 复用 M1 优先级：环境应急关闭 > 总闸 `enabled` > `merchant_store.collection_mode` | 按商家灰度名单、定时灰度 |
| 平台代收前置 | 建单前要求分账通道资质开关已启用，且目标渠道 `binding_status=BOUND`、`sub_mchid` 与 `settlement_account_id` 非空；不满足 409 且不落单 | 支付发起、回调验签、平台代收台账、分账/退分接线（M4-B，依赖服务商凭据） |
| 审计 | 失败原因直接返回给调用方；不改 M1 切换审计 | 下单失败审计表（M5 异常队列再评估） |
| 支付入口 | 保持现行 `PaymentGatewayService` 行为：`PLATFORM_COLLECT` 订单仍拒绝发起支付 | 平台收银台（M6） |

## 2. 规则

1. `MerchantCollectionModeService.modeForNewOrder(merchantId, payChannel)` 是建单唯一入口：
   - 商家直收：直接返回 `MERCHANT_DIRECT`，不查询渠道账户；
   - 平台代收：先校验 `PlatformCollectionService.enabled()`（分账资质/联调开关），未启用返回 409“平台代收分账通道未启用”；
   - 再按订单实际支付渠道查 `merchant_payment_account`，复用 M1 的绑定三要素校验，缺失返回 409“平台代收渠道未就绪：<原因>”。
2. 渠道取值：调用方已解析出 `payChannel` 时使用该值；为空时回落到店铺 `pay_channel`；非法渠道沿用 `PayChannel.normalize` 的 400。
3. 建单入口：
   - `OrderController#checkout`（普通直购）；
   - `PeerPayServiceImpl#createBuyerPeerPay`（买家代付）；
   - `PeerPayServiceImpl#createPushPayOrder`（卖家推单）。
   三者写值一致，禁止任何一处硬编码 `MERCHANT_DIRECT`。
4. 失败即不落单：快照解析在订单实体保存之前执行，抛 409 时订单、订单项、库存预占均不产生。
5. 已知边界：管理端允许把商家切到平台代收（M1 仅校验绑定），但资质开关未启用时该商家所有新订单会在下单时 409。这是“不降级”的直接结果，运营须在切模式前确认资质开关，待 M5 再评估是否加入切换前置提示。

## 3. 文件清单

| 文件 | 变化 |
| --- | --- |
| `shop-b2b2c-backend/.../service/MerchantCollectionModeService.java` | 新增 `modeForNewOrder`；注入 `PlatformCollectionService`；更新类注释 |
| `shop-b2b2c-backend/.../controller/OrderController.java` | 建单改用 `modeForNewOrder`，删除硬编码；注入服务，移除未用 `CollectionMode` 导入 |
| `shop-b2b2c-backend/.../service/impl/PeerPayServiceImpl.java` | 买家代付/卖家推单改用 `modeForNewOrder`；删除硬编码；更新注释 |
| `shop-b2b2c-backend/src/test/java/com/shop/b2b2c/MerchantCollectionModeServiceTest.java` | 构造器补依赖；新增 5 个下单解析用例 |
| `shop-b2b2c-backend/src/test/java/com/shop/b2b2c/OrderCollectionModeSnapshotIntegrationTest.java` | 新增 H2 集成用例：直收快照、总闸关闭回落、资质未启用 409、绑定未就绪 409、就绪快照平台代收、代付入口快照 |

## 4. 验证命令与预期（用户手动执行）

```bash
mvn -f shop-b2b2c-backend/pom.xml -Dtest=MerchantCollectionModeServiceTest,OrderCollectionModeSnapshotIntegrationTest test
mvn -f shop-b2b2c-backend/pom.xml test
```

预期：

- 单元用例：商家直收不触碰平台渠道依赖；总闸关闭时配置为平台代收的商家仍解析为直收；资质未启用/绑定未就绪均为 409 且不查询绑定；就绪时返回 `PLATFORM_COLLECT`。
- H2 集成用例：默认店铺快照 `MERCHANT_DIRECT`；总闸关闭时配置为平台代收仍快照直收；资质未启用与绑定未就绪均 409 且 `trade_order` 计数为 0；就绪时普通下单与买家代付均快照 `PLATFORM_COLLECT`。
- 以上不覆盖支付发起、回调、分账与真实渠道联调。

## 5. 未验证项与已知问题

1. **未执行验证**：代理未运行 `mvn`，所有结论为静态审阅结果。
2. **平台代收仍不可支付**：`PaymentGatewayService` 继续拒绝 `PLATFORM_COLLECT`，平台收银台需要服务商商户号、证书与回调配置，属于 M6；在此之前即使资质开关打开，平台代收订单也会在发起支付时失败。
3. **资质与模式切换不同步**：M1 允许在资质开关关闭时切平台代收，会阻断该商家下单；是否把资质校验并入切换前置条件，需与运营确认，本 Sprint 未改 M1 规则。
4. **无台账/分账**：支付成功后的平台代收台账、分账、退分未接线（M4-B），不得据本 Sprint 宣称资金可结算。
5. **旧订单不回填**：历史订单 `collection_mode` 保持原值，统计与对账须按快照口径，不得用商家当前模式回推。
