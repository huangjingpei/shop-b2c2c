<!-- 文件：SPRINT_29_详情图文与售罄展示.md -->
<!-- 用途：Sprint 29（F11 子项：商品详情图文维护与售罄展示规则）交付、验证与边界说明。 -->
<!-- 维护注意：验收由用户管理；本文件不替代台账状态，真实 MySQL 与浏览器交互未验证。 -->

# Sprint 29：F11 详情图文维护与售罄展示规则

日期：2026-11-07　状态：已实现·待人工验证

## 1. 范围与归属

- 主台账：**F11（部分修复）**。原清单"编辑商品与SKU、上下架、库存调整流水、违规下架/审核、**详情图文维护与售罄规则**"中的最后一项，本轮回补。
- 本轮完成后 F11 初始清单能力均已交付，整体验收状态由用户确认后更新。
- 边界：详情图文只改 `product.detail_images` 白名单内容；售罄为**纯展示派生**，不改交易路径（下单库存校验、预占闸门此前已建，本轮未动）。

## 2. 详情图文维护（detail_images）

### 数据契约（代理自行细化，待业务确认）

- 存储：`product.detail_images` 为 JSON 数组，元素仅两种白名单条目（服务端序列化为**规范JSON**，键序固定 type 在前）：
  - `{"type":"image","url":"..."}`：详情图片，url trim 后 1..255，须以 `https://`、`http://` 或 `/` 开头；
  - `{"type":"text","content":"..."}`：详情文字段落，content trim 后 1..2000。
- 最多 **20 条**；空数组/null 表示**清空**（落库 NULL）。
- 渲染侧一律按图片/纯文本展示，**禁止 v-html**，杜绝 XSS。
- 无数据库结构变更（列早已存在）；H2 夹具列由 JSON 简化为 VARCHAR(60000)，口径同 spec_data（H2 JSON 列会把参数化字符串包成 JSON 字符串字面量，应用层不使用 JSON 操作符）。

### 后端

- `ProductEditingDTOs.Update` 增加 `detailImages`（`DetailItem{type,url,content}`）。
- `ProductEditingService.normalizeDetailImages`：白名单校验+规范化（trim、键序固定 LinkedHashMap），非法 400；该 JSON 串**纳入 revision 指纹**——并发编辑图文同样触发 409 冲突，未变化时不落库（沿用最小 UPDATE 语义）。
- `ProductEditingMapper.updateMetadata` 白名单增加 `detail_images=#{detailImagesJson}`（独立参数，只接收服务层规范串）。
- 公开详情 `/api/v1/public/products/{id}` 随 Product 实体透出 JSON 字符串，前端容错解析。
- 发布接口不收图文：发布后经编辑对话框维护（代理细化）。

### 管理端（shop-admin-vue）

- `productEditing.mjs`：`parseDetailImages`（容错解析快照）、`detailImagesPayload`（提交校验，口径与后端一致，空数组→null）；`editorForm`/`updatePayload` 纳入 detailImages。
- 编辑对话框新增"详情图文"区：条目类型切换（图片/文字）、地址/内容输入、上移/下移/删除、最多 20 条。

## 3. 售罄展示规则（纯前端派生）

- 商城 `productDetail.mjs` 新增纯函数：`isSkuSoldOut`（缺省/非数按 0 处理，不伪造可售）、`isProductSoldOut`（无 SKU 或全部售罄）。
- 首页列表：商品总可用现货 ≤0 时主图叠加"已售罄"置灰遮罩（仍可进详情）。
- 详情页：整品售罄横幅提示暂不可下单；售罄规格在选择矩阵置灰并标"售罄"徽章；购买/代付按钮沿用既有按当前 SKU 库存禁用（交易拦截仍以后端为准）。

## 4. 测试

| 层 | 用例 | 覆盖 |
| --- | --- | --- |
| ProductEditingServiceTest（+3 方法） | 合法图文落规范 JSON 且 revision 变化；未变化跳过写、null 清空；非法条目（类型/协议/长度/条数/null 元素）400 且 20 条边界通过 | 全 mock 契约 |
| Sprint1SecurityIntegrationTest（+1 用例，共 223 项） | 编辑落库规范 JSON→商家快照/公开详情透出→坏协议/超长/超条数/未知类型 400 不落库→空数组清空为 NULL | 真实 SQL+H2 |
| shared/productEditing.test.mjs（+4 用例，共 25 项） | 解析容错、表单恢复、payload trim/边界/清空、非法条目拒绝与 XSS 协议拦截 | 纯 Node |
| shared/productDetail.test.mjs（+3 用例，共 17 项） | 解析容错白名单、售罄判定（缺省/非数按不可售）、渲染侧禁 v-html 指令契约 | 纯 Node |

## 5. 验证（代理执行）

- `mvn -f shop-b2b2c-backend/pom.xml test -Dtest="Sprint1SecurityIntegrationTest,ProductEditingServiceTest"`：223 + 16 项通过，BUILD SUCCESS
- 全量 `mvn -f shop-b2b2c-backend/pom.xml test`：**1024 项通过（1 项既有跳过），BUILD SUCCESS**
- `node --test shared/productDetail.test.mjs shared/productEditing.test.mjs`：37 项通过
- `npm --prefix shop-admin-vue run build`：通过（✓ built in 13.33s）
- `npm --prefix shop-storefront-vue run build`：通过（✓ built in 3.21s）

**未验证项**：真实 MySQL（JSON 列读写与规范串等价比较，生产为 JSON 列、夹具为 VARCHAR 的差异已在夹具注释说明）、浏览器交互（图文编辑器排序、售罄遮罩/横幅展示）。不标验收通过。

## 6. 人工验证步骤

1. 后端：`mvn -f shop-b2b2c-backend/pom.xml test`，预期全部通过（含既有 1 项跳过）。
2. 前端规则：`node --test shared/productDetail.test.mjs shared/productEditing.test.mjs`，预期 37 项通过。
3. 前端构建：`npm --prefix shop-admin-vue run build`、`npm --prefix shop-storefront-vue run build`，预期成功。
4. 浏览器：商家编辑商品——添加图片+文字条目、排序、保存；商城详情页按顺序展示"商品详情"区（文字按纯文本）；清空后详情区消失。将某商品全部 SKU 库存调为 0：首页出现"已售罄"遮罩、详情页出现售罄横幅、售罄规格标记且不可下单；恢复库存后展示复原。
