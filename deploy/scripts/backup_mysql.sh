#!/usr/bin/env bash
# 文件：backup_mysql.sh
# 用途：MySQL导出、压缩归档及历史备份清理工具。
# 维护注意：会读取真实库并按保留周期删除旧备份；确认目标库、导出工具与输出目录。
#          DB_PASS 必须通过环境变量注入，不允许硬编码默认密码；口令经临时 600 --defaults-extra-file 传递，
#          不出现在进程参数。仅使用系统 mysqldump（不再回退 Docker），缺失即失败；stderr 与归档分离，
#          归档先写 .partial，gzip -t 与解压非空校验通过后才原子改名。备份成功后必须另做隔离恢复演练（restore drill）。
# ==============================================================================
# shop-b2b2c 生产级 MySQL 数据库定时冷备与归档脚本
# 支持：事务级一致性导出、即时 Gzip 压缩、完整性校验、30天自动轮转清理与告警钩子
# ==============================================================================
set -euo pipefail

# ---------- 可配置参数（优先从环境变量读取；DB_PASS 无默认值，必须显式注入）----------
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-shop_b2b2c}"
DB_USER="${DB_USER:-shop_b2b2c}"
# 注意：不设置 DB_PASS 默认值。若未注入环境变量则立即退出，防止误备份错误库。
DB_PASS="${DB_PASS:-}"
BACKUP_DIR="${BACKUP_DIR:-/opt/shop-b2b2c/backups/mysql}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
ALARM_WEBHOOK_URL="${ALARM_WEBHOOK_URL:-}"

# ---------- 密码前置检查：拒绝使用空密码备份生产库 ----------
if [[ -z "${DB_PASS}" ]]; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [ERROR] DB_PASS 未设置，拒绝使用默认凭据备份生产库，请通过环境变量 DB_PASS 注入密码后重试。" >&2
    exit 1
fi

DATE_STR="$(date +%Y%m%d_%H%M%S)"
BACKUP_FILE="${BACKUP_DIR}/shop_${DB_NAME}_${DATE_STR}.sql.gz"
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
            -d "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":\"### 数据库备份失败告警\n> 主机: $(hostname)\n> 错误: $*\n> 时间: $(date '+%Y-%m-%d %H:%M:%S')\"}}" \
            "${ALARM_WEBHOOK_URL}" || true
    fi
}

mkdir -p "${BACKUP_DIR}"

# 凭据临时文件：600 权限，脚本退出时无论成功失败都删除。
CRED_FILE=""
cleanup() {
    [[ -n "${CRED_FILE}" ]] && rm -f "${CRED_FILE}"
    [[ -n "${TMP_SQL_GZ:-}" && -f "${TMP_SQL_GZ}" ]] && rm -f "${TMP_SQL_GZ}"
}
trap cleanup EXIT INT TERM

log "====== 开始执行数据库全量一致性备份 ======"
log "目标库: ${DB_NAME}@${DB_HOST}:${DB_PORT}, 备份文件: ${BACKUP_FILE}"

# 仅允许系统 mysqldump；缺失直接失败，避免 Docker 环境差异导致备份静默为空。
command -v mysqldump >/dev/null 2>&1 || { err "未检测到 mysqldump 命令，备份终止！"; exit 1; }
command -v gzip >/dev/null 2>&1 || { err "未检测到 gzip 命令，备份终止！"; exit 1; }

CRED_FILE="$(mktemp)"
chmod 600 "${CRED_FILE}"
printf '[client]\nhost=%s\nport=%s\nuser=%s\npassword=%s\n' \
    "${DB_HOST}" "${DB_PORT}" "${DB_USER}" "${DB_PASS}" > "${CRED_FILE}"

DUMP_ARGS=(
    --defaults-extra-file="${CRED_FILE}"
    --single-transaction
    --quick
    --routines
    --triggers
    --no-tablespaces
    --default-character-set=utf8mb4
    "${DB_NAME}"
)

TMP_SQL_GZ="${BACKUP_FILE}.partial"
ERR_FILE="${BACKUP_DIR}/backup-dump.err"

# 执行导出与压缩：stderr 单独落盘，避免 warning/错误文本混入 SQL；pipefail 保证 mysqldump 失败可被捕获。
if mysqldump "${DUMP_ARGS[@]}" 2>"${ERR_FILE}" | gzip -9 > "${TMP_SQL_GZ}"; then
    if [[ ! -s "${TMP_SQL_GZ}" ]] || ! gzip -t "${TMP_SQL_GZ}" 2>/dev/null; then
        err "备份文件为空或 gzip 校验失败，导出内容异常！"
        exit 1
    fi
    UNCOMPRESSED_BYTES="$(gzip -l "${TMP_SQL_GZ}" | awk 'NR==2 {print $2}')"
    if [[ "${UNCOMPRESSED_BYTES:-0}" -le 0 ]]; then
        err "备份文件为空 (0 字节)，导出内容异常！"
        exit 1
    fi
    chmod 600 "${TMP_SQL_GZ}"
    mv -f "${TMP_SQL_GZ}" "${BACKUP_FILE}"
    FILE_SIZE="$(du -h "${BACKUP_FILE}" | cut -f1)"
    log "备份成功！文件大小: ${FILE_SIZE}, 路径: ${BACKUP_FILE}"
else
    err "mysqldump 执行失败，错误摘要："
    tail -n 20 "${ERR_FILE}" 2>/dev/null || true
    exit 1
fi

rm -f "${ERR_FILE}"

# 清理保留周期外的旧备份（先统计、后删除，均不吞错误）
log "清理 ${RETENTION_DAYS} 天前的历史旧备份..."
DELETED_COUNT="$(find "${BACKUP_DIR}" -name "shop_${DB_NAME}_*.sql.gz" -type f -mtime +"${RETENTION_DAYS}" | wc -l)"
find "${BACKUP_DIR}" -name "shop_${DB_NAME}_*.sql.gz" -type f -mtime +"${RETENTION_DAYS}" -delete
log "已清理历史备份文件数: ${DELETED_COUNT}"
log "====== 数据库备份与维护任务执行完毕 ======"
