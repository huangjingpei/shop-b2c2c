#!/usr/bin/env bash
# 文件：02-init-infra.sh
# 用途：初始化数据库账号/表结构和检查Redis等基础设施。
# 维护注意：涉及真实凭据与数据库写入；确认目标库、权限和备份，不能将演示初始化当成存量迁移或恢复验证。只导入schema.sql结构，禁止导入demo-seed.sql；.env必须写入SPRING_DATASOURCE_USERNAME/PASSWORD供应用显式注入，不允许回退root；首次管理员通过BOOTSTRAP_ADMIN_USERNAME/PASSWORD受控创建，禁止使用admin123。GMALL_SERVER_NAME/CERTBOT_EMAIL/DB_NAME/DB_USER/REDIS_PASS/BOOTSTRAP_ADMIN_* 必须由调用方显式提供，缺失即终止，不再写回任何默认口令。 阿里云目录仍含GMall/PDK等历史工程路径，使用前必须对照当前项目核对，详见D34/D35。
# ============================================================================
# GMall 基础设施初始化 —— 生成 .env 配置 / 创建 MySQL 数据库 / 导入 schema
#
# 幂等设计：
#   - /opt/gmall/.env 已存在则不覆盖
# 用法：sudo bash /opt/gmall/scripts/02-init-infra.sh
# ============================================================================
set -euo pipefail

GMALL_ROOT="${GMALL_ROOT:-/opt/gmall}"
ENV_FILE="${GMALL_ROOT}/.env"
SCHEMA_SQL="${GMALL_ROOT}/src/shop-b2b2c-backend/src/main/resources/db/schema.sql"

log()  { printf '\033[0;32m[infra]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[infra]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[infra][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"

rand_pass() { openssl rand -base64 30 | tr -dc 'A-Za-z0-9' | head -c 24; }

# ================================================================ 0. 必填项校验
# 缺配置即终止：不保留任何Redis/数据库/管理员默认口令或空值回退。
for key in GMALL_SERVER_NAME CERTBOT_EMAIL DB_NAME DB_USER REDIS_PASS BOOTSTRAP_ADMIN_USERNAME BOOTSTRAP_ADMIN_PASSWORD; do
  [[ -n "${!key:-}" ]] || die "缺少必填环境变量 ${key}，拒绝生成带空值或默认口令的 .env"
done
[[ "${BOOTSTRAP_ADMIN_PASSWORD}" != "admin123" ]] || die "BOOTSTRAP_ADMIN_PASSWORD 不得使用演示默认口令 admin123"

SERVER_PORT="${SERVER_PORT:-8080}"
REDIS_HOST_VALUE="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT_VALUE="${REDIS_PORT:-6379}"
REDIS_DB_VALUE="${REDIS_DB:-1}"
ADMIN_PORTAL_PATH_VALUE="${ADMIN_PORTAL_PATH:-/console}"

# ================================================================ 1. 环境文件
if [[ -f "${ENV_FILE}" ]]; then
  log ".env 已存在，保持不变"
else
  log "生成 ${ENV_FILE} ..."
  DB_PASSWORD=$(rand_pass)
  cat > "${ENV_FILE}" <<EOF
# ============================================================================
# GMall 生产环境变量
# 注意：本文件由 02-init-infra.sh 生成；凭据缺失时部署预检会终止，不要删除必填键。
# ============================================================================
TZ=Asia/Shanghai
SERVER_PORT=${SERVER_PORT}
SERVER_ADDRESS=127.0.0.1
SPRING_PROFILES_ACTIVE=prod

# ---- 数据库（复用本机 MySQL 8.0.46）----
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=${DB_NAME}
DB_USER=${DB_USER}
DB_PASS=${DB_PASSWORD}
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/${DB_NAME}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=${DB_USER}
SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}

# ---- 首次管理员受控初始化（仅库中无超管时创建；密码不得为admin123；初始化后轮换口令但保留键）----
BOOTSTRAP_ADMIN_USERNAME=${BOOTSTRAP_ADMIN_USERNAME}
BOOTSTRAP_ADMIN_PASSWORD=${BOOTSTRAP_ADMIN_PASSWORD}

# ---- Redis（复用本机 Redis 6379；口令必须显式提供，无默认值）----
REDIS_HOST=${REDIS_HOST_VALUE}
REDIS_PORT=${REDIS_PORT_VALUE}
REDIS_PASS=${REDIS_PASS}
REDIS_DB=${REDIS_DB_VALUE}
SPRING_DATA_REDIS_HOST=${REDIS_HOST_VALUE}
SPRING_DATA_REDIS_PORT=${REDIS_PORT_VALUE}
SPRING_DATA_REDIS_PASSWORD=${REDIS_PASS}
SPRING_DATA_REDIS_DATABASE=${REDIS_DB_VALUE}

# ---- 站点与前台地址（代付短链与二维码海报基准地址）----
GMALL_SERVER_NAME=${GMALL_SERVER_NAME}
CERTBOT_EMAIL=${CERTBOT_EMAIL}
ADMIN_PORTAL_PATH=${ADMIN_PORTAL_PATH_VALUE}
APP_FRONTEND_STOREFRONT_URL=https://${GMALL_SERVER_NAME}/m/

# ---- 财务双人复核（生产必须开启；需至少两个具备财务权限的后台账号）----
# 关闭时仅允许开发/试点单管理员模式，同人复核会落 SAME_OPERATOR 并打 WARN，禁止用于生产。
FINANCE_DUAL_APPROVAL_REQUIRED=true

# ---- JVM ----
JAVA_OPTS="-Xms128m -Xmx${JAVA_XMX:-300m} -XX:MaxMetaspaceSize=128m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${GMALL_ROOT}/logs -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
EOF
  chmod 600 "${ENV_FILE}"
  log ".env 生成完毕（权限 600）"
fi

# shellcheck disable=SC1090
set -a; . "${ENV_FILE}"; set +a

# 已存在 .env 时同样校验，防止旧文件缺键继续部署
for key in DB_NAME DB_USER DB_PASS REDIS_PASS SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD APP_FRONTEND_STOREFRONT_URL BOOTSTRAP_ADMIN_USERNAME BOOTSTRAP_ADMIN_PASSWORD; do
  [[ -n "${!key:-}" ]] || die ".env 缺少 ${key}，请补齐后重试"
done

# ================================================================ 2. 数据库配置
mysql_root() {
  if [[ -n "${GMALL_MYSQL_ROOT_PASSWORD:-}" ]]; then
    mysql -uroot -p"${GMALL_MYSQL_ROOT_PASSWORD}" "$@"
  else
    mysql -uroot "$@"
  fi
}

log "检测本机 MySQL 连接..."
if ! mysql_root -e "SELECT 1;" >/dev/null 2>&1; then
  die "无法以 root 连接本机 MySQL"
fi
log "MySQL 连接正常"

log "创建数据库 ${DB_NAME} 与用户 ${DB_USER} ..."
mysql_root -e "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql_root -e "CREATE USER IF NOT EXISTS '${DB_USER}'@'127.0.0.1' IDENTIFIED BY '${DB_PASS}';"
mysql_root -e "CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';"
mysql_root -e "ALTER USER '${DB_USER}'@'127.0.0.1' IDENTIFIED BY '${DB_PASS}';"
mysql_root -e "ALTER USER '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';"
mysql_root -e "GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'127.0.0.1';"
mysql_root -e "GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';"
mysql_root -e "FLUSH PRIVILEGES;"

log "执行表结构初始化/更新: ${SCHEMA_SQL} ..."
if [[ -f "${SCHEMA_SQL}" ]]; then
  mysql -u"${DB_USER}" -p"${DB_PASS}" -h127.0.0.1 "${DB_NAME}" < "${SCHEMA_SQL}"
  log "表结构初始化完成"
elif [[ -f "${GMALL_ROOT}/releases/latest/schema.sql" ]]; then
  mysql -u"${DB_USER}" -p"${DB_PASS}" -h127.0.0.1 "${DB_NAME}" < "${GMALL_ROOT}/releases/latest/schema.sql"
  log "表结构已从 releases/latest 导入完成"
else
  warn "未找到 schema.sql，将在首次部署或构建时导入"
fi

# ================================================================ 3. Redis 检查
log "检测 Redis 连通性 (${REDIS_HOST}:${REDIS_PORT})..."
if command -v redis-cli >/dev/null 2>&1; then
  if redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" -a "${REDIS_PASS}" ping 2>/dev/null | grep -q PONG; then
    log "Redis 连接正常"
  else
    warn "redis-cli 连接返回非 PONG，请检查 Redis 状态"
  fi
elif docker ps | grep -q pdk-redis; then
  if docker exec pdk-redis redis-cli -a "${REDIS_PASS}" ping 2>/dev/null | grep -q PONG; then
    log "Docker pdk-redis 容器响应正常"
  fi
fi

log "✅ GMall 基础设施初始化完成"
