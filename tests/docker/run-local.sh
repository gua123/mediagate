#!/bin/bash
# 不依赖 Docker 的本地直跑入口（用项目自带 JDK）
# 用法：./run-local.sh [类名]，类名默认 MultiConn
set -e
cd "$(dirname "$0")"
CLASS="$1"
if [ -z "$CLASS" ]; then CLASS=MultiConn; fi
JAR="jsch-2.28.7.jar"
CREDS="../../.toolchain/real-server.properties"
if [ ! -f "$CREDS" ]; then echo "缺少 $CREDS"; exit 1; fi
export CREDS="$(cd ../../.toolchain && pwd)/real-server.properties"
source ../../scripts/env.sh >/dev/null 2>&1
javac -cp "$JAR" *.java
java -cp "$JAR:." "$CLASS"
