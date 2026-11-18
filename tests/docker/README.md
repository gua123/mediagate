# mediagate SFTP 测试环境（Docker）

隔离在容器里，不往宿主机装任何东西。用于验证 SFTP 吞吐、多连接、通道上限、读取时序。

## 用法

    cd tests/docker
    ./run.sh              # 默认 MultiConn：16 连接 x 10 通道吞吐
    ./run.sh Jitter       # 内网+公网 20 连接，多轮采样看抖动
    ./run.sh SftpProbe    # stat / 当场连续读 4MB / get 后延迟 1 秒再读
    ./run.sh ChanTest     # 单 SSH 连接能开多少条 SFTP 通道

凭据来自宿主机 ../../.toolchain/real-server.properties（gitignore），以**只读**方式挂进容器。

## 实测基线（2026-11-18）

| 场景 | 结果 |
|---|---|
| 单连接通道上限 | 10 条（第 11 条 channel is not opened，OpenSSH MaxSessions 默认值） |
| 16 连接 x 10 通道 | 47 MB/s |
| 20 连接 x 10 通道（内网） | 49-51 MB/s，抖动 61ms，零失败 |
| 单连接（内网） | 2.2 MB/s |
| 建连开销 | 20 条串行 18 秒（需并行建连） |

## 已知限制

- 容器出站到公网被 NAT 限制：nas.example.com:2222 在容器内建连失败（--network host 也一样）；内网 192.168.1.10:2222 正常。公网请用宿主机直连验证。
- Docker Hub 直连拉基础镜像会超时（auth.docker.io），Dockerfile 已改用 docker.m.daocloud.io 镜像源。
