#!/bin/bash
# HTTP/WebDAV 多连接 Range 吞吐 + 抖动测量
# 每个并发流固定读 BYTES_STREAM 字节；每轮记录墙钟、总吞吐、单请求延迟分布、失败数。
#
# 用法：./bench_multi.sh [BASE_URL] [ENCODED_PATH]
# 环境变量：
#   CONC="1 2 4 8 16 32"   并发序列
#   BYTES_STREAM=50331648  每流字节数（默认 48MiB）
#   ROUNDS=5               每个并发跑几轮（第 1 轮默认当预热，仍记录）
#   SPAN=1048576           每流起始 offset 间距（默认每流独占 1MiB 起点，避免完全重叠）
set -u
. "$(dirname "$0")/lib.sh"

BASE="${1:-${DAV_LAN_URL%/}}"
ENC_PATH="${2:-/home/cms%2Boracle.zip}"
CONC="${CONC:-1 2 4 8 16 32}"
ROUNDS="${ROUNDS:-5}"
BYTES_STREAM="${BYTES_STREAM:-50331648}"
SPAN="${SPAN:-1048576}"
OUT="${OUT:-$HERE/results/multi_$(date +%H%M%S).csv}"
mkdir -p "$(dirname "$OUT")"
RC="$(make_curlrc)"; trap 'rm -f "$RC"' EXIT
URL="$BASE$ENC_PATH"
echo "url=$URL stream=${BYTES_STREAM}B rounds=$ROUNDS conc=$CONC out=$OUT"
echo "conc,round,bytes,wall_ms,mbps,req_min_ms,req_p50_ms,req_p95_ms,req_max_ms,fails" > "$OUT"

for c in $CONC; do
  for r in $(seq 1 "$ROUNDS"); do
    tmp="$(mktemp -d)"
    t0=$(date +%s%N)
    for i in $(seq 0 $((c-1))); do
      off=$(( i * SPAN + (r-1) * BYTES_STREAM ))
      end=$(( off + BYTES_STREAM - 1 ))
      curl "${CURL_COMMON[@]}" -K "$RC" -r "$off-$end" -o /dev/null \
        -w '%{http_code} %{size_download} %{time_total}\n' "$URL" > "$tmp/$i.out" 2>"$tmp/$i.err" &
    done
    wait
    t1=$(date +%s%N)
    wall_ms=$(( (t1 - t0) / 1000000 ))
    python3 - "$tmp" "$c" "$r" "$wall_ms" "$OUT" <<'PY'
import sys, glob, os
tmp, c, r, wall_ms, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
bytes_ = 0; fails = 0; lat = []
for f in sorted(glob.glob(os.path.join(tmp, '*.out'))):
    line = open(f).read().strip()
    if not line: fails += 1; continue
    parts = line.split()
    if len(parts) < 3: fails += 1; continue
    code, n, t = parts[0], int(parts[1]), float(parts[2])
    if code not in ('200','206'): fails += 1
    if n <= 0: fails += 1
    bytes_ += n; lat.append(t*1000)
lat.sort()
def q(p):
    return lat[min(len(lat)-1, int(round(p*(len(lat)-1))))] if lat else 0.0
mbps = bytes_/1048576/(wall_ms/1000)
row = f"{c},{r},{bytes_},{wall_ms},{mbps:.2f},{lat[0]:.1f},{q(.50):.1f},{q(.95):.1f},{lat[-1]:.1f},{fails}"
open(out,'a').write(row+"\n")
print(f"c={c:<3d} r={r} {bytes_/1048576:7.1f}MiB {wall_ms:6d}ms {mbps:7.2f}MB/s  p50={q(.5):7.1f} p95={q(.95):7.1f} max={lat[-1]:7.1f} fails={fails}", flush=True)
PY
    rm -rf "$tmp"
  done
done

echo "--- 汇总（按并发；第 1 轮为预热，单列） ---"
python3 - "$OUT" <<'PY'
import csv,sys,statistics,collections
rows=list(csv.DictReader(open(sys.argv[1])))
by=collections.OrderedDict()
for x in rows: by.setdefault(int(x['conc']),[]).append(x)
print(f"{'并发':>4} {'吞吐均值':>9} {'最小':>8} {'最大':>8} {'抖动(ms)':>9} {'p95均值':>9} {'最大单请求(ms)':>13} {'失败':>4} {'预热轮':>7}")
for c,xs in by.items():
    warm=[x for x in xs if int(x['round'])>1] or xs
    tp=[float(x['mbps']) for x in warm]; wall=[int(x['wall_ms']) for x in warm]
    p95=[float(x['req_p95_ms']) for x in warm]; mx=max(float(x['req_max_ms']) for x in warm)
    fails=sum(int(x['fails']) for x in xs)
    print(f"{c:>4} {statistics.mean(tp):>9.2f} {min(tp):>8.2f} {max(tp):>8.2f} {max(wall)-min(wall):>9} {statistics.mean(p95):>9.1f} {mx:>13.1f} {fails:>4} {float(xs[0]['mbps']):>7.2f}")
PY
