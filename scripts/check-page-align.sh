#!/usr/bin/env bash
# 校验 native 库（libvlc / ffmpeg / whisper）是否满足 Android 16 KB 页对齐要求。
# 用法：
#   bash scripts/check-page-align.sh <目录或 .so 文件 ...>
#   bash scripts/check-page-align.sh app/build/outputs/apk/debug/app-debug.apk   # 直接查 APK
# 判定：每个 LOAD 段的 Align 必须 >= 0x4000（16 KB）。不达标在 Android 15+/16KB 页设备上会出现
#       "ELF alignment" 类加载失败。
set -uo pipefail

READELF="${READELF:-readelf}"
command -v "$READELF" >/dev/null || { echo "缺少 readelf（binutils），请安装或设置 READELF= 指向可用 readelf"; exit 2; }

TMP=""
cleanup() { [ -n "$TMP" ] && rm -rf "$TMP"; }
trap cleanup EXIT

inputs=("$@")
[ "${#inputs[@]}" -eq 0 ] && { echo "用法: bash scripts/check-page-align.sh <目录|.so|.apk> ..."; exit 2; }

# 收集待检查的 .so
list_file=$(mktemp)
for in_ in "${inputs[@]}"; do
  case "$in_" in
    *.apk)
      TMP=$(mktemp -d); unzip -qq -o "$in_" 'lib/*' -d "$TMP" 2>/dev/null
      find "$TMP" -name '*.so' >> "$list_file" ;;
    *.so) echo "$in_" >> "$list_file" ;;
    *) find "$in_" -name '*.so' >> "$list_file" ;;
  esac
done

ok=0; bad=0; badlist=""
while IFS= read -r so; do
  [ -z "$so" ] && continue
  # 只看 LOAD 段；取所有 LOAD 段 Align 的最小值
  min=$("$READELF" -lW "$so" 2>/dev/null | awk '/^  LOAD/ {print $NF}' | sort -u | head -1)
  aligns=$("$READELF" -lW "$so" 2>/dev/null | awk '/^  LOAD/ {printf "%s ", $NF}')
  # 16 KB = 0x4000；用十进制比较（readelf 输出十六进制）
  worst=0
  for a in $aligns; do
    v=$((a))
    [ "$v" -gt "$worst" ] && worst=$v
  done
  mins=$((min))
  if [ "$mins" -ge 16384 ]; then
    ok=$((ok+1))
    printf '  ✓ %-70s LOAD align=%s\n' "$(basename "$so")" "$aligns"
  else
    bad=$((bad+1)); badlist="$badlist\n    $so (align=$aligns)"
    printf '  ✗ %-70s LOAD align=%s\n' "$(basename "$so")" "$aligns"
  fi
done < "$list_file"

echo
echo "16 KB 页对齐校验：通过 $ok 个，不达标 $bad 个"
if [ "$bad" -gt 0 ]; then
  echo -e "不达标清单：$badlist"
  exit 1
fi
