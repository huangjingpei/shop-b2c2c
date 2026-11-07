#!/usr/bin/env bash
# 文件：06-status.sh
# 用途：只读汇总服务、代理及基础设施状态。
# 维护注意：探针成功只能说明相应检查通过，不能证明真实支付、渠道结算或备份恢复正常；不要输出凭据。
# ============================================================================
# Shop-B2B2C 服务状态自检脚本
# 用法：sudo bash /opt/shop-b2b2c/scripts/06-status.sh
# ============================================================================
set -uo pipefail

SHOP_ROOT="${SHOP_ROOT:-/opt/shop-b2b2c}"
SERVICE_NAME="shop-b2b2c"

C_G='\033[0;32m'; C_Y='\033[0;33m'; C_R='\033[0;31m'; C_C='\033[0;36m'; C_0='\033[0m'
sec() { printf "\n${C_C}===== %s =====${C_0}\n" "$*"; }
ok()  { printf "${C_G}  [OK]${C_0}   %s\n" "$*"; }
bad() { printf "${C_R}  [FAIL]${C_0} %s\n" "$*"; }
warn_(){ printf "${C_Y}  [WARN]${C_0} %s\n" "$*"; }

[[ -f "${SHOP_ROOT}/.env" ]] && { set -a; . "${SHOP_ROOT}/.env"; set +a; }
PORT="${SERVER_PORT:-8080}"

sec "Shop-B2B2C 后端服务状态"
if systemctl is-active --quiet "${SERVICE_NAME}"; then
  ok "${SERVICE_NAME} 运行中 (PID $(systemctl show -p MainPID --value ${SERVICE_NAME}))"
else
  bad "${SERVICE_NAME} 未运行"
fi

sec "就绪探测 (端口 ${PORT})"
API_RESP="$(curl -fsS --max-time 3 "http://127.0.0.1:${PORT}/api/v1/auth/ready" 2>/dev/null || echo '')"
if echo "${API_RESP}" | grep -q '"code":200'; then
  ok "后端 /api/v1/auth/ready 正常返回 200"
else
  bad "后端端口 ${PORT} 响应异常或无法连接"
fi

sec "Nginx 反向代理探测"
SERVER_DOMAIN="${SHOP_SERVER_NAME:-1688.graddu.com}"
PROXY_RESP="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -H "Host: ${SERVER_DOMAIN}" "http://127.0.0.1/api/v1/auth/ready" || echo 000)"
if [[ "${PROXY_RESP}" == "200" ]]; then
  ok "Nginx 代理 -> 后端 API (HTTP 200)"
else
  warn_ "Nginx 代理探测返回 HTTP ${PROXY_RESP}"
fi

ADMIN_RESP="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -H "Host: ${SERVER_DOMAIN}" "http://127.0.0.1/console/" || echo 000)"
ok "B 端管理后台首页响应: HTTP ${ADMIN_RESP}"

STORE_RESP="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -H "Host: ${SERVER_DOMAIN}" "http://127.0.0.1/" || echo 000)"
ok "C 端商城首页响应: HTTP ${STORE_RESP}"

sec "数据库与缓存连接"
if [[ -n "${DB_PASS:-}" ]] && mysql -u"${DB_USER:-shop_b2b2c}" -p"${DB_PASS}" -h127.0.0.1 "${DB_NAME:-shop_b2b2c}" -e "SELECT COUNT(*) FROM trade_order;" >/dev/null 2>&1; then
  ok "MySQL (${DB_NAME}) 连接正常且表已存在"
else
  warn_ "MySQL 未初始化或尚未配置 .env"
fi

if [[ -n "${REDIS_PASS:-}" ]] && redis-cli -h "${REDIS_HOST:-127.0.0.1}" -p "${REDIS_PORT:-6379}" -a "${REDIS_PASS}" ping 2>/dev/null | grep -q PONG; then
  ok "Redis 连接正常 (PONG)"
else
  warn_ "Redis 未连接或密码错误"
fi
