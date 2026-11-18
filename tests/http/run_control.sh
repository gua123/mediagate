#!/bin/bash
# 对照实验驱动：本地每连接限速 RATE 的 HTTP 服务，验证“协议层可线性叠加”
# 用法：./run_control.sh [RATE_MBps=8]
set -u
RATE="${1:-8}"
PORT="${PORT:-18080}"
HERE="$(cd "$(dirname "$0")" && pwd)"
node "$HERE/control_stack.js" "$RATE" "$PORT" &
SRV=$!
trap 'kill $SRV 2>/dev/null' EXIT
sleep 0.8
SIZE=$((16*1024*1024))   # 每流 16MiB
for c in 1 2 4 8 16; do
  tmp=$(mktemp -d); t0=$(date +%s%N); pids=()
  for i in $(seq 0 $((c-1))); do
    off=$(( i*SIZE ))
    curl -sS --max-time 60 -r "$off-$((off+SIZE-1))" -o /dev/null -w '%{http_code} %{size_download}\n' "http://127.0.0.1:$PORT/f.bin" > "$tmp/$i" &
    pids+=($!)
  done
  wait "${pids[@]}"; t1=$(date +%s%N); dt=$(( (t1-t0)/1000000 ))
  python3 - "$tmp" "$dt" "$c" "$RATE" <<'PY'
import sys,glob
tmp,ms,c,rate=sys.argv[1],int(sys.argv[2]),int(sys.argv[3]),sys.argv[4]
b=0
for p in glob.glob(tmp+'/*'):
    parts=open(p).read().split()
    if len(parts)>=2: b+=int(parts[1])
print(f"per-conn cap={rate}MB/s  conns={c:<3d} {b/1048576:6.1f} MiB / {ms:5d} ms = {b/1048576/(ms/1000):6.2f} MB/s")
PY
  rm -rf "$tmp"
done
