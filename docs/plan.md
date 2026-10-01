# mediagate · 实施方案（Android 多协议媒体播放器）

> 版本 v4 · 2026-09-29 · 状态：**决议已全部落定，待用户确认开工**
> v3 → v4 变更：新增需求 **R19 批量字幕生成任务中心**（用户 2026-09-29 补充：批量选文件夹下所有视频、独立页面生成、进度条、后台生成、不影响看视频）。
> v2 → v3 变更：用户第二轮答复 6 点（ASR 默认 small、模型 App 内下载、字幕单文件、软解可选但默认硬解、FTP 自测、密码不入 git），并新增需求 **R18 后台播放**。
> v1 → v2 变更：按用户 2026-09-29 的 9 条拍板意见更新（多播放内核 + 硬/软解、FFmpeg 简版、SAF 与全盘访问都做、支持 m3u8、画中画与字幕、只保澎湃系统、简体中文、一次做完），并新增**本地小模型 CPU 音转字幕**、**开发环境按项目隔离**、**用户提供的局域网/公网测试连接**三项。
> 原件位置：/root/project/project-plan/mediagate.md；确认后建 /root/project/mediagate 并交接（记忆 + 任务 + docs/plan.md 副本），本工作区原件冻结为只读存档。

---

## 0. 需求与拍板结果（v2 九条拍板 + v3 六条决议）

**用户原始需求（原话）**

> 我需要一个安卓手机 app，系统为小米澎湃系统，是一个视频、歌曲、图片的播放器，协议方面需要支持本地、webdev、sftp、ftp，文件格式为视频、歌曲、图片的通用视频格式，并且需要额外支持 .ts 视频文件格式，同时需要支持视频进度条拖拽。需要展示文件的缩略图。软件本身无需登录、权限功能。需要支持局域网和公网域名连接，根据当前设备的网络情况连接不同的连接，可以记录多个连接，并且可以测试此项是否连通。
>
> 补充：**「我需要你来自研一套」**

**用户 2026-09-29 的拍板**

| # | 问题 | 用户决定 | 落地 |
| --- | --- | --- | --- |
| 1 | 项目名 | 可以 | 项目名 **mediagate** |
| 2 | 播放内核 | 可以有多个内核，支持硬解与软解，**默认硬解** | Media3 + LibVLC 双引擎 + 解码模式开关（R9/R10） |
| 3 | FFmpeg | 先引简版，不够用再换全版 | FFmpeg 精简包（R11） |
| 4 | 本地访问 | 都做 | SAF 与「所有文件访问」双模式（R12） |
| 5 | m3u8 | 一起支持 | HLS 支持（R3） |
| 6 | 画中画 / 字幕 | 要；**并新增：本地小模型 CPU 音转字幕，默认与视频同名同文件夹** | R13 画中画 + R14 字幕与 ASR |
| 7 | 最低系统 | 只保留澎湃系统 | minSdk 33（Android 13）/ targetSdk 36（R15） |
| 8 | 界面语言 | 简体中文 | 单语 zh-CN（R16） |
| 9 | 交付节奏 | 先做完 | 一次做全再交付（内部按里程碑自测） |

**用户第二轮答复（2026-09-29，v3 决议）**

| # | 问题 | 用户决定 | 落地 |
| --- | --- | --- | --- |
| 1 | ASR 默认模型 | 准（要准） | 默认 **small**（≈466 MB 量化版），可一键切 base |
| 2 | 模型获取 | App 内下载 | App 内下载为默认方式（进度 / 断点 / 取消）；从连接导入作备用 |
| 3 | 字幕输出 | 字幕全要，但只单个字幕文件 | 外挂 + 样式 + 延迟 + 音转字幕全做，但**一次只产出一个字幕文件**：默认 SRT，需要时另存 VTT 或纯文本；单字幕轨道，不做双字幕 |
| 4 | 软解 | 支持软解但默认硬解 | 保持 AUTO（硬解优先），FORCE_SW 手动可切 |
| 5 | FTP 测试 | 自测试 | 本机 pyftpdlib 自建 FTP / FTPS 测试服务 |
| 6 | 密码 | 不进入 git | 密码只存本机记忆库 + App 连接记录（Keystore 加密），任何会提交 git 的文件都不含密码 |

**用户补充需求（原话）**

> 补充需要支持后台播放

→ 落为 **R18**，详见 4.11。

> 用模型生成字幕时可以批量选择，包括文件夹下的所有视频文件，可以单独在一个地方生成字幕，显示进度条以及支持后台生成，不影响正常看视频。

→ 落为 **R19**，详见 4.12（批量字幕生成任务中心）。

**用户追加的扩展需求（原话）**

> 能否在本机搭建一个安卓开发环境但开发环境能否按照项目搭建，不影响其他项目环境，例如本次需要安装 Java，我的其他项目可能跟本项目的 java 版本不一致，就不好处理了，给一个方案

→ 见第 5 章「开发环境按项目隔离」，作为工程的硬要求。

**用户提供的测试连接（2026-09-29）**

| 协议 | 局域网 | 公网域名 |
| --- | --- | --- |
| SFTP | 192.168.1.10:2222 | dav.example.com:2222 |
| WebDAV | 192.168.1.10:8080 | dav.example.com:8443 |

账号 `demo`。**密码不写入本文件**（方案要随项目进 git）；密码只存本机记忆库与实施时的 App 连接记录（Keystore 加密）。WebDAV 的 8080/8443 是 http 还是 https、认证方式，实施时先探测。

> **2026-10-01 实测结论（M3 落地后直连验证）**
> - 局域网 WebDAV `http://192.168.1.10:8080` **可用**：Apache，**明文 http + Basic 认证**（https 同端口不通）；PROPFIND Depth:0/1 回 207；文件带 `Accept-Ranges: bytes`，Range 请求回 206 且**与全量下载对应偏移逐字节一致**（已实测 bytes=0-99 / 200-299）。
> - 该服务器的 XML 用 `D:`/`lp1:` 前缀绑定 DAV: 命名空间、根列表里集合 href **可能不带尾斜杠**（`/downloads`）、目录不返回 `getcontentlength` —— 正好验证了 WebDAV 后端「按 local name 解析 / 靠 resourcetype 判目录 / 缺属性容忍为 -1」的设计。
> - 局域网 SFTP 2222 端口可连（后端 M5 才做）。**公网侧：dav.example.com 解析到 203.0.113.10，SFTP 2222 可连，但 WebDAV 8443 连接被拒**（http/https 均不通）—— R7/R8 的公网 WebDAV 验收暂时做不了，需检查端口映射。

---

## 1. 需求清单（验收基线）

| 编号 | 需求 | 落点 |
| --- | --- | --- |
| R1 | 视频 / 歌曲 / 图片 三类媒体播放与查看 | feature/player-video、player-audio、viewer-image |
| R2 | 协议：本地、WebDAV、SFTP、FTP | data/storage-* 四后端 |
| R3 | 通用格式 + .ts，并支持 .m3u8 | Media3 抽取器 + TS 强化 + HLS 模块 |
| R4 | 视频进度条可拖拽，远端也要准 | RandomAccessSource + 各协议随机读 + TS 索引 |
| R5 | 缩略图（视频抽帧/图片/音频封面） | media/thumbnail + 两级缓存 |
| R6 | App 本身无登录、无权限体系 | 无账号体系；协议凭据存连接里 |
| R7 | 局域网 + 公网域名，按网络自动选 | 连接内多地址 + 网络监听 + 竞速探测 |
| R8 | 多连接记录 + 连通性测试 | feature/connections |
| R9 | **多播放内核**（Media3 为主，LibVLC 兜底） | media/engine + 引擎切换 |
| R10 | **硬解 / 软解可切换，默认硬解** | media/engine/decode-mode |
| R11 | **FFmpeg 简版**（抽帧兜底、时间戳重建、音频解码给 ASR） | media/ffmpeg |
| R12 | **本地访问双模式**：SAF 与「所有文件访问」 | data/storage-local |
| R13 | **画中画** | feature/player-video/pip |
| R14 | **字幕**：外挂字幕 + 本地小模型 CPU 音转字幕（默认与视频同名同目录） | media/asr + feature/player-video/subtitle |
| R15 | 只保澎湃系统机型 | minSdk 33 / targetSdk 36 |
| R16 | 界面简体中文单语 | 全部 strings 走 zh-CN |
| R17 | **开发环境按项目隔离**，不影响其他项目 | 第 5 章（scripts/env.sh + 项目私有 JDK/Gradle） |
| R18 | **后台播放**：切后台 / 息屏不中断（音频与视频音轨），通知栏与锁屏可控 | media/playback + MediaSessionService + 前台服务 |
| R19 | **批量字幕生成**：批量选文件 / 整个文件夹，独立任务中心页面，逐项进度条，后台生成且不影响看视频 | feature/tasks + media/asr 队列 |

口径沿用 v1：webdev=WebDAV；.ts=MPEG-TS 单文件；无登录=App 内无账号体系（系统权限与协议凭据仍要）；网络切换=连接内 LAN/WAN 多地址自动选 + 多连接聚合。

**非目标**：投屏/DLNA、第三方网盘 OAuth、BT/磁力、服务端转码、云同步、账号体系、广告、应用商店上架（自用 sideload）。

---

## 2. 技术选型与版本基线（2026-09-29 实测 Maven）

| 层 | 选型 | 版本 | 说明 |
| --- | --- | --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose + Material3 | Kotlin 2.4.20 / Compose BOM 2026.09.00 | 声明式 UI |
| 构建 | AGP + Gradle + KSP + Version Catalog | AGP 9.4.1 / Gradle 9.8.0 / KSP 2.3.12（Kotlin 由 AGP 9 内置，不再声明 kotlin.android 插件） | 见第 5 章隔离方案 |
| 系统 | compileSdk 37.2（AGP 9 的 compileSdk + compileSdkMinor）/ targetSdk 36 / minSdk 33（Android 13） | — | 只保澎湃机型；2026 年的 androidx 已强制要求 compileSdk ≥ 37 |
| 播放内核①（默认） | Media3 ExoPlayer | 1.11.1（exoplayer / ui-compose / session / datasource-okhttp / extractor / exoplayer-hls） | 硬解为主，seek 支持完善 |
| 播放内核②（兜底） | LibVLC | 3.7.6（3.x 稳定线） | 可强制软解，覆盖畸形 TS / 冷门编码 |
| 软件音频解码 | 不走 Media3 FFmpeg 扩展（该扩展不发布 Maven 包，需自建 NDK），软解统一交给 LibVLC | — | 少一个 native 依赖，软件解码路径只留一条 |
| FFmpeg 原生库 | **FFmpeg 简版**：dev.ffmpegkit-maintained:ffmpeg-kit-min | **8.1.9**（原 com.arthenica 线已下架；社区分支支持 16 KB 页） | 抽帧兜底、时间戳重建、ASR 音频解码；不够用再换全版 |
| 语音识别 | **whisper.cpp（ggml 量化模型，CPU 多线程）** | **v1.9.4 自建**（NDK r30 / CMake 4.1.2） | Maven 上只有桌面 native，Android 必须自编；带段时间戳，适合生成 SRT；模型不内置 APK |
| 图片 | Coil 3 | 3.6.3 | 自定义 Fetcher 走随机访问源 |
| WebDAV | OkHttp + 自研 PROPFIND/Range | OkHttp 5.5.0 | 约 600 行，可控 |
| SFTP | JSch（mwiede 分支） | com.github.mwiede:jsch 2.28.7 | Android 上成熟 |
| FTP | Apache Commons Net | 3.13.0 | REST 偏移随机读 |
| 数据库 / 偏好 | Room / DataStore | 2.8.5 / 1.2.1 | 连接、索引、任务、进度 |
| DI / 异步 / 序列化 | Hilt / Coroutines / kotlinx-serialization | 实施时锁最新稳定 / 1.11.0 / 1.11.0 | — |
| 加密 | Android Keystore + AES-GCM | — | 凭据落库只存密文 |
| 分页 / 导航 | Paging 3 / navigation-compose | 3.5.1 / 2.10.2 | — |

### 2.1 M0 实测落定（2026-09-30）

工程骨架已在 /root/project/mediagate 落地，**`./gradlew :app:assembleDebug` 构建通过**；下面每一条都是实测结果，不再是预估：

- **Gradle 9.8.0**（wrapper 固定）。官方发行包在本机只有 ~20 KB/s，首次改用华为镜像取回后预置进项目私有 `GRADLE_USER_HOME/wrapper/dists`，此后 `./gradlew` 不再联网下载发行包。
- **AGP 9 内置 Kotlin**：AGP 9 起不允许再声明 `org.jetbrains.kotlin.android`（会直接构建失败），Kotlin 编译由 AGP 自带；Compose 仍用 `org.jetbrains.kotlin.plugin.compose`，KSP 2.3.12 正常跑 Room 处理器（`core:database` 已建 connection/address 两张表验证）。
- **compileSdk 37.2**（AGP 9 的 `compileSdk` + `compileSdkMinor`）：2026-09 的 androidx（navigation 2.10.2 / coil 3.6.3 / room 2.8.5 …）AAR 元数据要求 `compileSdk ≥ 37`，用 36 会被 `checkDebugAarMetadata` 直接拦下。targetSdk 仍 36。
- **FFmpeg 简版 = `dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.9`**：原 `com.arthenica` 线已归档并从 Maven Central 下架；该社区分支同名 API、支持 16 KB 页。
- **whisper.cpp v1.9.4 自建**：Maven 上的 `io.github.givimad:whisper-jni` 只含桌面 native，Android 只能自己编。用项目私有 NDK r30（30.0.16248370）+ CMake 4.1.2 编 `arm64-v8a`，产出 `libwhisper.so / libggml.so / libggml-base.so / libggml-cpu.so`；**四个库 LOAD 段对齐均为 0x4000（16 KB）**，由 `scripts/check-page-align.sh` 校验通过。重新生成：`bash scripts/build-whisper-android.sh`（产物不进 git）。
- **依赖源加速**：`settings.gradle.kts` 阿里云镜像优先、官方源兜底（本机实测快 10~20 倍）。
- **签名**：`keystore/mediagate.jks`（PKCS12 / RSA4096 / 30 年）+ `keystore.properties`，均在 .gitignore 内；`app/build.gradle.kts` 自动读取，文件缺失时 release 退化为未签名，不影响 debug 构建。
- **Android SDK 整体改为项目私有**：见第 5 章（公共 `/opt/android-sdk` 只有 android-36，满足不了 compileSdk 37，又不该去改公共资源）。

---

## 3. 总体架构

**不变量**：所有媒体数据（播放、抽帧、图片解码、ASR 取音）都只经过同一个 `RandomAccessSource`；播放层再往上是 **PlayerEngine 抽象**（Media3 与 LibVLC 两个实现），两者都消费同一份数据层。

    ┌──────────────────────── UI（Compose，简体中文） ────────────────────────┐
    │ 首页(视频/音乐/图片) 浏览器 视频播放页(含字幕/PIP) 音频页 图片查看器     │
    │ 连接管理 连通性测试 字幕任务中心 设置 诊断                               │
    └──────────┬───────────────────────────────┬───────────────────────────┘
               │ ViewModel(StateFlow)          │
    ┌──────────▼───────────────────────────────▼───────────────────────────┐
    │ 领域层：MediaRepository · ConnectionManager(选路/探测) ·                │
    │         PlaybackController(引擎与解码模式) · SubtitleService(外挂+ASR)   │
    └──────────┬───────────────────────────────┬───────────────────────────┘
               │                               │
    ┌──────────▼───────────────┐   ┌───────────▼───────────────────────────┐
    │ StorageBackend 四实现     │   │ media/：DataSource · engine(Media3/VLC)│
    │ local/webdav/sftp/ftp    │──▶│ thumbnail · tsext · ffmpeg · asr       │
    │ 统一产出 RandomAccessSource│   │ 回环 HTTP 代理(给 LibVLC / 外部播放)    │
    └──────────┬───────────────┘   └───────────────────────────────────────┘
               │
    ┌──────────▼───────────────────────────────────────────────────────────┐
    │ 基础设施：Room · Keystore · 两级缩略图缓存 · WorkManager/前台服务 ·      │
    │           ConnectivityManager · 日志与诊断                            │
    └──────────────────────────────────────────────────────────────────────┘

---

## 4. 关键设计

### 4.1 存储抽象层（沿用 v1）

    interface StorageBackend : Closeable {
        val id: String
        val caps: Caps                       // randomAccess / rangeHeader / resumeByRest / maxParallelReads ...
        suspend fun list(dir: RemotePath, page: Page? = null): List<RemoteEntry>
        suspend fun stat(path: RemotePath): RemoteEntry
        suspend fun openRead(path: RemotePath, offset: Long = 0, length: Long = -1): RangeStream
        suspend fun write(path: RemotePath, data: InputStream)   // 新增：字幕写回远端
        suspend fun probe(): ProbeReport
    }

| 后端 | 列目录 | 随机读 | 拖拽定位 | 已知坑与对策 |
| --- | --- | --- | --- | --- |
| 本地 | File / SAF DocumentFile | RandomAccessFile / ContentResolver | 指针 seek，精确 | SAF 需持久化 URI 授权；双模式都做（R12） |
| WebDAV | PROPFIND Depth:1 | GET + Range | 每次 seek 一个新 Range | 不支持 Range → 探测 Accept-Ranges，降级分段缓存 |
| SFTP | readdir | SSH_FXP_READ + offset | 直接按偏移读 | 单会话复用 + 通道池，断线重连不丢 seek |
| FTP | MLSD→LIST | RETR + REST | 每次 REST+RETR | 不支持 REST → 分段缓存；PASV 优先 |

降级链：精确随机读 → 分段缓存（LRU 4 GB，可调）→ 全量下载后播放（仅手动选择）。

### 4.2 Media3 DataSource 与拖拽 seek（沿用 v1 核心）

- `BackendDataSource : BaseDataSource`，把 `DataSpec.position/length` 映射到后端的 Range/REST/read-at；能随机读 = 能拖拽，无需预下载。
- 预读窗口自适应：局域网 8 MB / 公网 512 KB / 默认 2 MB；超时 5 s 连接、15 s 读取（可配）；指数退避重试 3 次；ETag/mtime 变化即失效重开。
- 高延迟链路（公网 SFTP）加"分段预取 + 本地分片缓存"，避免每次 seek 都打远端起新会话。

### 4.3 .ts 专项强化（沿用 v1）

1. **TS 索引缓存**（自研）：解析 PAT/PMT 定位视频 PID，扫 PES 头 + IDR/IRAP + PCR/PTS，生成 (时间 ↔ 字节偏移) 表；key=`(backendId, path, size, mtime)`；拖拽直接跳最近关键帧，**毫秒级**；可取消、可断点续扫、边扫边用。
2. **时间戳重建**：无 PCR / 拼接流 → FFmpeg `-c copy -fflags +genpts` 边读边转封装 MP4，输出到回环 HTTP 服务供播放器消费。
3. **兜底解码**：设备缺 HEVC/AV1 硬解或抽取失败 → 自动切 LibVLC 引擎（硬解/软解按当前模式）。
4. 兼容扩展名 .ts / .m2ts / .mts / .tp；同时支持 .m3u8（HLS）。

### 4.4 缩略图流水线（沿用 v1）

| 媒体 | 主策略 | 兜底 |
| --- | --- | --- |
| 视频 | MediaMetadataRetriever + 自研 MediaDataSource（包装 RandomAccessSource）取 10% 帧 | 1% / 25% 重试 → **FFmpeg 简版抽帧** → 类型占位图 |
| 图片 | Coil Fetcher：RandomAccessSource → okio → 按屏采样解码 | 占位图 + 重试 |
| 音频 | MMR 内嵌封面（ID3/FLAC） | 首字母色块 |

两级缓存（内存 LruCache + 磁盘 webp，默认 512 MB LRU）+ 并发上限 3（SFTP/FTP 降到 2）+ 离屏取消 + 失败负缓存。

### 4.5 连接、地址与网络切换（沿用 v1，接真实测试地址）

- 连接记录含多个地址（LAN / 公网域名），规则 = 传输类型 / SSID / 本机网段 → 优先 LAN 或 WAN；无规则时并行竞速探测（TCP 1.5 s）。
- 判定顺序：网络能力 → 规则 → 竞速 → 协议握手（真正的决定权）；结果缓存 60 s，`NetworkCallback` 变化即失效并重测、必要时无缝换地址续播。
- 连通性测试三段计时：DNS 解析 / TCP 握手 / 协议握手（WebDAV PROPFIND 期望 207 或 200，401=账号密码错；SFTP banner + 认证 + 列目录，主机密钥变更红色告警；FTP 220 + 登录 + PASV）。
- 验收直接用用户给的两组地址：在家 Wi-Fi 应命中 192.168.1.10，切蜂窝应命中 dav.example.com。

### 4.6 播放内核：多引擎 + 硬解/软解（R9/R10，v2 新增）

    interface PlayerEngine {
        fun setMedia(src: MediaSourceRef)      // 内部映射到 ExoPlayer MediaSource 或 VLC Media
        fun prepare(); fun play(); fun pause(); fun seekTo(ms: Long)
        fun setSpeed(x: Float); fun setResizeMode(m: ResizeMode)
        fun setDecoderMode(m: DecoderMode)     // AUTO_HW(默认) / FORCE_SW / FORCE_HW
        fun subtitles(): SubtitleTrackController
        fun videoView(): View / Surface        // Compose 里用 AndroidView 挂载
        fun positionMs(): Long; fun durationMs(): Long
        fun release()
    }

| 引擎 | 实现 | 硬解 | 软解 | 说明 |
| --- | --- | --- | --- | --- |
| Media3（默认） | ProgressiveMediaSource + BackendDataSource | MediaCodec（默认优先） | 音频可叠 media3 FFmpeg 解码扩展 | seek/字幕/缩略图联动最顺 |
| LibVLC | libvlc-all 3.7.6，媒体源走**本机回环 HTTP 代理**（自研，支持 Range）或本地缓存文件 | `--avcodec-hw=any` | `--avcodec-hw=none` | 覆盖畸形 TS / 冷门编码 |

- **解码模式开关**：AUTO（默认，硬解优先）→ 失败或用户手动切 FORCE_SW 时走 LibVLC 软解；切换时保留播放位置与字幕状态。
- **自动降级**：Media3 报解码/抽取错误 → 提示并一键切 VLC 同位置续播。
- 回环代理（127.0.0.1 随机端口，仅本机绑定）同时给 LibVLC、外部播放器、以及"分享播放地址"复用，是让第二引擎复用同一数据层的关键件。

### 4.7 字幕：外挂 + 本地小模型 CPU 音转字幕（R14，v2 新增）

**A. 外挂字幕**
- 支持 .srt / .vtt / .ass(基础样式) / .ssa；Media3 自带 SubRip / WebVTT / SSA 解析器。
- 来源：与视频同名同目录的本地或**远端**字幕文件（走同一 StorageBackend），或手动选择文件；自动匹配优先级：同目录同名 → 同目录语言后缀（.zh.srt/.chs.srt）→ 手动。
- 字幕设置：开关、字号、颜色、描边、底部边距、时间轴微调（±0.5 s 步进）；**单字幕轨道，不做双字幕**。

**B. 音转字幕（本地小模型，CPU）**
- 引擎：**whisper.cpp**（ggml 量化，JNI 调用，多线程 CPU），输出带段时间戳 → 直接生成 SRT。
- 模型档位（不内置 APK）：tiny ≈75 MB / base ≈142 MB / **small ≈466 MB（默认，按用户"要准"）**，均为量化版；获取方式：① **App 内下载为默认**（显示进度、可断点、可取消）② 从本地或远端连接导入模型文件（备用）；模型存 `filesDir/models/`。
- 流水线：FFmpeg 简版把任意音视频轨解码为 16 kHz 单声道 PCM → 按 30 s 窗口（5 s 重叠）分段识别（便于显示进度、暂停、续跑）→ 合并时间轴 → 生成 SRT。
- 输出：**一次只产出一个字幕文件**（用户口径）——默认 **SRT**，与视频**同名同目录**（本地直接写；远端 WebDAV 用 PUT、SFTP 用 write、FTP 用 STOR）；需要时在任务详情里另存为 VTT 或纯文本稿（同样只落一个文件，不并行生成多份）；**无写权限时**自动落到 App 缓存并提示"另存为/分享/稍后重试"，绝不静默丢弃。
- 任务形态：前台服务 + 通知进度（可暂停/取消），建议充电时执行；任务可排队，结果记入 `asr_task` 表。
- **性能预期（如实说明，不夸大）**：8 系骁龙 4 线程下，base 约 3–6 倍实时、small 约 1–2 倍实时；即 1 小时视频用 base 约 10–20 分钟、small 约 30–60 分钟。默认 small 保证中文质量，慢可一键换 base。
- 语言：默认中文（zh），可切自动检测；模型只跑 CPU，不依赖 NNAPI/GPU（后续可加）。
- 播放器联动：生成完成后自动加载并跳到字幕起点；可在播放页直接微调时间轴后另存。

### 4.8 画中画（R13）
- `PictureInPictureParams`：播放中按 Home 自动进入 PIP；PIP 窗口内播放/暂停、快退快进 10 s；与后台音频、通知栏控制共用 MediaSession；Android 13+ 支持 PIP 内自定义动作。

### 4.9 权限与安全

| 权限 | 用途 | 时机 |
| --- | --- | --- |
| INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE | 联网、判断网络 | 安装即声明 |
| POST_NOTIFICATIONS | 后台播放 / ASR 进度通知 | 首次需要时申请 |
| FOREGROUND_SERVICE + …_MEDIA_PLAYBACK / …_MEDIA_PROCESSING | 后台播放 / 音转字幕 | 对应功能启动时 |
| READ_MEDIA_VIDEO/AUDIO/IMAGES | 媒体库入口（可选功能） | 首次使用时 |
| MANAGE_EXTERNAL_STORAGE（可选） | 全盘浏览本地媒体 | 设置页引导；同时提供 SAF 模式 |

安全：明文流量只对用户配置的 FTP 主机放开（network_security_config）；SFTP 主机密钥 TOFU + 变更告警；WebDAV 支持自签证书指纹白名单；凭据 Keystore 加密；**测试账号密码等敏感信息不写进任何会提交 git 的文件**。

### 4.10 缓存与性能（沿用 v1，新增 ASR 相关）

缩略图 512 MB / 分段播放缓存 4 GB / 模型与识别中间件存 filesDir（可清理）；目录列表分页 + 缓存；长任务（TS 扫描、ASR）走 WorkManager + 前台可见进度，避免 Android 15+ 前台服务时限。

### 4.11 后台播放（R18，用户补充需求）

- **音频**：`MediaSessionService` 前台服务 + MediaSession；通知栏 / 锁屏 / 耳机键 / 蓝牙控制；音频焦点被抢占自动暂停；播放队列与断点续播。
- **视频**：切后台或息屏后**播放不中断**（音频轨继续输出，画面暂停渲染，回前台或点通知恢复画面）；与画中画、通知栏控制联动。
- **后台任务**（TS 索引、缩略图预热、音转字幕）继续执行：前台服务 + 通知进度，长任务可暂停 / 续跑。
- **澎湃 OS 保活**：首次运行引导开启通知权限、省电策略「无限制」、自启动、后台弹出界面；被系统回收后回到前台能恢复到原播放位置。
- 验收：息屏 / 切后台 30 分钟播放不中断；系统清理后重进恢复位置与进度。

### 4.12 批量字幕生成任务中心（R19，用户补充需求）

**用户原话**：『用模型生成字幕时可以批量选择，包括文件夹下的所有视频文件，可以单独在一个地方生成字幕，显示进度条以及支持后台生成，不影响正常看视频。』

- **入口独立**：底部「任务中心」页，不依附播放页；可以把它当"字幕工厂"，一边排队生成一边正常看视频。
- **批量选择**：① 目录里多选文件；② 直接选**整个文件夹**（可勾选"含子文件夹"递归扫描）；③ 自动过滤非视频（扩展名 + 探测），可选"跳过已有字幕的文件"。
- **队列与进度**：任务入队后默认**串行执行**（并发 1，可调 2，避免与播放抢 CPU/IO）；页面显示总进度 + 每个文件的分段进度（已识别时长 / 总时长、预计剩余）；支持暂停 / 继续 / 取消 / 调整顺序 / 单条重试 / 一键重试全部失败项。
- **后台生成**：前台服务 + 通知（总进度、当前文件、暂停与取消按钮）；息屏、切应用、锁屏都继续跑；进程被杀后重启能恢复队列（状态落 `asr_task` 表，未完成任务标为"已中断、可续跑"）。
- **不影响看视频**（关键约束）：ASR 推理线程降优先级（`Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)`）、线程数可配（默认 4，可降 2）；**检测到前台正在播放时自动让路**——暂停识别或把线程降到 1（设置项可关），播放结束自动恢复；远端取音复用同一 StorageBackend 的低优先级连接，不与播放抢带宽。
- **结果汇总**：成功 N / 失败 M / 跳过 K；失败项给原因（无写权限、无音轨、模型未下载、网络中断）并可重试；成功的字幕按 4.7 规则与视频同名同目录写回。

---

## 5. 开发环境按项目隔离（回答用户的扩展问题）

**问题**：本机装 Android 开发环境要装 Java，但其他项目的 Java 版本可能不同，怎么做到"按项目搭建、互不影响"。

**方案 A（推荐）：项目自带工具链 + 项目级 Gradle 用户目录**

    /root/project/mediagate/
    ├─ .toolchain/
    │  ├─ jdk-21/          # Temurin 解压版，项目私有（不写系统 PATH、不装系统包）
    │  ├─ gradle-9.8.0/    # Gradle 发行版本体（wrapper 已预置，不联网取发行包）
    │  ├─ gradle-home/     # GRADLE_USER_HOME：依赖缓存、守护进程、配置，项目私有
    │  ├─ android-sdk/     # 项目私有 Android SDK：platform 37.2 / build-tools 37 / platform-tools / NDK r30 / CMake 4.1.2
    │  └─ android-user/    # ANDROID_USER_HOME：许可、缓存，项目私有
    ├─ local.properties    # sdk.dir=...（本机文件，不入 git）
    └─ scripts/env.sh      # source 一下即进入本项目环境

    # scripts/env.sh
    export JAVA_HOME=/root/project/mediagate/.toolchain/jdk-21
    export ANDROID_HOME=/root/project/mediagate/.toolchain/android-sdk   # 项目私有（含 platform 37.2 / build-tools / NDK / CMake）
    export ANDROID_USER_HOME=/root/project/mediagate/.toolchain/android-user
    export GRADLE_USER_HOME=/root/project/mediagate/.toolchain/gradle-home
    export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

- 所有构建命令统一 `source scripts/env.sh` 之后再跑；**系统层面不安装任何 JDK/SDK**，因此对别的项目零影响。
- 同一台机可并存多套 JDK（17/21/25…），每个项目在自己的 `GRADLE_USER_HOME` 下有独立依赖缓存与 Gradle 守护进程，构建不会互相踩。
- 也可用 Gradle 官方的 **Java Toolchain**（`java.toolchain.languageVersion = 21` + foojay-resolver 插件）让 Gradle 自动获取所需 JDK，配合 `org.gradle.java.home` 指定守护进程 JVM——与方案 A 叠加使用更省心。
- **Android SDK 已私有化（2026-09-30 实做）**：`ANDROID_HOME=/root/project/mediagate/.toolchain/android-sdk`，内含 platform 37.2、build-tools 37.0.0、platform-tools 37.0.1、NDK r30（30.0.16248370）、CMake 4.1.2，约 2.7 GB，随 `.toolchain/` 一起不进 git。公共 `/opt/android-sdk` 只作为 sdkmanager 的下载来源，**不再被本项目构建引用**。原因是 2026 年的 androidx 要求 compileSdk ≥ 37，而公共 SDK 只装到 android-36，与其去改公共资源不如整体私有；代价是多占约 2.7 GB。
- 内存注意：本机 9 GB，Gradle 守护进程限 `-Xmx3g`，多项目同时构建时错峰。

**方案 B：容器完全隔离**（Podman/Docker）：镜像 pin 死 JDK21 + SDK36 + Gradle，项目目录挂载进去构建；真机调试需 `--network host -v /dev/bus/usb:/dev/bus/usb`。优点是可复现、可迁移，缺点是首次拉镜像慢、adb 稍绕。作为 `scripts/` 里的可选项提供。

**方案 C：SDKMAN! / update-alternatives 全局切换**：最省事，但**不满足"不影响其他项目"**（同一 shell 全局生效，容易忘记切换）——仅作对比，不采用。

**结论**：默认走 A（零系统改动、按项目隔离），需要可复现交付时用 B。

---

## 6. 目录结构（Gradle 多模块，v2 新增模块）

    mediagate/
    ├─ app/                      # 壳、导航、DI、权限引导、设置、诊断、任务中心
    ├─ core/{common,model,database,crypto,network}
    ├─ data/
    │  ├─ storage-api/           # StorageBackend / RangeStream / RandomAccessSource / 预读 / 降级缓存
    │  ├─ storage-local/         # File 与 SAF 双模式
    │  ├─ storage-webdav/        # OkHttp + PROPFIND + Range + PUT（字幕写回）
    │  ├─ storage-sftp/          # JSch(mwiede)
    │  └─ storage-ftp/           # Commons Net（FTP/FTPS + REST + STOR）
    ├─ media/
    │  ├─ engine/                # PlayerEngine 抽象 + ExoPlayerEngine + VlcEngine + 解码模式
    │  ├─ proxy/                 # 回环 HTTP 代理（给 LibVLC / 外部播放）
    │  ├─ playback/              # Media3 DataSource.Factory、MediaSource 工厂
    │  ├─ thumbnail/             # 抽帧 / 封面 / 两级缓存 / 并发控制
    │  ├─ tsext/                 # TS 解析器、索引构建、时间戳修复
    │  ├─ ffmpeg/                # FFmpeg 简版封装（抽帧、转封装、PCM 解码）
    │  ├─ asr/                   # whisper.cpp JNI 绑定、模型管理、分段识别、SRT 生成
    │  └─ subtitle/              # 外挂字幕匹配/加载/设置、时间轴微调
    ├─ feature/{home,browser,player-video,player-audio,viewer-image,connections,settings,tasks}
    │                            # tasks = 任务中心：批量字幕队列、进度、失败重试
    ├─ docs/                     # plan.md（方案副本）、架构说明、验收记录
    └─ scripts/                  # env.sh、测试服务（webdav/sftp/ftp）、样本生成、构建脚本

---

## 7. 数据模型（Room）

| 表 | 关键字段 | 用途 |
| --- | --- | --- |
| connection | id,name,protocol,basePath,username,secretRef,options,tls,lastWorkingAddressId,lastCheckedAt | 连接 |
| address | id,connectionId,label(LAN/WAN),scheme,host,port,priority | 多地址 |
| network_rule | id,connectionId,transport,ssidPattern,localSubnet,prefer | 选路规则 |
| dir_cache | id,connectionId,path,entriesJson,fetchedAt,ttl | 目录缓存 |
| ts_index | id,connectionId,path,size,mtime,indexBlobPath,status,progress | TS 索引 |
| thumb_meta | key,connectionId,path,size,mtime,kind,filePath,failedAt | 缩略图索引/负缓存 |
| playback_progress | id,connectionId,path,positionMs,durationMs,updatedAt | 断点续播 |
| subtitle_pref | id,connectionId,path,style,offsetMs,source(AUTO/EXTERNAL/ASR) | 字幕偏好 |
| asr_batch | id,name,sourceKind(FILES/FOLDER),rootPath,recursive,skipExisting,state,createdAt | 批量字幕批次 |
| asr_task | id,batchId,connectionId,path,model,state,progress,outputPath,error,retryCount,queueOrder | 音转字幕任务（单文件，可入队排队） |
| recent / task | … | 最近打开 / 后台任务中心 |

---

## 8. 构建、签名、交付与澎湃 OS 适配

- 构建：`source scripts/env.sh` → `./gradlew :app:assembleDebug` / `assembleRelease`；ABI 只打 arm64-v8a（可选 armeabi-v7a）；native 库（libvlc、ffmpeg、whisper）需 **16 KB 页对齐**。
- 签名：自签 keystore 放 `keystore/`（**不进 git**），`keystore.properties` 一并忽略。
- git：`git init -b main`；`.gitignore` **首行 `.dsh-meow/`**（记忆数据绝不入库），另含 build/ .gradle/ local.properties .toolchain/ keystore/ *.jks *.keystore models/；提交前双查（`git check-ignore -v .dsh-meow/memory.db` + 暂存区 grep）。
- 交付：`app/build/outputs/apk/release/mediagate-<version>.apk`，经会话直接发文件给用户安装。
- 澎湃 OS 首次运行引导：通知权限、省电策略「无限制」、自启动、后台弹出界面；后台播放/长任务时下拉通知锁定任务。

---

## 9. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| FFmpeg 简版来源不确定（ffmpeg-kit 官方已归档） | 抽帧/ASR 解码受阻 | M0 先验证社区分支可用性；不可用则用 ffmpeg-android-maker 自建精简包（只留 demux/decode + PCM/抽帧） |
| LibVLC 与 Media3 双引擎状态同步（位置、字幕、倍速） | 切换体验割裂 | 统一 PlayerEngine 状态机 + 切引擎保留 position/字幕/倍速；切换过程显示"正在切换解码器" |
| ASR 长视频耗时长 | 用户以为卡死 | 前台服务 + 分段进度 + 可暂停/取消 + 明确的耗时预估提示 |
| 远端无写权限 | 字幕存不进视频目录 | 自动落本地缓存 + 提示另存/分享，绝不静默失败 |
| 公网 SFTP seek 延迟高 | 拖拽慢 | 分段预取 + 本地分片缓存 + TS 索引 |
| FTP 不支持 REST / 无 FTP 测试地址 | 拖拽失效 / 验收缺样本 | 能力探测 + 降级缓存；FTP 用本机 pyftpdlib 自建（或用户提供地址） |
| 设备缺 HEVC/AV1 硬解 | 黑屏 | 自动切 LibVLC + 软解开关 |
| 16 KB 页 / native 库体积 | 装机失败 / APK 过大 | 校验对齐；ABI 拆分；按需只保留必要解码器 |
| 澎湃 OS 杀后台 | 播放/ASR 中断 | 前台服务 + 引导设置 + WorkManager 续跑 |
| 大目录 / 大文件 | 列表卡、内存涨 | 分页 + 缓存 + 懒加载缩略图 + 及时释放播放器 |

---

## 10. 验收标准（对应 R1–R19）

**测试素材**：本地用 ffmpeg 生成 H.264 TS（10 分钟 / 2 GB 各一）、HEVC TS、无 PCR 拼接 TS、MP4/MKV/AVI/FLV、m3u8、MP3/FLAC/OGG/Opus/M4A、JPG/PNG/HEIC/AVIF/GIF/8000×6000 大图；远端用**用户给的两组连接**（LAN 192.168.1.10、公网 dav.example.com），FTP 用本机 pyftpdlib 自建。

| 编号 | 验收项 | 通过标准 |
| --- | --- | --- |
| R1 | 三类媒体 | 视频可播可停可拖；音频后台播放（锁屏可控）；图片可缩放翻页 |
| R2 | 四协议 | 每个协议都能列目录、播放、抽缩略图；断开重连不崩 |
| R3 | .ts 与 .m3u8 | 2 GB TS 音画同步可拖拽；无 PCR 样本有提示且可播；m3u8 正常起播 |
| R4 | 拖拽 | 局域网松手到出画面 ≤3 s，公网 ≤6 s；有 TS 索引时 ≤1.5 s；连续拖 20 次不错位 |
| R5 | 缩略图 | 200 项滚动 ≥55 fps；≥90% 视频出帧；缓存命中二次进入秒显 |
| R6 | 无登录 | 冷启动直达首页，除系统权限外无任何登录/权限弹窗 |
| R7 | 网络切换 | 家中 Wi-Fi 走 192.168.1.10，切蜂窝走 dav.example.com，切换后续播不丢位置 |
| R8 | 多连接与自测 | ≥5 个连接可建；「测试全部」给出每地址三段耗时与错误分类；改错端口能复现对应错误 |
| R9 | 多内核 | 同一视频可在 Media3 / LibVLC 间切换，位置、倍速、字幕保持 |
| R10 | 硬/软解 | 默认硬解可播；切软解后同一视频（含 HEVC TS）仍可播，功耗/温度明显上升但可用 |
| R11 | FFmpeg | 抽帧兜底对 MMR 失败样本有效；PCM 解码供 ASR 使用成功 |
| R12 | 本地双模式 | SAF 选目录可浏览播放；开「所有文件访问」后可全盘浏览；未授权时给引导不崩 |
| R13 | 画中画 | 播放中 Home 进 PIP，PIP 内可暂停/快退，返回可续 |
| R14 | 字幕 | 外挂 srt/vtt/ass 自动加载，样式与延迟可调；音转字幕生成**与视频同名同目录的单个字幕文件**（默认 SRT），时间轴可读；另存 VTT / 纯文本可用；无写权限时落本地并提示 |
| R15 | 系统 | 在澎湃 OS 真机（Android 13+）安装运行正常 |
| R16 | 语言 | 界面全中文，无英文残留、无未翻译串 |
| R17 | 环境隔离 | 不装系统级 JDK/SDK 即可构建；其他项目的 JDK 版本与本项目互不影响（构建前后系统 java 状态不变） |
| R18 | 后台播放 | 切后台 / 息屏后音频与视频音轨持续播放 ≥30 分钟，通知栏与锁屏可控；TS 索引与音转字幕在后台继续并显示进度；被系统清理后重进恢复位置 |
| R19 | 批量字幕 | 选一个含 10 个视频的文件夹（含子目录）可一键入队；任务中心显示总进度与逐项进度，可暂停/取消/重试；生成期间正常播放视频不卡顿（后台线程自动让路）；全部生成后字幕与各自视频同名同目录 |
| R20 | 应用内更新 | 设置页「检查更新」能读出最新版本号与更新说明；有新版可**应用内下载**（进度、**断线续传**、SHA-256 + 签名证书校验），下完一键调起系统安装器；**必须明示"检查更新需要能访问 GitHub"**；私有仓库用**只读 token**（本地 Keystore 加密存储、可清除、界面不回显），缺 token / token 无效 / 连不上 GitHub 三种失败要分别给中文提示 |
| 通用 | 稳定性 | 连续 1 小时无崩溃、无内存持续增长；后台播放 30 分钟不被杀 |

### 10.1 R20 设计要点（2026-10-02 定，用户要求原文：「增加检查更新，同时如果有更新直接下载，而不是打开网页跳转到github页面」「更新的话，将github更改成公共的，并提示更新需要连接github才行，并且支持下载时的断线重连」）

- **更新源**（2026-10-02 用户改口径：「使用只读token和私有仓库方案吧，但是存储的只读token需要本地加密」）：
  **私有**仓库 `gua123/mediagate-releases` 的静态清单 `update.json`（versionCode / versionName / apkUrl / sizeBytes / sha256 / notes），
  App 用**只读 token** 走内容 API 拉清单、走资产 API 下载 APK（`Accept: application/octet-stream` + `Authorization: Bearer`）。
  token 由用户在 GitHub 建（fine-grained、只给该仓库 Contents: Read），在本应用设置页填入，
  **用 Android Keystore（AES-GCM，复用 :core:crypto）加密后落盘**，界面不回显、日志不打印。
- **安装**：FileProvider + `REQUEST_INSTALL_PACKAGES`，交给系统安装器——**Android 不允许静默安装**（这也是为什么只能说"直接下载"，安装那一下必须用户点一次）。
- **校验**：大小 → SHA-256 → **签名证书必须与当前安装包一致**，任一不过就拒绝安装并给中文原因。
- **断线续传**：`.part` 临时文件 + Range 请求；服务端回了 200（不支持 Range）就从头重下，绝不拼接（同时躲开"大文件被截断"这类链路问题）。
- **文案**：检查更新失败时明确提示"需要能访问 GitHub（可能需要代理）"，不谎报网络故障。

---

## 11. 里程碑与工期（单人全职估算，一次做全后交付）

| 阶段 | 内容 | 人日 |
| --- | --- | --- |
| M0 ✅ 2026-09-30 完成 | 环境按项目隔离（env.sh + 项目私有 JDK / Gradle / Android SDK / NDK）、27 模块工程骨架 + Version Catalog、git、签名、FFmpeg/VLC/whisper 依赖可用性验证（均已实测：debug APK 构建通过、whisper 四个 .so 16 KB 对齐） | 3–4 |
| M1 ✅ 2026-10-01 完成 | 本地闭环（SAF + 全盘访问双模式）、缩略图流水线、图片查看器、音频播放 + 后台播放（R18 音频侧）；累计 198 个 JVM 用例，见 docs/验收记录-M1.md | 6–8 |
| M2 ✅ 2026-10-01 | **播放内核框架**：PlayerEngine 抽象 + Media3 实现 + 回环代理 + LibVLC 实现 + 硬/软解切换（proxy 43 / engine 31 例）；视频播放页与两内核接进 App（player-video 48 例） | 6–8 |
| M3 ✅ 2026-10-01 | WebDAV 后端 + DataSource + Range seek + 远端缩略图（91 例，含真网 4 例）；真实服务器实测结论见第 0 章 | 5–7 |
| M4 ✅ 2026-10-01 | 连接管理 + 多地址选路 + 连通性测试（crypto 19 / network 70 / connections 52 例）；Room v1→v2 迁移 | 4–5 |
| M5 ✅ 2026-10-01 | SFTP + FTP 后端（sftp 103 / ftp 93 例，含 SFTP 真网 6 例）；能力探测与降级（REST 不支持→顺序跳过 + caps 降级） | 5–6 |
| M6 ✅ 2026-10-01 | TS 强化（解析器、索引缓存、断点续扫、时间戳重建）+ FFmpeg 简版接入（tsext 128 / ffmpeg 30 例） | 6–8 |
| M7 ✅ 2026-10-01 | 字幕（外挂 + 样式/延迟，110 例）+ **音转字幕 ASR**（JNI 桥 + 模型管理 + 分段识别 + SRT 写回，123 例）+ **批量任务中心**（25 例，多选/整文件夹、队列、进度、后台生成、播放让路） | 8–11 |
| M8 ✅ 2026-10-01 | 画中画（35 例）、**视频后台播放与澎湃保活引导**（16 例）、全量验收与文档 | 5–8 |
| M9 ✅ 2026-10-02 | **真机验收修复**：① SFTP / FTP 此前只交付数据层后端、App 组合根未接（真机提示"（M5 接入）"），现已接入「设为当前连接 → 浏览/播放」并清掉全部 M5 陈旧文案（connections +8 例）；② 修掉**明文 http 被系统默认拦死**——targetSdk 36 下局域网 WebDAV（http）在真机全挂，新增 `network_security_config.xml` + `CleartextPolicyTest`（app +2 例）；③ 按用户要求统一**界面中文**：新增 `:core:common` 的 `ErrorText`，各界面不再露出库的英文 message（+5 例） | — |
| 全量验收 | **1264 个 JVM 用例 0 失败**；release APK ≈74 MB、V2 签名、19 个 .so 全 16 KB 对齐；详见 docs/验收记录-M2-M8.md（含需真机验证清单与已知限制） | — |
| **合计** | | **48–65 人日（约 9–13 周单人全职）** |

---

## 12. 决议状态与开工确认

v2 的 6 条待确认已全部落定（见第 0 章「用户第二轮答复」）：ASR 默认 small、模型 App 内下载、字幕单文件（默认 SRT，可另存 VTT / 纯文本，单轨不双字幕）、支持软解但默认硬解、FTP 本机自测、密码不入 git；另加 R18 后台播放、R19 批量字幕生成任务中心。

**状态（2026-10-02）：M0–M8 全部完成，真机验收进行中（首轮反馈的 SFTP/FTP 接线缺口已修复，见 M9 与验收记录第 7 节）。** 本文档即本项目的计划权威（方案工作区的 mediagate.md 已冻结为只读存档），后续需求变更与实施都改这里。
代码侧验收见 docs/验收记录-M0.md、验收记录-M1.md、验收记录-M2-M8.md；**尚需用户在真机上验证的清单与已知限制见 M2–M8 记录的第 4、5 节**。

> **一处口径待你点头**（我按此理解写进了方案）：「字幕全要，但只单个字幕文件」＝ 外挂字幕、样式、延迟微调、音转字幕功能都做，但每次只落**一个**字幕文件（默认 SRT），需要别的格式时另存，不并行生成多份；同时不做双字幕叠加。如与你的意思不符，说一声我改。

---

## 13. 交接与后续

**已完成（2026-09-29～30）**：方案工作区 /root/project/project-plan/mediagate.md 冻结为只读存档；本项目工作空间建立并初始化（第 5 章的环境隔离 + git + 签名）；记忆、任务与本文档均已交接；M0 全部完成（见第 2.1 节与第 11 章）。后续开发一律在本工作区（/root/project/mediagate）进行。
