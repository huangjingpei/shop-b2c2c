<!--
文件：SPRINT_43_D35云网关路径统一.md
用途：D35「Nginx/云脚本使用/m、旧注册地址与/actuator/health，各云配置不一致」的收尾记录。
维护注意：
 1. Sprint 19 已落地通用网关deploy/nginx/nginx.prod.conf；本轮把阿里云/腾讯云模板、构建产物、部署目录、公开URL与状态探针统一到同一路径约定。
 2. /m、/invite、/actuator/health为明确404；/healthz转发真实存活接口，/readyz保留503语义，不得用静态healthy替代。
 3. 阿里云自本Sprint起不再生成www-mobile/m；历史站点切换、TLS、真实构建与Nginx校验仍待人工验证，不能把静态检查当作上线通过。
-->
# Sprint 43：D35 云网关路径统一

- 日期：2026-11-07
- 主台账：D35（P2）
- 状态：已修复 · 待人工验证
- 执行方：代码代理实现与静态审阅；本轮由代理执行跨平台静态用例与 Bash 语法检查，结果仅作参考。

## 1. 统一后的路径契约

| 入口 | 约定 |
| --- | --- |
| 商城 | 根路径 `/`，产物 `www-storefront`，构建 base `/` |
| 管理端 | `/console/`，产物 `www-admin`，构建 base `/console/` |
| 存活 | `/healthz` → `/api/v1/auth/health`；应用不可达保持 502 |
| 就绪 | `/readyz` → `/api/v1/auth/ready`；依赖异常保留 HTTP 503 |
| 旧入口 | `/m`、`/m/*`、`/invite`、`/invite/*`、`/actuator/health`、`/admin` 明确 404 |
| 静态资源 | `/assets/` 缺失即 404，不回退 `index.html`；`/uploads/` 由网关 alias |

## 2. 本轮修改

1. 阿里云构建：`03-build.sh` 商城改为 `build_frontend shop-storefront-vue / "${RELEASE_DIR}/www-storefront"`；管理端路径继续禁止占用 `/m`、`/invite`、`/actuator`、`/assets`、`/uploads`、`/healthz`、`/readyz` 与 API。
2. 阿里云部署：`04-deploy.sh`、`01-prepare-server.sh`、`deploy.sh` 的目录与预检统一为 `www/storefront`；移除 `/m` 与 `/m/uploads` 软链；摘要链接改为根地址。
3. 阿里云初始化：`02-init-infra.sh` 的 `APP_FRONTEND_STOREFRONT_URL` 由域名派生为根地址（不再拼 `/m/`）。
4. 状态自检：阿里云 `06-status.sh` 改探根路径首页；腾讯云文案同步为“C 端商城”。
5. 网关模板（阿里云 `nginx-gmall.conf`/`nginx-gmall-bootstrap.conf`、腾讯云 `nginx-shop.conf`/`nginx-shop-bootstrap.conf`）：
   - 商城挂载根路径并回退 `index.html`，`/assets/` 缺失 404；
   - 新增 `/healthz`、`/readyz` 真实转发；
   - 删除 `/m` 静态目录、`/invite` 302 与 `/actuator/health` 假入口，并将腾讯云 `/admin` 301 改为 404；
   - 模板说明同步为同一路径约定。
6. 测试：`test_aliyun_build.py` 产物与 base、`test_deploy_build_contract.py` 产物/网关契约、`test_deploy_lifecycle.py` 阿里云预检与 env、`test_release_credentials.py` 公开 URL 断言同步更新；新增静态检查锁定五个网关模板的根商城、探针与旧入口 404。
7. 文档：`部署入口与路径约定.md` 与阿里云 README 更新为根商城布局；历史 Sprint 文档保留原时点不改。

## 3. 测试（代理执行，仅作参考）

| 命令 | 结果 |
| --- | --- |
| `python -m unittest deploy.tests.test_deploy_build_contract deploy.tests.test_release_credentials -v` | 22 项通过 |
| `python -m unittest discover -s deploy/tests -p 'test_*.py'` | 94 项通过，68 项因无 Linux/Nginx 跳过（跳过不算通过） |
| `bash -n`（阿里云 `deploy.sh`、`01/02/03/04/05/06`，腾讯云 `02/06`） | 语法检查通过 |

## 4. 未验证与边界

- 未在真实 Nginx 执行 `test_nginx_gateway.py`，未执行 Linux/WSL 下 `test_aliyun_build.py` 的假工具演练与 `test_deploy_lifecycle.py`。
- 未执行真实 Maven/npm 构建、证书签发、站点切换或回滚演练；历史服务器上既有的 `/m` 产物不会被自动清理，需人工确认后再下线。
- 代付短链/海报等依赖 `APP_FRONTEND_STOREFRONT_URL` 的公开地址只在配置层改为主线根地址，未做浏览器端联调。
- 两云 README 的历史 PDK/GMall 段落仍保留，仅供历史参考，不能按旧步骤执行。
