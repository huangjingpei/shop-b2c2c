# Sprint 20：依赖就绪探针与Redis写保护

本文记录主台账D37的存活/就绪分离、依赖故障边界和人工验证方式。状态：**实现和测试用例已编写，待人工验证**。不执行部署、不修改现有Redis实例配置，不代表交易、渠道或资金验收通过。

## 接口与网关

原GET `/api/v1/auth/health`保持应用存活响应；新增匿名GET `/api/v1/auth/ready`。就绪检查全部成功时HTTP与业务码均为200，否则均为503。响应禁止缓存，仅含status、database、redis的UP/DOWN摘要，不回显异常、地址、SQL、INFO原文或凭据。

通用Nginx保留`/healthz`存活入口，增加`/readyz`转发就绪接口并保留503。腾讯云发布脚本改用就绪接口，不再因存活UP就报告依赖检查成功；其他旧云脚本仍按D35继续适配，本轮不宣称整套部署可用。

## 检查规则与负载边界

- 数据库通过当前应用数据源执行SELECT 1，校验返回1，语句超时设为1秒，关闭结果集和语句，连接由JdbcTemplate管理。
- Redis通过当前应用连接执行PING及INFO persistence：必须PONG、loading=0、rdb_last_bgsave_status=ok，aof_enabled必须明确为0或1；启用AOF时还要求aof_last_write_status和aof_last_bgrewrite_status均为ok。缺失/未知指标、ACL拒绝及连接异常均DOWN。
- 两项并行检查共享1.5秒依赖等待预算；用两条后台线程、无排队任务和1秒结果缓存限制重复检查。并发请求复用新鲜样本。超时取消任务；驱动若暂不响应中断，最多继续占用这两条线程，后续提交拒绝并返回DOWN，不新增无限线程或队列。没有改动全应用连接池或Redis超时。

这些是代理基于“故障不能报告就绪”细化的保守检查规则。不是全量业务验证：SELECT 1不证明业务表、写权限、事务及迁移正确；PING/INFO不证明具体Redis键写权限或每条写命令可用，也不代表支付渠道可用。即使仅使用AOF，已有RDB失败也会被报告为DOWN，需排查或明确调整持久化策略，不能当作无关告警忽略。

本轮不写探针业务键、不更改Schema、不执行CONFIG SET。Redis ACL需允许PING和INFO persistence；若现有最小权限不含INFO，就绪会失败，需由运维按策略处理权限，不能通过把未知状态改成UP绕过。

## Redis初始化修复

移除腾讯云02-init-infra.sh中关闭stop-writes-on-bgsave-error及CONFIG REWRITE的命令，保留原连通性检查。脚本今后不会通过修改持久化保护掩盖故障。

以前执行过旧脚本的实例可能仍为no，本次代码提交不会改变它。用户需在已确认的目标实例上人工核对运行配置、持久化错误及配置文件，修复磁盘/权限等根因，再按环境管理策略恢复保护并确认重启后持久生效。不能仅靠重新运行初始化脚本证明旧配置已修复，也不要为验证本轮代码运行具有数据库写入的初始化脚本。

## 测试用例与未验证范围

ReadinessServiceTest使用Mock JDBC/Redis，覆盖正常检查、SQL资源释放、双依赖异常、错误SQL结果、异常PING、缺失/未知指标、RDB/AOF故障、新鲜样本及并发复用、不响应中断时的有界等待和提交拒绝。

Sprint1SecurityIntegrationTest追加匿名200/503及no-store契约、故障时存活接口仍UP，以及H2真实SELECT 1配合Mock Redis的检查。H2、Mock和静态保护断言不能代替真实MySQL/Redis故障联调。

test_nginx_gateway.py增加就绪路径和503透传，现为8个真实Nginx隔离用例，另有2个只读脚本契约用例防止写保护关闭和发布探针退回存活入口。Nginx不可用时前8项跳过，后2项通过也不能算整组通过。历史Sprint 19的7项范围不覆盖新增就绪路径。

## 用户手动执行

PowerShell执行目录：`E:\shop-b2b2c`：

```powershell
$env:MAVEN_OPTS='-Xms32m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2'
mvn -f shop-b2b2c-backend/pom.xml '-DforkCount=0' test
```

预期后端测试失败/错误为0，原生MySQL专项默认跳过属正常现象。后端定向运行可选`'-Dtest=ReadinessServiceTest,Sprint1SecurityIntegrationTest'`，不要与完整回归同时运行。

已安装Nginx的Linux/WSL Ubuntu，从仓库根运行：

```bash
cd /mnt/e/shop-b2b2c
PATH="/usr/sbin:$PATH" python3 -m unittest discover -s deploy/tests -p 'test_nginx_gateway.py' -v
```

预期10个测试无失败、无错误、无跳过。代理未运行上述命令、Nginx、真实构建或部署，仅静态审阅和检查git diff/本地文档链接。本轮没有前端模块变化。

真实依赖联调仅在隔离开发环境手动进行：确认健康时ready=200；分别让数据库不可达、Redis不可达或持久化指标异常，确认ready=503且health仍可反映进程存活；恢复依赖并等待新样本后应恢复。不要对业务环境注入故障。云脚本其余发布、探针总等待时间和回滚行为仍须按D35审阅；D37保持进行中/待人工验证。
