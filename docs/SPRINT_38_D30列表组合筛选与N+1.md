<!--
文件：SPRINT_38_D30列表组合筛选与N+1.md
用途：D30「列表查询：组合条件、分页、N+1」本轮修复记录：复核各列表现状，消除商家商品列表的 N+1。
维护注意：
 1. 本轮只改查询方式，不改返回契约与权限范围；所有条目均为“待人工验证”。
 2. 商家商品列表 SKU 必须走 findByProductIds 单次批量查询；任何人回退为逐商品 findByProductId 都视为回归，
    MerchantProductContractTest 会拦截。
 3. 订单/卡密列表的分页与聚合是既有能力（T24/T25），本文件不重复实现；索引优化归 D32，不在本轮。
-->
# Sprint 38：D30 列表组合筛选与 N+1

- 日期：2026-11-07
- 主台账：D30
- 状态：已修复 · 待人工验证
- 执行方：代码代理实现与静态审阅；编译、测试执行按本轮授权由代理运行，结果仅作参考，验收由用户管理。

## 1. 复核结论（三处子项）

| 台账子项 | 复核结果 | 处理 |
| --- | --- | --- |
| 商品列表 categoryId 与 merchantId 不能组合 | 已过时：`findPublicProducts` 单 SQL，categoryId/categoryIds/merchantId/keyword/goodsType 均为 `<if>` 叠加 | 无需改代码；保持 categoryIds 与 categoryId 互斥校验 |
| 订单/卡密接口全量返回 | 订单列表已 keyset 分页（T24）+ 库内聚合（T25）；卡密批次/成员已 keyset 分页 | 无需改代码 |
| 逐条补查 N+1 | `GET /api/v1/merchant/products` 逐商品 `findByProductId` 属真实 N+1 | 本轮修复 |

## 2. 本轮修改

1. `ProductSkuMapper` 新增 `findByProductIds(Long merchantId, List<Long> productIds)`：单次 `IN` 查询，仍带 merchantId 过滤；空列表直接返回 `List.of()`，避免非法 `IN ()`。
2. `ProductController.listMerchantProducts`：商品列表先查出后，单次批量取全部 SKU 并按 productId 内存分组；空商品列表直接返回空数组。
3. 返回契约不变：`List<MerchantProductView>{product, skus}`，前端 `merchantProductRows` / `skusForMerchantProduct` 无改动；SKU 始终归属本店，无跨店泄露。
4. 不改公开列表 SQL、不改公开详情（单商品单查询，本就非 N+1）、不改订单/卡密分页。

查询次数：修改前 1 + N（N=本店商品数），修改后固定 2。

## 3. 测试（代理执行，仅作参考）

| 命令 | 结果 |
| --- | --- |
| `mvn -f shop-b2b2c-backend/pom.xml -Dtest=MerchantProductContractTest test` | 通过 |
| `mvn -f shop-b2b2c-backend/pom.xml test` | 全量通过，0 失败/0 错误，1 项既有跳过 |

`MerchantProductContractTest` 更新要点：
- 多商品用例断言仅调用一次 `findByProductIds`，且 `findByProductId` 一次都不调；
- 空商品列表断言两种 SKU 查询都不触发；
- 包装字段名 `product/skus`、SKU 归属与空 SKU 行为继续锁死。

## 4. 未验证与边界

- 真实 MySQL 大数据量下的执行计划、`product_sku(product_id)` 索引命中情况未验证；索引补齐归 D32。
- 商家商品列表仍是整店全量返回（供选品/看板联动），未做分页；若单店商品数远超预期需另立分页需求并同步前端。
- H2 通过不能替代真实 MySQL；浏览器端列表渲染未验证。
