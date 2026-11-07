# Sprint 32 — 买家退款申请与商家审批（F05 第一阶段）

## 主台账编号
- F05 退款、退货与原路退回付款人（原状态：待处理）。

## 实际子范围
- 新增 `refund_request` 表：买家退款申请单，与 `trade_refund`（实际执行记录）分离。
- 后端 `RefundRequestService`：买家申请（凭 orderSn+contact+queryToken 校验身份）、商家审批同意（调用 `RefundService.applyOrderRefund` 走渠道原路退款）、拒绝、买家撤回；可退余额校验（实付 − 已发起+已成功退款累计）；同一订单同一时间只允许一个 PENDING 申请。
- 接口：买家公开免密（申请/查询/撤回）、商家（列表/同意/拒绝，强制 MERCHANT_ADMIN/SUPERADMIN，超管可跨店）。
- 复用：T33/T34 已实现的渠道原路退款、全额退款时库存回退与卡密作废，本轮不重复建设。
- 前端：买家 QuickQuery 订单详情加「申请退款」入口与退款记录；商家新增「退款申请审批」页（同意/拒绝）。
- shared 规则：`refundRequest.mjs` 金额/原因校验、状态映射。

## 代理细化规则（待业务确认，不冒充已确认业务规则）
- 可退订单状态：PAID / SHIPPED / COMPLETED；UNPAID/CANCELLED/REFUNDED 不可申请。
- 可退余额 = pay_amount − sum(refund_amount where status ∈ {PROCESSING, SUCCESS})；FAILED 不占额度。
- 退款申请状态：PENDING（待审批）→ APPROVED（已同意并执行退款）/ REJECTED（已拒绝），或买家 CANCELLED（已撤回）。
- 商家同意直接执行渠道原路退款；若可退余额不足（期间有其他退款）则拒绝并提示买家重新申请。
- 退货物流流程（买家寄回、商家确认收货后退款）本轮未做，仅支持资金退款。

## 验收状态
- 待人工验证。后端 H2 单测+集成通过、shared 规则通过、两端生产构建通过；真实渠道退款并发与生产 MySQL 唯一键未覆盖。

## 验证命令与结果
- 后端：`mvn -f shop-b2b2c-backend/pom.xml test` → Tests run: 1056, Failures: 0, Errors: 0, Skipped: 1, BUILD SUCCESS。
- shared：`node --test shared/refundRequest.test.mjs`（及其他规则用例）→ 全部通过。
- 管理端构建：`npm --prefix shop-admin-vue run build` → built in 13.55s。
- 商城构建：`npm --prefix shop-storefront-vue run build` → built in 2.84s。

## 未验证项
- 真实微信/支付宝渠道退款调用与回调（集成测试用 Mock 适配器覆盖）。
- 并发退款申请与渠道回调竞态的真实压测。
- 退货物流流程未实现，留待 F05 第二阶段。

## 文件变更
- 新增：`refund_request` 表（schema.sql + sprint1-schema.sql）、`RefundRequest.java`、`RefundRequestMapper.java`、`RefundRequestDTOs.java`、`RefundRequestService.java`、`BuyerRefundRequestController.java`、`MerchantRefundRequestController.java`、`RefundRequestServiceTest.java`、`refundRequest.mjs`、`refundRequest.test.mjs`、`views/merchant/RefundRequests.vue`。
- 修改：`QuickQuery.vue`（买家退款入口）、`router/index.js`、`AdminLayout.vue`、`Sprint1SecurityIntegrationTest.java`（+4 集成用例、MockBean PaymentChannelRegistry）、`AGENTS.md`、台账、docs/README.md。
