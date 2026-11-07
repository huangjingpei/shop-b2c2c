<!--
文件：SPRINT_37_D27D28代付归属与付款页状态.md
用途：D27（代付收银台无法区分本人/他人预占、页面卸载释放不可靠）与 D28（付款成功后交付内容展示口径）本轮修复记录。
维护注意：
 1. 本轮只做前端归属识别与卸载释放加固，以及 D28 的口径确认；所有条目均为“待人工验证”。
 2. D27 依赖后端先发布：公开详情新增可选 X-Reservation-Token 与 lockOwnedByCaller；旧后端不返回该字段时前端回退本地判断。
 3. 付款页仍不展示交付内容（卡密/服务凭证），这是隐私设计，不是遗漏；买家/领取人通过独立领取凭证查询。
 4. 真实浏览器关闭、pagehide 与跨域 keepalive 行为需在预发环境验证，构建通过不代表网络层一定送达。
-->
# Sprint 37：D27 代付预占归属与 D28 付款页状态

- 日期：2026-11-07
- 主台账：D27、D28
- 状态：已修复 · 待人工验证
- 执行方：代码代理实现与静态审阅；编译、测试执行、生产构建按本轮授权由代理运行，结果仅作参考，验收由用户管理。

## 1. D27：区分本人/他人预占，卸载可靠释放

### 现状复核
- 旧问题中的 `quickPay` 已不存在；当前 `startReservation` 统一走 `pre-lock`，不会在已预占时跳过抢锁。
- 但公开详情 `GET /api/v1/peer-pay/detail/{token}` 不接收 ownerToken，前端只能凭 sessionStorage 猜测预占归属；他人预占时仍展示“立即代付”，点击后才 409。
- 页面卸载虽在 `onUnmounted` 调 cancel-lock，但普通 axios 请求在浏览器关闭/切页时可能被中止；原描述“离开即时释放”不可靠。

### 本轮实现
1. 后端详情接口新增可选请求头 `X-Reservation-Token`；仅当预占有效且凭证与 `InventoryReservation.ownerToken` 一致时返回 `lockOwnedByCaller=true`，否则 false；非 `RESERVED_LOCK` 状态不返回该字段。
2. 详情响应仍不包含 `ownerToken`、卡密、服务凭证；新增字段只是布尔判断。
3. `preLock` 分支区分两类冲突：
   - 有效预占属于他人 → “订单已被其他付款人预占中，请稍候再试”；
   - 预占记录缺失/已释放/已过期但订单仍停留在 `RESERVED_LOCK` → “本次预占已结束，请刷新账单后重试”，不再让同一付款人误以为被他人抢占。
4. 前端详情请求带本地 ownerToken；`lockOwnedByCaller=true` 时恢复本人倒计时与“释放库存”入口；false 时显示“订单已被其他付款人预占”，禁用抢锁按钮，并清理失效本地凭据。
5. 兼容部署顺序：旧后端不返回 `lockOwnedByCaller` 时按本地 ownerToken 回退判断。
6. 卸载释放加固：`pagehide` 监听 + `fetch keepalive`；`onUnmounted` 同样用 keepalive；释放使用组件加载时的 `activeToken`，避免路由切换后误清新 token 凭据。

### 涉及文件
- 后端：`PeerPayController.java`、`PeerPayService.java`、`PeerPayServiceImpl.java`、`PeerPayDTOs.java`
- 前端：`shop-storefront-vue/src/views/PeerPayCashier.vue`、`shop-storefront-vue/src/utils/peerPayReservation.mjs`（维护说明）
- 测试：`Sprint1SecurityIntegrationTest#detailLockOwnershipOnlyVisibleToOwner`

## 2. D28：付款页交付内容口径

复核结论：当前代码不存在 `serviceVoucher`/`deliveredVoucher` 状态映射。付款成功（首次支付或刷新后 `peerStatus=PAID`）统一进入成功态，页面明确提示“商品与服务内容由买家凭独立领取凭证查询，付款分享页不展示交付内容”。

因此 D28 的“刷新后看不到交付内容”不是缺陷，而是防止未登录分享页泄露卡密/服务凭证的隐私设计；首次支付与刷新后的展示口径已一致。后端集成测试已断言 PAID 详情不包含 `serviceVoucher`。

本轮不改付款页展示内容；如业务要求付款人也能看到交付内容，需另立需求并设计领取权限，不能直接复用分享 token。

## 3. 验证（代理执行，仅作参考）

| 命令 | 结果 |
| --- | --- |
| `mvn -f shop-b2b2c-backend/pom.xml test` | 1087 项通过，0 失败/0 错误，1 项既有跳过 |
| `node --test shared/peerPayReservation.test.mjs` | 6 项通过 |
| `npm --prefix shop-storefront-vue run build` | 通过（Vite 5，105 模块） |

新增/覆盖断言：
- 本人凭证 → `lockOwnedByCaller=true`；无凭证/错误凭证 → false；取消预占后字段不返回；
- 详情响应不包含 `ownerToken`；PAID 详情不包含 `serviceVoucher`（既有用例）。

## 4. 未验证与风险

- 真实浏览器关闭/刷新/切后台时 `pagehide + keepalive` 是否送达取消请求，需预发环境抓包验证；即使未送达，服务端 15 分钟到期仍会释放。
- 多付款人并发抢同一订单、跨浏览器/跨设备切换、网络极差场景未做真实联调。
- 旧后端 + 新前端的回退逻辑未在真实部署顺序下验证；发布顺序仍要求后端先发。
- 领奖凭证与付款页交付内容的业务口径未变更，D28 仅按现状确认，不代表新增“付款人可见交付内容”能力。
