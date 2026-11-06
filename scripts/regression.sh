#!/usr/bin/env bash
# 文件：regression.sh
# 用途：T38 可重跑的只读回归入口，按顺序执行后端 H2 单测、shared 前端规则用例与 deploy 隔离用例。
# 维护注意：只读运行，不连接业务数据库、不执行发布构建、不写 deploy 目标目录；H2 与环境跳过项不能替代真实 MySQL/Nginx/浏览器人工验收。
# 缺 Nginx 时网关用例会显式 skipped，退出码仍为 0 但不代表通过；需要覆盖时应先在 Linux/WSL 安装 Nginx。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

echo "== backend: mvn test =="
mvn -B -f shop-b2b2c-backend/pom.xml test

echo "== shared: npm ci + node --test =="
npm --prefix shop-storefront-vue ci
node --test shared/*.test.mjs

echo "== deploy: unittest discover =="
python3 -m unittest discover -s deploy/tests -v

echo "== regression finished; skips (if any) are not passes =="
