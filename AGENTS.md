# mediagate — 项目工作空间约定

> Android 多协议媒体播放器（本地 / WebDAV / SFTP / FTP），小米澎湃 OS，自用 sideload。
> **计划权威：`docs/plan.md`**。

## 1. 硬约束与红线

- **记忆数据绝不入库 git**：`.dsh-meow/` 已在 .gitignore；提交前双查 `git check-ignore -v .dsh-meow/memory.db` 与 `git diff --cached --name-only | grep dsh-meow`。
- **凭据不进 git**：账号密码只存工作区记忆库与 App 连接记录（Keystore 加密）；`keystore/`、`keystore.properties`、`*.jks/keystore`、`local.properties` 均已忽略。
- **环境按项目隔离**：构建/命令前先 `source scripts/env.sh`；JDK 21 / Gradle 9.8 / Android SDK / NDK 都在项目私有 `.toolchain/`。
- **仓库公开**：任何真实内网地址、公网域名/IP、账号、端口一律用示例值；发版走 `scripts/publish-update.sh`。
- 发版纪律：小改动攒着，重大错误修好即发；发版后冻结源码、核对 tag 指向 release commit。

## 2. 方案要点

- minSdk 33 / targetSdk 36 / compileSdk 37.2；界面简体中文单语。
- **视频播放：LibVLC 3.7.7 内核**（2026-11-12 用户要求「将视频播放引擎更改为 libvlc，并设置为硬解模式」）；媒体源经本机回环 HTTP 代理复用同一 `StorageBackend`（WebDAV/SFTP/FTP/本地都走它），代理支持 Range 拖拽。
- **硬解**：`Media.setHWDecoderEnabled(true, true)`（Android 上落成 `:codec=mediacodec_ndk,all`）；**Media3 只作内部兜底**（独立进程 `:vlcprobe` 探针失败/构造异常时回落，不进入切换入口），音频仍用 Media3 ExoPlayer。
- **LibVLC 网络缓存按连接地址类型**：局域网 1500 ms、公网 4000 ms、本地/未知 1000 ms（`VlcCachePolicy`，2026-11-12 用户要求）。
- 数据层：统一 `StorageBackend` → `RandomAccessSource`；Media3 `BackendDataSource` 实现远端拖拽 seek；.ts 索引缓存。
- 字幕已整条移除；FFmpeg 只做抽帧兜底、时间戳重建。
- 后台播放：MediaSessionService + 前台服务。
- **连接模型（2026-11-01 用户要求）**：一条连接只能一个地址、一种网络类型（公网或局域网二选一）；不再先内网后外网自动切换；卡片上的复制动作统一为全量复制连接。

## 3. 目录

    app/ core/{common,model,database,crypto,network,download} data/{storage-api,storage-local,storage-webdav,storage-sftp,storage-ftp}
    media/{engine,playback,thumbnail,tsext,ffmpeg}
    feature/{home,browser,viewer-image,player-video,player-audio,connections,settings,update}
    docs/ scripts/

## 4. 常用命令

    source scripts/env.sh
    ./gradlew :app:assembleDebug
    ./gradlew :app:testDebugUnitTest

## 5. 当前进度

- 正式版 0.1.83 / versionCode 84；**工作区已改为 LibVLC 视频内核**（候选 0.1.91，未发布）。
- 2026-11 完成连接模型简化：单地址/单网络类型、全量复制连接（复制后打开副本编辑器）。
- 2026-11-12 视频内核改为 LibVLC 3.7.7 + 硬解 + LAN/WAN 缓存；`:media:proxy` 回环代理恢复，`:vlcprobe` 独立进程探针把关，失败自动回落 Media3。
- 本机无安卓设备，真机验收靠用户。
- 下一步：真机验证单地址连接、复制连接、公网/局域网目录与播放；其它加载/设置/5 缓存方案待拍板。
