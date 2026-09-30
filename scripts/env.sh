#!/usr/bin/env bash
# mediagate 项目环境（按项目隔离：项目私有 JDK + 项目私有 Gradle/Android 用户目录；Android SDK 公共只读）
# 用法： source scripts/env.sh   —— 之后所有 gradle/adb/sdkmanager 都指向本项目工具链，不影响其它项目与系统
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$PROJ/.toolchain/jdk-21"
# Android SDK 也按项目私有（含 platform 37.2 / build-tools 37 / platform-tools / NDK / CMake），
# 公共只读 SDK 仅作为 sdkmanager 的来源，不再被本项目构建引用 —— 与其它项目彻底互不影响。
export ANDROID_HOME="$PROJ/.toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$PROJ/.toolchain/android-user"
export GRADLE_USER_HOME="$PROJ/.toolchain/gradle-home"

# 项目私有 NDK / CMake（native：whisper.cpp；不装进公共 SDK，也不动系统环境）
PRIVATE_SDK="$PROJ/.toolchain/android-sdk"
if [ -d "$PRIVATE_SDK/ndk" ]; then
  export ANDROID_NDK_HOME="$(ls -d "$PRIVATE_SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
  export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
fi
if [ -d "$PRIVATE_SDK/cmake" ]; then
  CMAKE_BIN_DIR="$(ls -d "$PRIVATE_SDK"/cmake/*/bin 2>/dev/null | sort -V | tail -1)"
  [ -n "$CMAKE_BIN_DIR" ] && export PATH="$CMAKE_BIN_DIR:$PATH"
fi

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
echo "mediagate env: JAVA_HOME=$JAVA_HOME"
echo "               ANDROID_HOME=$ANDROID_HOME  GRADLE_USER_HOME=$GRADLE_USER_HOME"
[ -n "${ANDROID_NDK_HOME:-}" ] && echo "               ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
