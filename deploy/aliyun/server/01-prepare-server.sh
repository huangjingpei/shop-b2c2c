#!/usr/bin/env bash
# 文件：01-prepare-server.sh
# 用途：服务器依赖、目录及基础运行环境准备。
# 维护注意：会安装或调整系统组件，先确认目标主机与权限；不可为验证注释而实际执行。 阿里云目录仍含GMall/PDK等历史工程路径，使用前必须对照当前项目核对，详见D34/D35。
# ============================================================================
# GMall 服务器环境检查与骨架创建
# 用法：sudo bash /opt/gmall/scripts/01-prepare-server.sh
# ============================================================================
set -euo pipefail

GMALL_ROOT="${GMALL_ROOT:-/opt/gmall}"
LOG_DIR="${GMALL_ROOT}/logs"

export DEBIAN_FRONTEND=noninteractive
export LANG=C.UTF-8

log()  { printf '\033[0;32m[prepare]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[prepare]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[prepare][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"

# ---------------- 目录骨架 ----------------
log "创建目录骨架 ${GMALL_ROOT}/..."
mkdir -p "${GMALL_ROOT}"/{src,app,releases,www/admin,www/mobile,logs,config,scripts,backups}
chmod 755 "${GMALL_ROOT}"

# ---------------- 虚拟内存 (Swap) 自动检测与配置 ----------------
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

# 检查基础组件
for cmd in java mvn node npm nginx mysql; do
  if command -v "$cmd" >/dev/null 2>&1; then
    log "已就绪组件: $cmd ($(command -v "$cmd"))"
  else
    warn "缺失组件: $cmd"
  fi
done

log "✅ GMall 服务器骨架准备完毕"
