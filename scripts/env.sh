#!/usr/bin/env bash
# mediagate 项目环境（按项目隔离：项目私有 JDK + 项目私有 Gradle/Android 用户目录；Android SDK 公共只读）
# 用法： source scripts/env.sh   —— 之后所有 gradle/adb/sdkmanager 都指向本项目工具链，不影响其它项目与系统
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$PROJ/.toolchain/jdk-21"
export ANDROID_HOME="/opt/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$PROJ/.toolchain/android-user"
export GRADLE_USER_HOME="$PROJ/.toolchain/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
echo "mediagate env: JAVA_HOME=$JAVA_HOME"
echo "               ANDROID_HOME=$ANDROID_HOME  GRADLE_USER_HOME=$GRADLE_USER_HOME"
