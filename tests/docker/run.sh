#!/bin/bash
set -e
cd "$(dirname "$0")"
CLASS="$1"
if [ -z "$CLASS" ]; then CLASS=MultiConn; fi
docker build -q -t mediagate-sftp-test . >/dev/null
docker run --rm \
  -v /root/project/mediagate/.toolchain/real-server.properties:/creds.properties:ro \
  mediagate-sftp-test "$CLASS"
