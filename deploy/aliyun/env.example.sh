# 文件：env.example.sh
# 用途：云部署参数填写示例。
# 维护注意：由部署脚本source读取；保留变量名、引号和格式，不公开真实密钥，不要把示例默认值直接用于生产。 阿里云目录仍含GMall/PDK等历史工程路径，使用前必须对照当前项目核对，详见D34/D35。
# ============================================================================
# GMall 阿里云部署配置示例
# 复制为 env.sh 后修改: cp env.example.sh env.sh
# ============================================================================

SERVER_IP="121.43.150.109"
SERVER_USER="root"
SSH_PORT="22"
SSH_KEY="$HOME/.ssh/id_rsa"
REMOTE_ROOT="/opt/gmall"

GMALL_SERVER_NAME="mall.graddu.com"
GMALL_PUBLIC_BASE_URL="https://mall.graddu.com"
# B 端运营管理后台私密挂载入口 (防止公网字典猜测扫描，默认为 /console，可自定义为任意私密前缀)
ADMIN_PORTAL_PATH="/console"

CERTBOT_EMAIL="hjp9221@63.com"
SERVER_PORT="8081"

GMALL_MYSQL_ROOT_PASSWORD=""
DB_NAME="shop_chain_db"
DB_USER="gmall"

REDIS_HOST="127.0.0.1"
REDIS_PORT="6379"
REDIS_PASS="3wTYvNMHNn89pZ3XmTZcQluG"
REDIS_DB="1"

JAVA_XMX="300m"
SKIP_PREPARE="yes"
