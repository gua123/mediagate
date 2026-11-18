# HTTP/WebDAV 多连接吞吐测试

2026-11-18。验证「HTTP/WebDAV 能否用多连接叠加带宽」，并给真机验证提供数字。
所有测试只读远端文件，不改产品代码。

## 被测环境

| 项 | 值 |
|---|---|
| 服务端 | Apache httpd，明文 http + Basic，`Accept-Ranges: bytes`，206 正常 |
| 客户端 | 30 核 Linux 容器，`eth0` 1000 Mbps 全双工，与 NAS 同网段直连 |
| 凭据 | `../../.toolchain/real-server.properties`（只读，脚本不打印密码） |

> ⚠️ 脚本里写死了真实内网路径（`/home/...`）。提交进公开仓库前要换成占位值。

## 脚本

| 文件 | 作用 |
|---|---|
| `lib.sh` | 读凭据、生成 600 权限的 curl 配置（密码不进 ps/命令行） |
| `bench_multi.sh` | 并发 1/2/4/8/16/32 × 多轮 Range 吞吐，输出 CSV（吞吐、墙钟、延迟分布、失败） |
| `bench_cold_multi.sh` | 4 个 GB 级文件随机高位 offset 首次读，判断磁盘/页缓存影响 |
| `bench_small.sh` | 目录 PROPFIND、256KiB 缩略图级 Range：顺序 / keep-alive / 32 路并发 |
| `bench_under_load.sh` | N 条后台满载 + 1 条「每块一个请求」的播放器读，看尾延迟 |
| `bench_stream_under_load.sh` | **真实播放形态**：一条持续 Range GET，在 16/32 条满载竞争下测 1MiB 到达间隔 |
| `run_control.sh` + `control_stack.js` | 对照实验：本地每连接限速的 HTTP 服务，验证协议层能否线性叠加 |
| `../docker/SftpBaseline.java` `SftpChunk.java` `SftpMulti2.java` `SftpJitter2.java` | SFTP 对照（同文件、同机器、同期） |

## 跑法

    cd tests/http
    ./bench_multi.sh                                   # 默认内网 WebDAV + 246MiB 文件
    ./bench_multi.sh http://HOST:PORT '/path%2Bfile'   # 指定目标
    CONC="1 2 4 8" ROUNDS=5 BYTES_STREAM=33554432 ./bench_multi.sh ...

    cd tests/docker
    ./run-local.sh                                     # 原有 SFTP 多连接
    javac -cp jsch-2.28.7.jar SftpMulti2.java && \
      CREDS=../../.toolchain/real-server.properties java -cp jsch-2.28.7.jar:. SftpMulti2

结果 CSV 落在 `tests/http/results/`。

## 关键实测（详见 REPORT.md）

- 1GbE 链路，HTTP 单连接 58–105 MB/s（取决于 NAS 页缓存），4 连接 101 MB/s，32 连接 107 MB/s（线速 94%）
- 拐点 **4 条**（达上限 95%），8 条 97%，再多只增抖动
- SFTP 对照：单通道 48–61 MB/s，10 通道 85.6 MB/s，200 通道 90 MB/s
- 播放长连接不会被饿死；短请求（1MiB/次）在满载下尾延迟 1–3.9 秒
