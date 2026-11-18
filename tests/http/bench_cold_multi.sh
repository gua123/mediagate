#!/bin/bash
# 冷读 vs 并发冷读：4 个 GB 级文件，随机高位 offset 各读 SIZE 字节
# 目的：判断“大文件首读”瓶颈在磁盘还是网络；多连接能否叠加磁盘吞吐
set -u
. "$(dirname "$0")/lib.sh"
BASE="${1:-${DAV_LAN_URL%/}}"
RC="$(make_curlrc)"; trap 'rm -f "$RC"' EXIT
SIZE=${SIZE:-$((96*1024*1024))}
FILES=(
 "/share/videos/Akari25_3_8%e3%80%90%e5%ae%9f%e5%86%99LIVE%e3%80%91%e5%b7%a8%e4%b9%b3%e3%82%a2%e3%82%a4%e3%83%89%e3%83%ab%e3%81%ab%e7%94%98%e3%80%85%e3%81%ab%e8%80%b3%e8%88%90%e3%82%81%e3%81%95%e3%82%8c%e3%82%8b%e2%99%a1ASMR%e2%99%a1.mp4"
 "/share/videos/akari_asmr%20%e5%92%8c%e5%88%9a%e6%b4%97%e5%ae%8c%e6%be%a1%e7%9a%84%e5%a5%b3%e6%9c%8b%e5%8f%8b%e6%89%93%e6%83%85%e9%aa%82%e4%bf%8f%e8%88%94%e8%80%b3%e6%9c%b5.mp4"
 "/share/videos/%e8%bf%b7%e4%bd%a0%e8%a3%99%e5%a5%b3%e4%bb%86%e6%bf%80%e7%83%88%e8%88%94%e8%80%b3%e6%9c%b5%e6%b2%bb%e6%84%88%20-%20Akari%e2%80%99s%20ASMR.mp4"
 "/share/videos/akari_asmr%20%e5%9c%b0%e9%9b%b7%e7%b3%bb%e7%9a%84%e4%b9%89%e5%a6%b9%e5%92%8c%e8%88%94%e8%80%b3%e6%9c%b5%e7%9a%84ASMR.mp4"
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
