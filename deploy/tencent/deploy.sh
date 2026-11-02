#!/usr/bin/env bash
# ============================================================================
# Shop-B2B2C 腾讯云一键自动化部署脚本 (通用标准模式)
# 适用环境：Windows Git Bash / macOS / Linux
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="${SCRIPT_DIR}/server"
LOCAL_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"      # 仓库根 E:/shop-b2b2c
ENV_FILE="${SCRIPT_DIR}/env.sh"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少配置文件。请先：cp env.example.sh env.sh 然后检查 SERVER_IP"
  exit 1
fi
# shellcheck disable=SC1090
. "${ENV_FILE}"

SERVER_IP="${SERVER_IP:?请在 env.sh 中填写 SERVER_IP}"
SERVER_USER="${SERVER_USER:-root}"
SSH_PORT="${SSH_PORT:-22}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_rsa}"
REMOTE_ROOT="${REMOTE_ROOT:-/opt/shop-b2b2c}"
SERVER_PORT="${SERVER_PORT:-8082}"
SHOP_SERVER_NAME="${SHOP_SERVER_NAME:-1688.graddu.com}"
SHOP_PUBLIC_BASE_URL="${SHOP_PUBLIC_BASE_URL:-https://${SHOP_SERVER_NAME}}"
CERTBOT_EMAIL="${CERTBOT_EMAIL:-hjp9221@163.com}"
JAVA_XMX="${JAVA_XMX:-512m}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-}"
DB_NAME="${DB_NAME:-shop_b2b2c}"
DB_USER="${DB_USER:-shop_b2b2c}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASS="${REDIS_PASS:-YourStrongRedisPassword}"
REDIS_DB="${REDIS_DB:-2}"
ADMIN_PORTAL_PATH="${ADMIN_PORTAL_PATH:-/console}"

MODE="full"
MIGRATE_FLAG=""
ARG_VALUE=""

for arg in "$@"; do
  case "${arg}" in
    --prepare)      MODE="prepare" ;;
    --infra)        MODE="infra" ;;
    --build)        MODE="build" ;;
    --deploy-only)  MODE="deploy" ;;
    --status)       MODE="status" ;;
    --clean-source) MODE="clean-source" ;;
    --rollback)     MODE="rollback" ;;
    --migrate)      MIGRATE_FLAG="--migrate" ;;
    --full)         MODE="full" ;;
    *)
      if [[ "${MODE}" == "rollback" && -z "${ARG_VALUE}" ]]; then
        ARG_VALUE="${arg}"
      else
        echo "未知参数: ${arg}（支持 --prepare --infra --build --deploy-only --migrate --clean-source --status --rollback）" >&2
        exit 1
      fi
      ;;
  esac
done

log()  { printf '\033[0;32m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[deploy]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[deploy][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

REMOTE_HOST="${SERVER_USER}@${SERVER_IP}"
SSH_KEY_PARAM="${SSH_KEY:-$HOME/.ssh/id_rsa}"
if command -v cygpath >/dev/null 2>&1; then
  SSH_KEY_PARAM="$(cygpath -m "${SSH_KEY_PARAM}" 2>/dev/null || echo "${SSH_KEY_PARAM}")"
fi

SSH_OPTS=(-p "${SSH_PORT}" -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new -o BatchMode=yes)
if [[ -f "${SSH_KEY_PARAM}" ]]; then SSH_OPTS+=(-i "${SSH_KEY_PARAM}"); fi
SCP_OPTS=(-P "${SSH_PORT}" -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new -o BatchMode=yes)
if [[ -f "${SSH_KEY_PARAM}" ]]; then SCP_OPTS+=(-i "${SSH_KEY_PARAM}"); fi

# ---------------- 工具检查 ----------------
for c in ssh scp tar; do command -v "$c" >/dev/null || die "缺少命令: $c"; done

# ---------------- SSH 连通性 ----------------
log "测试 SSH 连接 ${REMOTE_HOST}:${SSH_PORT} ..."
if ! ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "echo ok" >/dev/null 2>&1; then
  die "无法免密登录 ${REMOTE_HOST}，请检查 SSH 密钥配置"
fi
log "SSH 连通，远程主机: $(ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" 'hostname')"

# ---------------- 上传运维脚本 ----------------
log "同步运维脚本 -> ${REMOTE_ROOT}/scripts/"
ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "mkdir -p ${REMOTE_ROOT}/scripts ${REMOTE_ROOT}/config"
scp "${SCP_OPTS[@]}" -q "${SERVER_DIR}"/*.sh "${SERVER_DIR}"/*.conf "${REMOTE_HOST}:${REMOTE_ROOT}/scripts/"
ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" \
  "sed -i 's/\r$//' ${REMOTE_ROOT}/scripts/*.sh ${REMOTE_ROOT}/scripts/*.conf 2>/dev/null; \
   chmod +x ${REMOTE_ROOT}/scripts/*.sh; echo '脚本权限已就绪'"

# ---------------- 打包源码并上传 ----------------
upload_source() {
  local tar_file="/tmp/shop-src-$(date +%Y%m%d-%H%M%S).tar.gz"
  log "打包工程源码（排除 node_modules / target / dist / 日志）..."
  tar -czf "${tar_file}" \
    --exclude='node_modules' \
    --exclude='target' \
    --exclude='.git' \
    --exclude='.idea' \
    --exclude='.vscode' \
    --exclude='dist' \
    --exclude='*.log' \
    -C "${LOCAL_ROOT}" shop-b2b2c-backend shop-admin-vue shop-storefront-vue
  local size; size=$(du -h "${tar_file}" | cut -f1)
  log "源码包大小: ${size}"

  log "上传源码包至服务器..."
  scp "${SCP_OPTS[@]}" -q "${tar_file}" "${REMOTE_HOST}:/tmp/shop-src.tar.gz"

  log "解压到 ${REMOTE_ROOT}/src ..."
  ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" \
    "mkdir -p ${REMOTE_ROOT}/src && rm -rf ${REMOTE_ROOT}/src/* && tar -xzf /tmp/shop-src.tar.gz -C ${REMOTE_ROOT}/src && rm -f /tmp/shop-src.tar.gz && echo '解压完成'"
  rm -f "${tar_file}"
}

run_remote() {
  local script="$1"; shift
  local env_prefix="" k v
  for kv in "$@"; do
    k="${kv%%=*}"; v="${kv#*=}"
    v="${v//\'/\'\\\'\'}"
    env_prefix+="${k}='${v}' "
  done
  log ">>> 执行远程脚本 ${script} ..."
  # shellcheck disable=SC2029
  ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" \
    "${env_prefix} SHOP_ROOT=${REMOTE_ROOT} bash ${REMOTE_ROOT}/scripts/${script}"
}

COMMON_ENVS=(
  "SERVER_PORT=${SERVER_PORT}"
  "SHOP_SERVER_NAME=${SHOP_SERVER_NAME}"
  "SHOP_PUBLIC_BASE_URL=${SHOP_PUBLIC_BASE_URL}"
  "CERTBOT_EMAIL=${CERTBOT_EMAIL}"
  "JAVA_XMX=${JAVA_XMX}"
  "MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}"
  "DB_NAME=${DB_NAME}"
  "DB_USER=${DB_USER}"
  "REDIS_HOST=${REDIS_HOST}"
  "REDIS_PORT=${REDIS_PORT}"
  "REDIS_PASS=${REDIS_PASS}"
  "REDIS_DB=${REDIS_DB}"
  "ADMIN_PORTAL_PATH=${ADMIN_PORTAL_PATH:-/console}"
)

case "${MODE}" in
  prepare)
    run_remote 01-prepare-server.sh
    ;;
  infra)
    run_remote 02-init-infra.sh "${COMMON_ENVS[@]}"
    ;;
  build)
    upload_source
    run_remote 03-build.sh "${COMMON_ENVS[@]}"
    ;;
  deploy)
    DEPLOY_CMD="04-deploy.sh"
    if [[ -n "${MIGRATE_FLAG}" ]]; then DEPLOY_CMD+=" ${MIGRATE_FLAG}"; fi
    run_remote "${DEPLOY_CMD}" "${COMMON_ENVS[@]}"
    ;;
  status)
    run_remote 06-status.sh
    ;;
  clean-source)
    ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "rm -rf ${REMOTE_ROOT}/src /tmp/shop-src*.tar.gz 2>/dev/null && echo '✅ 源码已清除'"
    ;;
  rollback)
    run_remote "05-rollback.sh ${ARG_VALUE:-}"
    ;;
  full)
    if [[ "${SKIP_PREPARE:-no}" != "yes" ]]; then
      run_remote 01-prepare-server.sh
    else
      log "SKIP_PREPARE=yes，跳过系统环境初始化"
      ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "mkdir -p ${REMOTE_ROOT}/{src,app,releases,www/admin,www/storefront,logs,config,scripts,backups,uploads}"
    fi
    upload_source
    run_remote 02-init-infra.sh "${COMMON_ENVS[@]}"
    run_remote 03-build.sh "${COMMON_ENVS[@]}"
    DEPLOY_CMD="04-deploy.sh"
    if [[ -n "${MIGRATE_FLAG}" ]]; then DEPLOY_CMD+=" ${MIGRATE_FLAG}"; fi
    run_remote "${DEPLOY_CMD}" "${COMMON_ENVS[@]}"
    ;;
esac

if [[ "${MODE}" == "full" || "${MODE}" == "deploy" ]]; then
  cat <<EOF

$(printf '\033[0;32m[deploy]\033[0m') Shop-B2B2C 部署完成！
  C 端商城与收银台: ${SHOP_PUBLIC_BASE_URL}/
  B 端管理运营后台: ${SHOP_PUBLIC_BASE_URL}${ADMIN_PORTAL_PATH:-/console}/
  API 健康探测接口: ${SHOP_PUBLIC_BASE_URL}/api/v1/auth/health
  默认超级管理员  : superadmin / Admin@123456
  默认示范商户    : merchant1 / 123456
  
  查看状态命令: bash deploy/tencent/deploy.sh --status
  结构同步部署: bash deploy/tencent/deploy.sh --migrate
  回滚版本命令: bash deploy/tencent/deploy.sh --rollback
EOF
fi
