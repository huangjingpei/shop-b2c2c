<!-- 文件说明：记录成功卡密导入批次、成员追溯、数据约束和用户手动验收步骤。
维护注意：本轮未运行编译/测试/构建，Sprint 11也未收到人工验证反馈。仅记录新成功导入，不给旧卡密补造批次；成员查询不返回正文，批次快照不代表实时库存。 -->
# Sprint 12：卡密导入批次与追溯

日期：2026-11-04。状态：代码与用例已编写，待用户人工验证。

## 范围与问题状态

F12继续为“进行中”：补成功导入批次、成员关联及本店记录查询；Sprint 11去重/安全作废及本轮代码均待人工回归。F13已有“部分修复”状态保留，新增导入操作人记录待验证。加密、数据库唯一指纹、存量异常治理、补发和完整敏感字段权限尚未完成。

## 行为与接口

原导入请求不变，服务端从已验证会话取操作人，不接收客户端指定actorId。成功响应继续返回importedCount、newStock，新增batchId。记录商品、SKU、操作人、条数、前后可售数和时间；不保存卡密正文副本，也不为过去的导入推算批次或操作人。

| 接口 | 参数 | 响应 |
| --- | --- | --- |
| GET /api/v1/merchant/card-import-batches | 可选skuId、beforeId；limit默认20，范围1–100 | 本店批次items及nextBeforeId |
| GET /api/v1/merchant/card-import-batches/{id}/cards | 可选beforeId；limit默认20，范围1–100 | 卡ID、当前状态、售出订单号；不含卡密正文 |

新查询均经过后台身份与商户绑定校验，返回no-store。批次详情对外店/不存在返回业务404；列表按其他店铺SKU筛选只返回空列表。明细联表同时比较卡片的商户、商品和SKU，异常跨店关联也不能返回外店数据。

批次和成员分别使用自己的ID倒序游标，读取limit+1条判断下一页。状态为查询时状态，发卡、作废不覆盖原批次的导入数与库存快照。分页不承诺跨请求的全库一致性快照；查看新数据需刷新。

管理端卡密页新增“成功导入批次”、SKU筛选、加载更多和成员明细。筛选变化时必须刷新后再翻页，避免混入上一条件的数据。导入成功提示批次ID，用户可按记录核对不确定的网络结果；重复导入仍按历史去重整批拒绝，不自动重导或伪装成功重放。

## 事务与数据库

沿用商户→商品→SKU的导入锁顺序和独立READ_COMMITTED事务。批次、卡片、每条卡的批次关联及库存增量同事务提交；缺失数据库生成ID、批次关联失败或加库存失败均回滚。没有成功导入就没有成功批次，不记录伪成功或失败批次。

新增card_import_batch、card_import_item两张表，完整基线变为18张表。单卡关联以card_id为主键，防止同时属于两个批次；关联表外键指向批次和卡，禁止删除仍有关联的记录。批次有条数/前后数量一致性约束及商户、SKU游标索引。所有记录只追加，无批次删除、转移或整批作废入口；单卡作废仍使用既有安全流程。

仍只有db/schema.sql一个运行时SQL入口，既有列不变，不需删库。当前启用自动初始化的开发环境可在启动时创建新表；账号需具备建表及外键引用权限，既有卡表结构/引擎需与当前基线兼容。关闭初始化或采用版本迁移的环境按受控发布流程建表。不要物理删除卡密历史来解除批次关联或绕过去重。

## 已编写用例（未运行）

| 场景 | 覆盖 |
| --- | --- |
| 合法导入 | 操作人来自会话，返回batchId，条数和前后数正确，每张新卡关联一次 |
| 输入/生成键 | 缺操作人拒绝；批次或卡缺生成ID时停止并回滚，不产生不可追溯成员 |
| 去重/失败 | 重复卡不留空批次；关联失败回滚批次/卡；库存写入失败回滚已完成的关联 |
| 租户与隐私 | 匿名拒绝；外店批次404；跨SKU筛选为空；异常跨店关联不暴露外店订单；响应不含正文 |
| 分页 | 空结果、最后一页、批次/成员独立游标；非法租户、SKU、批次、游标和页大小拒绝 |
| 生命周期 | 导入后发卡/作废反映当前成员状态，历史导入条数和数量快照不变 |
| MySQL | 18表初始化、重复执行保留批次/成员、生成键、联表分页、唯一关联/外键及回滚 |

单元测试入口为CardAssetServiceTest、CardImportBatchServiceTest；集成用例纳入Sprint1SecurityIntegrationTest，真实SQL用例纳入可选SchemaMySqlTest。前端批次参数用例纳入已有shared/cardAssets.test.mjs。

## 请手动验证

Windows PowerShell工作目录`E:\shop-b2b2c`，Maven命令串行执行。后端默认使用隔离H2/Mock Redis，不连接业务数据库。

```powershell
Set-Location E:\shop-b2b2c
$env:MAVEN_OPTS='-Xms32m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2'
mvn -f shop-b2b2c-backend/pom.xml '-DforkCount=0' test
node --test shared/goodsType.test.mjs shared/idempotency.test.mjs shared/buyerReceipt.test.mjs shared/authSession.test.mjs shared/productEditing.test.mjs shared/inventoryAdjustment.test.mjs shared/cardAssets.test.mjs
$env:NODE_OPTIONS='--max-old-space-size=512'
npm --prefix shop-admin-vue run build
```

预期：后端BUILD SUCCESS，Failures/Errors为0；默认仅跳过需显式开启的SchemaMySqlTest。Node fail为0，Vite生产构建成功；现有chunk体积提示不算失败。

本机安装MySQL 8程序时可再验证独立临时实例，不需要连接3306或提供业务数据库密码：

```powershell
mvn -f shop-b2b2c-backend/pom.xml '-DforkCount=0' '-Dtest=SchemaMySqlTest' '-Dschema.mysql.bin=C:\Program Files\MySQL\MySQL Server 8.0\bin' test
```

预期该用例不跳过并通过。它只验证基线和相关SQL，不能替代整套业务在MySQL上的并发/渠道联调。

页面验收：在隔离开发环境导入两条虚构卡密，记录返回批次；核对操作人、商品/SKU、条数和快照，进入明细确认没有正文。发卡或安全作废后刷新成员状态，原批次数量不变。用另一店账号访问详情应拒绝；修改SKU筛选后先刷新再翻页。历史卡继续可查询，但不会凭空出现旧批次。

反馈失败时提供用例名、文件/行号和必要堆栈，完整日志留本地，不粘贴真实卡密、令牌或完整请求体。人工反馈通过前，不把本轮标为已验证或已修复。
