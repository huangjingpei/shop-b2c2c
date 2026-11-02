#!/usr/bin/env bash
# ============================================================================
# GMall 部署脚本 —— 切换 releases/latest 产物上线 (systemd + Nginx)
# ============================================================================
set -euo pipefail

GMALL_ROOT="${GMALL_ROOT:-/opt/gmall}"
RELEASE_DIR="${GMALL_ROOT}/releases/latest"
APP_DIR="${GMALL_ROOT}/app"
WWW_ADMIN_DIR="${GMALL_ROOT}/www/admin"
WWW_MOBILE_DIR="${GMALL_ROOT}/www/mobile"
BACKUP_DIR="${GMALL_ROOT}/backups"
SERVICE_NAME="gmall-backend"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
DO_HEALTHCHECK="yes"
DO_MIGRATE="no"
KEEP_SOURCE="no"

log()  { printf '\033[0;32m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[deploy]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[deploy][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

for arg in "$@"; do
  case "${arg}" in
    --clean-source)
      log "安全清理：彻底清除服务器源码目录 (${GMALL_ROOT}/src)..."
      rm -rf "${GMALL_ROOT}/src"
      rm -f /tmp/gmall-src*.tar.gz /tmp/gmall-*.tar.gz
      log "✅ 服务器源码已彻底清除"
      exit 0
      ;;
    --no-healthcheck) DO_HEALTHCHECK="no" ;;
    --migrate)        DO_MIGRATE="yes" ;;
    --keep-source)    KEEP_SOURCE="yes" ;;
  esac
done

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"
[[ -d "${RELEASE_DIR}" ]] || die "没有可部署的版本: ${RELEASE_DIR}（先执行 03-build.sh）"
[[ -f "${RELEASE_DIR}/app.jar" ]] || die "版本目录缺少 app.jar: ${RELEASE_DIR}"

if [[ -f "${GMALL_ROOT}/.env" ]]; then set -a; . "${GMALL_ROOT}/.env"; set +a; fi

mkdir -p "${APP_DIR}" "${WWW_ADMIN_DIR}" "${WWW_MOBILE_DIR}" "${BACKUP_DIR}" "${GMALL_ROOT}/logs" "${GMALL_ROOT}/uploads"
chmod 755 "${GMALL_ROOT}/uploads"

# ---------------- 1. 备份当前 jar ----------------
if [[ -f "${APP_DIR}/app.jar" ]]; then
  BK="${BACKUP_DIR}/app.jar.$(date +%Y%m%d-%H%M%S).bak"
  cp "${APP_DIR}/app.jar" "${BK}"
  log "已备份当前后端产物 -> ${BK}"
fi

# ---------------- 2. 切换后端 ----------------
log "部署后端应用 -> ${APP_DIR}/app.jar"
install -m 644 "${RELEASE_DIR}/app.jar" "${APP_DIR}/app.jar"

# ---------------- 3. 切换前端 ----------------
if [[ -d "${RELEASE_DIR}/www-admin" ]]; then
  log "部署 B 端管理后台 -> ${WWW_ADMIN_DIR}"
  rm -rf "${WWW_ADMIN_DIR:?}"/* && cp -r "${RELEASE_DIR}/www-admin/." "${WWW_ADMIN_DIR}/"
fi

if [[ -d "${RELEASE_DIR}/www-mobile" ]]; then
  log "部署 C 端移动商城 -> ${WWW_MOBILE_DIR}"
  rm -rf "${WWW_MOBILE_DIR:?}"/* && cp -r "${RELEASE_DIR}/www-mobile/." "${WWW_MOBILE_DIR}/"
  ln -sfn "${GMALL_ROOT}/uploads" "${WWW_MOBILE_DIR}/uploads"
  if [[ -d "${WWW_MOBILE_DIR}/m" ]]; then
    ln -sfn "${GMALL_ROOT}/uploads" "${WWW_MOBILE_DIR}/m/uploads"
  fi
fi

# ---------------- 4. 数据库同步 ----------------
NEED_MIGRATE="no"
if [[ -f "${RELEASE_DIR}/schema-mysql.sql" ]]; then
  TABLE_COUNT=$(mysql -u"${DB_USER:-gmall}" -p"${DB_PASS}" -h127.0.0.1 "${DB_NAME:-shop_chain_db}" -N -e "SELECT count(*) FROM information_schema.tables WHERE table_schema='${DB_NAME:-shop_chain_db}'" 2>/dev/null || echo 0)
  if [[ "${TABLE_COUNT}" -eq 0 || "${DO_MIGRATE}" == "yes" ]]; then
    NEED_MIGRATE="yes"
  fi
fi

if [[ "${NEED_MIGRATE}" == "yes" && -f "${RELEASE_DIR}/schema-mysql.sql" ]]; then
  log "同步数据库结构 ${RELEASE_DIR}/schema-mysql.sql ..."
  mysql -u"${DB_USER:-gmall}" -p"${DB_PASS}" -h127.0.0.1 "${DB_NAME:-shop_chain_db}" \
    < "${RELEASE_DIR}/schema-mysql.sql" || true
  log "数据库同步完毕"
fi

# ---------------- 5. 配置 systemd 服务 ----------------
JAVA_BIN="$(readlink -f "$(command -v java)")"
log "配置 systemd 服务: /etc/systemd/system/${SERVICE_NAME}.service"
cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<UNIT
[Unit]
Description=GMall Backend Application
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=${GMALL_ROOT}
EnvironmentFile=${GMALL_ROOT}/.env
ExecStart=${JAVA_BIN} \$JAVA_OPTS -jar ${APP_DIR}/app.jar
ExecStop=/bin/kill -TERM \$MAINPID
SuccessExitStatus=143
Restart=always
RestartSec=10
TimeoutStopSec=30
StandardOutput=append:${GMALL_ROOT}/logs/backend.log
StandardError=append:${GMALL_ROOT}/logs/backend.log
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}" >/dev/null 2>&1

# ---------------- 6. 配置 Nginx 站点 ----------------
SERVER_NAME="${GMALL_SERVER_NAME:-mall.graddu.com}"
NGINX_AVAIL="/etc/nginx/sites-available/gmall"
NGINX_ENABLED="/etc/nginx/sites-enabled/gmall"
CERT_DIR="/etc/letsencrypt/live/${SERVER_NAME}"
mkdir -p /var/www/certbot/.well-known/acme-challenge

render_site() {
  sed -e "s#__GMALL_ROOT__#${GMALL_ROOT}#g" \
      -e "s#__SERVER_NAME__#${SERVER_NAME}#g" \
      -e "s#__ADMIN_PORTAL_PATH__#${ADMIN_PORTAL_PATH:-/console}#g" \
      -e "s#__BACKEND_PORT__#${SERVER_PORT:-8081}#g" \
      -e "s#__SSL_CERT__#${CERT_DIR}/fullchain.pem#g" \
      -e "s#__SSL_KEY__#${CERT_DIR}/privkey.pem#g" \
      "$1" > "$2"
}

safe_nginx_switch() {
  local newconf="$1"
  cp "${newconf}" "${NGINX_AVAIL}"
  ln -sfn "${NGINX_AVAIL}" "${NGINX_ENABLED}"

  if ! nginx -t 2>/tmp/nginx-t.err; then
    warn "Nginx 配置检测失败，错误信息："
    sed 's/^/    /' /tmp/nginx-t.err
    rm -f "${NGINX_ENABLED}"
    return 1
  fi

  systemctl reload nginx
  return 0
}

# 检查是否存在已颁发的证书
HAVE_CERT="no"
if [[ -f "${CERT_DIR}/fullchain.pem" && -f "${CERT_DIR}/privkey.pem" ]]; then
  log "发现已有证书: ${CERT_DIR}"
  HAVE_CERT="yes"
else
  log "未检测到现有证书，先使用 HTTP 引导配置上线..."
  render_site "${GMALL_ROOT}/scripts/nginx-gmall-bootstrap.conf" /tmp/gmall-nginx-bootstrap.conf
  safe_nginx_switch /tmp/gmall-nginx-bootstrap.conf

  log "尝试申请 Let's Encrypt 证书 (域名: ${SERVER_NAME})..."
  if certbot certonly --webroot -w /var/www/certbot \
       -d "${SERVER_NAME}" --non-interactive --agree-tos \
       --email "${CERTBOT_EMAIL:-hjp9221@63.com}" --keep-until-expiring 2>&1 | tail -20; then
    if [[ -f "${CERT_DIR}/fullchain.pem" ]]; then
      log "✅ SSL 证书签发成功"
      HAVE_CERT="yes"
    fi
  else
    warn "Let's Encrypt 证书签发未完成（通常因为公网 DNS 尚未解析到本服务器），将以 HTTP 模式运行。"
    warn "待 DNS 解析生效后，随时运行: certbot certonly --webroot -w /var/www/certbot -d ${SERVER_NAME}"
  fi
fi

if [[ "${HAVE_CERT}" == "yes" ]]; then
  log "启用 HTTPS 正式站点配置..."
  render_site "${GMALL_ROOT}/scripts/nginx-gmall.conf" /tmp/gmall-nginx.conf
else
  log "启用 HTTP 站点配置..."
  render_site "${GMALL_ROOT}/scripts/nginx-gmall-bootstrap.conf" /tmp/gmall-nginx.conf
fi

safe_nginx_switch /tmp/gmall-nginx.conf
rm -f /tmp/gmall-nginx.conf /tmp/gmall-nginx-bootstrap.conf 2>/dev/null || true

# ---------------- 7. 启动后端应用 ----------------
log "重启后端服务 ${SERVICE_NAME} ..."
systemctl restart "${SERVICE_NAME}"

# ---------------- 8. 健康检查 ----------------
if [[ "${DO_HEALTHCHECK}" == "yes" ]]; then
  log "执行后端健康检查（最多等待 ${HEALTH_TIMEOUT} 秒）..."
  BACKEND_OK="no"
  for i in $(seq 1 "$((HEALTH_TIMEOUT / 3))"); do
    if curl -fsS --max-time 3 "http://127.0.0.1:${SERVER_PORT:-8081}/api/config/public" 2>/dev/null | grep -q '"code":200'; then
      BACKEND_OK="yes"
      log "后端响应正常 (耗时约 $((i*3)) 秒)"
      break
    fi
    if ! systemctl is-active --quiet "${SERVICE_NAME}"; then
      warn "后端进程已退出，最近日志："
      tail -n 40 "${GMALL_ROOT}/logs/backend.log" 2>/dev/null || true
      die "后端服务启动失败"
    fi
    sleep 3
  done

  [[ "${BACKEND_OK}" == "yes" ]] || {
    warn "健康检查超时，最近日志："
    tail -n 40 "${GMALL_ROOT}/logs/backend.log" 2>/dev/null || true
    die "后端健康检查超时"
  }
fi

# ---------------- 9. 源码清理 ----------------
if [[ "${KEEP_SOURCE}" != "yes" ]]; then
  log "部署验证通过，清除服务器源码目录 (${GMALL_ROOT}/src)..."
  rm -rf "${GMALL_ROOT}/src"
  rm -f /tmp/gmall-src*.tar.gz /tmp/gmall-*.tar.gz
  log "✅ 源码已安全清理"
fi

PROTO="http"
if [[ "${HAVE_CERT}" == "yes" ]]; then PROTO="https"; fi

cat <<EOF

$(printf '\033[0;32m[deploy]\033[0m') GMall 部署成功上线！
  B 端管理后台: ${PROTO}://${SERVER_NAME}${ADMIN_PORTAL_PATH:-/console}/
  C 端移动商城: ${PROTO}://${SERVER_NAME}/m/
  API 接口前缀: ${PROTO}://${SERVER_NAME}/api/
  服务运行状态: $(systemctl is-active ${SERVICE_NAME})
  日志文件路径: ${GMALL_ROOT}/logs/backend.log
EOF
