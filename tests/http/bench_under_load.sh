#!/bin/bash
# 播放饿死实验：N 条后台大流量 + 1 条“播放器”顺序 1MiB Range 读，逐次记延迟
# 用法：./bench_under_load.sh [BASE] [ENC_PATH] [后台流数=16] [每流字节=48MiB] [播放读次数=20]
set -u
. "$(dirname "$0")/lib.sh"
BASE="${1:-${DAV_LAN_URL%/}}"
ENC_PATH="${2:-/share/videos/demo-sample.mp4}"
BG="${3:-16}"
BG_BYTES="${4:-50331648}"
PLAY_READS="${5:-20}"
CHUNK=1048576
RC="$(make_curlrc)"; trap 'rm -f "$RC"' EXIT

play() {  # $1=标签
  : > /tmp/play_lat.txt
  for i in $(seq 0 $((PLAY_READS-1))); do
    off=$(( i*CHUNK ))
    curl "${CURL_COMMON[@]}" -K "$RC" -r "$off-$((off+CHUNK-1))" -o /dev/null \
      -w '%{time_total} %{size_download}\n' "$BASE$ENC_PATH" >> /tmp/play_lat.txt
  done
  python3 - "$1" <<'PY'
import sys
rows=[l.split() for l in open('/tmp/play_lat.txt') if l.strip()]
v=sorted(float(r[0])*1000 for r in rows)
b=sum(int(r[1]) for r in rows if len(r)>1)
if not v: print(sys.argv[1], 'no-data'); sys.exit()
q=lambda p: v[min(len(v)-1,int(round(p*(len(v)-1))))]
wall=sum(v)/1000
print(f"{sys.argv[1]}: n={len(v)} 总={b/1048576:.1f}MiB 用时={wall:.2f}s 有效={b/1048576/wall:.2f}MB/s "
      f"单块延迟 min={v[0]:.1f} p50={q(.5):.1f} p95={q(.95):.1f} max={v[-1]:.1f} ms")
PY
}

echo "=== 空载基线：播放器顺序读 $PLAY_READS x 1MiB ==="
play "空载"
echo "=== 负载中：$BG 条后台流（各 ${BG_BYTES}B）+ 播放器读取 ==="
for r in 1 2 3; do
  pids=()
  for i in $(seq 0 $((BG-1))); do
    off=$(( 300*1048576 + i*1048576 + (r-1)*BG_BYTES ))
    curl "${CURL_COMMON[@]}" -K "$RC" -r "$off-$((off+BG_BYTES-1))" -o /dev/null "$BASE$ENC_PATH" &
    pids+=($!)
  done
  sleep 0.3
  play "负载轮$r"
  wait "${pids[@]}" 2>/dev/null || true
done
