<!-- 文件：SPRINT_27_SKU规格维护.md -->
<!-- 用途：Sprint 27（F11 子项：既有 SKU 规格维护）交付、验证与边界说明。 -->
<!-- 维护注意：验收由用户管理；本文件不替代台账状态，真实 MySQL 与浏览器交互未验证。 -->

# Sprint 27：F11 既有 SKU 规格维护

日期：2026-11-07　状态：已实现·待人工验证

## 1. 范围与归属

- 主台账：**F11（部分修复→仍部分修复）**。原清单"SKU增删、规格编辑……仍待实现"中的"规格编辑"，本轮回补。
- 台账 F11 剩余：**违规下架/审核、详情图文维护与售罄规则**仍未实现，F11 保持部分修复。
- 边界：只允许修改既有 SKU 的 `spec_data`；**skuCode 创建后不可改**（代理细化，保持 Sprint 26 口径）；不触碰价格/库存/类型，历史订单快照不受影响。

## 2. 接口与语义

| 接口 | 角色 | 闸门 | 说明 |
| --- | --- | --- | --- |
| `PATCH /api/v1/merchant/products/{id}/skus/{skuId}/spec` | SUPERADMIN / MERCHANT_ADMIN | requireOpen | 修改单个 SKU 规格，返回最新编辑快照 |

- body：`{ revision, specData }`；specData 必须为合法 JSON 对象且 ≤255 字符（与发布/新增 SKU 同口径校验）。
- 历史订单安全：`trade_order_item.spec_info` 在建单时已快照（三条下单路径一致），改规格不改写历史订单，本轮以集成测试固化。
- revision 指纹**新增纳入 spec_data**：规格变化即改变版本号，并发编辑同一商品时后到请求按 409 拒绝（防止静默覆盖）；未变化提交直接返回当前快照、不写库、版本不变。
- 规格不触发 min_price 派生；价格/库存列不受影响。
- 商品行锁（FOR UPDATE）+ 全量 SKU 行锁沿用 Sprint 9/26 既有序列，无需新锁。

## 3. 前端（shop-admin-vue）

- 编辑对话框"规格"列改为行内输入框；内容与服务端快照不一致时出现"保存规格"按钮，单独提交（不混入"保存资料与售价"的白名单请求）。
- 新增 `skuSpecPayload` 规则（JSON 对象/≤255 字符），`skuCreatePayload` 复用同一校验。
- 保存成功后沿用 `applySkuSnapshot`：只替换 SKU 列表与 revision，保留未保存的资料输入。

## 4. 数据库与夹具说明（重要）

- `spec_data` 在生产 MySQL 为 JSON 列：写入时 MySQL 解析并规范化存储，读回为规范化文本（如冒号后带空格）。应用层全程把规格当**不透明 JSON 文本**（应用内校验合法性，不使用任何 JSON 操作符），该规范化对业务无影响；唯一可感知差异是"同文本重复保存"在生产会因规范化文本与输入不同而多写一次（幂等结果一致，无数据风险）。
- **H2 测试夹具差异（已固化注释）**：H2 的 JSON 列会把参数化字符串包成 JSON 字符串字面量（存成 `"{"a":1}"` 而非对象），与 MySQL 赋值即解析的行为不一致；为保证测试语义贴近应用视角，夹具 `product_sku.spec_data` 简化为 `VARCHAR(255)`（与无外键/无 ENGINE 同类简化）。真实 JSON 列行为未在 H2 验证，需真实 MySQL 人工验证。

## 5. 测试

| 层 | 用例 | 覆盖 |
| --- | --- | --- |
| ProductEditingServiceTest（新增 4 方法） | 变更才写库且不碰价格/最低价、未变化跳过写、9 类非法输入/过期 revision/跨 SKU 404 拒绝、规格纳入 revision 指纹（写入时变更快照） | 全 mock 契约 |
| Sprint1SecurityIntegrationTest（新增 2 用例） | 规格落库、历史订单 spec_info 哨兵值不被改写、未变化提交版本不变、越店 404；HTTP 匿名 401/员工(MERCHANT_STAFF) 403/店长 200、越店 404 且目标不变 | 真实 SQL+H2 |
| shared/productEditing.test.mjs（新增 1 用例，共 21 项） | skuSpecPayload 合法 trim 与 9 类非法拒绝 | 纯 Node |

## 6. 验证（代理执行）

- `mvn -f shop-b2b2c-backend/pom.xml test`：1005 项通过（1 项既有跳过），BUILD SUCCESS
- `node --test shared/productEditing.test.mjs`：21 项通过
- `npm --prefix shop-admin-vue run build`：通过（✓ built in 14.68s）
- 注意：`-DforkCount=0` 会让既有静态扫描用例的相对路径解析失败，手动验证请勿带该参数。

**未验证项**：真实 MySQL（JSON 列规范化读写回环）、浏览器行内编辑交互。不标验收通过。

## 7. 人工验证步骤

1. 后端：`mvn -f shop-b2b2c-backend/pom.xml test`，预期全部通过（含既有 1 项跳过）。
2. 前端规则：`node --test shared/productEditing.test.mjs`，预期 21 项通过。
3. 前端构建：`npm --prefix shop-admin-vue run build`，预期成功。
4. 浏览器：商家后台商品"编辑"对话框——修改某行规格出现"保存规格"按钮，保存后列表刷新且其他未保存输入保留；输入非法 JSON 被拦截；历史订单详情的规格快照保持下单时内容。
