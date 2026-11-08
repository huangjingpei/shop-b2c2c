<!--
文件：SPRINT_52_商家收款账户页真实状态展示.md
用途：记录 M2（商家资料页展示真实收款模式、渠道进件状态与已登记结算账户，删除假“审核中”和写死银行名）的实际范围、规则、验证命令与未验证项。
维护注意：
  1. 本功能只读展示，不新增进件提交、结算账户登记或审核动作；写入流程仍属 M2 后续或 M6 真实渠道接入。
  2. 页面不得把空对象显示成“审核中”，不得写死银行名，也不得在前端自行判断资金是否到账。
  3. 收款模式以服务端 effectiveCollectionMode 为准；总闸关闭或环境应急关闭时，商家配置的 PLATFORM_COLLECT 不生效。
  4. 代理未运行 npm/node/mvn，所有验证标记待人工执行。
  5. 不展示银行卡明文、身份证、sub_mchid 之外的渠道凭据或报文。
-->
# Sprint 52：商家收款账户页真实状态展示（M2）

- 日期：2026-11-08
- 主台账：F02（支付渠道与分账）
- 设计来源：[2026-11-08-商家收款双轨与微信电商收付通进件设计](2026-11-08-商家收款双轨与微信电商收付通进件设计.md)
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：待人工验证（代理未执行 npm/node/mvn）

## 1. 本轮实际子范围

| 项 | 已做 | 未做 |
| --- | --- | --- |
| 收款模式展示 | 商家资料接口对店长返回 `effectiveCollectionMode`；页面显示商家直收/平台代收/未获取，并按模式切换资金路径说明 | 商家自行切换模式（仅平台超管，M3 页面） |
| 进件状态 | `paymentAccountView` 区分未提交、审核中、通过、驳回、未知；空对象不再显示“审核中” | 渠道进件提交与微信真实审核（M6） |
| 结算账户 | 页面展示 `settlementAccounts` 的脱敏账号、开户行、核验状态、默认标记；无记录显示“尚未登记” | 商家提交登记/变更申请表单；平台审核页（后续模块） |
| 银行名 | 删除写死“中国工商银行”，无值显示“未配置” | 银行字典与支行选择 |
| 权限 | 员工资料白名单不返回 `effectiveCollectionMode`、`paymentAccount`、`settlementAccounts` | 员工自助查看收款信息（按现状不开放） |

## 2. 规则与语义

### 2.1 收款模式

- `MERCHANT_DIRECT`：页面说明“货款直接进入您的渠道账户，平台不接触货款；平台仅按订单收取技术服务费”。
- `PLATFORM_COLLECT`：页面说明“货款进入平台服务商商户号，由微信/支付宝按分账指令结算到已绑定账户；平台按订单留存服务费”。
- 未返回或未知值：显示“模式未获取”，提示刷新或联系运营，不默认成商家直收。

### 2.2 渠道进件状态

| applyStatus | 页面 |
| --- | --- |
| 空（无记录） | 未提交渠道进件（info） |
| `AUDITING` / `PENDING` | 渠道审核中（warning） |
| `PASSED` | 官方审核已通过并开通（success） |
| `REJECTED` | 审核已驳回，请修改资料后重新提交（danger） |
| 其他 | 未知进件状态：原值（info） |

### 2.3 结算账户

| status | 页面 |
| --- | --- |
| `VERIFIED` | 已核验（success） |
| `PENDING` | 待核验（warning） |
| `REJECTED` | 已驳回（danger） |
| `DISABLED` | 已停用（info） |
| 空/其他 | 状态未知/未知状态：原值（info） |

说明：结算账户脱敏值来自后端 `maskedList`，前端不再二次拼接或猜测完整账号。

## 3. 文件清单

| 文件 | 变化 |
| --- | --- |
| `shop-b2b2c-backend/.../controller/MerchantController.java` | 店长资料返回 `effectiveCollectionMode`（复用 M1 服务），员工不返回 |
| `shop-admin-vue/src/utils/orderStatistics.mjs` | `paymentAccountView` 无记录改“未提交”；新增 `settlementAccountView` |
| `shop-admin-vue/src/views/merchant/Profile.vue` | 展示真实模式、进件状态、脱敏结算账户；删除假审核中与写死银行名；按模式切换清算说明 |
| `shared/orderStatistics.test.mjs` | 新增 M2 规则用例（空对象、审核中、未知值、结算账户状态） |
| `shop-b2b2c-backend/.../Sprint1SecurityIntegrationTest.java` | 店长资料断言 `effectiveCollectionMode=MERCHANT_DIRECT`，员工不返回该字段 |

## 4. 验证命令与预期（用户手动执行）

```bash
node --test shared/orderStatistics.test.mjs
mvn -f shop-b2b2c-backend/pom.xml -Dtest=Sprint1SecurityIntegrationTest test
npm --prefix shop-admin-vue run build
```

预期：

- `node --test`：原有用例与两条 M2 用例通过；空对象不再得到“审核中”。
- `Sprint1SecurityIntegrationTest`：员工资料无 `effectiveCollectionMode`；店长资料在总闸默认关闭时返回 `MERCHANT_DIRECT`。
- `shop-admin-vue` 生产构建成功；页面无“中国工商银行”写死文案；无记录时显示未配置/未提交。
- 以上为本地规则/H2 验证，不代表真实微信进件、真实结算卡或生产页面验收通过。

## 5. 未验证项与已知问题

1. **未执行验证**：`node --test`、Maven、前端生产构建均由用户运行，本轮未运行。
2. **无真实进件数据**：`merchant_payment_account` 仍无写入流程，页面大概率显示“未提交渠道进件”；这不是页面缺陷，而是 M6 未接入。
3. **无登记/变更表单**：本轮只读展示；商家提交结算账户变更仍需后续页面与接口联调。
4. **模式展示依赖 M1**：`effectiveCollectionMode` 由 M1 解析，M1 的 H2/MySQL 验证未完成前，本页结论同样待验证。
5. **员工视角**：员工不返回收款信息；若后续要求员工可见，需要单独定义白名单与脱敏规则，不在本轮放开。
6. **视觉样式未截图验收**：新增结算账户表格与模式标签未做浏览器视觉回归，需用户在生产构建后确认。
