#!/usr/bin/env bash
# 安装 mediagate 的项目隔离工具链（全部落在本工作区 .toolchain/ 内，不写系统环境、不动其它项目）：
#   1) 项目私有 JDK 21        -> .toolchain/jdk-21
#   2) 项目私有 Android SDK   -> .toolchain/android-sdk
#      cmdline-tools / platform-tools / platforms;android-37.2 / build-tools;37.0.0 / ndk;30.0.16248370 / cmake;4.1.2
#   3) 项目私有用户目录        -> .toolchain/{gradle-home,android-user}
#   4) Gradle 9.8.0 发行版     -> .toolchain/gradle-9.8.0，并预置 wrapper 缓存（之后 ./gradlew 不再下载发行包）
# 说明：JDK 走清华 Adoptium 镜像；Gradle 走华为镜像（官方源在国内实测只有 ~20 KB/s）。
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$PROJ/.toolchain"
PRIV_SDK="$TC/android-sdk"
CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
JDK_MIRRORS=(
  "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"
  "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
)
GRADLE_VER="9.8.0"
GRADLE_MIRRORS=(
  "https://mirrors.huaweicloud.com/gradle/gradle-$GRADLE_VER-bin.zip"
  "https://mirrors.cloud.tencent.com/gradle/gradle-$GRADLE_VER-bin.zip"
  "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip"
)
NDK_VER="30.0.16248370"
CMAKE_VER="4.1.2"
PLATFORM="platforms;android-37.2"
BUILD_TOOLS="build-tools;37.0.0"

mkdir -p "$TC" "$PRIV_SDK"

# ---------- 1) 项目私有 JDK 21 ----------
if [ ! -x "$TC/jdk-21/bin/java" ]; then
  echo "[1/5] 下载 JDK 21 ..."
  ok=0
  for u in "${JDK_MIRRORS[@]}"; do
    echo "      尝试: $u"
    if curl -fL --retry 2 --connect-timeout 15 --max-time 1200 -o /tmp/jdk21.tar.gz "$u" && tar -tzf /tmp/jdk21.tar.gz >/dev/null 2>&1; then ok=1; break; fi
    echo "      失败，换下一个源"; rm -f /tmp/jdk21.tar.gz
  done
  [ "$ok" = "1" ] || { echo "JDK 下载失败"; exit 1; }
  mkdir -p "$TC/jdk-21"
  tar -xzf /tmp/jdk21.tar.gz -C "$TC/jdk-21" --strip-components=1
  rm -f /tmp/jdk21.tar.gz
fi
"$TC/jdk-21/bin/java" -version 2>&1 | head -2

export JAVA_HOME="$TC/jdk-21"
export ANDROID_HOME="$PRIV_SDK"
export ANDROID_SDK_ROOT="$PRIV_SDK"
export ANDROID_USER_HOME="$TC/android-user"
export GRADLE_USER_HOME="$TC/gradle-home"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"

# ---------- 2) 项目私有 Android SDK ----------
SDKM="$PRIV_SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKM" ]; then
  echo "[2/5] 下载 Android cmdline-tools ..."
  curl -fL --retry 3 --connect-timeout 15 --max-time 900 -o /tmp/clt.zip "$CLT_URL"
  rm -rf /tmp/clt-x && mkdir -p /tmp/clt-x
  unzip -q /tmp/clt.zip -d /tmp/clt-x
  rm -rf "$PRIV_SDK/cmdline-tools/latest"
  mkdir -p "$PRIV_SDK/cmdline-tools"
  mv /tmp/clt-x/cmdline-tools "$PRIV_SDK/cmdline-tools/latest"
  rm -f /tmp/clt.zip
fi

echo "[3/5] 接受许可并安装 platform / build-tools / NDK / CMake ..."
yes | "$SDKM" --sdk_root="$PRIV_SDK" --licenses >/dev/null 2>&1 || true
"$SDKM" --sdk_root="$PRIV_SDK" --install "platform-tools" "$PLATFORM" "$BUILD_TOOLS" "ndk;$NDK_VER" "cmake;$CMAKE_VER"

# ---------- 3) Gradle 发行版本体 ----------
if [ ! -x "$TC/gradle-$GRADLE_VER/bin/gradle" ]; then
  echo "[4/5] 下载 Gradle $GRADLE_VER ..."
  ok=0
  for u in "${GRADLE_MIRRORS[@]}"; do
    echo "      尝试: $u"
    if curl -fL --retry 2 --connect-timeout 15 --max-time 1800 -o /tmp/gradle.zip "$u" && unzip -tq /tmp/gradle.zip >/dev/null 2>&1; then ok=1; break; fi
    echo "      失败，换下一个源"; rm -f /tmp/gradle.zip
  done
  [ "$ok" = "1" ] || { echo "Gradle 下载失败"; exit 1; }
  rm -rf "$TC/gradle-$GRADLE_VER"
  unzip -q /tmp/gradle.zip -d "$TC"
  rm -f /tmp/gradle.zip
fi

# ---------- 4) 预置 wrapper 缓存（省掉 ./gradlew 的发行包下载） ----------
echo "[5/5] 预置 gradle wrapper 缓存 ..."
WURL="https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip"
cat > /tmp/MgHash.java <<'JAVA'
import java.math.BigInteger;
import java.security.MessageDigest;
public class MgHash {
  public static void main(String[] a) throws Exception {
    byte[] d = MessageDigest.getInstance("MD5").digest(a[0].getBytes());
    System.out.println(new BigInteger(1, d).toString(36));
  }
}
JAVA
HASH="$("$JAVA_HOME/bin/java" /tmp/MgHash.java "$WURL")"
DEST="$GRADLE_USER_HOME/wrapper/dists/gradle-$GRADLE_VER-bin/$HASH"
if [ ! -f "$DEST/gradle-$GRADLE_VER-bin.zip.ok" ]; then
  rm -rf "$DEST"; mkdir -p "$DEST"
  cp -a "$TC/gradle-$GRADLE_VER" "$DEST/gradle-$GRADLE_VER"
  ( cd "$TC/gradle-$GRADLE_VER" && zip -qr "$DEST/gradle-$GRADLE_VER-bin.zip" . ) 2>/dev/null || true
  touch "$DEST/gradle-$GRADLE_VER-bin.zip.ok"
fi
rm -f /tmp/MgHash.java

"$SDKM" --sdk_root="$PRIV_SDK" --list_installed
echo
echo "TOOLCHAIN_OK"
echo "私有 JDK    : $TC/jdk-21"
echo "私有 SDK    : $PRIV_SDK"
echo "私有 Gradle : $TC/gradle-$GRADLE_VER"
