#!/bin/bash
# 真实播放形态：一条持续 Range GET（顺序流式），在 0/16/32 条后台大流量下测
# “每 1MiB 到达间隔”的分布——播放卡顿来自这个间隔的尾部，而不是平均带宽。
# 用法：./bench_stream_under_load.sh [BASE] [ENC_PATH]
set -u
. "$(dirname "$0")/lib.sh"
BASE="${1:-${DAV_LAN_URL%/}}"
ENC_PATH="${2:-/share/videos/Akari25_3_8%e3%80%90%e5%ae%9f%e5%86%99LIVE%e3%80%91%e5%b7%a8%e4%b9%b3%e3%82%a2%e3%82%a4%e3%83%89%e3%83%ab%e3%81%ab%e7%94%98%e3%80%85%e3%81%ab%e8%80%b3%e8%88%90%e3%82%81%e3%81%95%e3%82%8c%e3%82%8b%e2%99%a1ASMR%e2%99%a1.mp4}"
TOTAL=$((64*1024*1024))
RC="$(make_curlrc)"; trap 'rm -f "$RC"' EXIT
export DAV_BASE="$BASE" DAV_PATH="$ENC_PATH" DAV_RC="$RC" DAV_TOTAL="$TOTAL"

stream() {
python3 - <<'PY'
import base64, http.client, os, sys, time, urllib.parse
base=os.environ['DAV_BASE']; path=os.environ['DAV_PATH']; rc=os.environ['DAV_RC']; total=int(os.environ['DAV_TOTAL'])
u=p=None
for line in open(rc):
    if line.startswith('user'):
        u,p=line.split('=',1)[1].strip().strip('"').split(':',1)
pu=urllib.parse.urlparse(base)
auth='Basic '+base64.b64encode(f'{u}:{p}'.encode()).decode()
conn=http.client.HTTPConnection(pu.hostname, pu.port or 80, timeout=120)
t0=time.time()
conn.request('GET', path, headers={'Range':f'bytes=0-{total-1}','Authorization':auth})
r=conn.getresponse()
got=0; marks=[]
CH=1048576
while got<total:
    b=r.read(CH)
    if not b: break
    got+=len(b); marks.append(time.time())
dt=time.time()-t0
gaps=[(marks[i]-marks[i-1])*1000 for i in range(1,len(marks))]
gaps.sort()
q=lambda p: gaps[min(len(gaps)-1,int(round(p*(len(gaps)-1))))] if gaps else 0
print(f"流式读取 {got/1048576:.0f}MiB / {dt:.2f}s = {got/1048576/dt:.2f}MB/s；"
      f"1MiB 到达间隔 n={len(gaps)} p50={q(.5):.0f} p95={q(.95):.0f} max={gaps[-1] if gaps else 0:.0f} ms")
PY
}

echo "=== 空载：单条流式 GET ${TOTAL}B ==="
stream

for BG in 16 32; do
  echo "=== 负载：$BG 条后台大流量 + 单条流式 GET ==="
  pids=()
  for i in $(seq 0 $((BG-1))); do
    off=$(( 400*1048576 + i*1048576 ))
    curl "${CURL_COMMON[@]}" -K "$RC" -r "$off-$((off+64*1048576-1))" -o /dev/null "$BASE$ENC_PATH" &
    pids+=($!)
  done
  sleep 0.5
  stream
  wait "${pids[@]}" 2>/dev/null || true
done
