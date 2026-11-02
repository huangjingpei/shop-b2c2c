#!/usr/bin/env bash
# ============================================================================
# GMall 编译构建脚本 —— 构建后端 jar 与前端产物
# 产物落入 releases/<时间戳>/ 并软链至 releases/latest
# ============================================================================
set -euo pipefail

GMALL_ROOT="${GMALL_ROOT:-/opt/gmall}"
SRC_DIR="${GMALL_ROOT}/src"

log()  { printf '\033[0;32m[build]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[build]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[build][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"
[[ -d "${SRC_DIR}/backend" ]] || die "源码缺失: ${SRC_DIR}/backend"

export PATH="/opt/maven/bin:/opt/node/bin:${PATH}"
export CI=true

# 内存上限限制：Maven 保持 512MB，Node.js 调优至 2048MB（配合 4GB Swap 虚拟内存彻底解决前端打包 OOM）
export MAVEN_OPTS="-Xmx512m -Dfile.encoding=UTF-8"
export NODE_OPTIONS="--max-old-space-size=2048"

TS="$(date +%Y%m%d-%H%M%S)"
RELEASE_DIR="${GMALL_ROOT}/releases/${TS}"
mkdir -p "${RELEASE_DIR}"
log "本次发布版本目录: ${RELEASE_DIR}"

# ================================================================ 1. 后端编译
log "编译 Spring Boot 后端..."
cd "${SRC_DIR}/backend"
MVN_FLAGS=(-B -ntp -DskipTests -Dmaven.test.skip=true)
if [[ -f /root/.m2/settings.xml ]]; then
  MVN_FLAGS+=(-s /root/.m2/settings.xml)
fi
mvn "${MVN_FLAGS[@]}" clean package

JAR_PATH="$(ls -1 "${SRC_DIR}"/backend/target/shop-chain-backend-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
[[ -n "${JAR_PATH}" ]] || die "未找到后端产物 jar"
cp "${JAR_PATH}" "${RELEASE_DIR}/app.jar"
log "后端产物已就绪: ${RELEASE_DIR}/app.jar ($(du -h "${RELEASE_DIR}/app.jar" | cut -f1))"

if [[ -f "${SRC_DIR}/backend/src/main/resources/schema-mysql.sql" ]]; then
  cp "${SRC_DIR}/backend/src/main/resources/schema-mysql.sql" "${RELEASE_DIR}/schema-mysql.sql"
fi

# ================================================================ 2. B 端管理后台
if [[ -d "${SRC_DIR}/apps/admin-vue3" ]]; then
  ADMIN_PORTAL_PATH="${ADMIN_PORTAL_PATH:-/console}"
  log "编译 B 端管理后台 (Vue3 + Vite, 挂载路径: ${ADMIN_PORTAL_PATH}/)..."
  cd "${SRC_DIR}/apps/admin-vue3"
  npm install --no-audit --no-fund --registry=https://registry.npmmirror.com
  export VITE_APP_BASE="${ADMIN_PORTAL_PATH}/"
  npm run build
  mkdir -p "${RELEASE_DIR}/www-admin"
  cp -r dist/. "${RELEASE_DIR}/www-admin/"
  log "B 端管理后台构建完成: $(find "${RELEASE_DIR}/www-admin" -type f | wc -l) 个文件"
fi

# ================================================================ 3. C 端移动商城
if [[ -d "${SRC_DIR}/apps/mobile-uni" ]]; then
  log "编译 C 端移动商城 (Uni-app H5)..."
  cd "${SRC_DIR}/apps/mobile-uni"
  npm install --no-audit --no-fund --registry=https://registry.npmmirror.com
  npm run build:h5
  mkdir -p "${RELEASE_DIR}/www-mobile/m"
  cp -r dist/build/h5/. "${RELEASE_DIR}/www-mobile/m/"
  log "C 端移动商城构建完成: $(find "${RELEASE_DIR}/www-mobile/m" -type f | wc -l) 个文件"
fi

# ================================================================ 4. 更新软链接
rm -f "${GMALL_ROOT}/releases/latest"
ln -s "${RELEASE_DIR}" "${GMALL_ROOT}/releases/latest"
echo "${TS}" > "${RELEASE_DIR}/BUILD_ID"

log "✅ 构建成功完成: ${RELEASE_DIR} (已挂载至 releases/latest)"
