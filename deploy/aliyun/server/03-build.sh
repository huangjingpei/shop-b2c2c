#!/usr/bin/env bash
# 文件：03-build.sh
# 用途：为当前B2B2C后端、管理端和商城生成完整的阿里云候选发布产物。
# 维护注意：产物为www-admin与www-storefront，商城在根路径/；不执行部署、迁移或测试。
# 三个工程与锁文件必须齐全，全部产物校验后才原子切换latest，失败保留旧指针及排错目录。
# 管理端base默认/console/，商城base为/；网关模板需与该布局一致（见D35/部署入口与路径约定）。
set -euo pipefail

GMALL_ROOT="${GMALL_ROOT:-/opt/gmall}"
SRC_DIR="${GMALL_ROOT}/src"
ADMIN_PORTAL_PATH="${ADMIN_PORTAL_PATH:-/console}"
ADMIN_PORTAL_PATH="${ADMIN_PORTAL_PATH%/}"
[[ "${ADMIN_PORTAL_PATH}" =~ ^(/[A-Za-z0-9_-]+)+$ ]] || {
  printf '[build][ERROR] ADMIN_PORTAL_PATH必须是非根目录路径\n' >&2; exit 1;
}
case "${ADMIN_PORTAL_PATH}" in
  /api|/api/*|/m|/m/*|/invite|/invite/*|/actuator|/actuator/*|\
  /assets|/assets/*|/uploads|/uploads/*|/healthz|/readyz)
    printf '[build][ERROR] 管理端路径不能占用API、商城或网关保留路径\n' >&2
    exit 1
    ;;
esac

log() { printf '[build] %s\n' "$*"; }
die() { printf '[build][ERROR] %s\n' "$*" >&2; exit 1; }

# 只要求目录可写，不要求root；不加载.env或修改服务。
for required in shop-b2b2c-backend/pom.xml shop-b2b2c-backend/src/main/resources/db/schema.sql \
                shop-admin-vue/package.json shop-admin-vue/package-lock.json \
                shop-storefront-vue/package.json shop-storefront-vue/package-lock.json; do
  [[ -s "${SRC_DIR}/${required}" ]] || die "必要源码或锁文件缺失: ${required}"
done
# 尊重调用者已配置的工具链；PATH末尾只补历史安装位置。
export PATH="${PATH}:/opt/maven/bin:/opt/node/bin"
for command in mvn npm node; do
  command -v "${command}" >/dev/null || die "缺少构建工具: ${command}"
done
export CI=true
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m -Dfile.encoding=UTF-8}"
export NODE_OPTIONS="${NODE_OPTIONS:---max-old-space-size=2048}"

mkdir -p "${GMALL_ROOT}/releases"
LATEST="${GMALL_ROOT}/releases/latest"
[[ ! -e "${LATEST}" || -L "${LATEST}" ]] || die "latest必须为软链接，拒绝覆盖普通文件或目录"
RELEASE_DIR="$(mktemp -d "${GMALL_ROOT}/releases/$(date +%Y%m%d-%H%M%S)-XXXXXX")"
# 固定绝对目录，使相对GMALL_ROOT也不会生成错误的软链接或Vite输出位置。
RELEASE_DIR="$(cd "${RELEASE_DIR}" && pwd -P)"
TEMP_LINK="${RELEASE_DIR}.latest"
on_exit() {
  local result=$?
  if (( result != 0 )); then
    printf '%s\n' '构建失败；此目录不可部署，latest未主动切换。' > "${RELEASE_DIR}/BUILD_FAILED"
    log "失败产物保留供排查: ${RELEASE_DIR}"
  fi
  if [[ -L "${TEMP_LINK}" ]]; then rm -f -- "${TEMP_LINK}"; fi
}
trap on_exit EXIT
log "候选发布目录: ${RELEASE_DIR}"

(
  cd "${SRC_DIR}/shop-b2b2c-backend"
  # 测试由用户单独执行；本脚本仅构建，不将跳过测试当作验收通过。
  mvn -B -ntp -DskipTests -Dmaven.test.skip=true clean package
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

build_frontend() {
  local project=$1 base=$2 output=$3
  (
    cd "${SRC_DIR}/${project}"
    npm ci --no-audit --no-fund
    VITE_APP_BASE="${base}" npm run build -- --outDir "${output}" --emptyOutDir
  )
  [[ -s "${output}/index.html" ]] || die "${project}缺少非空index.html"
  local asset
  asset="$(find "${output}/assets" -type f -name '*.js' -print -quit)"
  [[ -n "${asset}" && -s "${asset}" ]] || die "${project}缺少非空JavaScript产物"
}
build_frontend shop-admin-vue "${ADMIN_PORTAL_PATH}/" "${RELEASE_DIR}/www-admin"
build_frontend shop-storefront-vue / "${RELEASE_DIR}/www-storefront"

basename "${RELEASE_DIR}" > "${RELEASE_DIR}/BUILD_ID"
ln -s -- "${RELEASE_DIR}" "${TEMP_LINK}"
# 同文件系统rename替换软链接，避免先删除latest产生空窗。
mv -Tf -- "${TEMP_LINK}" "${LATEST}"
log "三端构建产物完整，已更新latest: ${RELEASE_DIR}"
