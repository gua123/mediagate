#!/bin/bash
# 公共：从 .toolchain/real-server.properties 读取 WebDAV 凭据（只读，不打印密码）
# 用法：. "$(dirname "$0")/lib.sh"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROPS="${PROPS:-$HERE/../../.toolchain/real-server.properties}"

_prop() { grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2-; }

[ -f "$PROPS" ] || { echo "缺少 $PROPS"; exit 1; }
DAV_USER="$(_prop user)"
DAV_PASS="$(_prop password)"
DAV_LAN_URL="$(_prop dav.lan.url)"
DAV_WAN_URL="$(_prop dav.wan.url)"

# 生成 600 权限的 curl 配置文件，避免密码出现在 ps / 命令行
make_curlrc() {
  local f; f="$(mktemp /tmp/dav.XXXXXX)"
  chmod 600 "$f"
  local u p
  u="$(printf '%s' "$DAV_USER" | sed 's/\\/\\\\/g; s/"/\\"/g')"
  p="$(printf '%s' "$DAV_PASS" | sed 's/\\/\\\\/g; s/"/\\"/g')"
  printf 'user = "%s:%s"\n' "$u" "$p" > "$f"
  printf '%s' "$f"
}

# curl 公共参数：不重试、超时、静默
CURL_COMMON=(-sS --max-time 120 --connect-timeout 10 -H 'Connection: keep-alive')
