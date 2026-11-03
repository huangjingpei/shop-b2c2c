<!-- 文件说明：记录D25状态统计和D26商家看板修正、依赖边界及手动验收步骤。
维护注意：当前只是订单状态统计，未接入实际资金账本；金额字符串保持精度，null财务指标不可改为零。未运行编译/测试/构建，不能据代码提交标为已验收。 -->
# Sprint 13：订单统计口径与看板真实性

日期：2026-11-04。实现与用例已编写，待用户人工验证；不追验Sprint 11/12，不默认其已通过。

## 原条目和范围

- D25：修平台将所有意向金额累计为成交、商家将订单列表长度当累计成交；平台/商家统一状态统计。
- D26商家部分：真实营业状态、零费率、上架数量及移除写死的“微信直结生效”。商城店铺页及公开店铺接口仍未处理。
- F01/F03/F05/F09、D30/D33为依赖/后续协同项，本轮不实现真实资金账本、退款或状态迁移，也不把其缺口伪装成0。

整体依赖见[问题依赖与修复边界](问题依赖与修复边界.md)。D25/D26均保持进行中、待人工验证；即使本轮验证通过，账本和商城剩余范围也只能标部分修复。

## 统一口径

| 字段 | 定义 |
| --- | --- |
| totalOrders | 查询范围内全部订单，不等于成交量 |
| paidOrders / paidTurnover | PAID、SHIPPED、COMPLETED、REFUNDED的数量/原订单payAmount之和，含退款状态订单，非退款净额 |
| pendingOrders / pendingIntentAmount | UNPAID且peerStatus不是EXPIRED/CANCELLED；意向金额不计历史支付 |
| closedOrders | CLOSED/CANCELLED/EXPIRED，或上述已过期/取消的UNPAID |
| refundedOrders / refundedOrderAmount | REFUNDED数量及这些订单的原金额，不是实际退款金额或退款流水 |
| estimatedCommission | PAID/SHIPPED/COMPLETED且支付金额有效的commissionAmount之和，排除REFUNDED，不是已结算收入 |
| unknownOrders | 未识别的主订单状态及异常空行；不猜测已支付 |
| amountAnomalyOrders | 参与金额统计的字段为null、负数或超两位精度时计异常，同一订单只计一次；不自动四舍五入 |
| actualRefundAmount / netTurnover / settledCommission | 当前均为null，financialLedgerAvailable=false，不从FINISHED推断实际结算 |

金额为BigDecimal累计的两位小数字符串，避免大额JSON数值在浏览器丢分位。支付金额异常不计该金额及对应预计佣金；佣金异常不计该佣金，但保留有效的支付金额。关闭/未知状态的金额不参与汇总，诊断不是全部历史数据清洗。

已支付主状态优先，不因遗留peerStatus过期就否定历史支付；这不是D33状态一致性修复。新状态如REFUNDING目前列为未知，后续由原状态/退款需求统一扩展，而非各页面补一套规则。

## 接口与页面

`GET /api/v1/admin/orders`保留orders、totalOrders及数值类型的旧totalTurnover/totalCommission，修正后分别兼容历史支付额和预计佣金；新增statistics对象。新页面仅使用statistics的精确字符串，不回退旧字段。

新增`GET /api/v1/merchant/orders/statistics`：商户只取已校验会话，忽略客户端额外merchantId，返回聚合白名单，不返回订单明细或领取凭证，并设置no-store。不再为了商家看板加载所有订单明细。

平台订单页/看板区分总订单、历史支付、意向金额和预计佣金，显示未知状态/异常金额提示。FINISHED改称内部记录完成、待对账；订单列表金额列标明快照，不将未付款应付额称实付。商家看板按真实store.status、commissionRate展示，零费率不回退3%；商品数仅数status=1并命名“已上架”，不保证库存或店铺可交易。

尚无统计字段或请求失败时显示未知/保留已有快照，不伪装0；真实空查询返回的0正常展示。没有SQL结构变更，不需要删库。全量订单读取和平台实体列表负载仍归D30，本轮未声称分页或财务账本完成。

## 用例和人工验证

已编写OrderStatisticsTest及shared/orderStatistics.test.mjs：混合状态、过期意向、退款原金额、分账标记不等于结算、空/未知数据、null/负数/超精度金额、零费率、大额字符串和商品上架展示。Spring/H2用例覆盖匿名/角色拒绝、忽略伪造商户选择、跨店统计隔离、平台兼容键和商家空统计。

本轮未运行编译、测试或构建。Windows PowerShell在`E:\shop-b2b2c`依次手动执行：

```powershell
Set-Location E:\shop-b2b2c
$env:MAVEN_OPTS='-Xms32m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2'
mvn -f shop-b2b2c-backend/pom.xml '-DforkCount=0' test
node --test shared/goodsType.test.mjs shared/idempotency.test.mjs shared/buyerReceipt.test.mjs shared/authSession.test.mjs shared/productEditing.test.mjs shared/inventoryAdjustment.test.mjs shared/cardAssets.test.mjs shared/orderStatistics.test.mjs
$env:NODE_OPTIONS='--max-old-space-size=512'
npm --prefix shop-admin-vue run build
```

预期后端BUILD SUCCESS且Failures/Errors为0，默认独立MySQL用例跳过1项属正常；Node fail=0，Vite构建成功。测试使用隔离H2/Mock，未新增真实MySQL统计SQL或连接业务数据库；可选基线SQL验证仍按已有交付命令独立运行，不代表渠道验收。

页面场景（仅在隔离开发数据中准备）：未付款1000、已付10、退款状态2、已过期意向500，则本店历史支付12、支付数量2、待付意向1000，不应显示1512或把FINISHED当收益。另一店已付订单不计本店；0费率、打烊/封禁应按资料展示；新后端统计缺失不能回退旧GMV。实际退款/净额/结算应始终明确不可用。
