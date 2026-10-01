#!/usr/bin/env bash
#
# 发布应用内更新（R20）：建 GitHub Release → 传 APK → 写 update.json 并提交。
#
# 用法：
#   bash scripts/publish-update.sh                 # 构建 release 包并发布
#   bash scripts/publish-update.sh --skip-build    # 复用已有的 app-release.apk
#   NOTES="更新说明" bash scripts/publish-update.sh  # 指定更新说明（默认读 docs/release-notes-<版本>.md）
#
# 前置：
#   - 版本号来自 app/build.gradle.kts 的 versionCode / versionName（发版前先改它）；
#   - 推送凭据：环境变量 GH_TOKEN，或本机 ~/.git-credentials（git store 助手）；
#   - 网络：GitHub 直连不稳时脚本会自动带上系统代理（环境变量 HTTPS_PROXY，或 /etc/profile 里的 https_proxy）。
#
# 安全：脚本只用 token 调 GitHub API，**不打印 token**，也不把它写进任何文件。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OWNER="gua123"
REPO="mediagate"
SKIP_BUILD=0
[ "${1:-}" = "--skip-build" ] && SKIP_BUILD=1

VERSION_CODE="$(grep -m1 'versionCode = ' app/build.gradle.kts | sed 's/[^0-9]//g')"
VERSION_NAME="$(grep -m1 'versionName = ' app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')"
TAG="v${VERSION_NAME}"
APK="app/build/outputs/apk/release/app-release.apk"
ASSET="mediagate-${VERSION_NAME}.apk"
NOTES_FILE="docs/release-notes-${VERSION_NAME}.md"

echo "== 发布 mediagate ${VERSION_NAME}（versionCode ${VERSION_CODE}）到 ${OWNER}/${REPO} =="

# 代理：直连 github.com 常常 135 s 超时（本机实测），有代理就带上
PROXY="${HTTPS_PROXY:-${https_proxy:-}}"
if [ -z "$PROXY" ] && [ -r /etc/profile ]; then
  PROXY="$(grep -m1 'https_proxy=' /etc/profile | sed 's/.*="\(.*\)".*/\1/' || true)"
fi
CURL=(curl -sS)
[ -n "$PROXY" ] && CURL+=(-x "$PROXY")

# token：环境变量优先，其次本机 git 凭据（读出来只放内存）
TOKEN="${GH_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -r "$HOME/.git-credentials" ]; then
  TOKEN="$(sed -E 's#https://([^:]+):([^@]+)@github.com#\2#' "$HOME/.git-credentials" | head -1)"
fi
if [ -z "$TOKEN" ]; then
  echo "！！ 没有可用的 GitHub token：请设 GH_TOKEN 或先让 git 记住凭据" >&2
  exit 1
fi

api() {
  "${CURL[@]}" -H "Authorization: Bearer $TOKEN" -H "User-Agent: mediagate-publish" \
    -H "Accept: application/vnd.github+json" "$@"
}

# ---------------------------------------------------------------- 1. 构建
if [ "$SKIP_BUILD" = "0" ]; then
  echo "-- 构建 release 包（source scripts/env.sh 后跑 gradle）"
  # shellcheck disable=SC1091
  source scripts/env.sh >/dev/null 2>&1
  ./gradlew :app:assembleRelease
fi
if [ ! -f "$APK" ]; then
  echo "！！ 找不到 $APK（先跑 ./gradlew :app:assembleRelease）" >&2
  exit 1
fi

SIZE="$(stat -c %s "$APK")"
SHA="$(sha256sum "$APK" | awk '{print $1}')"
echo "-- APK：$APK（$SIZE 字节，sha256 $SHA）"

# ---------------------------------------------------------------- 2. 建/取 Release
NOTES="${NOTES:-}"
if [ -z "$NOTES" ] && [ -f "$NOTES_FILE" ]; then
  NOTES="$(cat "$NOTES_FILE")"
fi
if [ -z "$NOTES" ]; then
  NOTES="mediagate ${VERSION_NAME}（versionCode ${VERSION_CODE}）"
fi

PAYLOAD="$(python3 - "$TAG" "$VERSION_NAME" "$NOTES" <<'PY'
import json, sys
tag, name, notes = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({"tag_name": tag, "name": name, "body": notes, "draft": False, "prerelease": False}))
PY
)"

echo "-- 建 Release $TAG"
RESP="$(api -X POST "https://api.github.com/repos/$OWNER/$REPO/releases" -d "$PAYLOAD" -w '\n%{http_code}')"
CODE="$(printf '%s' "$RESP" | tail -1)"
BODY="$(printf '%s' "$RESP" | sed '$d')"
if [ "$CODE" = "422" ]; then
  echo "-- 该 tag 的 Release 已存在，改为复用它"
  RESP="$(api "https://api.github.com/repos/$OWNER/$REPO/releases/tags/$TAG" -w '\n%{http_code}')"
  CODE="$(printf '%s' "$RESP" | tail -1)"
  BODY="$(printf '%s' "$RESP" | sed '$d')"
fi
if [ "$CODE" != "200" ] && [ "$CODE" != "201" ]; then
  echo "！！ 建 Release 失败（HTTP $CODE）：$BODY" >&2
  exit 1
fi

RELEASE_ID="$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
echo "-- Release id=$RELEASE_ID"

# ---------------------------------------------------------------- 3. 传资产（同名先删再传，保证可重跑）
# 注意：这里不能用 "python3 - <<PY" 读管道——heredoc 会占掉 stdin，json.load(sys.stdin) 拿到空串。
api "https://api.github.com/repos/$OWNER/$REPO/releases/$RELEASE_ID/assets" -o /tmp/publish-assets.json
EXISTING="$(python3 -c '
import json, sys
name, path = sys.argv[1], sys.argv[2]
for asset in json.load(open(path)):
    if asset.get("name") == name:
        print(asset["id"])
        break
' "$ASSET" /tmp/publish-assets.json)"
if [ -n "$EXISTING" ]; then
  echo "-- 删除同名旧资产 id=$EXISTING"
  api -X DELETE "https://api.github.com/repos/$OWNER/$REPO/releases/assets/$EXISTING" -o /dev/null
fi

# 大文件上传强制 HTTP/1.1 并关掉 Expect: 100-continue：
# 实测走代理时 HTTP/2 的 74 MB POST 会卡住（curl 读完全部字节却迟迟拿不到响应）。
ASSET_URL="$(api "https://api.github.com/repos/$OWNER/$REPO/releases/$RELEASE_ID" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["upload_url"].split("{")[0])')"
echo "-- 上传 $ASSET → $ASSET_URL"
# 上传走**直连**：实测代理下 74 MB 的 POST 会卡死（curl 读完全部字节却拿不到响应），
# 而 uploads.github.com 直连是通的（302/0.7 s）。API 调用仍然走代理。
curl -sS --http1.1 -X POST \
  -H "Authorization: Bearer $TOKEN" -H "User-Agent: mediagate-publish" \
  -H "Content-Type: application/vnd.android.package-archive" \
  -H "Expect:" \
  --data-binary @"$APK" \
  "$ASSET_URL?name=$ASSET" \
  -o /tmp/publish-asset.json -w 'HTTP %{http_code}\n'
python3 - <<'PY'
import json
d = json.load(open('/tmp/publish-asset.json'))
print("  资产：", d.get("name"), d.get("size"), "字节")
print("  直链：", d.get("browser_download_url"))
PY

APK_URL="https://github.com/$OWNER/$REPO/releases/download/$TAG/$ASSET"

# ---------------------------------------------------------------- 4. 写 update.json（提交进仓库，App 从 raw 读）
echo "-- 写 update.json"
python3 - "$VERSION_CODE" "$VERSION_NAME" "$APK_URL" "$SIZE" "$SHA" "$NOTES" <<'PY'
import json, sys
code, name, url, size, sha, notes = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5], sys.argv[6]
manifest = {
    "versionCode": int(code),
    "versionName": name,
    "apkUrl": url,
    "sizeBytes": size,
    "sha256": sha,
    "notes": notes.strip(),
}
with open("update.json", "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(json.dumps(manifest, ensure_ascii=False)[:200])
PY

git add update.json
if git diff --cached --quiet; then
  echo "-- update.json 无变化，跳过提交"
else
  git -c user.name="mediagate-publish" -c user.email="publish@local" commit -q -m "release: ${VERSION_NAME}（versionCode ${VERSION_CODE}）"
fi
echo "-- 推送（带代理）"
GIT_TERMINAL_PROMPT=0 git ${PROXY:+-c http.proxy="$PROXY"} -c https.proxy="${PROXY:-}" push origin HEAD:main 2>&1 | tail -2

echo
echo "== 完成 =="
echo "  版本：${VERSION_NAME}（${VERSION_CODE}）"
echo "  资产：$APK_URL"
echo "  清单：https://raw.githubusercontent.com/$OWNER/$REPO/main/update.json"
echo "  自检：curl -s https://raw.githubusercontent.com/$OWNER/$REPO/main/update.json"
