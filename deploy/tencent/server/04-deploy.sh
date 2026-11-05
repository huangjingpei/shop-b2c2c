#!/usr/bin/env bash
# 文件：04-deploy.sh
# 用途：按预检->兼容迁移->切换->就绪烟测四阶段切换发布产物，失败整套自动回滚。
# 维护注意：会影响线上服务，必须在预检和迁移通过后才执行切换；任何阶段失败自动恢复既有版本与站点。仅同步schema结构，不能导入demo-seed.sql；.env必须显式提供DB_NAME/DB_USER/DB_PASS/SPRING_DATASOURCE_USERNAME/SPRING_DATASOURCE_PASSWORD/REDIS_PASS/APP_FRONTEND_STOREFRONT_URL/BOOTSTRAP_ADMIN_*/SHOP_SERVER_NAME，缺失即终止，不保留任何默认口令或地址回退；BOOTSTRAP_ADMIN_PASSWORD为演示默认口令时预检直接终止。
# ============================================================================
# Shop-B2B2C 部署脚本 —— 四阶段安全上线与自动回滚 (systemd + Nginx)
# ============================================================================
set -euo pipefail

SHOP_ROOT="${SHOP_ROOT:-/opt/shop-b2b2c}"
RELEASE_DIR="${SHOP_ROOT}/releases/latest"
APP_DIR="${SHOP_ROOT}/app"
WWW_ADMIN_DIR="${SHOP_ROOT}/www/admin"
WWW_STOREFRONT_DIR="${SHOP_ROOT}/www/storefront"
BACKUP_DIR="${SHOP_ROOT}/backups"
SERVICE_NAME="shop-b2b2c"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
DO_HEALTHCHECK="yes"
DO_MIGRATE="no"
KEEP_SOURCE="no"
ALLOW_HTTP="no"

log()  { printf '\033[0;32m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[deploy]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[deploy][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

for arg in "$@"; do
  case "${arg}" in
    --clean-source)
      log "安全清理：彻底清除服务器源码目录 (${SHOP_ROOT}/src)..."
      rm -rf "${SHOP_ROOT}/src"
      rm -f /tmp/shop-*.tar.gz
      log "✅ 服务器源码已彻底清除"
      exit 0
      ;;
    --no-healthcheck) DO_HEALTHCHECK="no" ;;
    --migrate)        DO_MIGRATE="yes" ;;
    --keep-source)    KEEP_SOURCE="yes" ;;
    --allow-http)     ALLOW_HTTP="yes" ;;
  esac
done

[[ "${DEPLOY_SKIP_ROOT_CHECK:-no}" == "yes" || $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"

# ================================================================
# 阶段 1：预检（验证发布产物完整性，缺端/失败产物立即阻断）
# ================================================================
log "阶段 1/4：执行发布产物预检..."
[[ -d "${RELEASE_DIR}" ]] || die "没有可部署的版本: ${RELEASE_DIR}（先执行 03-build.sh）"
[[ ! -f "${RELEASE_DIR}/BUILD_FAILED" ]] || die "发布目录标记为构建失败，拒绝部署: ${RELEASE_DIR}"
[[ -s "${RELEASE_DIR}/app.jar" ]] || die "版本目录缺少有效非空 app.jar: ${RELEASE_DIR}"
[[ -s "${RELEASE_DIR}/www-admin/index.html" ]] || die "版本目录缺少有效管理端前端: ${RELEASE_DIR}/www-admin/index.html"
[[ -s "${RELEASE_DIR}/www-storefront/index.html" ]] || die "版本目录缺少有效商城前端: ${RELEASE_DIR}/www-storefront/index.html"
[[ -s "${RELEASE_DIR}/schema.sql" ]] || die "版本目录缺少数据库表结构文件: ${RELEASE_DIR}/schema.sql"

if [[ -f "${SHOP_ROOT}/.env" ]]; then set -a; . "${SHOP_ROOT}/.env"; set +a; fi

# 必填配置缺失即终止：不保留密钥、数据库口令、管理员口令、前台地址或域名的默认回退。
REQUIRED_ENV_KEYS=(
  DB_NAME DB_USER DB_PASS
  SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
  REDIS_PASS APP_FRONTEND_STOREFRONT_URL
  BOOTSTRAP_ADMIN_USERNAME BOOTSTRAP_ADMIN_PASSWORD
  SHOP_SERVER_NAME
)
for key in "${REQUIRED_ENV_KEYS[@]}"; do
  [[ -n "${!key:-}" ]] || die ".env 缺少 ${key}，拒绝回退默认值或空口令"
done

if [[ "${BOOTSTRAP_ADMIN_PASSWORD}" == "admin123" ]]; then
  die "BOOTSTRAP_ADMIN_PASSWORD 不得使用演示默认口令 admin123"
fi

SERVER_PORT="${SERVER_PORT:-8080}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"

mkdir -p "${APP_DIR}" "${WWW_ADMIN_DIR}" "${WWW_STOREFRONT_DIR}" "${BACKUP_DIR}" "${SHOP_ROOT}/logs" "${SHOP_ROOT}/uploads"
chmod 755 "${SHOP_ROOT}/uploads"

DEPLOY_TS="$(date +%Y%m%d-%H%M%S)"
DB_BACKUP_FILE=""
MIGRATION_APPLIED="no"

# 统一用临时 --defaults-extra-file 传递凭据，避免口令出现在进程参数；调用结束即删除。
mysql_client() {
  local defaults_file rc
  defaults_file="$(mktemp)"
  chmod 600 "${defaults_file}"
  printf '[client]\nuser=%s\npassword=%s\nhost=%s\nport=%s\n' \
    "${DB_USER}" "${DB_PASS}" "${DB_HOST}" "${DB_PORT}" > "${defaults_file}"
  set +e
  mysql --defaults-extra-file="${defaults_file}" "${DB_NAME}" "$@"
  rc=$?
  set -e
  rm -f "${defaults_file}"
  return "${rc}"
}

# 迁移前备份数据库；任何一步失败都返回非零，调用方必须中止且不得执行迁移。
backup_database_before_migration() {
  local target="$1" defaults_file rc
  command -v mysqldump >/dev/null 2>&1 || { warn "缺少 mysqldump，拒绝在无备份的情况下迁移"; return 1; }
  command -v gzip >/dev/null 2>&1 || { warn "缺少 gzip，拒绝在无备份校验的情况下迁移"; return 1; }
  defaults_file="$(mktemp)"
  chmod 600 "${defaults_file}"
  printf '[client]\nuser=%s\npassword=%s\nhost=%s\nport=%s\n' \
    "${DB_USER}" "${DB_PASS}" "${DB_HOST}" "${DB_PORT}" > "${defaults_file}"
  set +e
  mysqldump --defaults-extra-file="${defaults_file}" \
    --single-transaction --quick --routines --triggers --no-tablespaces \
    --default-character-set=utf8mb4 "${DB_NAME}" 2>"${target}.err" | gzip -9 > "${target}"
  rc=${PIPESTATUS[0]}
  set -e
  rm -f "${defaults_file}"
  if [[ "${rc}" -ne 0 ]]; then
    warn "mysqldump 执行失败（exit ${rc}），错误摘要："
    tail -n 20 "${target}.err" 2>/dev/null || true
    rm -f "${target}" "${target}.err"
    return 1
  fi
  if [[ ! -s "${target}" ]] || ! gzip -t "${target}" 2>/dev/null; then
    warn "迁移前备份文件为空或 gzip 校验失败: ${target}"
    rm -f "${target}" "${target}.err"
    return 1
  fi
  chmod 600 "${target}"
  rm -f "${target}.err"
  return 0
}

# 仅当本次已执行迁移时，才允许从迁移前备份恢复；失败时打印可执行的手工恢复命令。
restore_database_from_backup() {
  [[ "${MIGRATION_APPLIED}" == "yes" ]] || return 0
  if [[ -z "${DB_BACKUP_FILE}" || ! -f "${DB_BACKUP_FILE}" ]]; then
    warn "没有可用的迁移前备份，无法自动恢复数据库"
    return 1
  fi
  log "正在从迁移前备份恢复数据库: ${DB_BACKUP_FILE}"
  if gunzip -c "${DB_BACKUP_FILE}" | mysql_client; then
    log "数据库已恢复到迁移前状态"
    MIGRATION_APPLIED="restored"
    return 0
  fi
  warn "数据库自动恢复失败，请手工执行：gunzip -c '${DB_BACKUP_FILE}' | mysql -u'${DB_USER}' -p -h'${DB_HOST}' -P'${DB_PORT}' '${DB_NAME}'"
  return 1
}

# ================================================================
# 阶段 2：数据库结构同步（先备份后迁移；失败即恢复并终止）
# ================================================================
log "阶段 2/4：数据库结构同步检查..."
NEED_MIGRATE="no"
TABLE_COUNT=$(mysql_client -N -e "SELECT count(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}'" 2>/dev/null || echo 0)
if [[ "${TABLE_COUNT}" -eq 0 || "${DO_MIGRATE}" == "yes" ]]; then
  NEED_MIGRATE="yes"
fi

if [[ "${NEED_MIGRATE}" == "yes" ]]; then
  DB_BACKUP_FILE="${BACKUP_DIR}/db-pre-migration-${DEPLOY_TS}.sql.gz"
  log "迁移前备份数据库 -> ${DB_BACKUP_FILE}"
  if ! backup_database_before_migration "${DB_BACKUP_FILE}"; then
    die "迁移前数据库备份失败，已中止：未执行任何结构变更"
  fi
  log "迁移前备份完成并通过 gzip 校验: ${DB_BACKUP_FILE}"

  MIGRATION_APPLIED="yes"
  log "执行数据库结构同步（仅 schema，不含演示种子） ${RELEASE_DIR}/schema.sql ..."
  if ! mysql_client < "${RELEASE_DIR}/schema.sql"; then
    warn "数据库结构同步失败，正在从迁移前备份恢复..."
    if restore_database_from_backup; then
      warn "数据库已恢复到迁移前状态（备份保留在 ${DB_BACKUP_FILE}）"
    else
      warn "自动恢复未完成，请手工执行：gunzip -c '${DB_BACKUP_FILE}' | mysql -u'${DB_USER}' -p -h'${DB_HOST}' -P'${DB_PORT}' '${DB_NAME}'"
    fi
    die "数据库同步失败，部署中止！已尝试从迁移前备份恢复"
  fi
  log "数据库同步完毕（迁移前备份：${DB_BACKUP_FILE}）"
else
  log "跳过数据库同步 (现有表数: ${TABLE_COUNT}，如需强刷请加 --migrate 参数)"
fi

# ================================================================
# 阶段 3：切换（备份当前线上状态，安装新产物与配置，注册失败回滚）
# ================================================================
log "阶段 3/4：执行产物切换与网关配置..."

CURRENT_BACKUP="${BACKUP_DIR}/deploy-${DEPLOY_TS}-$$"
mkdir -p "${CURRENT_BACKUP}"

SYSTEMD_DIR="${SYSTEMD_DIR:-/etc/systemd/system}"
NGINX_AVAIL="${NGINX_AVAIL:-/etc/nginx/sites-available/shop-b2b2c}"
NGINX_ENABLED="${NGINX_ENABLED:-/etc/nginx/sites-enabled/shop-b2b2c}"
mkdir -p "${SYSTEMD_DIR}" "$(dirname "${NGINX_AVAIL}")" "$(dirname "${NGINX_ENABLED}")"

HAD_JAR="no"
if [[ -f "${APP_DIR}/app.jar" ]]; then
  cp "${APP_DIR}/app.jar" "${CURRENT_BACKUP}/app.jar"
  HAD_JAR="yes"
fi

HAD_ADMIN="no"
if [[ -d "${WWW_ADMIN_DIR}" && -n "$(ls -A "${WWW_ADMIN_DIR}" 2>/dev/null)" ]]; then
  mkdir -p "${CURRENT_BACKUP}/www-admin"
  cp -r "${WWW_ADMIN_DIR}/." "${CURRENT_BACKUP}/www-admin/"
  HAD_ADMIN="yes"
fi

HAD_STOREFRONT="no"
if [[ -d "${WWW_STOREFRONT_DIR}" && -n "$(ls -A "${WWW_STOREFRONT_DIR}" 2>/dev/null)" ]]; then
  mkdir -p "${CURRENT_BACKUP}/www-storefront"
  cp -r "${WWW_STOREFRONT_DIR}/." "${CURRENT_BACKUP}/www-storefront/"
  HAD_STOREFRONT="yes"
fi

HAD_NGINX="no"
if [[ -f "${NGINX_ENABLED}" ]]; then
  cp -L "${NGINX_ENABLED}" "${CURRENT_BACKUP}/nginx.conf"
  HAD_NGINX="yes"
fi

DEPLOY_COMMITTED="no"
rollback_on_failure() {
  local exit_code=$?
  if [[ "${DEPLOY_COMMITTED}" != "yes" && "${exit_code}" -ne 0 ]]; then
    warn "============================================================"
    warn "部署验证未通过 (exit code: ${exit_code})，正在整套自动回滚..."
    warn "============================================================"

    # 1. 恢复后端应用 JAR
    if [[ "${HAD_JAR}" == "yes" && -f "${CURRENT_BACKUP}/app.jar" ]]; then
      install -m 644 "${CURRENT_BACKUP}/app.jar" "${APP_DIR}/app.jar"
      log "已恢复旧版后端应用 -> ${APP_DIR}/app.jar"
    fi

    # 2. 恢复前端
    if [[ "${HAD_ADMIN}" == "yes" && -d "${CURRENT_BACKUP}/www-admin" ]]; then
      rm -rf "${WWW_ADMIN_DIR:?}"/*
      cp -r "${CURRENT_BACKUP}/www-admin/." "${WWW_ADMIN_DIR}/"
      log "已恢复旧版管理端前端"
    fi
    if [[ "${HAD_STOREFRONT}" == "yes" && -d "${CURRENT_BACKUP}/www-storefront" ]]; then
      rm -rf "${WWW_STOREFRONT_DIR:?}"/*
      cp -r "${CURRENT_BACKUP}/www-storefront/." "${WWW_STOREFRONT_DIR}/"
      ln -sfn "${SHOP_ROOT}/uploads" "${WWW_STOREFRONT_DIR}/uploads"
      log "已恢复旧版商城前端"
    fi

    # 3. 恢复 Nginx；首次部署则移除本次新建站点，避免留下半配置
    if [[ "${HAD_NGINX}" == "yes" && -f "${CURRENT_BACKUP}/nginx.conf" ]]; then
      cp "${CURRENT_BACKUP}/nginx.conf" "${NGINX_AVAIL}"
      ln -sfn "${NGINX_AVAIL}" "${NGINX_ENABLED}"
      if nginx -t >/dev/null 2>&1; then
        systemctl reload nginx || systemctl restart nginx || true
        log "已恢复旧版 Nginx 配置并重载"
      else
        warn "旧版 Nginx 配置未通过 nginx -t，请人工核查 ${NGINX_AVAIL}"
      fi
    else
      rm -f "${NGINX_ENABLED}" "${NGINX_AVAIL}"
      if nginx -t >/dev/null 2>&1; then
        systemctl reload nginx || systemctl restart nginx || true
      fi
      log "首次部署失败：已移除本次新建的 Nginx 站点 ${NGINX_AVAIL}"
    fi

    # 4. 恢复数据库（仅本次已实际执行迁移）；应用启动失败也属于“全成功或回滚 DB”边界
    if [[ "${MIGRATION_APPLIED}" == "yes" ]]; then
      if restore_database_from_backup; then
        log "已从迁移前备份恢复数据库: ${DB_BACKUP_FILE}"
      else
        warn "数据库未能自动恢复，请手工执行：gunzip -c '${DB_BACKUP_FILE}' | mysql -u'${DB_USER}' -p -h'${DB_HOST}' -P'${DB_PORT}' '${DB_NAME}'"
      fi
    fi

    # 5. 重启旧版服务
    if [[ "${HAD_JAR}" == "yes" ]]; then
      log "重启旧版服务 ${SERVICE_NAME} ..."
      systemctl restart "${SERVICE_NAME}" || true
    fi

    warn "⚠️ 线上服务与三端站点已恢复至部署前版本 (${CURRENT_BACKUP})"
  fi
}
trap rollback_on_failure EXIT

# 切换后端
log "部署后端应用 -> ${APP_DIR}/app.jar"
install -m 644 "${RELEASE_DIR}/app.jar" "${APP_DIR}/app.jar"

# 切换前端
log "部署 B 端管理后台 -> ${WWW_ADMIN_DIR}"
rm -rf "${WWW_ADMIN_DIR:?}"/* && cp -r "${RELEASE_DIR}/www-admin/." "${WWW_ADMIN_DIR}/"

log "部署 C 端商城与代付收银台 -> ${WWW_STOREFRONT_DIR}"
rm -rf "${WWW_STOREFRONT_DIR:?}"/* && cp -r "${RELEASE_DIR}/www-storefront/." "${WWW_STOREFRONT_DIR}/"
ln -sfn "${SHOP_ROOT}/uploads" "${WWW_STOREFRONT_DIR}/uploads"

# 配置 systemd 服务
JAVA_BIN="$(readlink -f "$(command -v java)")"
log "配置 systemd 服务: ${SYSTEMD_DIR}/${SERVICE_NAME}.service"
cat > "${SYSTEMD_DIR}/${SERVICE_NAME}.service" <<UNIT
[Unit]
Description=Shop-B2B2C Backend Application (Spring Boot 3 + JDK 17)
After=network-online.target mysql.service redis.service
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=${SHOP_ROOT}
EnvironmentFile=${SHOP_ROOT}/.env
ExecStart=${JAVA_BIN} \$JAVA_OPTS -jar ${APP_DIR}/app.jar
ExecStop=/bin/kill -TERM \$MAINPID
SuccessExitStatus=143
Restart=always
RestartSec=10
TimeoutStopSec=30
StandardOutput=append:${SHOP_ROOT}/logs/backend.log
StandardError=append:${SHOP_ROOT}/logs/backend.log
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}" >/dev/null 2>&1

# 配置 Nginx 站点
SERVER_NAME="${SHOP_SERVER_NAME}"
CERT_DIR="${CERT_DIR:-/etc/letsencrypt/live/${SERVER_NAME}}"
ACME_CHALLENGE_DIR="${ACME_CHALLENGE_DIR:-/var/www/certbot/.well-known/acme-challenge}"
mkdir -p "${ACME_CHALLENGE_DIR}"

render_site() {
  sed -e "s#__SHOP_ROOT__#${SHOP_ROOT}#g" \
      -e "s#__SERVER_NAME__#${SERVER_NAME}#g" \
      -e "s#__ADMIN_PORTAL_PATH__#${ADMIN_PORTAL_PATH:-/console}#g" \
      -e "s#__BACKEND_PORT__#${SERVER_PORT}#g" \
      -e "s#__SSL_CERT__#${CERT_DIR}/fullchain.pem#g" \
      -e "s#__SSL_KEY__#${CERT_DIR}/privkey.pem#g" \
      "$1" > "$2"
}

safe_nginx_switch() {
  local newconf="$1"
  local stamp snapshot_dir had_avail had_enabled enabled_is_link enabled_target
  stamp="$(date +%Y%m%d-%H%M%S)-$$"
  snapshot_dir="${BACKUP_DIR}/nginx-switch-${stamp}"
  mkdir -p "${snapshot_dir}"

  # 先快照旧站点文件与启用状态；回滚必须恢复软链本身，而不是只恢复内容。
  had_avail="no"; had_enabled="no"; enabled_is_link="no"; enabled_target=""
  if [[ -f "${NGINX_AVAIL}" ]]; then
    cp -p "${NGINX_AVAIL}" "${snapshot_dir}/avail.conf"
    had_avail="yes"
  fi
  if [[ -L "${NGINX_ENABLED}" ]]; then
    enabled_target="$(readlink "${NGINX_ENABLED}" 2>/dev/null || true)"
    had_enabled="yes"; enabled_is_link="yes"
  elif [[ -f "${NGINX_ENABLED}" ]]; then
    cp -p "${NGINX_ENABLED}" "${snapshot_dir}/enabled.conf"
    had_enabled="yes"
  fi

  restore_nginx_snapshot() {
    if [[ "${had_avail}" == "yes" ]]; then
      cp -p "${snapshot_dir}/avail.conf" "${NGINX_AVAIL}"
    else
      rm -f "${NGINX_AVAIL}"
    fi
    if [[ "${had_enabled}" == "yes" ]]; then
      if [[ "${enabled_is_link}" == "yes" ]]; then
        if [[ -n "${enabled_target}" ]]; then ln -sfn "${enabled_target}" "${NGINX_ENABLED}"; fi
      else
        cp -p "${snapshot_dir}/enabled.conf" "${NGINX_ENABLED}"
      fi
    else
      rm -f "${NGINX_ENABLED}"
    fi
  }

  install -m 644 "${newconf}" "${NGINX_AVAIL}.candidate" \
    || { rm -f "${NGINX_AVAIL}.candidate"; die "无法写入 Nginx 候选配置"; }
  mv -f "${NGINX_AVAIL}.candidate" "${NGINX_AVAIL}" || die "无法安装 Nginx 站点配置"
  ln -sfn "${NGINX_AVAIL}" "${NGINX_ENABLED}" || die "无法启用 Nginx 站点配置"

  if ! nginx -t 2>"${snapshot_dir}/nginx-t.err"; then
    warn "Nginx 配置检测失败，错误信息："
    sed 's/^/    /' "${snapshot_dir}/nginx-t.err" || true
    restore_nginx_snapshot
    if nginx -t >/dev/null 2>&1; then
      systemctl reload nginx || systemctl restart nginx || true
      log "已恢复切换前的 Nginx 配置"
    else
      warn "切换前的 Nginx 配置同样无法通过 nginx -t，请人工核查（快照：${snapshot_dir}）"
    fi
    die "Nginx 配置语法验证失败，中止上线"
  fi

  if ! systemctl reload nginx; then
    warn "Nginx reload 失败，回滚到切换前配置..."
    restore_nginx_snapshot
    if systemctl reload nginx || systemctl restart nginx; then
      log "已回滚并重载切换前的 Nginx 配置（快照：${snapshot_dir}）"
    else
      warn "回滚后仍无法加载 Nginx，请人工处理（快照：${snapshot_dir}）"
    fi
    die "Nginx reload 失败，已回滚站点配置"
  fi
  log "Nginx 配置已校验并生效"
  return 0
}

# 检查是否存在已颁发的证书；无证书时仅允许显式 --allow-http 降级
HAVE_CERT="no"
if [[ -f "${CERT_DIR}/fullchain.pem" && -f "${CERT_DIR}/privkey.pem" ]]; then
  log "发现已有证书: ${CERT_DIR}"
  HAVE_CERT="yes"
else
  [[ -n "${CERTBOT_EMAIL:-}" ]] || die "缺少 CERTBOT_EMAIL，无法申请证书；如确需明文运行请显式加 --allow-http"
  log "未检测到现有证书，先使用 HTTP 引导配置发布 ACME 挑战..."
  render_site "${SHOP_ROOT}/scripts/nginx-shop-bootstrap.conf" /tmp/shop-nginx-bootstrap.conf
  safe_nginx_switch /tmp/shop-nginx-bootstrap.conf

  CERTBOT_LOG="${BACKUP_DIR}/certbot-${DEPLOY_TS}.log"
  log "尝试申请 Let's Encrypt 证书 (域名: ${SERVER_NAME})，日志: ${CERTBOT_LOG}"
  if certbot certonly --webroot -w /var/www/certbot \
       -d "${SERVER_NAME}" --non-interactive --agree-tos \
       --email "${CERTBOT_EMAIL}" --keep-until-expiring >"${CERTBOT_LOG}" 2>&1; then
    if [[ -f "${CERT_DIR}/fullchain.pem" && -f "${CERT_DIR}/privkey.pem" ]]; then
      log "SSL 证书签发成功"
      HAVE_CERT="yes"
    else
      warn "certbot 返回成功但证书文件缺失（${CERT_DIR}），视为证书申请失败"
    fi
  else
    warn "Let's Encrypt 证书签发失败，certbot 日志摘要："
    tail -n 20 "${CERTBOT_LOG}" 2>/dev/null || true
  fi

  if [[ "${HAVE_CERT}" != "yes" && "${ALLOW_HTTP}" != "yes" ]]; then
    warn "当前没有有效 HTTPS 证书，且未显式指定 --allow-http，拒绝以明文 HTTP 上线。"
    warn "待 DNS 解析生效后重新部署，或由环境负责人手工执行: certbot certonly --webroot -w /var/www/certbot -d ${SERVER_NAME}"
    die "无有效 HTTPS 证书，部署中止（已回滚引导站点与线上产物）"
  fi
  if [[ "${HAVE_CERT}" != "yes" ]]; then
    warn "已显式允许 HTTP 降级运行（--allow-http）：登录、订单、卡密与 PII 将以明文传输，仅限临时排障。"
  fi
fi

if [[ "${HAVE_CERT}" == "yes" ]]; then
  log "启用 HTTPS 正式站点配置..."
  render_site "${SHOP_ROOT}/scripts/nginx-shop.conf" /tmp/shop-nginx.conf
else
  log "启用 HTTP 站点配置..."
  render_site "${SHOP_ROOT}/scripts/nginx-shop-bootstrap.conf" /tmp/shop-nginx.conf
fi

safe_nginx_switch /tmp/shop-nginx.conf
rm -f /tmp/shop-nginx.conf /tmp/shop-nginx-bootstrap.conf 2>/dev/null || true

# ================================================================
# 阶段 4：就绪烟测（启动服务，验证真实响应，超时或异常自动回滚）
# ================================================================
log "阶段 4/4：重启后端服务并执行就绪烟测..."
systemctl restart "${SERVICE_NAME}"

if [[ "${DO_HEALTHCHECK}" == "yes" ]]; then
  log "执行后端健康检查（最多等待 ${HEALTH_TIMEOUT} 秒）..."
  BACKEND_OK="no"
  for i in $(seq 1 "$((HEALTH_TIMEOUT / 3))"); do
    if curl -fsS --max-time 3 "http://127.0.0.1:${SERVER_PORT}/api/v1/auth/ready" 2>/dev/null | grep -q '"code":200'; then
      BACKEND_OK="yes"
      log "后端响应正常 (耗时约 $((i*3)) 秒)"
      break
    fi
    if ! systemctl is-active --quiet "${SERVICE_NAME}"; then
      warn "后端进程已退出，最近日志："
      tail -n 40 "${SHOP_ROOT}/logs/backend.log" 2>/dev/null || true
      die "后端服务启动失败"
    fi
    sleep 3
  done

  [[ "${BACKEND_OK}" == "yes" ]] || {
    warn "健康检查超时，最近日志："
    tail -n 40 "${SHOP_ROOT}/logs/backend.log" 2>/dev/null || true
    die "后端健康检查超时"
  }

  if [[ "${HAVE_CERT}" == "yes" ]]; then
    log "执行 HTTPS 443 网关探测 (${SERVER_NAME})..."
    if ! curl -fsS --max-time 8 --resolve "${SERVER_NAME}:443:127.0.0.1" \
         "https://${SERVER_NAME}/api/v1/auth/ready" 2>/dev/null | grep -q '"code":200'; then
      warn "HTTPS 443 探测失败，最近 Nginx 错误日志："
      tail -n 20 /var/log/nginx/error.log 2>/dev/null || true
      die "HTTPS 网关探测失败，拒绝以不可用证书/网关上线"
    fi
    log "HTTPS 443 探测通过"
  fi
fi

# 确认上线成功，解除异常回滚 trap
DEPLOY_COMMITTED="yes"
trap - EXIT

# 源码清理
if [[ "${KEEP_SOURCE}" != "yes" ]]; then
  log "部署验证通过，清除服务器源码目录 (${SHOP_ROOT}/src)..."
  rm -rf "${SHOP_ROOT}/src"
  rm -f /tmp/shop-*.tar.gz
  log "✅ 源码已安全清理"
fi

PROTO="http"
if [[ "${HAVE_CERT}" == "yes" ]]; then PROTO="https"; fi

cat <<EOF

$(printf '\033[0;32m[deploy]\033[0m') Shop-B2B2C 部署成功上线！
  C 端商城与收银台: ${PROTO}://${SERVER_NAME}/
  B 端运营管理后台: ${PROTO}://${SERVER_NAME}${ADMIN_PORTAL_PATH:-/console}/
  API 接口前缀    : ${PROTO}://${SERVER_NAME}/api/
  服务运行状态    : $(systemctl is-active ${SERVICE_NAME})
  日志文件路径    : ${SHOP_ROOT}/logs/backend.log
EOF
