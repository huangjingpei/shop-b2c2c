<!--
文件：SPRINT_42_D34构建脚本复核与静态契约.md
用途：D34「阿里云 03-build.sh 仍构建 apps/admin-vue3、apps/mobile-uni 与 build:h5」的复核收尾记录。
维护注意：
 1. 代码修复主体在 Sprint 18 已落地（两云构建脚本已指向当前三工程）；本轮只做复核并补跨平台静态契约，不重复改造构建流程。
 2. test_deploy_build_contract.py 只读取脚本文本，Windows 也可执行；它不能替代 Linux/WSL 假工具演练、真实 Maven/npm 构建与部署演练。
 3. 阿里云仍保留 www-mobile/m 与 /m/ 历史布局，与通用网关根路径统一属 D35 协同范围，不能据本 Sprint 推定整套部署可用。
-->
# Sprint 42：D34 构建脚本复核与静态契约

- 日期：2026-11-07
- 主台账：D34（P1）
- 状态：已修复 · 待人工验证
- 执行方：代码代理实现与静态审阅；本轮由代理执行跨平台静态用例与 Bash 语法检查，结果仅作参考。

## 1. 复核结论

Sprint 18 已把阿里云与腾讯云的构建入口同步到当前仓库：

| 检查点 | 现状 | 证据 |
| --- | --- | --- |
| 工程目录 | 两云 `03-build.sh` 均使用 `shop-b2b2c-backend`、`shop-admin-vue`、`shop-storefront-vue`，不再出现 `apps/admin-vue3`、`apps/mobile-uni`、`backend-springboot` | 脚本文本与静态用例 |
| 前端脚本 | 两云均执行 `npm ci` 与 `npm run build -- --outDir ... --emptyOutDir`，通过 `VITE_APP_BASE` 控制 base；不再调用 `build:h5` | 脚本文本与静态用例 |
| 必需源码 | 两云 `03-build.sh` 与 `package-source.sh` 都要求两前端 `package-lock.json`、后端 `pom.xml` 与 `schema.sql`，缺项即失败 | 静态用例 |
| 产物边界 | 要求唯一非空后端 jar（排除 sources/javadoc）、每端非空 `index.html` 与 JavaScript 产物 | 静态用例 |
| 目录一致性 | 阿里云 `www-admin` + `www-mobile/m`、腾讯云 `www-admin` + `www-storefront`，与各自 `04-deploy.sh` 预检路径逐字一致 | 静态用例 |
| 发布切换 | 独立候选目录校验通过后写 `BUILD_ID`，以同文件系统 `mv -Tf` 原子替换 `latest`；失败保留旧指针与 `BUILD_FAILED` | 脚本复核 |

## 2. 本轮新增

新增 `deploy/tests/test_deploy_build_contract.py`（5 项，纯文本静态契约，跨平台可执行）：

1. 两云构建脚本只指向当前三工程，且不得重新出现 `apps/admin-vue3`、`apps/mobile-uni`、`build:h5`、`backend-springboot`。
2. 锁文件、唯一 jar、`index.html` 与 `assets` 校验仍在。
3. 两云 `package-source.sh` 均打包三个工程、保留锁文件并排除 `node_modules`/`target`。
4. `03-build.sh` 的产物目录与 `04-deploy.sh` 预检路径必须一致。
5. 阿里云 `deploy.sh` 仍经 `package-source.sh` 与 `03-build.sh` 进入部署。

同时以 Git Bash 对 `deploy/aliyun/package-source.sh`、`deploy/aliyun/deploy.sh`、两云 `server/03-build.sh`、阿里云 `04-deploy.sh` 执行 `bash -n`，全部通过。

## 3. 测试（代理执行，仅作参考）

| 命令 | 结果 |
| --- | --- |
| `python -m unittest deploy.tests.test_deploy_build_contract -v` | 5 项通过 |
| `bash -n`（上述 5 个脚本，Git Bash on Windows） | 语法检查通过 |
| `python -m unittest discover -s deploy/tests -p 'test_aliyun_build.py' -v` | 未执行（本机无 Linux/WSL，Windows 会跳过，跳过不算通过） |

## 4. 未验证与边界

- 未执行 `test_aliyun_build.py` / `test_tencent_build.py` 的假工具演练，也未执行真实 Maven 打包、`npm ci`/Vite 构建或部署演练；须在 Linux/WSL 由用户运行。
- Windows 的 Git Bash 无法创建原生软链接，`latest` 原子切换只能靠脚本审阅与 Linux 用例覆盖，本轮未在 Windows 做等价演练。
- 阿里云产物仍为 `www-mobile/m` 与 `/m/` base，与 [部署入口与路径约定](../部署入口与路径约定.md) 的根商城布局不同；统一构建、前端公开 URL、部署消费者与网关属 D35，本轮不改。
- 两云 README 保留 PDK/GMall 历史段落，仅供历史参考；实际执行以当前脚本与 Sprint 交付文档为准。
