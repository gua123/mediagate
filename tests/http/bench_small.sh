#!/bin/bash
# 小请求延迟：目录 PROPFIND、缩略图级 Range（256KiB），顺序 vs 并发，新连接 vs keep-alive
# 用法：./bench_small.sh
set -u
. "$(dirname "$0")/lib.sh"
BASE="${1:-${DAV_LAN_URL%/}}"
RC="$(make_curlrc)"; trap 'rm -f "$RC"' EXIT
OUTDIR="${OUTDIR:-$HERE/results}"; mkdir -p "$OUTDIR"

stat_lat() {  # 从 stdin 读毫秒值，输出 min/p50/p95/max
python3 -c '
import sys
v=sorted(float(x) for x in sys.stdin.read().split())
if not v: print("no-data"); sys.exit()
q=lambda p: v[min(len(v)-1,int(round(p*(len(v)-1))))]
print(f"n={len(v)} min={v[0]:.1f} p50={q(.5):.1f} p95={q(.95):.1f} max={v[-1]:.1f}")
'
}

echo "=== A. 目录 PROPFIND（Depth:1）顺序 20 次，每次新连接 ==="
: > /tmp/small_a.txt
for i in $(seq 1 20); do
  curl "${CURL_COMMON[@]}" -K "$RC" -X PROPFIND -H 'Depth: 1' -o /dev/null \
    -w '%{time_total}\n' "$BASE/share/" >> /tmp/small_a.txt
done
awk '{print $1*1000}' /tmp/small_a.txt | stat_lat

echo "=== B. 目录 PROPFIND 顺序 20 次，单连接 keep-alive ==="
python3 - "$BASE" "$RC" <<'PY'
import base64, http.client, sys, time, urllib.parse
base, rc = sys.argv[1], sys.argv[2]
u = p = None
for line in open(rc):
    if line.startswith('user'):
        u, p = line.split('=',1)[1].strip().strip('"').split(':',1)
host = urllib.parse.urlparse(base).hostname or '192.168.1.10'
port = urllib.parse.urlparse(base).port or 80
auth = 'Basic ' + base64.b64encode(f'{u}:{p}'.encode()).decode()
conn = http.client.HTTPConnection(host, port, timeout=30)
lat=[]
for i in range(20):
    t=time.time()
    conn.request('PROPFIND', '/share/', headers={'Depth':'1','Authorization':auth})
    r=conn.getresponse(); r.read()
    lat.append((time.time()-t)*1000)
lat.sort()
def q(p): return lat[min(len(lat)-1,int(round(p*(len(lat)-1))))]
print(f"n={len(lat)} min={lat[0]:.1f} p50={q(.5):.1f} p95={q(.95):.1f} max={lat[-1]:.1f}  (同一 TCP 连接)")
PY

echo "=== C. 缩略图级 Range 256KiB 顺序 30 次（新连接） ==="
: > /tmp/small_c.txt
for i in $(seq 1 30); do
  curl "${CURL_COMMON[@]}" -K "$RC" -r 0-262143 -o /dev/null -w '%{time_total}\n' "$BASE/home/cms%2Boracle.zip" >> /tmp/small_c.txt
done
awk '{print $1*1000}' /tmp/small_c.txt | stat_lat

echo "=== D. 缩略图级 Range 256KiB 32 路并发 1 轮（瀑布流场景） ==="
tmp="$(mktemp -d)"; t0=$(date +%s%N)
for i in $(seq 0 31); do
  off=$(( i*262144*4 ))
  curl "${CURL_COMMON[@]}" -K "$RC" -r "$off-$((off+262143))" -o /dev/null -w '%{time_total}\n' "$BASE/home/cms%2Boracle.zip" > "$tmp/$i" &
done
wait; t1=$(date +%s%N)
cat "$tmp"/* | stat_lat
echo "墙钟: $(( (t1-t0)/1000000 )) ms  (32 x 256KiB)"
rm -rf "$tmp"
