#!/usr/bin/env bash
# 文件：02-init-infra.sh
# 用途：初始化数据库账号/表结构和检查Redis等基础设施。
# 维护注意：涉及真实凭据与数据库写入；确认目标库、权限和备份，不能将演示初始化当成存量迁移或恢复验证。
# ============================================================================
# Shop-B2B2C 基础设施初始化 —— 生成 .env 配置 / 创建 MySQL 数据库 / 导入 schema
#
# 幂等设计：
#   - /opt/shop-b2b2c/.env 已存在则不覆盖
# 用法：sudo bash /opt/shop-b2b2c/scripts/02-init-infra.sh
# ============================================================================
set -euo pipefail

SHOP_ROOT="${SHOP_ROOT:-/opt/shop-b2b2c}"
ENV_FILE="${SHOP_ROOT}/.env"
SCHEMA_SQL="${SHOP_ROOT}/src/shop-b2b2c-backend/src/main/resources/db/schema.sql"
DB_NAME="${DB_NAME:-shop_b2b2c}"
DB_APP_USER="${DB_USER:-shop_b2b2c}"

log()  { printf '\033[0;32m[infra]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[infra]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[infra][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"

rand_pass() { echo "Shop#$(openssl rand -hex 12)"; }

# ================================================================ 1. 环境文件 (.env)
if [[ -f "${ENV_FILE}" ]]; then
  log ".env 已存在，保持不变"
else
  log "生成 ${ENV_FILE} ..."
  DB_PASSWORD=$(rand_pass)
  cat > "${ENV_FILE}" <<EOF
# ============================================================================
# Shop-B2B2C 生产环境变量
# ============================================================================
TZ=Asia/Shanghai
SERVER_PORT=${SERVER_PORT:-8080}
SERVER_ADDRESS=127.0.0.1
SPRING_PROFILES_ACTIVE=prod

# ---- 数据库（复用本机 MySQL 8.0）----
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=${DB_NAME}
DB_USER=${DB_APP_USER}
DB_PASS=${DB_PASSWORD}
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/${DB_NAME}?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true
SPRING_DATASOURCE_USERNAME=${DB_APP_USER}
SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}

# ---- Redis（复用本机 Redis 6379）----
REDIS_HOST=${REDIS_HOST:-127.0.0.1}
REDIS_PORT=${REDIS_PORT:-6379}
REDIS_PASS=${REDIS_PASS:-YourStrongRedisPassword}
REDIS_DB=${REDIS_DB:-2}
SPRING_DATA_REDIS_HOST=${REDIS_HOST:-127.0.0.1}
SPRING_DATA_REDIS_PORT=${REDIS_PORT:-6379}
SPRING_DATA_REDIS_PASSWORD=${REDIS_PASS:-YourStrongRedisPassword}
SPRING_DATA_REDIS_DATABASE=${REDIS_DB:-2}

# ---- 前台地址 (供代付生成短链与二维码海报) ----
APP_FRONTEND_STOREFRONT_URL=https://${SHOP_SERVER_NAME:-1688.graddu.com}

# ---- JVM ----
JAVA_OPTS="-Xms256m -Xmx${JAVA_XMX:-512m} -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${SHOP_ROOT}/logs -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
EOF
  chmod 600 "${ENV_FILE}"
  log ".env 生成完毕（权限 600）"
fi

# shellcheck disable=SC1090
set -a; . "${ENV_FILE}"; set +a

# ================================================================ 2. 数据库配置
mysql_exec() {
  if [[ -f /etc/mysql/debian.cnf ]]; then
    mysql --defaults-file=/etc/mysql/debian.cnf "$@"
  elif [[ -n "${MYSQL_ROOT_PASSWORD:-}" ]]; then
    mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" "$@"
  else
    mysql -uroot "$@"
  fi
}

log "检测本机 MySQL 连接..."
if ! mysql_exec -e "SELECT 1;" >/dev/null 2>&1; then
  die "无法连接本机 MySQL，请确认 MySQL 服务运行中"
fi
log "MySQL 连接正常"

log "创建数据库 ${DB_NAME} 与用户 ${DB_USER} ..."
mysql_exec -e "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql_exec -e "CREATE USER IF NOT EXISTS '${DB_USER}'@'127.0.0.1' IDENTIFIED BY '${DB_PASS}';"
mysql_exec -e "CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';"
mysql_exec -e "ALTER USER '${DB_USER}'@'127.0.0.1' IDENTIFIED BY '${DB_PASS}';"
mysql_exec -e "ALTER USER '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';"
mysql_exec -e "GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'127.0.0.1';"
mysql_exec -e "GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';"
mysql_exec -e "FLUSH PRIVILEGES;"

log "执行表结构初始化/自愈: ${SCHEMA_SQL} ..."
if [[ -f "${SCHEMA_SQL}" ]]; then
  mysql -u"${DB_USER}" -p"${DB_PASS}" -h127.0.0.1 "${DB_NAME}" < "${SCHEMA_SQL}"
  log "表结构初始化完成"
elif [[ -f "${SHOP_ROOT}/releases/latest/schema.sql" ]]; then
  mysql -u"${DB_USER}" -p"${DB_PASS}" -h127.0.0.1 "${DB_NAME}" < "${SHOP_ROOT}/releases/latest/schema.sql"
  log "表结构已从 releases/latest 导入完成"
else
  warn "未找到 schema.sql，将在首次部署或后端启动时自动初始化"
fi

# ================================================================ 3. Redis 检查
log "检测 Redis 连通性 (${REDIS_HOST}:${REDIS_PORT})..."
sysctl -w vm.overcommit_memory=1 >/dev/null 2>&1 || true
if ! grep -q 'vm.overcommit_memory = 1' /etc/sysctl.conf 2>/dev/null; then
  echo 'vm.overcommit_memory = 1' >> /etc/sysctl.conf 2>/dev/null || true
fi
if command -v redis-cli >/dev/null 2>&1; then
  redis-cli -a "${REDIS_PASS}" config set stop-writes-on-bgsave-error no >/dev/null 2>&1 || true
  redis-cli -a "${REDIS_PASS}" config rewrite >/dev/null 2>&1 || true
  if redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" -a "${REDIS_PASS}" ping 2>/dev/null | grep -q PONG; then
    log "Redis 连接正常 (PONG)"
  else
    warn "redis-cli 连接未返回 PONG，请检查密码或 Redis 状态"
  fi
fi

log "✅ Shop-B2B2C 基础设施初始化完成"
