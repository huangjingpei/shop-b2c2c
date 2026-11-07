<!--
文件：SPRINT_45_D38海报存储落盘与格式兼容.md
用途：记录主台账 D38 剩余部分（海报 Base64 入库）的实现范围、兼容策略、待执行验证与未验证边界。
维护注意：本文只描述代码代理已完成的实现与待用户执行的命令，不代表已验收；真实磁盘/多实例/构建与浏览器下载需人工验证后另行更新结论。
-->
# Sprint 45：D38 海报存储落盘与格式兼容

- 日期：2026-11-07
- 主台账：D38（P2，`部分修复（待人工验证）`）
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：待人工验证（本轮未执行编译、测试或生产构建）

## 1. 复核结论（D38 前序部分）

| 子项 | 现状 | 来源 |
| --- | --- | --- |
| 商城运行时加载外部 Tailwind CDN | 已改为本地 CSS 构建，依赖锁定 | Sprint 21 |
| 订单列表返回实体与大字段 | 列表/统计 SQL 不读 `poster_url`，商家按订单 ID 按需读取 | Sprint 22 |
| 海报在事务内同步生成 | 已移出建单事务，提交后由 `attachPoster` 按需补齐；单字段更新不覆盖并发支付 | T26 |
| 海报刷取成本 | `TradeCostLimiter.checkPosterGeneration` 按 IP/凭证限流；已存海报直接复用 | T26 |
| Base64 入库 | 本轮改为 PNG 落盘 + 库内公开路径 | 本 Sprint |
| 独立异步队列、对象存储/CDN、历史行清理 | 未做，保留为后续候选 | — |

## 2. 本轮实现

### 2.1 新增 `PosterStorageService`

- 文件：`shop-b2b2c-backend/src/main/java/com/shop/b2b2c/service/PosterStorageService.java`
- 文件名：分享凭证的 SHA-256（64 位小写十六进制）`.png`，不可逆、不接受外部指定名称。
- 目录：`shop.poster.storage-dir`，默认 `./uploads/order-posters`。该目录位于服务 `WorkingDirectory` 下，且部署脚本已创建/授权 `uploads`，避免写入未授权目录；须纳入备份。
- 写入：先建临时文件，再 `ATOMIC_MOVE` 替换；文件系统不支持原子移动时退化为普通替换；同名重复生成覆盖同一文件。
- 读取：文件名白名单正则 + `normalize` 后限制在根目录内 + 仅普通文件；非法、越界、缺失统一 404，不回显路径或异常原文。
- 空内容返回 `null`；空凭证 400；落盘失败返回 503，不写库。

### 2.2 生成与写库路径

- `PosterUtils.generatePeerPayPoster` 改为 `generatePeerPayPosterBytes`，返回 PNG 字节，失败仍返回 `null`（保持“海报失败不影响已提交订单”的降级语义）。
- `PeerPayServiceImpl.regeneratePoster`：优先返回已存值；缺失时渲染 → 落盘 → 单字段 `updatePosterUrl`，写入 `/api/v1/peer-pay/poster/image/<sha256>.png`。
- `posterBase64` 响应字段名保持不变，内容对新海报为公开路径、对历史行为 Base64；前端 `img src`/`download` 两种形式均可直接使用。

### 2.3 公开只读图片接口

- 新增 `PosterImageController`：`GET /api/v1/peer-pay/poster/image/{fileName:.+}`。
- 返回 `image/png`、`Cache-Control: public, max-age=31536000, immutable`、`X-Content-Type-Options: nosniff`。
- 路径已在 Sa-Token 公开白名单 `/api/v1/peer-pay/**` 内；不新增鉴权、不代理外部地址。

### 2.4 前端格式校验

- `shop-admin-vue/src/utils/storedPoster.mjs` 接受：
  - `data:image/png;base64,` 历史格式（Base64 长度仍校验 4 的倍数）；
  - `/api/v1/peer-pay/poster/image/<64位小写十六进制>.png` 新路径。
- 继续拒绝：外链、`javascript:`、SVG、空 Base64、大小写摘要、带查询串、其他接口路径。
- 商城收银台与商家下单弹窗直接使用该值作为 `img src`/`download href`，无需改动模板。

## 3. 兼容与数据库影响

- `trade_order.poster_url` 为 `MEDIUMTEXT`，无需迁移；新旧值混存。
- 历史 Base64 行不会被自动改写：仍需保留原值用于展示；后续如需清理，应单独评估行体积与回刷策略。
- 新路径长度约 100 字符，远小于列容量。
- 无 Schema 变更，无新增依赖。

## 4. 回归用例

- 新增 `PosterStorageServiceTest`（4 项）：落盘路径格式、同凭证覆盖、不同凭证隔离、非法/越界/缺失 404、空凭证 400 与空内容 `null`。
- 扩充 `Sprint1SecurityIntegrationTest`：
  - 海报补齐成功后订单仍为 `PAID`/`COMPLETED`，`poster_url` 为落盘路径；
  - `GET` 落盘路径返回 `image/png` + `nosniff` + 长缓存；
  - 非法文件名 404；
  - 幂等重放返回同一路径，不重新渲染。
- 扩充 `shared/storedPoster.test.mjs`：新路径与历史 Base64 均可用；非法路径/外链/SVG 等继续拒绝。
- 测试上下文新增 `shop.poster.storage-dir=target/test-posters`，避免写入仓库 `uploads`。

## 5. 待用户执行的验证

```bash
# 后端（串行，勿加 -DforkCount=0）
mvn -f shop-b2b2c-backend/pom.xml test

# shared 规则（含更新后的 storedPoster 用例）
node --test shared/storedPoster.test.mjs

# 前端生产构建（涉及管理端海报格式校验）
npm --prefix shop-admin-vue run build
```

预期：后端测试通过；`PosterStorageServiceTest` 4 项通过；shared 用例通过；管理端构建成功。

## 6. 未验证边界

- 真实磁盘权限、磁盘写满、只读挂载与原子移动失败回退。
- 多实例/多容器共享目录：默认单机目录，跨实例读取会 404。
- 历史 Base64 行的清理与体积治理未做。
- 真正对象存储/CDN、独立异步生成队列未实现；生成仍在请求线程内完成（已移出事务）。
- 浏览器实际下载（`download` 属性）、微信内打开海报、反向代理缓存行为未联调。
- 一键部署后 `uploads/order-posters` 的备份覆盖未验证。

## 7. 回滚

- 回滚代码即恢复 Base64 写入；已落盘文件与库内路径不影响旧版本读取（旧版本前端只接受 Base64，需同步回滚前端或保留路径校验）。
- `uploads/order-posters` 可保留，不影响其它上传资源。
