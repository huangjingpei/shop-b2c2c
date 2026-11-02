#!/usr/bin/env bash
# 文件：01-prepare-server.sh
# 用途：服务器依赖、目录及基础运行环境准备。
# 维护注意：会安装或调整系统组件，先确认目标主机与权限；不可为验证注释而实际执行。
# ============================================================================
# Shop-B2B2C 腾讯云服务器环境检查与骨架创建 (通用脚本)
# 用法：sudo bash /opt/shop-b2b2c/scripts/01-prepare-server.sh
# ============================================================================
set -euo pipefail

SHOP_ROOT="${SHOP_ROOT:-/opt/shop-b2b2c}"
LOG_DIR="${SHOP_ROOT}/logs"

export DEBIAN_FRONTEND=noninteractive
export LANG=C.UTF-8

log()  { printf '\033[0;32m[prepare]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[prepare]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[prepare][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"

# ---------------- 1. 目录骨架 ----------------
log "创建目录骨架 ${SHOP_ROOT}/..."
mkdir -p "${SHOP_ROOT}"/{src,app,releases,www/admin,www/storefront,logs,config,scripts,backups,uploads}
chmod 755 "${SHOP_ROOT}"
chmod 755 "${SHOP_ROOT}/uploads"

# ---------------- 2. 虚拟内存 (Swap) 自动检测与配置 ----------------
SWAP_TOTAL=$(free -m | awk '/^Swap:/ {print $2}')
if [[ "${SWAP_TOTAL}" -lt 1500 ]]; then
  log "检测到服务器虚拟内存 (Swap) 仅为 ${SWAP_TOTAL}MB，正在通过磁盘划分 4GB Swap 虚拟内存以防止编译构建 OOM..."
  if [[ ! -f /swapfile ]]; then
    fallocate -l 4G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=4096 status=none
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
    swapon /swapfile || true
    if ! grep -q '/swapfile' /etc/fstab; then
      echo '/swapfile none swap sw 0 0' >> /etc/fstab
    fi
    sysctl -w vm.swappiness=60 >/dev/null 2>&1 || true
    log "✅ 4GB Swap 虚拟内存已成功创建并启用"
  else
    swapon /swapfile 2>/dev/null || true
    log "已激活现存 /swapfile"
  fi
else
  log "当前 Swap 容量充足: ${SWAP_TOTAL}MB"
fi

# ---------------- 3. 基础组件检测与补齐 ----------------
# 确保 PATH 包含常见自定义安装路径 (如 NVM Node20, Maven)
export PATH="/root/.nvm/versions/node/$(ls /root/.nvm/versions/node 2>/dev/null | tail -1)/bin:/opt/maven/bin:${PATH}"

for cmd in java node npm nginx mysql redis-cli certbot; do
  if command -v "$cmd" >/dev/null 2>&1; then
    log "已就绪组件: $cmd ($(command -v "$cmd"))"
  else
    warn "缺失组件: $cmd，正在尝试自动安装..."
    apt-get update -qq && apt-get install -y -qq "$cmd" 2>/dev/null || warn "无法自动安装 $cmd，请检查包管理器"
  fi
done

# 检测 Maven
if command -v mvn >/dev/null 2>&1 || [[ -x /opt/maven/bin/mvn ]]; then
  log "Maven 已就绪"
else
  warn "Maven 未检测到，正在安装 maven..."
  apt-get update -qq && apt-get install -y -qq maven
fi

# 配置国内 Maven 镜像加速源
if [[ ! -f /root/.m2/settings.xml ]]; then
  mkdir -p /root/.m2
  echo 'PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiPz4KPHNldHRpbmdzIHhtbG5zPSJodHRwOi8vbWF2ZW4uYXBhY2hlLm9yZy9TRVRUSU5HUy8xLjAuMCIKICAgICAgICAgIHhtbG5zOnhzaT0iaHR0cDovL3d3dy53My5vcmcvMjAwMS9YTUxTY2hlbWEtaW5zdGFuY2UiCiAgICAgICAgICB4c2k6c2NoZW1hTG9jYXRpb249Imh0dHA6Ly9tYXZlbi5hcGFjaGUub3JnL1NFVFRJTkdTLzEuMC4wIGh0dHBzOi8vbWF2ZW4uYXBhY2hlLm9yZy94c2Qvc2V0dGluZ3MtMS4wLjAueHNkIj4KICA8bWlycm9ycz4KICAgIDxtaXJyb3I+CiAgICAgIDxpZD5hbGl5dW5tYXZlbjwvaWQ+CiAgICAgIDxtaXJyb3JPZj4qPC9taXJyb3JPZj4KICAgICAgPG5hbWU+QWxpeXVuIFB1YmxpYyBNaXJyb3I8L25hbWU+CiAgICAgIDx1cmw+aHR0cHM6Ly9tYXZlbi5hbGl5dW4uY29tL3JlcG9zaXRvcnkvcHVibGljPC91cmw+CiAgICA8L21pcnJvcj4KICA8L21pcnJvcnM+Cjwvc2V0dGluZ3M+' | base64 -d > /root/.m2/settings.xml
  log "✅ 已配置国内 Maven 镜像加速源"
fi

# 确保 Certbot 与 Nginx 插件就绪
if ! command -v certbot >/dev/null 2>&1; then
  log "安装 certbot 与 python3-certbot-nginx..."
  apt-get update -qq && apt-get install -y -qq certbot python3-certbot-nginx
fi

log "✅ Shop-B2B2C 服务器环境与骨架准备完毕"
