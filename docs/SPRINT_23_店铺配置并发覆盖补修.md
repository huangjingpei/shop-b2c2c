<!-- 文件说明：Sprint23记录R09整实体更新残留的根因、五条写路径的定向SQL及人工验证入口。
维护注意：沿用D12/D13/F13，关联D33/F10日期清除；无Schema或收费政策变化，测试由用户运行，未反馈前不能标验收通过。 -->
# Sprint 23：店铺配置并发覆盖补修

日期：2026-11-05。基线：`5c0cc95`。范围：**R09 / D12、D13、F13**，关联R23 / D33、F10的已存在显式清日期契约。状态：**已实现及编写用例，待人工验证**。

## 根因与前次整改残留

`76bcffd`为状态/费率加了定向SQL，却在随后继续save已读取的MerchantStore；费用/通道路径也仍整实体保存。不同操作读取旧快照后提交可覆盖封禁、费率、月费或收款配置，串行“先封禁、再改公告”的用例不能证明这些交错安全。本轮属于实际Bug补修，复用原R09，不新增独立需求。

## 实现边界

五条既有店铺更新路径统一只写本操作列：

| 操作 | SQL允许更新列 |
| --- | --- |
| 平台营业状态 | status |
| 平台抽成 | commission_rate |
| 平台服务费 | monthly_fee、fee_free_until、fee_status |
| 平台收款通道 | payment_channel_type、custom_pay_qrcode |
| 商家展示资料 | notice、logo_url、contact_phone、custom_pay_qrcode |

移除定向SQL后的全实体保存及对读快照的无用修改。SQL里COALESCE按执行时数据库列保留未传值，不能先将未传字段填成旧读取值。创建店铺仍使用新实体插入；不存在更新其他店铺实体的生产save调用。

已有店铺更新的编号非法返回400、记录缺失返回404，取代原缺失记录走默认500。空收款/资料请求返回400。查询及写异常继续向上抛出，由已有异常处理返回；不吞数据库异常，不在失败后退回普通save。

保留R23协议：`clearFeeFreeUntil=true`明确清日期，优先于同时传入的日期；false/未传且日期null保留；有新日期则按当日00:00保存。移除实体全局ALWAYS写null，清除仅由服务费专用SQL执行，其他更新不能误清日期。五种服务费编码、未知旧值未传时保留、零费用等现有语义不变。

商家租户仍从会话取值，请求体的id/status/commission等字段不用于定位或写入；店长/超管权限检查直接执行，不因无会话上下文吞异常。未绑定账号、员工拒绝由既有授权与本次回归覆盖，不增加新角色规则。

删除没有调用方、会吞FOR UPDATE异常回退普通查询的Mapper辅助方法。这里不依赖显式行锁保护全实体：数据库原子列级UPDATE在原事务内执行，跨操作未涉及的列不写回。相同列并发仍按数据库写入顺序最终生效，尤其资料与通道共享custom_pay_qrcode，不提供编辑版本冲突检测；该能力另行设计。

当前没有物理删店入口。存在性读取不是行锁；重复设置相同值允许成功，兼容MySQL返回changed rows=0。若未来引入物理删店或复杂跨列一致性规则，必须统一删除/版本/锁策略，不能靠本轮的存在性读取保证并发删除检测。

本轮不新增审计系统、支付开通/收费引擎或迁移，不改变库存/资金模型；F13和D13整体仍部分完成。

## 用例与静态审阅

- `MerchantStoreUpdateTest`：五入口缺记录不更新、非法编号/空请求、会话租户及注入字段忽略、权限失败不访问库、重复设置返回0兼容、写异常不退回全实体。
- `ManagementSecurityTest`：费率合法边界改为验证定向更新，不再把修改Mock对象当作保存成功。
- `ServiceFeeConfigTest`：五状态/零费用、空值保留参数、显式清除、非法编码和null请求；Mock只证明入口参数，不冒充SQL结果。
- `Sprint1SecurityIntegrationTest`：真实H2 Mapper与Spring事务，屏障暂停旧读取、另一路提交后才继续，覆盖状态↔费率、费用↔通道、公告↔封禁及同收费接口不同列并发。另覆盖实际null保留/清除/重新设日期/空字符串收款码与未传通道保留。

测试通过与否未知。本轮仅静态核对调用方、允许列、异常传播、文件链接和git diff空白。H2即使通过也不替代MySQL隔离级别、驱动影响行数与并发验证。没有改SQL基线，无需删库或重建。

## 用户手动验证

执行目录：`E:\shop-b2b2c`，PowerShell。同一工作区Maven命令串行执行：

```powershell
Set-Location E:\shop-b2b2c
$env:MAVEN_OPTS='-Xms32m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2'
mvn -f shop-b2b2c-backend/pom.xml '-DforkCount=0' '-Dtest=MerchantStoreUpdateTest,ManagementSecurityTest,ServiceFeeConfigTest,Sprint1SecurityIntegrationTest' test
```

聚焦用例预期编译成功、Failures/Errors为0、屏障用例无超时。若需要完整后端回归，另行运行：

```powershell
mvn -f shop-b2b2c-backend/pom.xml '-DforkCount=0' test
```

已有其他整改与本次共同进入全量回归；此前日志结果不代表本次修改通过。默认隔离MySQL用例跳过仍属于未验证MySQL。本轮未修改Vue或前端工具，不要求前端构建。

可在隔离环境补MySQL两连接交错验证和重复同值设置（含useAffectedRows两种配置），不得将H2夹具写入业务库。生产构建、MySQL专项、浏览器联调均未执行。

## 剩余范围

同列编辑冲突检测、敏感配置完整审计、正式收款渠道和服务费自动收费仍在原D/F范围；其他R项不因本次交付关闭。工作区已有备份脚本/测试、缓存目录及merchat.md保留，不纳入本次提交。
