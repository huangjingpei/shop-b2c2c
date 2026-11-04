#!/usr/bin/env bash
# 文件：03-build.sh
# 用途：为当前B2B2C后端、管理端和商城生成完整的腾讯云候选发布产物。
# 维护注意：三端源码与锁文件必须齐全；全部产物校验通过后原子切换latest，失败保留旧指针及排错目录。
# 管理端base默认/console/，商城为/；构建成功不代表可上线，真实部署需经04-deploy验证。
set -euo pipefail

SHOP_ROOT="${SHOP_ROOT:-/opt/shop-b2b2c}"
SRC_DIR="${SHOP_ROOT}/src"
ADMIN_PORTAL_PATH="${ADMIN_PORTAL_PATH:-/console}"
ADMIN_PORTAL_PATH="${ADMIN_PORTAL_PATH%/}"
[[ "${ADMIN_PORTAL_PATH}" =~ ^(/[A-Za-z0-9_-]+)+$ ]] || {
  printf '[build][ERROR] ADMIN_PORTAL_PATH必须是非根目录路径\n' >&2; exit 1;
}
[[ "${ADMIN_PORTAL_PATH}" != /api && "${ADMIN_PORTAL_PATH}" != /api/* ]] || {
  printf '[build][ERROR] 管理端路径不能占用API路径\n' >&2; exit 1;
}

log()  { printf '\033[0;32m[build]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[build]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[build][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

# 三个工程与锁文件必须齐全，缺端直接阻断
for required in shop-b2b2c-backend/pom.xml shop-b2b2c-backend/src/main/resources/db/schema.sql \
                shop-admin-vue/package.json shop-admin-vue/package-lock.json \
                shop-storefront-vue/package.json shop-storefront-vue/package-lock.json; do
  [[ -s "${SRC_DIR}/${required}" ]] || die "必要源码或锁文件缺失: ${required}"
done

# 自动定位 Node 与 Maven 路径
NVM_NODE_DIR="$(ls -d /root/.nvm/versions/node/v20* 2>/dev/null | tail -1 || true)"
if [[ -n "${NVM_NODE_DIR}" ]]; then
  export PATH="${NVM_NODE_DIR}/bin:/opt/maven/bin:${PATH}"
else
  export PATH="${PATH}:/opt/maven/bin:/opt/node/bin"
fi

for command in mvn npm node; do
  command -v "${command}" >/dev/null || die "缺少构建工具: ${command}"
done

export CI=true
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m -Dfile.encoding=UTF-8}"
export NODE_OPTIONS="${NODE_OPTIONS:---max-old-space-size=2048}"

mkdir -p "${SHOP_ROOT}/releases"
LATEST="${SHOP_ROOT}/releases/latest"
[[ ! -e "${LATEST}" || -L "${LATEST}" ]] || die "latest必须为软链接，拒绝覆盖普通文件或目录"

RELEASE_DIR="$(mktemp -d "${SHOP_ROOT}/releases/$(date +%Y%m%d-%H%M%S)-XXXXXX")"
RELEASE_DIR="$(cd "${RELEASE_DIR}" && pwd -P)"
TEMP_LINK="${RELEASE_DIR}.latest"

on_exit() {
  local result=$?
  if (( result != 0 )); then
    printf '%s\n' '构建失败；此目录不可部署，latest未主动切换。' > "${RELEASE_DIR}/BUILD_FAILED"
    warn "构建失败，保留排查目录: ${RELEASE_DIR} (旧latest保持不变)"
  fi
  if [[ -L "${TEMP_LINK}" ]]; then rm -f -- "${TEMP_LINK}"; fi
}
trap on_exit EXIT
log "本次发布候选目录: ${RELEASE_DIR}"

# ================================================================ 1. 后端编译
log "编译 Spring Boot 后端 (JDK 17 + Maven 3.9)..."
(
  cd "${SRC_DIR}/shop-b2b2c-backend"
  MVN_FLAGS=(-B -ntp -DskipTests -Dmaven.test.skip=true clean package)
  if [[ -f /root/.m2/settings.xml ]]; then
    MVN_FLAGS+=(-s /root/.m2/settings.xml)
  fi
  mvn "${MVN_FLAGS[@]}"
)

shopt -s nullglob
JARS=()
for candidate in "${SRC_DIR}"/shop-b2b2c-backend/target/shop-b2b2c-backend-*.jar; do
  case "${candidate}" in *-sources.jar|*-javadoc.jar) continue ;; esac
  JARS+=("${candidate}")
done
[[ ${#JARS[@]} -eq 1 ]] || die "必须生成唯一的后端jar"
[[ -s "${JARS[0]}" ]] || die "后端jar不能为空"
cp -- "${JARS[0]}" "${RELEASE_DIR}/app.jar"
cp -- "${SRC_DIR}/shop-b2b2c-backend/src/main/resources/db/schema.sql" "${RELEASE_DIR}/schema.sql"
log "后端产物已就绪: ${RELEASE_DIR}/app.jar ($(du -h "${RELEASE_DIR}/app.jar" | cut -f1))"

# ================================================================ 2. 前端构建函数
build_frontend() {
  local project=$1 base=$2 output=$3
  log "编译前端 ${project} (挂载路径: ${base}, 输出: ${output})..."
  (
    cd "${SRC_DIR}/${project}"
    npm ci --no-audit --no-fund --registry=https://registry.npmmirror.com
    VITE_APP_BASE="${base}" npm run build -- --outDir "${output}" --emptyOutDir
  )
  [[ -s "${output}/index.html" ]] || die "${project} 缺少非空 index.html"
  local asset
  asset="$(find "${output}/assets" -type f -name '*.js' -print -quit 2>/dev/null || true)"
  [[ -n "${asset}" && -s "${asset}" ]] || die "${project} 缺少非空 JavaScript 产物"
  log "${project} 构建完成: $(find "${output}" -type f | wc -l) 个文件"
}

# ================================================================ 3. B 端管理后台
build_frontend shop-admin-vue "${ADMIN_PORTAL_PATH}/" "${RELEASE_DIR}/www-admin"

# ================================================================ 4. C 端商城与代付收银台
build_frontend shop-storefront-vue "/" "${RELEASE_DIR}/www-storefront"

# ================================================================ 5. 原子更新软链接
basename "${RELEASE_DIR}" > "${RELEASE_DIR}/BUILD_ID"
ln -s -- "${RELEASE_DIR}" "${TEMP_LINK}"
mv -Tf -- "${TEMP_LINK}" "${LATEST}"

log "✅ 三端构建产物完整，已原子更新至 releases/latest: ${RELEASE_DIR}"
