#!/bin/bash
# 冷读 vs 并发冷读：4 个 GB 级文件，随机高位 offset 各读 SIZE 字节
# 目的：判断“大文件首读”瓶颈在磁盘还是网络；多连接能否叠加磁盘吞吐
set -u
. "$(dirname "$0")/lib.sh"
BASE="${1:-${DAV_LAN_URL%/}}"
RC="$(make_curlrc)"; trap 'rm -f "$RC"' EXIT
SIZE=${SIZE:-$((96*1024*1024))}
FILES=(
 "/share/videos/demo-sample.mp4"
 "/share/videos/demo-sample.mp4"
 "/share/videos/demo-sample.mp4"
 "/share/videos/demo-sample.mp4"
)
echo "SIZE=$SIZE 文件数=${#FILES[@]}"
echo "--- 顺序冷读 ---"
for f in "${FILES[@]}"; do
  off=$(( (RANDOM % 800 + 900) * 1048576 ))
  st=$(date +%s%N)
  out=$(timeout 90 curl "${CURL_COMMON[@]}" -K "$RC" -r "$off-$((off+SIZE-1))" -o /dev/null -w '%{http_code} %{size_download}' "$BASE$f")
  en=$(date +%s%N); dt=$(( (en-st)/1000000 ))
  echo "off=$((off/1048576))MiB code/bytes=$out ${dt}ms $(echo "$out" | awk -v d="$dt" '{printf "%.2f MB/s", $2/1048576/(d/1000)}')"
done

echo "--- 4 路并发冷读（不同文件、不同 offset） ---"
tmp=$(mktemp -d); t0=$(date +%s%N)
for i in 0 1 2 3; do
  f="${FILES[$i]}"; off=$(( (RANDOM % 800 + 900) * 1048576 ))
  curl "${CURL_COMMON[@]}" -K "$RC" -r "$off-$((off+SIZE-1))" -o /dev/null -w '%{http_code} %{size_download}\n' "$BASE$f" > "$tmp/$i" &
done
wait; t1=$(date +%s%N); dt=$(( (t1-t0)/1000000 ))
python3 - "$tmp" "$dt" <<'PY'
import sys,glob
b=0;f=0
for p in glob.glob(sys.argv[1]+'/*'):
    parts=open(p).read().split()
    if len(parts)<2 or parts[0]!='206': f+=1; continue
    b+=int(parts[1])
ms=int(sys.argv[2])
print(f"总 {b/1048576:.1f} MiB / {ms} ms = {b/1048576/(ms/1000):.2f} MB/s，失败 {f}")
PY
rm -rf "$tmp"
