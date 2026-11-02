#!/usr/bin/env bash
# 文件：05-rollback.sh
# 用途：回滚后端与静态发布产物。
# 维护注意：产物回滚不自动回滚数据库或恢复库存语义；跨版本迁移须评估兼容，先确认目标版本和备份。 阿里云目录仍含GMall/PDK等历史工程路径，使用前必须对照当前项目核对，详见D34/D35。
# ============================================================================
# GMall 回滚脚本 —— 将 releases/latest 指回历史版本并重启服务
# 用法：
#   sudo bash /opt/gmall/scripts/05-rollback.sh            # 回滚到上一个版本
#   sudo bash /opt/gmall/scripts/05-rollback.sh <版本号>
#   sudo bash /opt/gmall/scripts/05-rollback.sh --list     # 列出可用版本
# ============================================================================
set -euo pipefail

GMALL_ROOT="${GMALL_ROOT:-/opt/gmall}"
RELEASES_DIR="${GMALL_ROOT}/releases"

log()  { printf '\033[0;32m[rollback]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[rollback]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[rollback][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行"

list_releases() {
  echo "可用版本列表："
  local current
  current="$(basename "$(readlink -f "${RELEASES_DIR}/latest")" 2>/dev/null || echo '')"
  (ls -1dt "${RELEASES_DIR}"/[0-9]* 2>/dev/null || true) | while read -r d; do
    if [[ -z "${d}" ]]; then continue; fi
    b="$(basename "$d")"
    mark=" "
    if [[ "$b" == "$current" ]]; then mark="*"; fi
    printf '  %s %s\n' "$mark" "$b"
  done
}

case "${1:-}" in
  --list|-l) list_releases; exit 0 ;;
esac

TARGET="${1:-}"
if [[ -z "${TARGET}" ]]; then
  CURRENT="$(basename "$(readlink -f "${RELEASES_DIR}/latest")" 2>/dev/null || echo '')"
  TARGET="$( (ls -1dt "${RELEASES_DIR}"/[0-9]* 2>/dev/null || true) | while read -r d; do basename "$d"; done | grep -v "^${CURRENT}$" | head -1 || true)"
  [[ -n "${TARGET}" ]] || die "没有可回滚的历史版本"
fi

TARGET_DIR="${RELEASES_DIR}/${TARGET}"
[[ -d "${TARGET_DIR}" ]] || die "指定版本不存在: ${TARGET_DIR}"

log "切换 releases/latest -> ${TARGET_DIR} ..."
ln -sfn "${TARGET_DIR}" "${GMALL_ROOT}/releases/latest"

log "执行重新部署上线..."
bash "${GMALL_ROOT}/scripts/04-deploy.sh" --keep-source
log "✅ 已成功回滚至 ${TARGET}"
