# ============================================================================
# Shop-B2B2C 腾讯云生产部署配置 —— 针对服务器 101.35.139.215
# 部署域名: 1688.graddu.com
# ============================================================================

# ---------------- 服务器连接 ----------------
SERVER_IP="101.35.139.215"
SERVER_USER="root"
SSH_PORT="22"
SSH_KEY="$HOME/.ssh/id_rsa"
REMOTE_ROOT="/opt/shop-b2b2c"

# ---------------- 访问域名与公网路由 ----------------
SHOP_SERVER_NAME="1688.graddu.com"
SHOP_PUBLIC_BASE_URL="https://${SHOP_SERVER_NAME}"
# B 端运营管理后台私密挂载入口 (例如 /console 或 /admin)
ADMIN_PORTAL_PATH="${ADMIN_PORTAL_PATH:-/console}"

# 证书申请邮箱（Let's Encrypt 到期提醒用）
CERTBOT_EMAIL="hjp9221@163.com"

# 后端 Spring Boot 监听独立端口 8082（8080 与 8081 已被其他服务占用），由 Nginx 反代
SERVER_PORT="8082"

# ---------------- 数据库（复用服务器已有 MySQL 8.0）----------------
MYSQL_ROOT_PASSWORD=""
DB_NAME="shop_b2b2c"
DB_USER="shop_b2b2c"

# ---------------- Redis（复用服务器已有 Redis）----------------
REDIS_HOST="127.0.0.1"
REDIS_PORT="6379"
REDIS_PASS="YourStrongRedisPassword"
REDIS_DB="2"

# ---------------- JVM 内存调优 ----------------
# 腾讯云主机 4GB 内存配置，512m 安全上限兼顾吞吐与低开销
JAVA_XMX="512m"

# ---------------- 跳过系统包重复安装 ----------------
SKIP_PREPARE="no"
