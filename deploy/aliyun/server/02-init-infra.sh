#!/usr/bin/env bash
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
SCHEMA_SQL="${GMALL_ROOT}/src/backend/src/main/resources/schema-mysql.sql"
DB_NAME="${DB_NAME:-shop_chain_db}"
DB_APP_USER="${DB_USER:-gmall}"

log()  { printf '\033[0;32m[infra]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[infra]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[infra][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"

rand_pass() { openssl rand -base64 30 | tr -dc 'A-Za-z0-9' | head -c 24; }

# ================================================================ 1. 环境文件
if [[ -f "${ENV_FILE}" ]]; then
  log ".env 已存在，保持不变"
else
  log "生成 ${ENV_FILE} ..."
  DB_PASSWORD=$(rand_pass)
  cat > "${ENV_FILE}" <<EOF
# ============================================================================
# GMall 生产环境变量
# ============================================================================
TZ=Asia/Shanghai
SERVER_PORT=${SERVER_PORT:-8081}
SERVER_ADDRESS=127.0.0.1
SPRING_PROFILES_ACTIVE=prod

# ---- 数据库（复用本机 MySQL 8.0.46）----
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=${DB_NAME}
DB_USER=${DB_APP_USER}
DB_PASS=${DB_PASSWORD}
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/${DB_NAME}?useUNICODE=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true

# ---- Redis（复用本机 Redis 6379）----
REDIS_HOST=${REDIS_HOST:-127.0.0.1}
REDIS_PORT=${REDIS_PORT:-6379}
REDIS_PASS=${REDIS_PASS:-3wTYvNMHNn89pZ3XmTZcQluG}
SPRING_DATA_REDIS_DATABASE=${REDIS_DB:-1}

# ---- JVM ----
JAVA_OPTS="-Xms128m -Xmx${JAVA_XMX:-300m} -XX:MaxMetaspaceSize=128m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${GMALL_ROOT}/logs -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
EOF
  chmod 600 "${ENV_FILE}"
  log ".env 生成完毕（权限 600）"
fi

# shellcheck disable=SC1090
set -a; . "${ENV_FILE}"; set +a

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
elif [[ -f "${GMALL_ROOT}/releases/latest/schema-mysql.sql" ]]; then
  mysql -u"${DB_USER}" -p"${DB_PASS}" -h127.0.0.1 "${DB_NAME}" < "${GMALL_ROOT}/releases/latest/schema-mysql.sql"
  log "表结构已从 releases/latest 导入完成"
else
  warn "未找到 schema-mysql.sql，将在首次部署或构建时导入"
fi

# ================================================================ 3. Redis 检查
log "检测 Redis 连通性 (127.0.0.1:${REDIS_PORT})..."
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
