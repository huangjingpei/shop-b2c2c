#!/usr/bin/env bash
# 文件：03-build.sh
# 用途：在服务器构建前后端并归档发布产物。
# 维护注意：构建依赖源码目录与前端base路径；必要产物缺失应排查，不能只凭脚本成功判定部署可用。
# ============================================================================
# Shop-B2B2C 编译构建脚本 —— 构建后端 jar 与双端前端产物
# 产物落入 releases/<时间戳>/ 并软链至 releases/latest
# ============================================================================
set -euo pipefail

SHOP_ROOT="${SHOP_ROOT:-/opt/shop-b2b2c}"
SRC_DIR="${SHOP_ROOT}/src"

log()  { printf '\033[0;32m[build]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[build]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[build][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"
[[ -d "${SRC_DIR}/shop-b2b2c-backend" ]] || die "后端源码缺失: ${SRC_DIR}/shop-b2b2c-backend"

# 自动定位 Node 20 与 Maven 路径
NVM_NODE_DIR="$(ls -d /root/.nvm/versions/node/v20* 2>/dev/null | tail -1 || true)"
if [[ -n "${NVM_NODE_DIR}" ]]; then
  export PATH="${NVM_NODE_DIR}/bin:/opt/maven/bin:${PATH}"
else
  export PATH="/opt/maven/bin:${PATH}"
fi

export CI=true
export MAVEN_OPTS="-Xmx512m -Dfile.encoding=UTF-8"
export NODE_OPTIONS="--max-old-space-size=2048"

TS="$(date +%Y%m%d-%H%M%S)"
RELEASE_DIR="${SHOP_ROOT}/releases/${TS}"
mkdir -p "${RELEASE_DIR}"
log "本次发布版本目录: ${RELEASE_DIR}"

# ================================================================ 1. 后端编译
log "编译 Spring Boot 后端 (JDK 17 + Maven 3.9)..."
cd "${SRC_DIR}/shop-b2b2c-backend"
MVN_FLAGS=(-B -ntp -DskipTests -Dmaven.test.skip=true)
if [[ -f /root/.m2/settings.xml ]]; then
  MVN_FLAGS+=(-s /root/.m2/settings.xml)
fi
mvn "${MVN_FLAGS[@]}" clean package

JAR_PATH="$(ls -1 target/shop-b2b2c-backend-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
[[ -n "${JAR_PATH}" ]] || die "未找到后端产物 jar"
cp "${JAR_PATH}" "${RELEASE_DIR}/app.jar"
log "后端产物已就绪: ${RELEASE_DIR}/app.jar ($(du -h "${RELEASE_DIR}/app.jar" | cut -f1))"

if [[ -f "src/main/resources/db/schema.sql" ]]; then
  cp "src/main/resources/db/schema.sql" "${RELEASE_DIR}/schema.sql"
fi

# ================================================================ 2. B 端管理后台
if [[ -d "${SRC_DIR}/shop-admin-vue" ]]; then
  ADMIN_PORTAL_PATH="${ADMIN_PORTAL_PATH:-/console}"
  log "编译 B 端管理后台 (Vue3 + Vite, 挂载路径: ${ADMIN_PORTAL_PATH}/)..."
  cd "${SRC_DIR}/shop-admin-vue"
  npm install --no-audit --no-fund --registry=https://registry.npmmirror.com
  export VITE_APP_BASE="${ADMIN_PORTAL_PATH}/"
  npm run build
  mkdir -p "${RELEASE_DIR}/www-admin"
  cp -r dist/. "${RELEASE_DIR}/www-admin/"
  log "B 端管理后台构建完成: $(find "${RELEASE_DIR}/www-admin" -type f | wc -l) 个文件"
fi

# ================================================================ 3. C 端商城与收银台
if [[ -d "${SRC_DIR}/shop-storefront-vue" ]]; then
  log "编译 C 端移动商城与代付收银台 (Vue3 + Tailwind, 挂载路径: /)..."
  cd "${SRC_DIR}/shop-storefront-vue"
  npm install --no-audit --no-fund --registry=https://registry.npmmirror.com
  export VITE_APP_BASE="/"
  npm run build
  mkdir -p "${RELEASE_DIR}/www-storefront"
  cp -r dist/. "${RELEASE_DIR}/www-storefront/"
  log "C 端商城构建完成: $(find "${RELEASE_DIR}/www-storefront" -type f | wc -l) 个文件"
fi

# ================================================================ 4. 更新软链接
rm -f "${SHOP_ROOT}/releases/latest"
ln -s "${RELEASE_DIR}" "${SHOP_ROOT}/releases/latest"
echo "${TS}" > "${RELEASE_DIR}/BUILD_ID"

log "✅ 构建成功完成: ${RELEASE_DIR} (已挂载至 releases/latest)"
