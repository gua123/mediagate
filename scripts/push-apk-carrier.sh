#!/usr/bin/env bash
#
# 把 release APK 切成小块，逐块提交并逐块推到 github 的「承载分支」。
#
# 为什么不用一次性推：本机 -> github.com / uploads.github.com 上行 70-110 KB/s，
# 且单次 HTTP 上传请求在 8-10 MB 处就被掐断（实测 6/8 MB 成功、10 MB 必挂）。
# 单包 33 MiB 的 git push 会反复重连重传，永远推不完（实测推了 18 分钟、每次都在 4-8 MB 处断）。
# 切成 ~8 MiB 一块、每块一次 push + 独立重试，单请求体积落进天花板以内，失败只丢一块。
#
# 用法：
#   bash scripts/push-apk-carrier.sh
#   TAG=v0.1.113 BRANCH=apk-carrier CHUNK_MB=8 bash scripts/push-apk-carrier.sh
#
# 成功后由 .github/workflows/release-asset.yml 把分块拼回原文件、校验 sha256、上传到已有 Release。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TAG="${TAG:-v0.1.113}"
BRANCH="${BRANCH:-apk-carrier}"
APK="${APK:-app/build/outputs/apk/release/app-release.apk}"
NAME="${NAME:-mediagate-0.1.113.apk}"
CHUNK_MB="${CHUNK_MB:-8}"
PARTS_DIR=".release-asset/parts"

[ -f "$APK" ] || { echo "找不到 $APK" >&2; exit 1; }

SIZE="$(stat -c %s "$APK")"
SHA="$(sha256sum "$APK" | awk '{print $1}')"
echo "== APK $APK：$SIZE 字节，sha256 $SHA =="

git checkout -q -B "$BRANCH" main
rm -rf .release-asset
mkdir -p "$PARTS_DIR"
split -b "$((CHUNK_MB*1024*1024))" -d -a 3 "$APK" "$PARTS_DIR/$NAME.part"

{
  echo "name=$NAME"
  echo "size=$SIZE"
  echo "sha256=$SHA"
  echo "parts=$(ls "$PARTS_DIR"/*.part* | wc -l)"
  echo "chunk_bytes=$((CHUNK_MB*1024*1024))"
} > "$PARTS_DIR/MANIFEST.txt"
cat "$PARTS_DIR/MANIFEST.txt"

push_commit() {
  local msg="$1"
  git -c user.name=release-bot -c user.email=release-bot@local commit -q -m "$msg"
  local attempt
  for attempt in 1 2 3 4 5; do
    local t0 t1
    t0=$(date +%s)
    if GIT_TERMINAL_PROMPT=0 git -c http.postBuffer=524288000 push origin "$BRANCH:$BRANCH" >/tmp/push-part.log 2>&1; then
      t1=$(date +%s)
      echo "  [ok] $msg（$((t1-t0))s）"
      return 0
    fi
    t1=$(date +%s)
    echo "  [retry $attempt] $msg 失败（$((t1-t0))s）：$(tail -1 /tmp/push-part.log | cut -c1-120)"
    sleep 3
  done
  echo "  [FAILED] $msg" >&2
  return 1
}

# 1) 清单先上（几 KB）
git add -f "$PARTS_DIR/MANIFEST.txt"
push_commit "chore($TAG): apk carrier manifest"

# 2) 逐块：每块一个 commit、一次 push（单请求体积 = 一块）
n=0
total=$(ls "$PARTS_DIR"/*.part* | wc -l)
for f in "$PARTS_DIR/$NAME".part*; do
  n=$((n+1))
  git add -f "$f"
  echo "-- part $n/$total：$(basename "$f") $(stat -c %s "$f") 字节"
  push_commit "chore($TAG): apk part $n/$total ($(basename "$f"))"
done

echo "== 核对远端 =="
GIT_TERMINAL_PROMPT=0 git fetch -q origin "$BRANCH"
remote_parts=$(git ls-tree -r --name-only FETCH_HEAD | grep -c "^.release-asset/parts/.*\.part" || true)
echo "远端 part 文件数=$remote_parts / 本地=$total"
[ "$remote_parts" = "$total" ] || { echo "！！ 远端分块数不足" >&2; exit 1; }
echo "== 完成 =="
