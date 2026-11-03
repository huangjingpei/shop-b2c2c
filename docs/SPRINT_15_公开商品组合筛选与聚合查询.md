# Sprint 15：公开商品组合筛选与聚合查询

本文记录主台账D30中公开商品列表的实现范围、兼容约定及人工验证命令。状态：**代码和测试用例已编写，待人工验证**。不代表商品、订单及卡密全部查询问题已解决。

## 问题及本轮变化

原入口用if/else if选择分类或商户，两个条件同时传入时忽略商户；随后逐商品查SKU、逐店查资料。本轮仅改GET `/api/v1/public/products`，保留URL、参数名、R响应封装和data数组。

- categoryId与merchantId均为可选，传入时共同作为AND条件；非正数返回400业务码，正数但不存在或无匹配返回空数组。
- ProductMapper一次SQL连接营业店铺、筛选上架商品并聚合SKU库存，不在Controller逐件查询店铺和SKU。SKU同时按product_id和merchant_id关联，错误归属的历史数据不计入库存。此处仅读取，不修写历史数据。
- 固定PublicProductView仅含原11个字段：id、title、subTitle、goodsType、mainImage、minPrice、merchantId、storeName、storeCode、storeLogo、totalStock。无详情大字段、卡密、身份和支付配置；空副标题/Logo继续允许null。
- 无SKU库存为0，多SKU求和使用Long承接，避免原int加总溢出。库存依然是扣除预占后的可售量，展示不承诺下单一定成功。
- 按sort_order、id升序稳定排列。这是代理补充的确定性排序细节，保留原有商品排序优先级；原商户分支未保证顺序，不应依赖数据库偶然返回顺序。

沿用status=1营业/上架资格及既有库存字段，不修改D26独立店铺接口、D25订单统计、交易状态或扣库存规则。无Schema、索引、种子及初始化入口变化，无需重建数据库。

## 边界及剩余工作

本轮消除公开商品列表的应用层N+1，但仍是全量数组，未增加分页。管理端商品、订单、卡密列表分页与查询优化仍在D30内；不要因此将整个D30标为已修复。以后分页需同步各消费者的响应与空态逻辑，不能直接把data数组替换成分页对象。

SQL在数据库中执行关联及聚合，单次查询不等于已证明大数据量性能达标；真实MySQL的执行计划、容量与耗时尚未验证。GROUP BY列显式列齐，避免依赖关闭ONLY_FULL_GROUP_BY；H2测试仍不能证明MySQL兼容性。

## 新增测试用例

PublicProductControllerTest：四种可选条件组合传递、拒绝非法ID、使用一次Mapper入口且不触发逐件SKU/店铺读取、固定11字段序列化、空字段和Long库存。

Sprint1SecurityIntegrationTest追加隔离H2用例：真实SQL分类/商户组合、空结果、同排序商品按ID排序、排序优先级、下架/缺店过滤、非营业状态、合法营业恢复、多SKU合计、错误商户SKU排除、无SKU商品库存0，以及匿名HTTP读取的价格/店铺字段和敏感字段排除。使用现有隔离Schema及Mock Redis，不连接业务数据库。

## 用户手动执行

执行目录：`E:\shop-b2b2c`，PowerShell。代理不执行以下命令，本轮仅完成静态审阅和git diff格式检查。

先运行本轮用例：

```powershell
$env:MAVEN_OPTS='-Xms32m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2'
mvn -f shop-b2b2c-backend/pom.xml '-DforkCount=0' '-Dtest=PublicProductControllerTest,Sprint1SecurityIntegrationTest' test
```

需要完整后端回归时串行执行，不与上一个Maven进程并行：

```powershell
mvn -f shop-b2b2c-backend/pom.xml '-DforkCount=0' test
```

预期测试失败/错误均为0；完整回归中原生MySQL专项测试默认跳过属正常现象。本轮未改Vue或前端模块，不新增前端构建要求，亦不复用历史结果宣称当前通过。

开发环境人工接口与页面检查：

1. 对两个营业商户分别创建分类A/B商品，同时传categoryId和merchantId，结果只能包含该店该分类；单传分类可跨营业店铺，单传商户不得返回别店商品。
2. 检查商城首页及店铺页仍能消费data数组；无商品/无SKU、空图片或副标题时不应发生字段映射错误。
3. 打烊/封禁店铺商品及下架商品不可出现在列表；重新营业/上架后可见。
4. 若验证MySQL，使用隔离开发环境确认接口执行、库存聚合及ONLY_FULL_GROUP_BY兼容；本轮没有声称执行过该联调或性能压测。

台账保留进行中，后续按用户反馈记录验证结果及缺陷，不通过文档追认未执行的验收。
