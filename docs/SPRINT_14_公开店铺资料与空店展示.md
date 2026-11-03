# Sprint 14：公开店铺资料与空店展示

本文说明主台账D26的商城店铺页修复范围、接口约定和人工验证方法。状态：**代码与测试用例已编写，待人工验证**。不复验Sprint 11、12，不代表整个电商业务或资金账本完成。

## 范围与依赖

原问题：Store.vue从首个商品反推店铺，空店无法展示资料，公告/客服电话及认证文案含演示值。Sprint 13处理商家看板，本轮补商城页面，复用既有PublicStoreView，不新建另一套店铺资料模型。

- 新增匿名GET `/api/v1/public/stores/{id}`，独立于商品查询。只返回id、storeCode、storeName、logoUrl、notice、contactPhone；联系号码沿用既有公开契约，禁止扩大到经营者身份、证件、费率、收款配置。
- 沿用商品公开规则：仅status=1营业店铺可读；待审、打烊、封禁、未知状态和不存在统一业务码404。非法非正数ID为400业务码。响应禁止缓存。该接口沿用R封装，业务码不等同HTTP状态，前端必须检查code。
- 页面展示真实资料，缺少电话显示“暂未提供”，缺少公告显示“暂无公告”，删除无依据的“官方认证小微商户”。上架数量不代表可购买库存，实际交易仍由服务端验证。
- 资料成功后才读取本店商品；空数组展示未上架，商品读取失败独立显示错误与重试，不能伪装空店。切换店铺或组件卸载后，迟到响应不能覆盖当前页面。

“仅营业店铺公开”和分阶段读取是代理基于现有资格规则的实现细化；不新增认证业务。D30商品分页、N+1及组合筛选未改；D25账本指标仍依赖资金模块。无SQL、数据库结构或种子变化，无需重建数据库。

## 测试用例与验证边界

后端PublicStoreControllerTest使用Mock仓储与独立MVC，覆盖真实空店、匿名路由映射、六字段白名单、无缓存、非营业/未知/缺失状态、不存在和非正数ID拒绝。独立MVC不经过鉴权链；另在Sprint1SecurityIntegrationTest补H2隔离环境完整拦截链回归，覆盖匿名空店查询、空商品列表、封禁和不存在店铺。均不连接业务数据库，不替代部署环境联调。

前端storePage.test.mjs注入请求函数，覆盖合法资料/商品、空店、缺失电话、非法/越界ID、业务错误、网络失败、错店资料/商品与异常响应。未运行浏览器组件测试；路由竞争和模板展示按下列步骤手工验收。

## 用户手动执行

执行目录：`E:\shop-b2b2c`，PowerShell。代理本轮不运行编译、测试或构建；以下是交付命令，不是通过记录。

```powershell
$env:MAVEN_OPTS='-Xms32m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2'
mvn -f shop-b2b2c-backend/pom.xml '-DforkCount=0' test
node --test shared/goodsType.test.mjs shared/idempotency.test.mjs shared/buyerReceipt.test.mjs shared/authSession.test.mjs shared/productEditing.test.mjs shared/inventoryAdjustment.test.mjs shared/cardAssets.test.mjs shared/orderStatistics.test.mjs shared/storePage.test.mjs
$env:NODE_OPTIONS='--max-old-space-size=512'
npm --prefix shop-storefront-vue run build
```

预期：测试失败/错误为0，商城生产构建成功；原生MySQL专项测试默认跳过属正常现象。若尚未安装前端依赖，先在该工程按锁文件执行npm ci。后端测试使用隔离环境，不连接业务数据库；H2不证明MySQL及支付渠道兼容。

浏览器手工检查：

1. 在开发环境访问有真实公告/电话的营业店铺，再访问没有商品的营业店铺；二者均应显示自身资料，不出现演示电话或认证承诺。
2. 待审、打烊、封禁、不存在店铺均显示不可用，不显示此前访问店铺内容；空公告/电话显示缺省说明。
3. 浏览器拦截商品请求或令其失败，店铺资料应保留且显示商品错误，不能显示“暂未上架”；恢复后重试应正常。
4. 网络限速下快速从店铺A切至B，A的迟到响应不能覆盖B；离开页面后不得回写已卸载组件。

本轮静态审阅及git diff格式检查不替代上述命令和人工验收。D26保持进行中，用户反馈验证结果后再更新状态。
