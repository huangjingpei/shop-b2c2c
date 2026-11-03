# Sprint 16：代付状态编码与默认值统一

本文记录原台账D33中代付状态编码的根因、实现边界和人工验证方式。状态：**实现与回归用例已编写，待人工验证**。服务费状态集合差异尚未处理，D33保持进行中。

## 根因与变化

TradeOrder原默认peerStatus=INTENT且注释写LOCKED，但数据库默认INIT，实际建单/预占使用INIT与RESERVED_LOCK。现有建单显式赋值掩盖了差异，新路径仅依赖实体默认值时可能创建无法预占的订单。

新增PeerStatus作为后端固定字符串编码来源：INIT、RESERVED_LOCK、PAID、EXPIRED、CANCELLED。修正实体默认值为INIT，普通建单、买家代付、商户推单、预占、释放、支付CAS、到期处理和关闭统计复用常量。接口、实体及DTO继续保留String字段，不引入枚举序号存储或改变JSON类型；这是代理为保持兼容所做的技术细化，没有新增业务状态。

支付CAS的SQL仍要求UNPAID、RESERVED_LOCK和未过期锁，常量替换不放宽前提；库存与卡密事务、锁顺序不变。INIT、RESERVED_LOCK分别表示待预占与有效预占，不能把历史INTENT、LOCKED或未知值直接当作等价支付资格。

数据库基线及H2夹具原本已默认INIT，因此本轮不修改SQL、不重建数据库、不自动清洗历史状态。保留数据环境若已有旧编码，需要结合订单、预占流水及时间确认后制定迁移；仅替换字符串不能证明库存已经预占。

## 依赖和未完成范围

- CANCELLED为既有Schema/统计契约保留编码，本轮未实现订单取消功能。
- D10/F08超时释放及D25分类口径继续复用原实现；本轮不改变倒计时或资金业务。
- 服务费的NORMAL/TRIAL/OVERDUE与EXEMPTED/ARREARS仍未统一，其含义涉及旧数据兼容和收费政策，后续单独处理。
- 未将全部订单、分账、服务费状态改成统一枚举；本轮明确只处理代付状态子范围。

## 用例及验证边界

PeerStatusContractTest覆盖实体默认值与JSON、运行时Schema和H2夹具默认值一致，以及固定/历史未知编码仍按字符串原样序列化。读取结构文本不等于执行SQL。

Sprint1SecurityIntegrationTest追加隔离H2回归：实体默认值持久化与数据库默认值一致；INIT→RESERVED_LOCK→INIT→再次预占→PAID的存储与公开响应；历史INTENT/LOCKED、未知值和终态不能预占或付款，失败后库存/卡密/订单保持原状。现有超时用例补EXPIRED编码断言，并复用原支付CAS、回滚及并发回归。测试使用Mock Redis，不连接业务数据库，不证明真实MySQL或支付渠道通过。

## 用户手动运行

执行目录：`E:\shop-b2b2c`，PowerShell。代理本轮只做静态审阅、文档链接及git diff格式检查，未执行编译、测试或构建。

本轮定向验证：

```powershell
$env:MAVEN_OPTS='-Xms32m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2'
mvn -f shop-b2b2c-backend/pom.xml '-DforkCount=0' '-Dtest=PeerStatusContractTest,OrderStatisticsTest,Sprint1SecurityIntegrationTest' test
```

完整后端回归，须等上一条命令结束后串行运行：

```powershell
mvn -f shop-b2b2c-backend/pom.xml '-DforkCount=0' test
```

预期失败/错误为0；原生MySQL专项测试默认跳过属正常现象。本轮没有前端模块变化，无新增前端构建要求。全部命令由用户执行，当前没有通过记录。
