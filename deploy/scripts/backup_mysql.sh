#!/usr/bin/env bash
# 文件：backup_mysql.sh
# 用途：MySQL导出、压缩归档及历史备份清理工具。
# 维护注意：会读取真实库并按保留周期删除旧备份；确认目标库、导出工具与输出目录，默认路径/账号可能为历史示例。备份成功必须另做隔离恢复演练。
# ==============================================================================
# GMall 生产级 MySQL 数据库定时冷备与归档脚本
# 支持：事务级一致性导出、即时 Gzip 压缩、完整性校验、30天自动轮转清理与告警钩子
# ==============================================================================
set -euo pipefail

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-shop_chain_db}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-SecretRoot123!}"
BACKUP_DIR="${BACKUP_DIR:-/opt/gmall/backups/mysql}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
ALARM_WEBHOOK_URL="${ALARM_WEBHOOK_URL:-}"

DATE_STR="$(date +%Y%m%d_%H%M%S)"
BACKUP_FILE="${BACKUP_DIR}/gmall_${DB_NAME}_${DATE_STR}.sql.gz"
LOG_FILE="${BACKUP_DIR}/backup.log"

log() {
    local msg="[$(date '+%Y-%m-%d %H:%M:%S')] [BACKUP] $*"
    echo "$msg"
    echo "$msg" >> "${LOG_FILE}"
}

err() {
    local msg="[$(date '+%Y-%m-%d %H:%M:%S')] [ERROR] $*"
    echo "$msg" >&2
    echo "$msg" >> "${LOG_FILE}"
    if [[ -n "${ALARM_WEBHOOK_URL}" ]]; then
        curl -s -X POST -H 'Content-Type: application/json' \
            -d "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":\"### 🚨 数据库备份失败告警\n> 主机: $(hostname)\n> 错误: $*\n> 时间: $(date '+%Y-%m-%d %H:%M:%S')\"}}" \
            "${ALARM_WEBHOOK_URL}" || true
    fi
}

mkdir -p "${BACKUP_DIR}"

log "====== 开始执行数据库全量一致性备份 ======"
log "目标库: ${DB_NAME}@${DB_HOST}:${DB_PORT}, 备份文件: ${BACKUP_FILE}"

# 检查 mysqldump 命令或容器内工具
if command -v mysqldump >/dev/null 2>&1; then
    DUMP_CMD="mysqldump -h${DB_HOST} -P${DB_PORT} -u${DB_USER} -p${DB_PASS} --single-transaction --quick --default-character-set=utf8mb4 --routines --triggers ${DB_NAME}"
elif docker ps --format '{{.Names}}' | grep -q 'gmall-mysql'; then
    log "检测到本地 Docker 容器 gmall-mysql，通过容器内 mysqldump 执行..."
    DUMP_CMD="docker exec -i gmall-mysql mysqldump -u${DB_USER} -p${DB_PASS} --single-transaction --quick --default-character-set=utf8mb4 --routines --triggers ${DB_NAME}"
else
    err "未检测到 mysqldump 命令或运行中的 gmall-mysql 容器，备份终止！"
    exit 1
fi

# 执行导出与压缩
if ${DUMP_CMD} 2>>"${LOG_FILE}" | gzip -9 > "${BACKUP_FILE}"; then
    FILE_SIZE="$(du -h "${BACKUP_FILE}" | cut -f1)"
    if [[ -s "${BACKUP_FILE}" ]]; then
        log "✅ 备份成功！文件大小: ${FILE_SIZE}, 路径: ${BACKUP_FILE}"
    else
        err "备份文件为空 (0 字节)，备份异常！"
        rm -f "${BACKUP_FILE}"
        exit 1
    fi
else
    err "mysqldump 执行失败，请查看日志: ${LOG_FILE}"
    rm -f "${BACKUP_FILE}"
    exit 1
fi

# 清理保留周期外的旧备份
log "清理 ${RETENTION_DAYS} 天前的历史旧备份..."
DELETED_COUNT="$(find "${BACKUP_DIR}" -name "gmall_${DB_NAME}_*.sql.gz" -type f -mtime +"${RETENTION_DAYS}" | wc -l || true)"
find "${BACKUP_DIR}" -name "gmall_${DB_NAME}_*.sql.gz" -type f -mtime +"${RETENTION_DAYS}" -delete || true
log "已清理历史备份文件数: ${DELETED_COUNT}"
log "====== 数据库备份与维护任务执行完毕 ======"
