<!--
文件：SPRINT_46_D39可重复回归入口补齐.md
用途：记录主台账 D39（自动化测试/CI）剩余缺口的实现范围、可重跑命令、跳过语义与未验证边界。
维护注意：本文只描述代码代理已完成的实现与待用户执行的命令，不代表 CI 已运行、线上烟测已通过或验收完成。
-->
# Sprint 46：D39 可重复回归入口补齐

- 日期：2026-11-08
- 主台账：D39（P2，`已修复（待人工验证）`）
- 执行方：代码代理（实现、用例、静态审阅）
- 验证状态：待人工验证（本轮未执行编译、测试或生产构建）

## 1. 复核结论（D39 前序部分）

| 能力 | 现状 | 来源 |
| --- | --- | --- |
| 交易不变量、租户隔离、并发/退款回归 | 后端 H2 集成测试（真实 Spring 事务与 Mapper SQL，Redis Mock） | Sprint 1 |
| shared 前端规则用例 | `shared/*.test.mjs`，含样式、金额、订单、退款等规则 | T38 |
| CI 工作流 | 三套 job（后端 H2、shared、deploy），只读权限，不构建不发布 | T38 |
| 本地入口 | `scripts/regression.sh` 顺序执行三套用例 | T38 |
| 部署侧隔离用例 | 构建契约、生命周期、网关、备份脚本 | T38 及后续 Sprint |
| 前端 test 脚本 | 缺失（仅 dev/build/preview） | 本轮补齐 |
| 已部署烟测 | 缺失（仅服务器侧 `06-status.sh`） | 本轮补齐 |
| 真实 MySQL、浏览器 E2E、前端 lint | 未建设 | 保留边界 |

## 2. 本轮实现

### 2.1 前端 test 入口

- `shop-storefront-vue/package.json`、`shop-admin-vue/package.json` 增加：
  ```json
  "test": "node --test ../shared/"
  ```
- 只复用仓库 `shared` 规则用例，不新增依赖、不改锁文件；`node:test` 自 Node 20 起可直接以目录递归发现用例。
- `.github/workflows/ci.yml` 的 shared 任务改为 `npm --prefix shop-storefront-vue test`，让入口真正被 CI 执行；样式用例仍使用该工程已安装的锁定依赖（`createRequire` 锚定工程目录）。

### 2.2 已部署只读烟测 `deploy/tests/test_deployed_smoke.py`

- 触发条件：显式设置 `SMOKE_BASE_URL`（`http://` 或 `https://` 绝对地址）；未设置时 `skipUnless` 显式 skipped。
- 只发 GET，不登录、不带 Cookie/口令、不关闭 TLS 校验、不发写请求。
- 断言：
  - `/` 与 `/console/` 返回 200 HTML，且未被重定向；
  - `/healthz` 返回 200 且 `code=200`；
  - `/readyz` 返回 200 且 `status`、`database`、`redis` 均为 `UP`；
  - `/m`、`/m/product/1`、`/invite`、`/invite/register`、`/actuator/health`、`/admin`、`/admin/login` 均为 404；
  - `/assets/__smoke_missing__.js`、`/console/assets/__smoke_missing__.js` 为 404 且不回退页面内容。
- 不访问任何业务订单/商户 ID，避免依赖目标环境数据。

### 2.3 入口契约静态用例 `deploy/tests/test_regression_contract.py`

5 项断言，锁定：

- CI 同时包含后端 H2、`npm --prefix shop-storefront-vue test`、deploy 隔离三套入口，并保持 `contents: read`；
- CI 不出现 `npm run build`、`ssh `、`secrets.`，避免误把发布或凭据带进只读回归；
- `scripts/regression.sh` 与 CI 入口一致且不构建；
- 两个前端工程均暴露 `node --test ../shared/` 的 `test` 脚本且保留 `build`；
- 烟测必须由 `SMOKE_BASE_URL` 显式开启、只发 GET、不带 Cookie。

## 3. 可重跑命令

```bash
# 离线三套（Windows Git Bash / Linux 均可；需要已安装 JDK 17 与 Node 20）
bash scripts/regression.sh

# 前端 test 入口（含 shared 规则）
npm --prefix shop-storefront-vue test
npm --prefix shop-admin-vue test

# 已部署只读烟测（Linux/WSL，目标必须是可访问的完整域名）
SMOKE_BASE_URL=https://实际域名 python3 -m unittest discover -s deploy/tests -p 'test_deployed_smoke.py' -v
```

预期：离线套件通过；Nginx/Linux 依赖缺失时对应用例显式 skipped（不是通过）；设置 `SMOKE_BASE_URL` 后烟测全绿才表示该时刻路径契约可达。

## 4. 未验证边界

- 本轮未执行 `mvn test`、`node --test`、前端构建或烟测；CI 也没有在 GitHub 托管运行器实际跑过。
- `SMOKE_BASE_URL` 线上实跑未做：真实证书链、CDN/代理缓存、WAF 或限流对 404/JSON 的影响未知。
- 真实 MySQL 回归、真实 Redis 故障注入、浏览器 E2E 未建设；H2/Mock 不能替代。
- 前端未引入 ESLint/类型检查：没有 `lint` 脚本，新增脚本只覆盖 shared 规则，不覆盖 Vue 模板编译错误（后者由生产构建发现）。
- `node --test ../shared/` 依赖 Node 20 的目录递归行为；更低版本 Node 需改用显式文件列表。

## 5. 回滚

- 删除两个 `package.json` 中的 `test` 脚本并恢复 CI 的 `node --test shared/*.test.mjs` 即可回到 T38 行为。
- 删除新增两个 deploy 测试文件不影响其它用例与部署脚本。
