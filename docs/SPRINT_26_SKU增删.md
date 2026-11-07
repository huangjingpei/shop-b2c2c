<!-- 文件：SPRINT_26_SKU增删.md -->
<!-- 用途：Sprint 26（F11 子项：既有商品 SKU 增删）交付、验证与边界说明。 -->
<!-- 维护注意：验收由用户管理；本文件不替代台账状态，真实 MySQL 与浏览器交互未验证。 -->

# Sprint 26：F11 既有商品 SKU 增删

日期：2026-11-07　状态：已实现·待人工验证

## 1. 范围与归属

- 主台账：**F11（部分修复→仍部分修复）**。原清单"编辑商品与 SKU"中剩余子项"SKU 增删"，本轮回补。
- 台账 F11 建议清单核对：编辑商品与 SKU（Sprint 9 资料/售价 + 本轮增删）、上下架（Sprint 9）、库存调整流水（Sprint 10）已交付；**规格编辑、违规下架/审核、详情图文维护与售罄规则仍未实现，F11 保持部分修复**。
- 本轮只做 SKU 增删：不触碰 SKU 编码/规格修改（另行设计），不改变库存调整、卡密、订单履约语义。

## 2. 接口与语义

| 接口 | 角色 | 闸门 | 说明 |
| --- | --- | --- | --- |
| `POST /api/v1/merchant/products/{id}/skus` | SUPERADMIN / MERCHANT_ADMIN | requireOpen + requireQualificationValid | 新增 SKU，返回最新编辑快照 |
| `DELETE /api/v1/merchant/products/{id}/skus/{skuId}`（body 带 revision） | SUPERADMIN / MERCHANT_ADMIN | requireOpen | 删除 SKU，返回最新编辑快照 |

**新增 SKU：**
- 字段：skuCode（trim 必填 ≤64）、specData（JSON 对象 ≤255）、price（正数两位小数上限 99999999.99）、stockQuantity（初始库存，自动卡密 goodsType=2 必须为 0，与发布商品同口径）。
- 同店编码重复 409（uk_merchant_sku_code 唯一键并发兜底）；单商品 SKU 上限 100；带 revision 防覆盖（409）。
- 初始库存等同创建时初始库存，**不写库存调整流水**（与 createProduct 一致，代理细化，待业务确认）。
- 新增后按全部 SKU 派生 min_price（新 SKU 更低时才写定向列，兼容 useAffectedRows）。

**删除 SKU（物理删除）：**
- 仅允许：商品至少保留 1 个 SKU、目标 SKU 零库存、且无任何历史引用记录（trade_order_item / product_virtual_card / inventory_reservation / inventory_adjustment / virtual_card_void / card_import_batch / card_reissue / card_access_audit 八表汇总计数为 0）。
- 有库存 409（先通过库存业务清零）；有历史记录 409（保护订单快照与审计可追溯，永不物理删）；最后一个 409；越店/跨商品 404；delete 影响行数不为 1 时 409。
- 删除后按剩余 SKU 重算 min_price（仅变化时写）。
- 并发口径：增删共用商品行锁（FOR UPDATE）串行化，READ_COMMITTED 下后加锁事务看到最新集合；外键与唯一键仅作兜底。

**代理细化规则（待业务确认）：**
- 新增 SKU 视同"产生新可售单元"，与发布新品同口径校验资质有效期（O9 扩展）；删除 SKU 为减损操作不加该闸门。

## 3. 前端（shop-admin-vue）

- 编辑对话框 SKU 表格新增"删除"操作（`canRemoveSku`：零库存且非最后一个才可点；历史记录拦截由后端 409 兜底并提示原因）。
- 表格下方新增 SKU 输入区（编码/规格/单价/初始库存；自动卡密库存输入禁用并强制 0），`skuCreatePayload` 做编码/JSON/价格/整数库存校验。
- 增删成功后仅替换 SKU 列表与 revision（`applySkuSnapshot`），保留未保存的资料输入，随后"保存资料与售价"不会因版本过期误报 409。

## 4. 测试

| 层 | 用例 | 覆盖 |
| --- | --- | --- |
| ProductEditingServiceTest（新增 7 个测试方法/20+ 断言） | 新增持久化与派生最低价（更低价写/更高价不写）、参数边界（编码/规格/价格/revision 格式/过期 revision）、虚拟初始库存/重复编码/100 上限、删除成功与最低价重算（删除最低价/非最低价）、拒绝场景（最后一个/有库存/有引用/跨商品）、身份与 revision 边界不进库 | 全 mock 契约 |
| Sprint1SecurityIntegrationTest（新增 2 用例） | 增删落库+min_price 派生、重复编码 409、虚拟库存 400、越店 404、有库存 409、卡密引用 409、订单明细引用 409、最后一个 409、删除后重算；HTTP 匿名 401/员工 403/店长 200/重复编码 409/打烊 409（O4）/资质到期拦新增不拦删除（O9 细化）/越店 404 | 真实 SQL+H2 |
| shared/productEditing.test.mjs（新增 3 用例，共 20 项） | skuCreatePayload 卡密强制 0/实物保留/服务默认 0、9 类非法输入拒绝、canRemoveSku 矩阵 | 纯 Node |

## 5. 验证（代理执行）

- `mvn -f shop-b2b2c-backend/pom.xml test`：999 项通过（1 项既有跳过 SchemaMySqlTest），含本轮新增 9 项
- `node --test shared/productEditing.test.mjs`：20 项通过
- `npm --prefix shop-admin-vue run build`：通过
- 注意：`-DforkCount=0` 会让既有静态扫描用例的相对路径解析失败，手动验证请勿带该参数。

**未验证项**：真实 MySQL（含 uk_merchant_sku_code 并发兜底、外键行为）、浏览器交互、资质到期真实日期边界。不标验收通过。

## 6. 人工验证步骤

1. 后端：`mvn -f shop-b2b2c-backend/pom.xml test`，预期全部通过（含既有 1 项跳过）。
2. 前端规则：`node --test shared/productEditing.test.mjs`，预期 20 项通过。
3. 前端构建：`npm --prefix shop-admin-vue run build`，预期成功。
4. 浏览器：商家后台商品"编辑"对话框——
   - 新增 SKU（合法/重复编码/卡密带库存），观察提示与列表刷新、最低价变化；
   - 删除零库存新 SKU 成功；删除有库存或有订单的 SKU 被拒并说明原因；最后一个 SKU 无删除按钮；
   - 增删后未保存的标题/售价输入保留，再点"保存资料与售价"一次成功。
