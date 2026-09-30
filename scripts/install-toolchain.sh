#!/usr/bin/env bash
# 安装 mediagate 的项目隔离工具链：
#   项目私有 JDK21   -> <proj>/.toolchain/jdk-21
#   公共只读 SDK     -> /opt/android-sdk （按 API 版本分目录，跨项目共享不冲突）
#   项目私有用户目录  -> .toolchain/{gradle-home,android-user}
# 说明：JDK 优先走清华 Adoptium 镜像（国内快），失败再回退官方 API。
set -euo pipefail
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$PROJ/.toolchain"
SDK="/opt/android-sdk"
CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
JDK_MIRRORS=(
  "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"
  "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
)

mkdir -p "$TC" "$SDK/cmdline-tools"

if [ ! -x "$TC/jdk-21/bin/java" ]; then
  echo "[1/4] 下载 JDK 21 ..."
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

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "[2/4] 下载 Android cmdline-tools ..."
  curl -fL --retry 3 --connect-timeout 15 --max-time 900 -o /tmp/clt.zip "$CLT_URL"
  rm -rf /tmp/clt-x && mkdir -p /tmp/clt-x
  unzip -q /tmp/clt.zip -d /tmp/clt-x
  rm -rf "$SDK/cmdline-tools/latest"
  mv /tmp/clt-x/cmdline-tools "$SDK/cmdline-tools/latest"
  rm -f /tmp/clt.zip
fi

export JAVA_HOME="$TC/jdk-21"
export ANDROID_HOME="$SDK"
export ANDROID_USER_HOME="$TC/android-user"
mkdir -p "$ANDROID_USER_HOME"
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"

echo "[3/4] 接受 SDK 许可 ..."
yes | "$SDKM" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true

echo "[4/4] 安装 platform-tools / platforms;android-36 / 最新 build-tools ..."
"$SDKM" --sdk_root="$SDK" --install "platform-tools" "platforms;android-36"
# 注意：sdkmanager --list 里显示为 build-tools/36.0.0，安装参数用 build-tools;36.0.0
BT="$("$SDKM" --sdk_root="$SDK" --list 2>/dev/null | grep -o 'build-tools/[0-9.]*' | sed 's#.*/##' | sort -V | tail -1 || true)"
if [ -n "${BT:-}" ]; then "$SDKM" --sdk_root="$SDK" --install "build-tools;$BT"; fi
"$SDKM" --sdk_root="$SDK" --list_installed
echo "TOOLCHAIN_OK"
