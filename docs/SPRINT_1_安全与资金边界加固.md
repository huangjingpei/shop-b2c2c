# Sprint 1：安全与资金边界加固

日期：2026-11-03
目标：整改 D01–D06、D15–D16，补充问题状态并提交可追溯的 Git 记录。

## 1. 本轮交付与状态

| 问题 | 状态 | 结果 |
| --- | --- | --- |
| D01 未付款直接交付 | 已修复 | checkout 仅创建 UNPAID 订单和明细，不生成支付流水、不扣库存、不发卡；返回 paymentAvailable=false |
| D02 公开 Mock 支付 | 已修复 | 删除 mock-pay HTTP 映射，payment.mock 默认 false；即使旧环境传 true 也不能恢复入口；不存在的接口明确返回 HTTP 404 |
| D03 商品/SKU 错配 | 已修复 | 自购、买家代付、商家推单统一验证 SKU 的 productId 和 merchantId；自购兼容字段 merchantId 如传入也必须匹配 |
| D04 并发超卖 | 已修复 | 支付事务执行带库存下限、商品与租户条件的数据库原子扣减，影响行数必须为1；卡密导入库存增加也改为原子增量 |
| D05 重复发卡 | 已修复 | 当前单商品订单按租户/商品/SKU选择卡密并 FOR UPDATE 锁定，随后以 status=0 条件认领；失败抛异常回滚整个事务 |
| D06 重复扣减/履约 | 已修复 | 支付先锁订单行，再通过 UNPAID + RESERVED_LOCK + 有效期条件 CAS 迁移；PAID/COMPLETED/SHIPPED 重试直接返回，失败回滚支付与库存 |
| D15 跨商户发货 | 已修复 | 必须有绑定店铺并验证订单归属，只允许实物待发货订单；物流公司与运单号必填；同一订单变更事务串行化 |
| D16 跨商户卡密读写 | 已修复 | 导入验证商品/SKU/会话租户且限定虚拟商品；按 SKU 查卡同样附加当前租户条件，拒绝跨店读取 |
| D17 缺省店铺/全平台回退 | 已修复（关联加固） | 商家商品、订单、卡密接口不再默认店铺1或回退查询全平台；超管未绑定店铺也拒绝走商家资产接口 |
| D10 详情/超时覆盖支付 | 部分修复 | 详情、预占、取消、海报重建、人工履约等订单写路径统一使用数据库订单行锁；定时关闭与完整补偿仍未实现 |
| D39 缺少可重复验证 | 部分修复 | 新增16项自动化集成测试；CI、真实MySQL回归和完整交易售后测试尚待建设 |

原始问题台账已为所有 D/F 项增加“状态”列，保留初始问题描述作为整改前证据。其余问题保持待处理或待业务确认。

## 2. 资金边界与兼容影响

目前没有接入真实支付渠道，因此本轮明确关闭前端模拟付款路径。普通购买可以生成待付款订单，商城弹窗显示“尚未扣款或交付”；代付收银台显示“支付暂未开放”，不会再生成随机付款人并调用 Mock。

`PeerPayService.notifyPaymentSuccess` 仅保留为内部事务落账方法，没有任何 HTTP Controller 调用。方法校验必需支付参数并保护库存、发卡和状态迁移，但本身不执行渠道验签或核实实际资金。未来必须由完成验签、金额/商户/币种核验及支付事件持久化的渠道适配器调用，不能直接映射为公开接口。F01 仍为待处理，自购支付接续流程也留待渠道接入实现。

本轮没有引入真实分账执行，自动/手工虚拟履约不再把 sharingStatus 标成 FINISHED，保持 PENDING。现有历史状态和历史重复交付数据不会自动修复，需另行核对。

由于原订单详情、海报和超时处理可能整行回写订单，本轮对这些路径同步加订单行锁，避免在支付 CAS 之外用旧对象覆盖已提交状态。数据库事务提交后才尝试删除支付 Redis 锁，删除失败等待原 TTL，不把已提交交易返回为失败。完整的 Redis/数据库补偿及持锁者授权仍属于 D09 后续整改范围。

当前数据模型把卡密和定制服务均标为 goodsType=2；卡密不足转人工服务的 D11 仍未解决，测试中仅证明不会重复分配同一张卡，不能据此声称即时交付率已得到保障。跨订单 SKU 真实预占 D07 同样仍待实现。

超管全平台订单查看继续使用 `/api/v1/admin/orders`。本轮禁止没有店铺上下文的超管通过商家接口写默认店铺或读取全部卡密；如果后续需要代运营，应单独设计显式选店、授权与审计。

## 3. 实现位置

- `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/common/TradeGuards.java`：统一商品/SKU归属校验。
- `common/SecurityUtils.java`：必须绑定当前店铺的访问入口。
- `mapper/ProductSkuMapper.java`：带租户、商品、库存条件的扣减和原子补库存。
- `mapper/ProductVirtualCardMapper.java`：卡密锁定、条件认领、租户隔离查询。
- `mapper/TradeOrderMapper.java`：订单行锁及支付状态 CAS。
- `service/impl/PeerPayServiceImpl.java`：事务幂等、卡密认领、回滚与提交后清锁。
- `controller/OrderController.java`、`ProductController.java`、`PeerPayController.java`：收紧公开交易和商家边界。
- `shop-storefront-vue/src/views/ProductDetail.vue`、`PeerPayCashier.vue`：同步展示待付款及支付不可用状态。

除首项外，后端相对路径均位于 `shop-b2b2c-backend/src/main/java/com/shop/b2b2c/` 下。

## 4. 验证记录

后端测试入口：`shop-b2b2c-backend/src/test/java/com/shop/b2b2c/Sprint1SecurityIntegrationTest.java`。

测试运行完整 Spring 应用、真实 Mapper SQL 和 Spring 数据库事务。测试使用 H2 MySQL 兼容模式，Redis 使用 Mock；隔离内存数据，不连接开发或生产数据库。`sprint1-schema.sql` 源于当前生产列定义，去除 MySQL 建库/引擎和索引声明以兼容 H2，仅用于测试。

| 验证 | 结果与覆盖 |
| --- | --- |
| 后端 `mvn test` | 16项通过，0失败/0错误/0跳过：待付款无资产副作用、Mock映射不存在、三类建单SKU校验、末份库存并发、8次并发通知幂等、卡密竞争、认领失败事务回滚、发货后通知重放、失效预占拒绝、跨租户发货/读卡/导入、合法操作与无店铺拒绝 |
| 商城 `npm run build` | Vite 生产构建通过 |
| 管理端 `npm run build` | Vite 生产构建通过；存在超过500kB的既有分包提醒，不阻断构建 |
| `git diff --check` | 无空白格式错误 |

以上不等同于生产MySQL事务隔离/锁行为压测、真实渠道联调或完整浏览器端到端验收。上线前需在隔离MySQL环境复跑竞争与回滚场景；本轮没有对线上数据进行变更，没有启动支付或执行部署。

复跑方式（在各工程目录执行）：

```text
shop-b2b2c-backend: mvn test
shop-storefront-vue: npm run build
shop-admin-vue: npm run build
```

## 5. 后续衔接

下一轮应优先处理真实渠道支付/主动查单/事件幂等、SKU真实预占与释放、数字商品类型拆分、退款和对账补偿。原始台账其余项保持独立跟踪，不因完成 Sprint 1 而标记整个平台可承接真实资金交易。
