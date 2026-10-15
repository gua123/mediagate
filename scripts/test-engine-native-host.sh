#!/usr/bin/env bash
# 自研引擎"纯逻辑"的开发机单测：不需要设备，也不需要 FFmpeg。
#
# 覆盖两份源文件（都在 src/test/cpp 下，各自一个可执行文件，全跑一遍）：
#   · av_sync_host_test.cpp       —— pts→毫秒、时间轴归一、音画同步判据、音频时钟、PCM 环形缓冲（含真并发用例）
#   · subtitle_text_host_test.cpp —— 内嵌字幕的纯文本路线：格式识别、payload 剥包装、SRT 时间戳与拼块
# native 的其余部分（解复用/解码/AAudio/字幕抽取）只能在真机上验。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CPP_DIR="$ROOT/media/engine-native/src/main/cpp"
TEST_DIR="$ROOT/media/engine-native/src/test/cpp"
OUT_DIR="/tmp/mediagate-native-host-tests"
CXX="g++"

if [ -n "${CXX_OVERRIDE:-}" ]; then
  CXX="$CXX_OVERRIDE"
fi

if ! command -v "$CXX" >/dev/null 2>&1; then
  echo "找不到 C++ 编译器（$CXX）：这一步需要开发机上的 g++ 或 clang++" >&2
  exit 127
fi

mkdir -p "$OUT_DIR"
for source in av_sync_host_test.cpp subtitle_text_host_test.cpp; do
  src="$TEST_DIR/$source"
  if [ ! -f "$src" ]; then
    echo "找不到测试源文件：$src" >&2
    exit 2
  fi
  out="$OUT_DIR/$(basename "$source" .cpp)"
  "$CXX" -std=c++17 -O1 -Wall -Wextra -pthread -I"$CPP_DIR" "$src" -o "$out"
  "$out"
done
