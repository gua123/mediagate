#!/usr/bin/env bash
# 交叉编译 FFmpeg（Android arm64-v8a），供 **自研播放引擎** 使用。
#
# 为什么自己编：ffmpeg-kit 的 AAR 只给 .so、**不给头文件**（没有 prefab），
# 而 FFmpeg 的 C API 必须"头文件与 .so 同版本"（结构体布局会变），所以自研引擎要自己出一套。
#
# 约定（项目里长任务的规矩）：**幂等可重跑**、日志落 .toolchain、产物不进 git。
#   source scripts/env.sh && bash scripts/build-ffmpeg-android.sh
#
# 产物：.toolchain/ffmpeg-android/arm64-v8a/{include,lib}
# 注意：与 :media:ffmpeg 的 ffmpeg-kit（AAR 里的 libav*.so）**同名**，
#       等引擎接管播放后要一并去掉 ffmpeg-kit，避免打包冲突。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$ROOT/.toolchain/android-sdk/ndk/30.0.16248370}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
PREFIX="$ROOT/.toolchain/ffmpeg-android/arm64-v8a"
SRC_DIR="$ROOT/.toolchain/ffmpeg-src"
LOG="$ROOT/.toolchain/ffmpeg-build.log"
API=33
FFMPEG_VERSION="${FFMPEG_VERSION:-7.1}"

PROXY="$(grep -m1 http_proxy /etc/profile 2>/dev/null | sed 's/.*="\(.*\)"/\1/' || true)"

mkdir -p "$ROOT/.toolchain"
echo "== FFmpeg $FFMPEG_VERSION → $PREFIX（API $API, NDK $(basename "$NDK")）" | tee -a "$LOG"

# 1) 取源码（已存在就跳过）
if [ ! -d "$SRC_DIR" ]; then
  echo "-- 下载 FFmpeg 源码" | tee -a "$LOG"
  TARBALL="$ROOT/.toolchain/ffmpeg-$FFMPEG_VERSION.tar.xz"
  MIRROR="$ROOT/.toolchain/ffmpeg-$FFMPEG_VERSION-mirror.tar.gz"
  mkdir -p "$SRC_DIR"
  # 判据：xz 包完整约 10 MB 以上；不足就是上次下到一半（本机直连很慢，走代理明显快）
  if [ ! -s "$TARBALL" ] || [ "$(stat -c %s "$TARBALL")" -lt 9000000 ]; then
    echo "-- 下载 FFmpeg 源码（优先走系统代理 + 断点续传）" | tee -a "$LOG"
    curl -fL -C - --connect-timeout 20 -m 900 ${PROXY:+-x "$PROXY"} \
      -o "$TARBALL" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" 2>>"$LOG" \
      && echo "-- ffmpeg.org 下载完成" | tee -a "$LOG" \
      || {
        echo "-- ffmpeg.org 不通，改用 GitHub 源码镜像" | tee -a "$LOG"
        rm -f "$MIRROR"
        curl -fL --connect-timeout 20 -m 900 ${PROXY:+-x "$PROXY"} \
          -o "$MIRROR" "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n$FFMPEG_VERSION.tar.gz" 2>>"$LOG" \
          || { echo "源码下载失败，见 $LOG"; exit 1; }
        tar -xf "$MIRROR" -C "$SRC_DIR" --strip-components=1
        touch "$SRC_DIR/.from-mirror"
      }
  fi
  if [ ! -f "$SRC_DIR/.from-mirror" ] && [ ! -f "$SRC_DIR/configure" ]; then
    echo "-- 解包 xz 源码" | tee -a "$LOG"
    tar -xf "$TARBALL" -C "$SRC_DIR" --strip-components=1
  fi
fi

# 2) configure（要 MediCodec 硬解 + 全量软解/解复用，尽量覆盖所有格式）
cd "$SRC_DIR"
if [ ! -f "config.h" ] || [ "${FORCE_CONFIGURE:-0}" = "1" ]; then
  echo "-- configure" | tee -a "$LOG"
  make distclean >>"$LOG" 2>&1 || true
  ./configure \
    --prefix="$PREFIX" \
    --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
    --sysroot="$TOOLCHAIN/sysroot" \
    --cc="$TOOLCHAIN/bin/aarch64-linux-android$API-clang" \
    --cxx="$TOOLCHAIN/bin/aarch64-linux-android$API-clang++" \
    --ar="$TOOLCHAIN/bin/llvm-ar" --nm="$TOOLCHAIN/bin/llvm-nm" \
    --ranlib="$TOOLCHAIN/bin/llvm-ranlib" --strip="$TOOLCHAIN/bin/llvm-strip" \
    --enable-shared --disable-static --enable-pic \
    --build-suffix=-mg \
    --disable-programs --disable-doc --disable-avdevice --disable-postproc --disable-avfilter \
    --enable-jni --enable-mediacodec \
    --enable-decoder=h264_mediacodec,hevc_mediacodec,mpeg4_mediacodec,vp8_mediacodec,vp9_mediacodec,av1_mediacodec \
    --enable-encoder=aac \
    >>"$LOG" 2>&1 || { echo "configure 失败，见 $LOG"; tail -30 "$LOG"; exit 1; }
fi

# 3) 编译（-j 用满核）
JOBS="$(nproc 2>/dev/null || echo 4)"
echo "-- make -j$JOBS" | tee -a "$LOG"
make -j"$JOBS" >>"$LOG" 2>&1 || { echo "make 失败，见 $LOG"; tail -30 "$LOG"; exit 1; }
make install >>"$LOG" 2>&1 || { echo "install 失败，见 $LOG"; tail -30 "$LOG"; exit 1; }

# 4) 给 Gradle 用的 jniLibs 目录（AGP 要求**按 ABI 命名的子目录**，否则报 "is not an ABI"）
JNI_DIR="$ROOT/.toolchain/ffmpeg-jni/arm64-v8a"
mkdir -p "$JNI_DIR"
cp -f "$PREFIX"/lib/lib*-mg.so "$JNI_DIR"/
echo "-- jniLibs 目录已同步：$JNI_DIR" | tee -a "$LOG"

echo "-- 完成" | tee -a "$LOG"
ls -1 "$PREFIX/lib" | head -20 | tee -a "$LOG"
ls -1 "$PREFIX/include" | tee -a "$LOG"