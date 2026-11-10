<!--
文件：SPRINT_58_平台代收退款先退分后退款骨架.md
用途：M4-B 平台代收退款交付说明（先退分后退款编排骨架 + 平台收银台退款/退分契约 + 拒绝原因细化），
     供用户人工验证与后续 M5/M6 决策参考。
维护注意：
  1. 本文件记录 M4-B 实际代码范围与验证边界；问题解决后须回写设计文档与主台账 F02/F03。
  2. 2026-11-10 用户授权代理执行 mvn：定向 48 项与全量 1174 项均通过（0 失败，1 跳过）；
     真实 MySQL 行锁与微信渠道联调仍未验证，不得把 H2/单测通过当成生产可用。
  3. 平台收银台渠道适配器仍为占位 mock（enabled=false，全方法 501）；真实微信电商收付通联调属 M6，
     不得把本骨架当成“平台代收退款已可用”。
-->
# Sprint 58：平台代收退款（先退分后退款）骨架与拒绝细化（M4-B）

- 日期：2026-11-10
- 执行方：代码代理（实现、用例、静态审阅）；mvn 编译与测试经用户授权由代理执行
- 验证状态：H2/单测已实测通过（定向 48 项 + 全量 1174 项，0 失败，1 跳过）；真实 MySQL 与渠道联调待验证
- 关联台账：F02/F03；设计： [收款双轨设计](2026-11-08-商家收款双轨与微信电商收付通进件设计.md) D4（退款顺序：先退分、后退款，金额守恒、同一退款幂等）；路线图见 [M1–M4A 问题与待决事项](2026-11-08-收款双轨M1-M4A问题与待决事项.md) 第 5 节

## 1. 范围与不做事项

本轮只交付“平台代收退款”的契约与编排骨架：

- 平台收银台适配器补退款/退款查单/退分/退分查单契约；占位 mock 全部 501；
- `RefundService` 按订单快照 `collection_mode` 路由：PLATFORM_COLLECT 走平台收银台编排，绝不回退商家直收渠道、不读取商家直收账户；
- 编排顺序固定“先退分（渠道分账回退）→登记本地 split_reverse 账本→后退款（平台服务商商户号原路退回买家）”；
- 平台收银台未配置/已配置未联调时 409 细化文案（退款语义），退款记录落 FAILED 并释放预占。

不做（留给 M5/M6）：真实渠道退分/退款调用、退分 PROCESSING 的重试与人工队列、分账失败补偿、平台代收支付发起/回调/台账生成。

## 2. 资金语义（D4 落地）

| 事项 | 口径 |
| --- | --- |
| 退分金额 | 退分额 = 退款额 − 服务费冲正（`fee_refunded_amount`，按比例快照计算）；平台留成部分由平台服务商商户号随退款一并退回，金额守恒 |
| 退分上限 | 本地账本 `recordSplitReverse` 以条件 UPDATE 校验 `reversed_amount + 本次 ≤ split_amount`，超额 409 |
| 退分幂等 | 渠道退分以 `refund_no` 为幂等键（微信 out_return_no）；本地账本 `split_detail_id + refund_id` 唯一约束，重试不会产生二次退分 |
| 顺序守卫 | 渠道退分非 SUCCESS（含 PROCESSING/FAILED）一律中止退款发起，退款记录 FAILED，待退分确认后重试 |
| 重复付款补偿 | 资金未分账，跳过退分，直接平台收银台退款 |
| 服务费冲正 | 沿用既有 `finalizeSuccess` → `refundServiceFee`，仅退款成功后冲正，不回溯费率 |

## 3. 改动清单

| 文件 | 变化 |
| --- | --- |
| `dto/PaymentChannelDTOs.java` | 新增 `SplitReturnCommand`（refundNo 兼作渠道退分幂等键）与 `SplitReturnResult`；文件头补 M4-B 约定 |
| `service/payment/PlatformCashierChannelAdapter.java` | 新增 `refundPayment`/`queryRefund`/`returnSplit`/`querySplitReturn`；注释固化“退分先于退款、merchantChannelId 由适配器按 sp-mchid 自取” |
| `service/payment/MockPlatformCashierChannelAdapter.java` | 四个新方法全部 501“平台收银台未完成通道联调，暂不能办理平台代收退款/退分” |
| `service/payment/PlatformCashierChannelRegistry.java` | 新增 `requireForRefund`：与发起支付同一检查链，文案细化到退款语义（“平台代收订单暂不能退款（先退分后退款）”） |
| `service/payment/RefundService.java` | `executeChannelRefund` 按 `collectionMode` 路由；新增 `executePlatformCollectRefund`/`returnSplitBeforeRefund`/`platformRefundCommandOf`；`syncRefund` 平台分支走平台收银台查单；渠道结果落迁提取为 `applyChannelResult` 两路共用 |
| `service/payment/RefundTransactionService.java` | 入口守卫由 `requireStageOne` 改为 `requireKnown`（本层只校验已知模式，渠道路由上移到 RefundService） |
| `common/CollectionMode.java` | 删除已无调用的 `requireStageOne`（语义由支付/退款入口路由替代） |
| `test/RefundServiceTest.java` | 构造器补 3 个编排依赖 mock；新增 5 个 M4-B 用例（路由守卫、先退分后退款顺序与金额守恒、退分未完成中止、重复付款补偿跳过退分、平台查单路由） |
| `test/PaymentChannelSecurityTest.java` | requireStageOne 用例改 requireKnown 语义；registry 用例补 `requireForRefund` 三态细化与 mock 四方法 501 |

## 4. 关键行为

- 商家直收退款路径零变化：仍由商家自己的渠道账户原路退款。
- 平台代收退款在当前生产配置下必然在第一行 `requireForRefund` 409（凭据未配置），失败原因写入退款记录并释放预占——与直收“账户缺失 409→FAILED”同一语义，不留永久 PROCESSING 预占。
- 未来凭据/联调就绪后（M6），同一编排方法自然走通：退分→账本→退款；退款 PROCESSING 仍由 `syncRefund` 向平台收银台查单确认。

## 5. 验证命令与结果（2026-11-10 用户授权代理执行）

```bash
mvn -f shop-b2b2c-backend/pom.xml "-Dtest=RefundServiceTest,PaymentChannelSecurityTest,PlatformCollectionServiceTest" test
mvn -f shop-b2b2c-backend/pom.xml test
```

实测结果：

- 定向：48 项全部通过（RefundServiceTest 21 / PaymentChannelSecurityTest 18 / PlatformCollectionServiceTest 9），0 失败 0 跳过。
- 全量：1174 项，0 失败 0 错误，1 跳过，BUILD SUCCESS（较基线 1169 多出 5 项即本 Sprint 新增 M4-B 编排用例）。
- `platformCollectRefundReturnsSplitBeforeRefunding` 已验证 returnSplit 先于 refundPayment、退分额 97=100−3、账本登记参数正确；
  `platformCollectRefundStopsWhenSplitReturnNotSuccessful` 已验证退分 PROCESSING 时退款不落渠道、账本不登记、记录 FAILED；
  `platformCollectRefundRoutesToPlatformCashierAndNeverTouchesMerchantChannel` 已验证 409 细化且直收注册表/账户零调用。

## 6. 未验证项与风险

1. H2 单测不替代真实 MySQL 行锁与微信渠道联调；真实退分/退款属 M6。
2. 平台代收订单当前无法支付成功（M4-A 支付入口 409），生产环境尚不存在可退的平台代收支付单；本骨架的资金路径只经单测验证。
3. 退分 PROCESSING 的重试/人工队列未实现（D5 属 M5）；当前按“FAILED 后可重试、渠道幂等键防二次退分”处理。
