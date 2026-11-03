#!/usr/bin/env bash
# 文件：package-source.sh
# 用途：只打包当前B2B2C三个工程，为阿里云构建提供匹配的源码布局。
# 维护注意：参数为仓库根目录和目标tar.gz；不连接远端、不执行构建。排除依赖和已有产物，锁文件必须保留；先校验全部工程，避免生成缺端源码包。
set -euo pipefail
[[ $# -eq 2 ]] || { printf '用法: package-source.sh <源码根目录> <目标tar.gz>\n' >&2; exit 1; }
SOURCE_ROOT=$1
ARCHIVE=$2
for required in shop-b2b2c-backend/pom.xml shop-b2b2c-backend/src/main/resources/db/schema.sql \
                shop-admin-vue/package.json shop-admin-vue/package-lock.json \
                shop-storefront-vue/package.json shop-storefront-vue/package-lock.json; do
  [[ -s "${SOURCE_ROOT}/${required}" ]] || { printf '缺少必要源码: %s\n' "${required}" >&2; exit 1; }
done
tar -czf "${ARCHIVE}" \
  --exclude='node_modules' --exclude='target' --exclude='.git' --exclude='.workbuddy' \
  --exclude='.idea' --exclude='dist' --exclude='*.log' \
  -C "${SOURCE_ROOT}" shop-b2b2c-backend shop-admin-vue shop-storefront-vue
