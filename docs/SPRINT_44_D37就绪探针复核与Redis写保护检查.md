<!--
文件：SPRINT_44_D37就绪探针复核与Redis写保护检查.md
用途：D37「存活探针不查依赖、Redis初始化关闭写保护」的复核收尾与状态脚本只读检查记录。
维护注意：
 1. 就绪探针主体已在 Sprint 20 落地（有界 SELECT 1 + PING + INFO persistence，超时/未知均 DOWN 并返回 503）；本轮不重复实现，只补运维侧只读核对。
 2. 06-status 的 Redis 检查使用 REDISCLI_AUTH 传口令、只读 CONFIG GET，不输出凭据；读不到配置只告警，不修改 Redis。
 3. 静态检查与 H2/单测不能替代真实 Redis ACL、旧实例配置、Nginx 与故障注入验收。
-->
# Sprint 44：D37 就绪探针复核与 Redis 写保护检查

- 日期：2026-11-07
- 主台账：D37（P2）
- 状态：已修复 · 待人工验证
- 执行方：代码代理实现与静态审阅；本轮由代理执行隔离用例与后端单测，结果仅作参考。

## 1. 复核结论

| 子项 | 现状 | 证据 |
| --- | --- | --- |
| 存活与就绪分离 | `/api/v1/auth/health` 只表示进程存活；`/api/v1/auth/ready` 检查数据库 `SELECT 1`、Redis `PING` 与 `INFO persistence` | `AuthController`、`ReadinessService`、`SaTokenConfigure` 放行 |
| 有界与故障关闭 | 1.5 秒预算、两条无队列工作线程、1 秒缓存；超时/拒绝/未知指标均 DOWN，控制器返回 HTTP 503 | `ReadinessServiceTest` 8 项 |
| 持久化语义 | `loading=0`、`rdb_last_bgsave_status=ok`；AOF 开启时同时要求 write/bgrewrite 为 ok，缺失或未知不判 UP | `persistenceUp` |
| 网关与部署 | 两云模板 `/healthz`、`/readyz` 真实转发；两云 04-deploy 与 06-status 均探 `/api/v1/auth/ready` | Sprint 43 模板与静态契约 |
| 初始化不再关闭写保护 | 两云初始化脚本不含 `config set stop-writes-on-bgsave-error no`；已有静态断言 | Sprint 20 与 test_nginx_gateway |
| 旧实例保护核对 | 仅文档要求人工核对，缺少运行时只读提示 | 本轮补齐 |

## 2. 本轮修改

1. 两云 `06-status.sh` 新增「Redis 持久化保护」只读检查：
   - 使用 `REDISCLI_AUTH` 传递口令，口令不出现在命令行参数与输出；
   - 仅执行 `CONFIG GET stop-writes-on-bgsave-error`，不执行任何 `CONFIG SET`；
   - 值为 `yes` 记 OK；`no`/未知记 WARN，提示恢复为 yes；ACL 受限、服务不可达或未装 `redis-cli` 时同样只告警，不影响其他检查；
   - 检查结果只代表当前实例的保护开关，不代表持久化已完成或备份可恢复。
2. `test_deploy_build_contract.py` 新增静态断言：两云状态脚本必须包含只读 `CONFIG GET stop-writes-on-bgsave-error`、使用 `REDISCLI_AUTH`，且不得出现 `CONFIG SET` 写保护命令。

未改后端就绪实现、未改初始化脚本、未调整 Redis 自身配置。

## 3. 测试（代理执行，仅作参考）

| 命令 | 结果 |
| --- | --- |
| `mvn -f shop-b2b2c-backend/pom.xml -Dtest=ReadinessServiceTest test` | 8 项通过 |
| `python -m unittest discover -s deploy/tests -p 'test_*.py'` | 95 项通过，68 项因无 Linux/Nginx 跳过（跳过不算通过） |
| `bash -n`（两云 `06-status.sh`） | 语法检查通过 |

## 4. 未验证与边界

- 未连接真实 Redis/MySQL，未验证 ACL 是否允许 `PING`、`INFO persistence`、`CONFIG GET`；生产若禁用 `CONFIG`，状态脚本只会告警。
- 未做 Redis 持久化故障注入（`rdb_last_bgsave_status=err`、AOF 写失败、`loading=1`），也未在真实 Nginx 验证 `/readyz` 的 503 透传。
- 旧实例上既有的 `stop-writes-on-bgsave-error no` 不会被自动修正，须人工确认后改为 `yes`；历史库中已关闭持久化的情况未核对。
- 就绪探针不代表 Schema 完整、Redis 可写权限、支付渠道或财务验收。
