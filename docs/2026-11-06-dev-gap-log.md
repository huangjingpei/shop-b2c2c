# 2026-11-06 开发过程缺口记录

用途：记录执行修复清单时发现的、任务卡未覆盖或与实际代码/阶段边界不一致的问题，避免顺手改动或静默遗漏。每条包含发现位置、现象、不属本卡范围的原因、建议归属与影响面。已验证状态由用户反馈填写，本文件只记录事实与待办。

## 记录规则

- 只记录，不在本文件标记“已修复”，除非对应任务卡提交并附证据。
- 不记录已由任务卡明确覆盖的普通实现细节。
- 发现 P0 级新缺陷时应即时向用户报告，再补录本文件。

## G01 T01 与 T28 同文件“须同批改”约束在 S0 无法满足

- 发现位置：[docs/2026-11-06-remediation-plan.md](2026-11-06-remediation-plan.md) T01"依赖/冲突"；[ProductDetail.vue](file:///e:/shop-b2b2c/shop-storefront-vue/src/views/ProductDetail.vue)。
- 现象：T01 卡片要求与 S4 的 T28（迟到响应覆盖新商品）同批修改同一状态机；本轮用户明确只执行 S0 T01–T04，且 S0 要求可独立上线。
- 不属本卡范围的原因：T28 属 S4，涉及迟到响应代付/海报链路，提前实现会扩大 S0 业务范围。
- 建议归属：T28；实施时必须基于本次抽取的 [productDetail.mjs](file:///e:/shop-b2b2c/shop-storefront-vue/src/utils/productDetail.mjs) 增加请求序号/取消令牌，避免回退本次的 finally 复位。
- 影响面：公开商品详情页在极慢请求与新商品快速切换时仍可能出现旧响应覆盖新商品；不影响 S0 主链路恢复。

## G02 T04 与 T19 同文件，后续须合并模板修改

- 发现位置：[04-deploy.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/04-deploy.sh)、[02-init-infra.sh](file:///e:/shop-b2b2c/deploy/aliyun/server/02-init-infra.sh)。
- 现象：T04 已在 `.env` 生成与部署预检中加入 `SPRING_DATASOURCE_USERNAME/PASSWORD` 强制校验；T19 还要改前台地址与 Redis 库号，同文件。
- 不属本卡范围的原因：T19 属 S1，涉及前台地址/Redis 回退，本轮不处理。
- 建议归属：T19；修改时必须保留 T04 的四键非空校验与 `admin123` 拒绝逻辑，并同步腾讯脚本。
- 影响面：若 T19 整段重写 `.env` 模板，可能回退凭据强制注入，导致生产再次回退默认账号。

## G03 生产首次管理员仅支持环境变量注入，未实现随机口令生成

- 发现位置：[AdminBootstrap.java](file:///e:/shop-b2b2c/shop-b2b2c-backend/src/main/java/com/shop/b2b2c/config/AdminBootstrap.java)。
- 现象：T03 允许“首次随机口令或环境变量注入”，本轮实现环境变量注入并拒绝 `admin123`；未生成随机口令，也未提供口令展示渠道。
- 不属本卡范围的原因：随机口令需要一次性安全展示与轮换流程，涉及运维约定，任务卡未给出展示渠道。
- 建议归属：T03 的运维补强或新增候选（生产初始化彩排）。
- 影响面：合法管理员创建仍可用；缺少随机口令便利性，不影响安全边界。首次创建后须由环境负责人轮换并移除环境变量。
