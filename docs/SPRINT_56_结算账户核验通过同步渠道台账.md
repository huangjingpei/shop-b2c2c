<!--
文件：SPRINT_56_结算账户核验通过同步渠道台账.md
用途：记录“结算账户核验通过时同步写 merchant_payment_account”（2026-11-08 问题汇总之问题 3，方案 A）的实际范围、规则、验证命令与未验证项。
维护注意：
  1. 本轮只建立“核验通过 -> 台账行”的同步链路；渠道字段（sub_mchid/binding_status/channel_merchant_id 等）
     仍只允许真实渠道回调/查询结果推进，本 Sprint 绝不写 BOUND/PASSED。
  2. sub_mchid 为 NOT NULL 列，官方未分配前写空串；切换（Sprint 55）与下单（M4-A）校验按 isBlank 如实拒绝。
  3. 代理未运行 mvn/node/npm，验证标记待人工执行。
-->
# Sprint 56：结算账户核验通过同步渠道台账（问题 3 方案 A）

- 日期：2026-11-09
- 主台账：F02（支付渠道与分账）
- 设计来源：[2026-11-08-商家收款双轨与微信电商收付通进件设计](2026-11-08-商家收款双轨与微信电商收付通进件设计.md)、[收款双轨 M1–M4A 问题与待决事项](2026-11-08-收款双轨M1-M4A问题与待决事项.md) 问题 3
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：待人工验证（代理未执行 mvn）

## 1. 背景与决策

`merchant_payment_account` 此前没有任何业务写入路径，切平台代收与 M3/M2 页面只能读到空表或测试夹具。
用户已确认按问题 3 方案 A 处理：入驻后的结算账户核验通过时同步写一条 `merchant_payment_account`
（渠道取店铺 `pay_channel`，结算账户 ID 引用已核验账户），**状态按真实值，不伪造 PASSED**。

## 2. 本轮实际子范围

| 项 | 已做 | 未做 |
| --- | --- | --- |
| 同步触发 | `approveVerified` 公共段：人工核验（MANUAL）与打款验证（PAYOUT）通过、且账户成为默认时同步；O5 变更账户核验通过同样同步 | 非默认账户核验通过不同步（不影响台账结算引用） |
| 新建台账行 | `channel`=店铺 `pay_channel`（空回落 WECHAT）、`settlement_account_id`=已核验账户、`apply_status=AUDITING`、`binding_status=UNBOUND`、`sub_mchid=''`、户名/脱敏账号/银行名同步 | 真实渠道进件提交与子商户号回写（属 F02 剩余/M6） |
| 已有台账行 | 仅更新 `settlement_account_id` 指向最新默认核验账户；`sub_mchid`/`binding_status`/`apply_status` 等渠道字段保持原值 | 渠道字段回写（仍只允许真实渠道结果推进） |
| 并发 | 命中 `uk_merchant_channel` 唯一冲突时重查并只补结算引用 | 真实 MySQL 并发验证（待人工） |

## 3. 规则

1. 只写真实值：`AUDITING`（渠道进件未提交）、`UNBOUND`（渠道侧未绑定）、`sub_mchid` 空串（官方未分配，NOT NULL 列兼容写法）；账号只同步 `accountNoMasked` 脱敏值，禁止明文。
2. 同步语义是“平台内台账登记”，不是渠道绑定完成：切平台代收（Sprint 55）仍会因 `UNBOUND`/空 `sub_mchid` 409，平台代收在真实进件前不可用——这是预期行为，不是缺陷。
3. 台账结算引用始终指向“当前默认已核验账户”：变更账户核验通过成为新默认后，引用切换到新账户；旧账户保持 VERIFIED 历史（O5 语义不变）。
4. 账户停用/驳回不回滚台账引用：停用后该商家本就不应再收款，平台代收侧另有 BOUND+生效时间校验兜底；如需清理属后续治理项。
5. 商家端 M2 / 管理端 M3 页面无需改动：它们读取 `merchant_payment_account` 真实状态，本轮后能看到“已关联结算账户、进件审核中、未绑定”的真实台账。

## 4. 文件清单

| 文件 | 变化 |
| --- | --- |
| `shop-b2b2c-backend/.../service/MerchantSettlementAccountService.java` | `approveVerified` 两分支接入 `syncPaymentAccountBinding`；新增私有同步方法；注入 `MerchantPaymentAccountMapper`/`MerchantStoreMapper`；类注释第 4 条更新 |
| `shop-b2b2c-backend/.../entity/MerchantPaymentAccount.java` | 注释第 3 条更新：`settlementAccountId` 由核验通过同步写入，渠道字段约束不变 |
| `shop-b2b2c-backend/src/test/java/com/shop/b2b2c/MerchantSettlementAccountServiceTest.java` | 构造器补依赖；新增 4 项用例（建台账真实值、已有行只更新引用、非默认不同步、变更切换引用） |
| `shop-b2b2c-backend/src/test/java/com/shop/b2b2c/SettlementAccountPaymentSyncIntegrationTest.java` | 新增 H2 集成用例：登记→核验→台账落库字段断言；非默认核验引用不变 |

## 5. 验证命令与预期（用户手动执行）

```bash
mvn -f shop-b2b2c-backend/pom.xml -Dtest=MerchantSettlementAccountServiceTest,SettlementAccountPaymentSyncIntegrationTest test
mvn -f shop-b2b2c-backend/pom.xml test
```

预期：

- 单元用例：核验通过且成为默认 → insert 台账行且字段为真实值（AUDITING/UNBOUND/空串/脱敏）；已有行只更新 `settlement_account_id`、`MCH-REAL`/`BOUND`/`PASSED` 等渠道字段不变；非默认核验不触碰台账；变更账户核验后引用指向新账户。
- H2 集成用例：HTTP 登记+核验后 `merchant_payment_account` 落一行，`sub_mchid=''`、`bank_account_number='****7890'` 无明文；第二账户核验（非默认）后结算引用不变且台账仍只有一行。
- 以上不覆盖真实 MySQL 唯一键并发与真实渠道进件。

## 6. 未验证项与已知问题

1. **未执行验证**：代理未运行 `mvn`，所有结论为静态审阅结果。
2. **平台代收仍不可切换/支付**：台账行的 `UNBOUND`+空 `sub_mchid` 会被 Sprint 55 切换前置与 M4-A 下单校验如实拦截；打通需真实渠道进件（方案 B 的独立进件接口或 M6）。
3. **demo 种子数据**：`db/demo-seed.sql` 仍有历史演示用 `merchant_payment_account` 种子行（SUB... 假值），仅演示环境使用，本轮未清理；如确认废弃可另行处理。
4. **停用不回滚引用**：结算账户停用后台账 `settlement_account_id` 仍指向它，属已知边界（见规则 4）。
5. **权威关系**：`merchant_settlement_account` 是收款账户权威表；`merchant_payment_account` 为渠道台账，结算引用以默认核验账户为准，渠道绑定以真实渠道结果为准。
