# ============================================================================
# GMall 阿里云部署配置 —— 针对服务器 121.43.150.109
# 域名: mall.graddu.com
# ============================================================================

# ---------------- 服务器连接 ----------------
SERVER_IP="121.43.150.109"
SERVER_USER="root"
SSH_PORT="22"
SSH_KEY="$HOME/.ssh/id_rsa"
REMOTE_ROOT="/opt/gmall"

# ---------------- 访问地址 ----------------
# 与已有站点（live-chat、pdk.graddu.com）共存，使用独立子域名 mall.graddu.com
GMALL_SERVER_NAME="mall.graddu.com"
GMALL_PUBLIC_BASE_URL="https://mall.graddu.com"
# B 端运营管理后台私密挂载入口 (防止公网字典猜测扫描，默认为 /console，可按需修改)
ADMIN_PORTAL_PATH="${ADMIN_PORTAL_PATH:-/console}"

# 证书申请邮箱（Let's Encrypt 到期提醒用）
CERTBOT_EMAIL="hjp9221@63.com"

# 后端监听独立端口 8081（8080 已被 PDK 占用），由 Nginx 反向代理
SERVER_PORT="8081"

# ---------------- 数据库（复用服务器已有 MySQL 8.0.46）----------------
# 3306 已被本机 MySQL 占用，直接复用，新建 shop_chain_db 库
GMALL_MYSQL_ROOT_PASSWORD=""
DB_NAME="shop_chain_db"
DB_USER="gmall"

# ---------------- Redis（复用服务器已有的 pdk-redis 容器）----------------
REDIS_HOST="127.0.0.1"
REDIS_PORT="6379"
REDIS_PASS="3wTYvNMHNn89pZ3XmTZcQluG"
REDIS_DB="1"

# ---------------- JVM 内存 ----------------
# 1.6GB 服务器内存受限，300m 安全上限
JAVA_XMX="300m"

# ---------------- 跳过系统包重复安装 ----------------
# 服务器上 JDK17 / Maven3.9.9 / Node22 / Nginx 均已就绪
SKIP_PREPARE="yes"
