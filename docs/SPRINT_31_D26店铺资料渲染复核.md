# Sprint 31 — D26 店铺页真实资料渲染复核

## 主台账编号
- D26 店铺页真实资料渲染（原状态：进行中）。

## 复核结论
- 本轮无新增代码。经核查，D26 两项原始缺陷均已由前期交付解决：
  1. 商城店铺页 `Store.vue`：通过 `/api/v1/public/stores/{id}` 取真实店铺资料（名称/编码/Logo/公告/客服电话），`PublicStoreController` 仅返回 `status=1` 营业店铺，`PublicStoreView` 只暴露六字段白名单，不暴露身份证/费率/收款配置；商品读取失败与空店分开处理，路由切换忽略迟到响应。
  2. 商家看板 `merchant/Dashboard.vue`：通过 `/merchant/profile` 取真实店铺状态与抽成比率，`storeDisplay` 按 `status` 映射（待审核/正常营业/已打烊/已封禁/已注销），已无写死的「正常营业」「微信直结生效」或演示公告/电话。

## 验证命令与结果
- shared 规则：`node --test shared/storePage.test.mjs` → pass 26。
- 商城前端构建：`npm --prefix shop-storefront-vue run build` → ✓ built in 2.59s。
- 全量后端与 shared 规则已在 Sprint 30 验证通过（1044 + 322），本轮无后端代码改动。

## 未验证项
- 浏览器端真实渲染与未营业店铺 404 体验待人工验收。
- 店铺状态变更（打烊/封禁）后公开入口的实时隐藏行为依赖后端 status 校验，未做并发变更场景验证。

## 文件变更
- 台账 `docs/B2B2C_项目缺陷与未实现功能清单_2026-11-03.md`：D26 状态由「进行中」更新为「已实现·待人工验证」。
- `docs/README.md`：导航表补充 Sprint 31。
- 新增本复核文档。
