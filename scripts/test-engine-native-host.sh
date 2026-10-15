#!/usr/bin/env bash
# 自研引擎"纯逻辑"的开发机单测：不需要设备，也不需要 FFmpeg。
#
# 覆盖 av_sync.h：pts→毫秒 换算、时间轴归一、音画同步判据、音频时钟、PCM 环形缓冲
#（含真并发用例）。native 的其余部分（解复用/解码/AAudio）只能在真机上验。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP_DIR="$ROOT/media/engine-native/src/main/cpp"
TEST_SRC="$ROOT/media/engine-native/src/test/cpp/av_sync_host_test.cpp"
OUT="${TMPDIR:-/tmp}/mediagate-av-sync-host-test"
CXX="${CXX:-g++}"

if [ ! -f "$TEST_SRC" ]; then
  echo "找不到测试源文件：$TEST_SRC" >&2
  exit 2
fi

if ! command -v "$CXX" >/dev/null 2>&1; then
  echo "找不到 C++ 编译器（$CXX）：这一步需要开发机上的 g++ 或 clang++" >&2
  exit 127
fi

"$CXX" -std=c++17 -O1 -Wall -Wextra -pthread -I"$CPP_DIR" "$TEST_SRC" -o "$OUT"
"$OUT"
