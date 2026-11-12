#!/usr/bin/env bash
# ChatShy 服务器更新脚本
#
# 由 GitHub Actions 构建并发布到 Release，服务器主动拉取产物，CI 无需访问服务器。
# 首次使用：把本文件放到服务器（如 /home/ChatShy/update.sh）并 chmod +x。
#
# 用法（在服务器上以 root 运行）：
#   ./update.sh                  # 从 GitHub Release 更新到最新构建
#   ./update.sh --local DIR      # 使用本地已下载的产物更新（GitHub 下载超时时用）
#   ./update.sh --rollback       # 回滚到上一版
#   ./update.sh --help
#
# --local DIR 中需要 chatshy-server.jar、dounai-dist.tar.gz；
# 可选 release.txt（第一行作为版本标识）。
#
# 可覆盖的环境变量：
#   CHATSHY_REPO         GitHub 仓库        默认 cn3shy/ChatShy
#   CHATSHY_ROOT         部署根目录          默认 /home/ChatShy
#   CHATSHY_SERVICE      systemd 服务名      默认 chatshy
#   CHATSHY_HEALTH_URL   健康检查地址        默认 http://127.0.0.1:8088/healthz
#   CHATSHY_KEEP         保留备份份数        默认 5

set -euo pipefail

REPO="${CHATSHY_REPO:-cn3shy/ChatShy}"
ROOT="${CHATSHY_ROOT:-/home/ChatShy}"
SERVICE="${CHATSHY_SERVICE:-chatshy}"
HEALTH_URL="${CHATSHY_HEALTH_URL:-http://127.0.0.1:8088/healthz}"
KEEP="${CHATSHY_KEEP:-5}"

RELEASE_BASE="https://github.com/${REPO}/releases/latest/download"
LOCAL_DIR=""
JAR="${ROOT}/server-java/target/chatshy-server.jar"
DIST="${ROOT}/dounai/dist"
BACKUP_DIR="${ROOT}/backups"
LOCK_FILE="/tmp/chatshy-update.lock"

if [[ "${EUID}" -eq 0 ]]; then SUDO=""; else SUDO="sudo"; fi

log()  { printf '==> %s\n' "$*"; }
warn() { printf '警告: %s\n' "$*" >&2; }
die()  { printf '错误: %s\n' "$*" >&2; exit 1; }

TMP=""
cleanup() { if [[ -n "${TMP}" && -d "${TMP}" ]]; then rm -rf "${TMP}"; fi; }
trap cleanup EXIT

# 同一时间只允许一个更新进程
exec 9>"${LOCK_FILE}"
flock -n 9 || die "已有更新正在进行（锁: ${LOCK_FILE}）"

stage_artifacts() {
  TMP="$(mktemp -d)"
  mkdir -p "${TMP}/dist"

  if [[ -n "${LOCAL_DIR}" ]]; then
    [[ -d "${LOCAL_DIR}" ]] || die "本地目录不存在: ${LOCAL_DIR}"
    [[ -f "${LOCAL_DIR}/chatshy-server.jar" ]] || die "缺少 ${LOCAL_DIR}/chatshy-server.jar"
    [[ -f "${LOCAL_DIR}/dounai-dist.tar.gz" ]] || die "缺少 ${LOCAL_DIR}/dounai-dist.tar.gz"
    cp -f "${LOCAL_DIR}/chatshy-server.jar" "${TMP}/chatshy-server.jar"
    cp -f "${LOCAL_DIR}/dounai-dist.tar.gz" "${TMP}/dounai-dist.tar.gz"
    if [[ -f "${LOCAL_DIR}/release.txt" ]]; then
      RELEASE_TAG="$(head -n1 "${LOCAL_DIR}/release.txt" | tr -d '\r')"
    else
      RELEASE_TAG="local"
    fi
    [[ -n "${RELEASE_TAG}" ]] || RELEASE_TAG="local"
    log "使用本地产物: ${LOCAL_DIR}（${RELEASE_TAG}）"
  else
    RELEASE_TAG="$(curl -fsSL --max-time 10 \
        "https://api.github.com/repos/${REPO}/releases/latest" 2>/dev/null \
        | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)" || true
    if [[ -z "${RELEASE_TAG}" ]]; then RELEASE_TAG="latest"; fi

    log "下载 ${REPO} @ ${RELEASE_TAG}"
    curl -fL --retry 3 --connect-timeout 10 --max-time 300 \
      -o "${TMP}/chatshy-server.jar" "${RELEASE_BASE}/chatshy-server.jar" \
      || die "下载后端失败（可先用浏览器下载两个文件，再执行 ./update.sh --local <目录>）"
    curl -fL --retry 3 --connect-timeout 10 --max-time 300 \
      -o "${TMP}/dounai-dist.tar.gz" "${RELEASE_BASE}/dounai-dist.tar.gz" \
      || die "下载前端失败（可先用浏览器下载两个文件，再执行 ./update.sh --local <目录>）"
  fi

  [[ -s "${TMP}/chatshy-server.jar" ]] || die "后端文件为空"
  head -c 2 "${TMP}/chatshy-server.jar" | grep -q 'PK' || die "后端文件不是有效的 jar"
  tar -xzf "${TMP}/dounai-dist.tar.gz" -C "${TMP}/dist"
  [[ -f "${TMP}/dist/index.html" ]] || die "前端包缺少 index.html"
}

# 备份当前线上版本（jar + 前端），成功更新后保留用于回滚
make_backup() {
  LATEST_BACKUP="${BACKUP_DIR}/$(date +%Y%m%d-%H%M%S)-${RELEASE_TAG}"
  mkdir -p "${LATEST_BACKUP}"
  if [[ -f "${JAR}" ]]; then cp -f "${JAR}" "${LATEST_BACKUP}/chatshy-server.jar"; fi
  if [[ -d "${DIST}" ]]; then tar -czf "${LATEST_BACKUP}/dounai-dist.tar.gz" -C "${DIST}" .; fi
  printf '%s\n' "${RELEASE_TAG}" > "${LATEST_BACKUP}/release.txt"
  log "已备份当前版本到 ${LATEST_BACKUP}"
}

install_jar() {
  mkdir -p "$(dirname "${JAR}")"
  # 先写临时文件再 rename，保证运行中的进程不会读到半个 jar
  cp -f "${TMP}/chatshy-server.jar" "${JAR}.new"
  mv -f "${JAR}.new" "${JAR}"
  log "后端已更新"
}

install_dist() {
  rm -rf "${DIST}.new" "${DIST}.old"
  mkdir -p "${DIST}.new"
  cp -a "${TMP}/dist/." "${DIST}.new/"
  if [[ -d "${DIST}" ]]; then mv "${DIST}" "${DIST}.old"; fi
  mv "${DIST}.new" "${DIST}"
  rm -rf "${DIST}.old"
  log "前端已更新"
}

restart_and_check() {
  log "重启 ${SERVICE}"
  ${SUDO} systemctl restart "${SERVICE}"
  local i
  for i in $(seq 1 30); do
    if curl -fsS --max-time 2 "${HEALTH_URL}" >/dev/null 2>&1; then
      log "健康检查通过"
      return 0
    fi
    sleep 1
  done
  return 1
}

restore_from() {
  local dir="$1"
  [[ -f "${dir}/chatshy-server.jar" ]] || die "备份不完整，缺少 jar: ${dir}"
  cp -f "${dir}/chatshy-server.jar" "${JAR}.new"
  mv -f "${JAR}.new" "${JAR}"
  if [[ -f "${dir}/dounai-dist.tar.gz" ]]; then
    rm -rf "${DIST}.new" "${DIST}.old"
    mkdir -p "${DIST}.new"
    tar -xzf "${dir}/dounai-dist.tar.gz" -C "${DIST}.new"
    if [[ -d "${DIST}" ]]; then mv "${DIST}" "${DIST}.old"; fi
    mv "${DIST}.new" "${DIST}"
    rm -rf "${DIST}.old"
  fi
  log "已恢复备份 ${dir}"
  restart_and_check
}

prune_backups() {
  local count
  count="$(find "${BACKUP_DIR}" -mindepth 1 -maxdepth 1 -type d | wc -l)"
  if (( count > KEEP )); then
    ls -1dt "${BACKUP_DIR}"/*/ | tail -n +$((KEEP + 1)) | xargs -r rm -rf
    log "已清理旧备份，保留最近 ${KEEP} 份"
  fi
}

update() {
  stage_artifacts

  local new_sum old_sum jar_changed=1 dist_changed=0
  new_sum="$(sha256sum "${TMP}/chatshy-server.jar" | cut -d' ' -f1)"
  if [[ "${RELEASE_TAG}" == "local" ]]; then RELEASE_TAG="local-${new_sum:0:8}"; fi
  if [[ -f "${JAR}" ]]; then
    old_sum="$(sha256sum "${JAR}" | cut -d' ' -f1)"
    if [[ "${old_sum}" == "${new_sum}" ]]; then jar_changed=0; fi
  fi
  if [[ ! -d "${DIST}" ]] || ! diff -qr "${DIST}" "${TMP}/dist" >/dev/null 2>&1; then
    dist_changed=1
  fi

  if [[ "${jar_changed}" -eq 0 && "${dist_changed}" -eq 0 ]]; then
    log "当前已是最新（${RELEASE_TAG}），无需更新"
    return 0
  fi

  make_backup
  if [[ "${jar_changed}" -eq 1 ]]; then
    install_jar
  else
    log "后端 jar 未变化，跳过替换"
  fi
  if [[ "${dist_changed}" -eq 1 ]]; then
    install_dist
  else
    log "前端未变化，跳过替换"
  fi

  if [[ "${jar_changed}" -eq 1 ]]; then
    if ! restart_and_check; then
      warn "新版本健康检查失败，自动回滚"
      if restore_from "${LATEST_BACKUP}"; then
        die "已回滚到上一版，请查看 journalctl -u ${SERVICE} -n 100 --no-pager"
      fi
      die "自动回滚也失败，请手动处理：journalctl -u ${SERVICE} -n 100 --no-pager"
    fi
  else
    log "后端未变化，无需重启服务"
  fi

  prune_backups
  log "更新完成：${RELEASE_TAG}"
}

rollback() {
  local dir
  dir="$(ls -1dt "${BACKUP_DIR}"/*/ 2>/dev/null | head -n1)" || true
  [[ -n "${dir:-}" ]] || die "没有可用备份（${BACKUP_DIR}）"
  restore_from "${dir%/}" || die "回滚后健康检查失败，请查看 journalctl -u ${SERVICE} -n 100 --no-pager"
  log "回滚完成"
}

case "${1:-}" in
  "") update ;;
  --local)
    [[ $# -ge 2 && -n "${2:-}" ]] || die "--local 需要指定产物目录，例如: ./update.sh --local /home/ChatShy/upload"
    LOCAL_DIR="$2"
    update ;;
  --rollback) rollback ;;
  -h|--help) sed -n '2,21p' "$0" ;;
  *) die "未知参数: $1（可用: --local DIR、--rollback）" ;;
esac
