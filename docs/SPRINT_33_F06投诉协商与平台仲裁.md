<!--
文件：SPRINT_33_F06投诉协商与平台仲裁.md
用途：记录 F06 投诉协商与平台仲裁第一阶段的交付范围、代理细化规则、验证命令与未验证项。
维护注意：状态为“部分修复·待人工验证”，不得改写为已验收；真实渠道退款与买家站外通知未联调，调整规则须同步主台账与 shared 规则用例。
-->
# Sprint 33：F06 投诉协商与平台仲裁（第一阶段）

## 1. 主台账编号与子范围

- 主台账编号：F06（投诉协商和平台仲裁）
- 实际子范围：
  1. 买家免密提交投诉：`orderSn + 下单联系方式 + queryToken` 校验，四项投诉类型，投诉说明 5–1000 字，举证链接最多 5 条。
  2. 商家答复：本店工单可答复并追加举证，历史答复进入追加事件表。
  3. 平台介入：买家在商家答复后（或未答复时）可申请平台介入，工单进入仲裁阶段并向超管发出通知。
  4. 平台裁决：超管裁决“支持诉求 / 驳回诉求”，支持时可判定退款金额，复用既有 `RefundService` 原路退回。
  5. 不可覆盖操作记录：新增 `complaint_event` 追加表，保存每次流转的操作人、说明与举证快照，不提供更新/删除入口。
  6. 三端页面：商城查单页（提交/查看/申请介入）、商家投诉处理页、平台投诉仲裁页。

## 2. 代理自行细化的规则（待业务确认）

| 细化规则 | 性质 | 说明 |
| --- | --- | --- |
| 状态机 `PENDING → MERCHANT_REPLIED → ARBITRATING → RESOLVED/REJECTED` | 业务规则细化，待业务确认 | 复用既有 `complaint` 表注释中的状态集合；终态不可再答复或申请介入；集中由 `ComplaintService` 控制 |
| 同一订单仅允许一个未终结投诉 | 业务规则细化，待业务确认 | `PENDING/MERCHANT_REPLIED/ARBITRATING` 任一条存在即拒绝再次提交（409） |
| 仅 `PAID/SHIPPED/COMPLETED` 订单可投诉 | 业务规则细化，待业务确认 | 与 F05 退款可申请状态同口径；未支付/已取消订单不可发起投诉 |
| 商家可在 `PENDING/MERCHANT_REPLIED` 多次答复 | 业务规则细化，待业务确认 | `complaint.merchant_reply` 保留最新文本，历次答复与举证写 `complaint_event`，保证历史不可覆盖 |
| 平台裁决必须先有买家“申请介入” | 业务规则细化，待业务确认 | 仅 `ARBITRATING` 状态可裁决；平台不能跳过买家申请直接裁决（如需主动介入，后续可加超管强制介入接口） |
| 退款判定 NONE / FULL_REFUND / PARTIAL_REFUND | 技术实现细化 | 由服务端按“实付 − 未失败退款累计”的可退余额推导，客户端只传金额，不传判定值 |
| 裁决退款幂等键 `COMPLAINT-{工单ID 32位零填充}` | 技术实现 | 满足渠道退款幂等键 16–64 位要求；同一工单重复提交裁决不会重复退款 |
| 驳回裁决不得携带退款金额 | 安全实现细化 | 后端 400 拒绝；前端驳回时金额置 0 且禁用 |
| 举证只接受 http/https 链接 | 安全实现细化 | 拒绝 `javascript:`、data URI 与含空白字符链接；前端仅以文本/新窗口链接渲染，不使用 `v-html` |
| 商家列表与详情不返回买家联系方式 | 隐私实现细化 | `buyer_id` 不下发给商家角色；超管详情保留完整信息 |
| 通知范围：商家收提交/裁决，超管收介入 | 业务规则细化，待业务确认 | 复用 `merchant_notification_outbox` 站内信；买家无登录态，站外短信/邮件未实现，仅在查单页看到状态 |

## 3. 验收状态

**部分修复 · 待人工验证。**

已交付：提交、答复、举证、平台介入、裁决、退款联动、站内通知、追加式操作记录与三端页面。
未完成：
- 买家站外通知（短信/邮件）未实现，买家只能通过查单页查看进展；
- 裁决退款仅通过现有渠道退款能力（测试使用 mock 适配器），未做微信/支付宝真实渠道联调；
- 退货物流、举证图片上传存储（当前只接受已有 URL）未实现；
- 投诉超时自动升级（商家长期不答复）未实现，需买家手动申请介入。

## 4. 验证命令与结果

| 层 | 命令 | 结果 |
| --- | --- | --- |
| 后端单测+集成 | `mvn -f shop-b2b2c-backend/pom.xml "-Dtest=ComplaintServiceTest,Sprint1SecurityIntegrationTest" test` | 通过（242 项，0 失败 0 错误） |
| 后端全量 | `mvn -f shop-b2b2c-backend/pom.xml test` | 1065 项，0 失败 0 错误 1 跳过，退出码 0（BUILD SUCCESS） |
| shared 规则 | `node --test shared/complaint.test.mjs` | 4 项通过 |
| 管理端构建 | `npm --prefix shop-admin-vue run build` | 构建成功 |
| 商城构建 | `npm --prefix shop-storefront-vue run build` | 构建成功 |

## 5. 未验证项

- 真实 MySQL 执行 `schema.sql` 新增 `complaint_event` 表与 FK（仅通过 H2 夹具与静态契约用例）。
- 微信/支付宝真实退款联调、渠道失败回滚的真实网络行为。
- 浏览器端三页交互与移动端展示（未执行人工点击回归）。
- 并发提交同一订单投诉：当前以“查询后插入”实现，高并发下可能突破“单一活动工单”约束（未加唯一索引，因终结后需允许再次投诉）。

## 6. 文件变更

后端：
- `entity/Complaint.java`（补状态/判定常量与维护说明，默认状态对齐 schema 的 `PENDING`）
- `entity/ComplaintEvent.java`（新增追加式操作记录）
- `mapper/ComplaintMapper.java`（活动工单计数、商家/超管分页、按订单查询）
- `mapper/ComplaintEventMapper.java`（新增）
- `dto/ComplaintDTOs.java`（新增请求/详情契约）
- `service/ComplaintService.java`（新增状态机、裁决退款与事件记录）
- `controller/BuyerComplaintController.java`、`MerchantComplaintController.java`、`AdminComplaintController.java`（新增）
- `service/MerchantNotificationService.java`（新增三个投诉通知业务类型）
- `db/schema.sql`、`test/resources/sprint1-schema.sql`（新增 `complaint_event`；H2 举证列由 JSON 改为 TEXT 防二次编码）
- `Sprint1SecurityIntegrationTest.java`（新增 F06 集成用例与清理表项）
- `SchemaMySqlTest.java`（表基线 40 → 41）

前端：
- `shop-storefront-vue/src/views/QuickQuery.vue`（投诉提交/列表/申请介入）
- `shop-admin-vue/src/views/merchant/Complaints.vue`（新增）
- `shop-admin-vue/src/views/admin/Complaints.vue`（新增）
- `shop-admin-vue/src/router/index.js`、`src/views/layout/AdminLayout.vue`（路由与菜单）
- `shared/complaint.mjs`、`shared/complaint.test.mjs`（新增规则与用例）

文档：
- `docs/B2B2C_项目缺陷与未实现功能清单_2026-11-03.md`（F06 → 部分修复）
- `docs/README.md`（批次表与文档索引）
- `AGENTS.md`（前端规则测试清单追加 `shared/complaint.test.mjs`）
