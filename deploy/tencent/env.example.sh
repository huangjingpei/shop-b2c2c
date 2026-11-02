# 文件：env.example.sh
# 用途：云部署参数填写示例。
# 维护注意：由部署脚本source读取；保留变量名、引号和格式，不公开真实密钥，不要把示例默认值直接用于生产。
# ============================================================================
# Shop-B2B2C 腾讯云生产部署配置模板 (通用模板)
# 复制为 env.sh 后按需修改：cp env.example.sh env.sh
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
ADMIN_PORTAL_PATH="${ADMIN_PORTAL_PATH:-/console}"

# 证书申请邮箱（Let's Encrypt 到期提醒用）
CERTBOT_EMAIL="your_email@example.com"

# 后端 Spring Boot 监听端口
SERVER_PORT="8080"

# ---------------- 数据库配置 ----------------
MYSQL_ROOT_PASSWORD=""
DB_NAME="shop_b2b2c"
DB_USER="shop_b2b2c"

# ---------------- Redis 配置 ----------------
REDIS_HOST="127.0.0.1"
REDIS_PORT="6379"
REDIS_PASS="YourStrongRedisPassword"
REDIS_DB="2"

# ---------------- JVM 内存调优 ----------------
JAVA_XMX="512m"

# ---------------- 跳过系统包重复安装 ----------------
SKIP_PREPARE="no"
