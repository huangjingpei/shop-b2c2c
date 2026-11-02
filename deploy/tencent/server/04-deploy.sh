#!/usr/bin/env bash
# ============================================================================
# Shop-B2B2C 部署脚本 —— 切换 releases/latest 产物上线 (systemd + Nginx)
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
  esac
done

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"
[[ -d "${RELEASE_DIR}" ]] || die "没有可部署的版本: ${RELEASE_DIR}（先执行 03-build.sh）"
[[ -f "${RELEASE_DIR}/app.jar" ]] || die "版本目录缺少 app.jar: ${RELEASE_DIR}"

if [[ -f "${SHOP_ROOT}/.env" ]]; then set -a; . "${SHOP_ROOT}/.env"; set +a; fi

mkdir -p "${APP_DIR}" "${WWW_ADMIN_DIR}" "${WWW_STOREFRONT_DIR}" "${BACKUP_DIR}" "${SHOP_ROOT}/logs" "${SHOP_ROOT}/uploads"
chmod 755 "${SHOP_ROOT}/uploads"

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

if [[ -d "${RELEASE_DIR}/www-storefront" ]]; then
  log "部署 C 端商城与代付收银台 -> ${WWW_STOREFRONT_DIR}"
  rm -rf "${WWW_STOREFRONT_DIR:?}"/* && cp -r "${RELEASE_DIR}/www-storefront/." "${WWW_STOREFRONT_DIR}/"
  ln -sfn "${SHOP_ROOT}/uploads" "${WWW_STOREFRONT_DIR}/uploads"
fi

# ---------------- 4. 数据库同步 ----------------
NEED_MIGRATE="no"
if [[ -f "${RELEASE_DIR}/schema.sql" ]]; then
  TABLE_COUNT=$(mysql -u"${DB_USER:-shop_b2b2c}" -p"${DB_PASS}" -h127.0.0.1 "${DB_NAME:-shop_b2b2c}" -N -e "SELECT count(*) FROM information_schema.tables WHERE table_schema='${DB_NAME:-shop_b2b2c}'" 2>/dev/null || echo 0)
  if [[ "${TABLE_COUNT}" -eq 0 || "${DO_MIGRATE}" == "yes" ]]; then
    NEED_MIGRATE="yes"
  fi
fi

if [[ "${NEED_MIGRATE}" == "yes" && -f "${RELEASE_DIR}/schema.sql" ]]; then
  log "同步数据库结构与种子数据 ${RELEASE_DIR}/schema.sql ..."
  mysql -u"${DB_USER:-shop_b2b2c}" -p"${DB_PASS}" -h127.0.0.1 "${DB_NAME:-shop_b2b2c}" \
    < "${RELEASE_DIR}/schema.sql" || true
  log "数据库同步完毕"
fi

# ---------------- 5. 配置 systemd 服务 ----------------
JAVA_BIN="$(readlink -f "$(command -v java)")"
log "配置 systemd 服务: /etc/systemd/system/${SERVICE_NAME}.service"
cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<UNIT
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

# ---------------- 6. 配置 Nginx 站点 ----------------
SERVER_NAME="${SHOP_SERVER_NAME:-1688.graddu.com}"
NGINX_AVAIL="/etc/nginx/sites-available/shop-b2b2c"
NGINX_ENABLED="/etc/nginx/sites-enabled/shop-b2b2c"
CERT_DIR="/etc/letsencrypt/live/${SERVER_NAME}"
mkdir -p /var/www/certbot/.well-known/acme-challenge

render_site() {
  sed -e "s#__SHOP_ROOT__#${SHOP_ROOT}#g" \
      -e "s#__SERVER_NAME__#${SERVER_NAME}#g" \
      -e "s#__ADMIN_PORTAL_PATH__#${ADMIN_PORTAL_PATH:-/console}#g" \
      -e "s#__BACKEND_PORT__#${SERVER_PORT:-8080}#g" \
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

  systemctl reload nginx || systemctl restart nginx
  return 0
}

# 检查是否存在已颁发的证书
HAVE_CERT="no"
if [[ -f "${CERT_DIR}/fullchain.pem" && -f "${CERT_DIR}/privkey.pem" ]]; then
  log "发现已有证书: ${CERT_DIR}"
  HAVE_CERT="yes"
else
  log "未检测到现有证书，先使用 HTTP 引导配置上线..."
  render_site "${SHOP_ROOT}/scripts/nginx-shop-bootstrap.conf" /tmp/shop-nginx-bootstrap.conf
  safe_nginx_switch /tmp/shop-nginx-bootstrap.conf

  log "尝试申请 Let's Encrypt 证书 (域名: ${SERVER_NAME})..."
  if certbot certonly --webroot -w /var/www/certbot \
       -d "${SERVER_NAME}" --non-interactive --agree-tos \
       --email "${CERTBOT_EMAIL:-hjp9221@163.com}" --keep-until-expiring 2>&1 | tail -20; then
    if [[ -f "${CERT_DIR}/fullchain.pem" ]]; then
      log "✅ SSL 证书签发成功"
      HAVE_CERT="yes"
    fi
  else
    warn "Let's Encrypt 证书签发未完成，将以 HTTP 模式运行。"
    warn "待 DNS 解析生效后，随时运行: certbot certonly --webroot -w /var/www/certbot -d ${SERVER_NAME}"
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

# ---------------- 7. 启动后端应用 ----------------
log "重启后端服务 ${SERVICE_NAME} ..."
systemctl restart "${SERVICE_NAME}"

# ---------------- 8. 健康检查 ----------------
if [[ "${DO_HEALTHCHECK}" == "yes" ]]; then
  log "执行后端健康检查（最多等待 ${HEALTH_TIMEOUT} 秒）..."
  BACKEND_OK="no"
  for i in $(seq 1 "$((HEALTH_TIMEOUT / 3))"); do
    if curl -fsS --max-time 3 "http://127.0.0.1:${SERVER_PORT:-8080}/api/v1/auth/health" 2>/dev/null | grep -q '"code":200'; then
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
fi

# ---------------- 9. 源码清理 ----------------
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
