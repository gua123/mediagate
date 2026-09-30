#!/usr/bin/env bash
# 用项目私有 NDK 编译 whisper.cpp（Android arm64-v8a），产出给 media/asr 用的 native 库。
# 用法： bash scripts/build-whisper-android.sh [版本tag，默认 v1.9.4]
# 产物： media/asr/src/main/jniLibs/arm64-v8a/*.so（不进 git，用本脚本重建）
# 环境： 源码放 .toolchain/third_party/（随 .toolchain/ 忽略）；NDK/CMake 用项目私有
#        .toolchain/android-sdk（不碰 /opt/android-sdk，也不装系统环境）。
# 网络： GitHub 直连在国内很慢，默认走镜像（GH_MIRROR= 可覆盖，设为空串走直连）。
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:-v1.9.4}"
GH_MIRROR="${GH_MIRROR-https://gh-proxy.com/}"

PRIVATE_SDK="$PROJ/.toolchain/android-sdk"
NDK_DIR="$(ls -d "$PRIVATE_SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "${NDK_DIR:-}" ]; then
  echo "未找到项目私有 NDK。请先执行："
  echo "  JAVA_HOME=$PROJ/.toolchain/jdk-21 /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \\"
  echo "    --sdk_root=$PRIVATE_SDK --install ndk;30.0.16248370 cmake;4.1.2"
  exit 2
fi
CMAKE_BIN="$(ls -d "$PRIVATE_SDK"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1 || true)"
[ -n "${CMAKE_BIN:-}" ] || CMAKE_BIN="$(command -v cmake)"
echo "NDK   : $NDK_DIR"
echo "CMake : $CMAKE_BIN"

TP="$PROJ/.toolchain/third_party"
SRC="$TP/whisper.cpp"
mkdir -p "$TP"

if [ ! -f "$SRC/CMakeLists.txt" ]; then
  TARBALL="$TP/src-$TAG.tar.gz"
  URL="https://github.com/ggml-org/whisper.cpp/archive/refs/tags/$TAG.tar.gz"
  ok=0
  for u in "${GH_MIRROR:+$GH_MIRROR$URL}" "$URL"; do
    [ -n "$u" ] || continue
    echo "下载源码: $u"
    if curl -sL --max-time 600 -o "$TARBALL" "$u" && [ "$(stat -c%s "$TARBALL" 2>/dev/null || echo 0)" -gt 1000000 ]; then ok=1; break; fi
  done
  [ "$ok" = "1" ] || { echo "源码下载失败（可设 GH_MIRROR 换镜像）"; exit 3; }
  rm -rf "$SRC" "$TP/whisper.cpp-${TAG#v}"
  tar -xzf "$TARBALL" -C "$TP"
  mv "$TP/whisper.cpp-${TAG#v}" "$SRC"
  rm -f "$TARBALL"
fi

BUILD="$SRC/build-android-arm64"
rm -rf "$BUILD"
ARGS=(
  -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=android-33
  -DCMAKE_BUILD_TYPE=Release
  -DBUILD_SHARED_LIBS=ON
  -DWHISPER_BUILD_TESTS=OFF
  -DWHISPER_BUILD_EXAMPLES=OFF
  -DWHISPER_BUILD_SERVER=OFF
  -DGGML_OPENMP=OFF
)
"$CMAKE_BIN" -S "$SRC" -B "$BUILD" "${ARGS[@]}"
"$CMAKE_BIN" --build "$BUILD" -j"$(nproc)" --target whisper

OUT="$PROJ/media/asr/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
find "$BUILD" -name "*.so" -exec cp -v {} "$OUT/" \;
echo
echo "产物："
ls -la "$OUT"
echo
bash "$PROJ/scripts/check-page-align.sh" "$OUT"
