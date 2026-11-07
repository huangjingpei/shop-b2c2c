# 文件：env.example.sh
# 用途：云部署参数填写示例。
# 维护注意：由部署脚本source读取；保留变量名、引号和格式，不公开真实密钥，不要把示例默认值直接用于生产。CERTBOT_EMAIL/DB_NAME/DB_USER/REDIS_PASS/BOOTSTRAP_ADMIN_* 为必填项，留空时初始化与部署预检直接终止，不再回退默认口令。D35起商城为根路径www/storefront，历史GMall/PDK目录仅作参考。
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

# 证书申请邮箱（Let's Encrypt 到期提醒用，必填）
CERTBOT_EMAIL="ops@example.com"
SERVER_PORT="8080"

GMALL_MYSQL_ROOT_PASSWORD=""
DB_NAME="shop_b2b2c"
DB_USER="shop_b2b2c"

# 首次管理员口令（必填；不得为 admin123；初始化后轮换，但不要从 env.sh 删除）
BOOTSTRAP_ADMIN_USERNAME=""
BOOTSTRAP_ADMIN_PASSWORD=""

# Redis 口令必须显式提供，无默认值
REDIS_HOST="127.0.0.1"
REDIS_PORT="6379"
REDIS_PASS=""
REDIS_DB="1"

# 财务双人复核：生产必须 true，并至少配置两个具备财务权限的后台账号；
# 开发/试点可设 false，此时同人复核落 SAME_OPERATOR 标记并打 WARN，禁止用于生产。
FINANCE_DUAL_APPROVAL_REQUIRED="true"

JAVA_XMX="300m"
SKIP_PREPARE="yes"
