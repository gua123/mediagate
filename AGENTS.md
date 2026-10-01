# mediagate — 项目工作空间约定

> Android 多协议媒体播放器（本地 / WebDAV / SFTP / FTP），小米澎湃 OS，自用 sideload。
> **计划权威：`docs/plan.md`**（方案工作区 /root/project/project-plan/mediagate.md 已冻结为只读存档，后续一切变更改这里）。

## 1. 硬约束与红线

- **记忆数据绝不入库 git**：`.dsh-meow/` 已在 .gitignore 首行；提交前双查 —— `git check-ignore -v .dsh-meow/memory.db` ＋ `git diff --cached --name-only | grep dsh-meow`，两条都过才 commit。
- **凭据不进 git**：测试账号密码只存本工作区记忆库与 App 连接记录（Keystore 加密）；`keystore/`、`keystore.properties`、`*.jks/keystore`、`local.properties` 均已忽略。
- **环境按项目隔离**：任何构建/命令前先 `source scripts/env.sh`；**不装系统级 JDK/SDK**。项目私有：JDK 21 `.toolchain/jdk-21`、Gradle 9.8.0 `.toolchain/gradle-9.8.0`、**Android SDK `.toolchain/android-sdk`（platform 37.2 / build-tools 37.0.0 / platform-tools / NDK r30 / CMake 4.1.2）**、`GRADLE_USER_HOME` 与 `ANDROID_USER_HOME`。公共 `/opt/android-sdk` 只当 sdkmanager 下载来源，**不再被本项目构建引用**；不得修改系统 java/环境变量以免影响其它项目。
- **不碰其它项目**：只在本工作区内读写；/root/project/project-plan 为只读存档。
- **仓库已公开（2026-10-03 起）**：`github.com/gua123/mediagate` 是公开仓库，更新源也挂在它上面（`update.json` + Release 资产）。
  因此**任何真实数据都不许进 git**：内网地址、公网域名/IP、测试账号、自建端口一律用示例值
  （`192.168.1.x` / `dav.example.com` / `203.0.113.10` / `demo` / 8080·2222·8443）；
  历史上已做全量脱敏并重写（`git filter-branch` + 清 `refs/original` + gc）。
  **替换对照表只存在本工作区记忆库**（不在仓库里），提交前按它自查一遍。发版走 `scripts/publish-update.sh`。

## 2. 方案要点（详见 docs/plan.md）

- 系统：minSdk 33（只保澎湃 OS）/ targetSdk 36 / **compileSdk 37.2**（AGP 9 的 `compileSdk`+`compileSdkMinor`；2026 年的 androidx 强制要求 ≥37）；界面简体中文单语。
- 构建口径：**AGP 9 内置 Kotlin，禁止再声明 `org.jetbrains.kotlin.android`**（会直接构建失败）；Compose 用 `org.jetbrains.kotlin.plugin.compose`。
- 播放：Media3 ExoPlayer 1.11.1（默认，硬解优先）＋ LibVLC 3.7.6（兜底，可强制软解）；统一 `PlayerEngine` 抽象，LibVLC 走本机回环 HTTP 代理复用同一数据层。
- 数据层：统一 `StorageBackend` → `RandomAccessSource`；Media3 `BackendDataSource` 实现远端拖拽 seek；.ts 自研索引缓存（时间 ↔ 字节偏移）让大 TS 拖拽毫秒级。
- 字幕：外挂 srt/vtt/ass（单轨）+ whisper.cpp 本地 CPU 音转字幕（默认 small 模型，App 内下载）；**一次只产出一个字幕文件**（默认 SRT，与视频同名同目录）。
- 批量字幕任务中心（R19）：多选/整文件夹、队列、进度、后台生成、播放时自动让路。
- FFmpeg 简版：抽帧兜底、时间戳重建、ASR 的 PCM 解码。
- 后台播放（R18）：MediaSessionService + 前台服务，切后台/息屏不中断。
- 验收基线：plan.md 第 10 章 R1–R19。

## 3. 目录

    app/ core/{common,model,database,crypto,network,download} data/{storage-api,storage-local,storage-webdav,storage-sftp,storage-ftp}
    media/{engine,proxy,playback,thumbnail,tsext,ffmpeg,asr,subtitle}
    feature/{home,browser,player-video,player-audio,viewer-image,connections,settings,tasks,update,asr-model}
    docs/（plan.md、验收记录）  scripts/（env.sh、测试服务、样本生成）  models/（ASR 模型，不进 git）

## 4. 常用命令

    source scripts/env.sh                      # 进入本项目环境
    bash scripts/install-toolchain.sh          # 首次：装项目私有 JDK21 + SDK(platform 37.2/NDK/CMake) + Gradle 9.8 + wrapper 缓存
    bash scripts/build-whisper-android.sh      # 需要音转字幕时：重建 ASR native 库（不进 git）
    ./gradlew :app:assembleDebug               # 构建
    adb install -r app/build/outputs/apk/debug/app-debug.apk

## 5. 当前进度

- 2026-09-29：方案 v4 定稿（R1–R19，48–65 人日），工作空间建立并交接完成。
- **M0 已完成（2026-09-30）**：27 模块 Gradle 骨架 + Version Catalog + Room/KSP 跑通；`:app:assembleDebug` 构建通过；
  签名 keystore 就绪；whisper.cpp v1.9.4 用私有 NDK r30 编出四个 16 KB 对齐的 .so（`scripts/build-whisper-android.sh`）；
  FFmpeg 简版锁定 `dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.9`；依赖源改用阿里云镜像优先。
- **M1 已完成（2026-10-01）**：数据层（File/SAF 双模式）+ 缩略图流水线（Key v2：视频抽帧 / 图片预览重编码 / 音频内嵌封面）
  + 应用接线（手写 AppContainer + 导航 + 首页 + 浏览器 + 双模式引导）+ 图片查看器 + 音频播放与后台播放（MediaSessionService）。
  验收记录见 docs/验收记录-M1.md；本阶段新增 198 个 JVM 用例（每片均以 --rerun-tasks --no-build-cache 复核）。
- **M2–M8 已完成（2026-10-01）**：播放内核与视频播放页、WebDAV/SFTP/FTP 三协议后端（含真网 opt-in 验收）、
  连接管理与多地址选路、TS 索引与 FFmpeg 简版、外挂字幕与 whisper 音转字幕（JNI 桥 + 批量任务中心）、
  画中画与视频后台播放、澎湃保活引导。
- **真机验收修复已完成（2026-10-02）**：真机反馈「SFTP 提示需要接入 M5」——M5 只交付了数据层后端
  （`data/storage-sftp` / `data/storage-ftp`），**App 组合根那一段没接**（`applyCurrentConnection` 只有 WEBDAV / LOCAL 两分支，
  `browsable` 也只放行这两种）。现已接进「设为当前连接 → 浏览/播放」：新增 `ConnectionBackends` 纯函数装配 +
  `AppContainer` 的 `selectAddress`/`installRemoteRoot`（WEBDAV / SFTP / FTP 同一条流程 + 中文失败提示），
  并清掉全部「M5 接入」陈旧文案（协议选择器 / 卡片 / 设置页 / core:network 提示）。
  同日第二个真机专属缺陷：清单缺 `android:networkSecurityConfig`，targetSdk 36 下**明文 http 被系统默认拦死**
  → 局域网 WebDAV（http://192.168.1.10:8080）在真机全挂（提交 `0af4e6a`：新增
  `res/xml/network_security_config.xml` + `CleartextPolicyTest` 回归护栏）。
  同日第三项：按用户要求「界面语言直接用中文，不要中英双语」做了错误提示统一——新增 `:core:common` 的
  `ErrorText`（含汉字原样保留 → 按异常类型/英文关键词归类 → 兜底中文），把连接页、:app 远端提示、WebDAV/SFTP/FTP
  后端、LibVLC、字幕任务、TS 扫描、whisper 引擎等处会露出的库英文 message 全部收口（提交 `e021c6c`）。
  同日第四项（真机截图确认后）：明文被拦不再谎报「网络不可达」——`ConnectivityError` 新增 `CLEARTEXT_BLOCKED`，
  WebDAV probe 去掉「网络不可达：」前缀（提交 `3f78b65`）。
- **当前基线（2026-10-03 更新）**：全项目 **1352 个 JVM 用例 0 失败**（29 个模块）（`./gradlew testDebugUnitTest`；
  本轮真机修复新增 15 例：:feature:connections +8、:app +2、:core:common +5）；
  release APK ≈74 MB、V2 签名、19 个 native 库全部 16 KB 页对齐。
- **M10 发版前整理（2026-10-03，0.1.1）已完成**：
  ① **R20 应用内更新**（最终口径：源码仓库转公开 → 更新源用公开 raw 清单 + Release 资产，**App 不要 token**）：
  `:core:download`（HTTP 传输 + 自定义请求头 + 断点续传 `FileDownloader`）、`:feature:update`（清单解析 /
  版本比较 / 检查更新的中文失败分类 / 状态机 / 设置页卡片），`:app` 的 UpdateHost 负责版本号、**签名证书
  SHA-256 比对**、FileProvider + `REQUEST_INSTALL_PACKAGES` 调系统安装器；
  ② **ASR 模型下载与管理界面**（`:feature:asr-model`：档位 / 进度 / 删除 / 设为当前 / 国内镜像开关）——
  此前该功能完全没有界面，音转字幕在真机上用不了；
  ③ **时间戳重建接线**（TS 家族在播放页有「修复时间戳」出口，产物进 cacheDir 并经"视频专用修复根目录"续播）；
  ④ **字幕另存为纯文本**（`SubtitleFormat.TXT` 只作导出目标，不进可加载字幕源）；
  ⑤ `scripts/publish-update.sh` 一键发版（建 Release + 传 APK + 写 update.json + 提交推送）；
  ⑥ 仓库**脱敏 + 包名替换 + 重写全部历史**后转公开（详见第 1 节红线与 docs/验收记录-M2-M8.md 第 10 节）。
- **M11 已知限制清空（2026-10-03，0.1.2）**：上一版列的"已知限制"基本清完——
  · **whisper 模型官方 SHA-256 回填**（下载三档官方模型逐份算，字节数与原 sizeBytes 一致），并修掉
    **失效的镜像写法**（`gh-proxy` 前缀式已 404 → 改成替换主机的 `hf-mirror.com`，实测可用）；
  · **SFTP 主机密钥指纹落库**（Room v3→v4 的 `sftp_host_key` 表 + `PersistentKnownHostsStore` 写穿；
    设置页新增「已信任的 SFTP 主机密钥」可逐条忘掉——服务器真换钥时的复原路径）；
  · **通知栏/锁屏封面**（缩略图 LRU 复用，不为封面额外抽帧）与**音频同目录 cover.jpg 兜底**；
  · **分段缓存**（`SegmentedCacheBackend`：4 MB 段 + LRU + 大小缓存；远端根目录统一套一层，
    让不支持随机读的协议也能拖拽）；
  · **TS 索引接进 App**（无 PCR 提示 + 拖拽落点预取；**没有**替换 Media3 的 seek 实现，
    因此不宣称毫秒级）；
  · **强制硬解的口径落地**（Media3 侧 = 剔除软件解码器候选，无硬解时如实退回并提示）。
- **M12 交互收尾（2026-10-03，0.1.3）**：① **连接页新增「本地目录」卡片**（模式/路径 + 选择目录 /
  使用内部存储 / 开启全部文件访问 / 清除）——此前只有首页能选本地目录，在连接页想换目录没有入口；
  ② **首页根目录卡片统一远端文案**（远端生效时写「正在使用远端连接：xxx」，本地那项改称「备用本地目录」
  并说明清除不影响远端；判据收敛成 `HomeRootUi.remoteActive/localStandby` 两个纯逻辑属性）。
- **M13 更新源缓存修复（2026-10-03，0.1.4）**：raw 清单有 CDN 缓存（`max-age=300`，实测发版后 6 分钟仍读到旧清单；
  查询参数与 `Cache-Control: no-cache` 都绕不过）→ App 改读**两条不同缓存键**的 raw 路径
  （`main/update.json` + `refs/heads/main/update.json`）取 versionCode 较高者；已确认有更新就不再打第二次网络。
- **M14 认证失败可自查（2026-10-03，0.1.5）**：真机截图报 SFTP 认证失败 → 用 opt-in 真网测试在本机对同一服务器
  跑 6/6 全过，定位为"App 里存的密码不对" → ① 连接编辑器新增「测试一下」（用草稿含新密码真测、不落库、
  密码留空沿用已存密文）；② 浏览页新增 `BrowserErrorKind.AUTH_FAILED`，认证失败时给出"去哪儿改密码"的指引。
- **M15 凭据输入加固（2026-10-03，0.1.6）**：用户坚持"账号密码都是对的" → 用凭据文件与真网测试把"凭据本身"钉死
  （逐字一致、纯 ASCII、无空白；只比布尔不打印），代码侧复核排除加密/trim/编码问题
  → 指向输入环节（密码框圆点显示看不见全角）→ 密码框改密码键盘 + 关自动大写纠错 + 「显示/隐藏」开关，
  校验对全角/首尾空格点名，「测试一下」标明用的是新输入还是已保存的密码。
- **M16 本地目录可达性（2026-10-03，0.1.7）**：真机反馈「加连接后回不到本地目录 / 选本地目录还要手写路径 /
  授权全部目录也要手写」。根因三条：① `publishRoot` 远端优先，而 `clearCurrent()` 没有任何 UI 调用
  → 连接页「本地目录」卡片加「改用本地目录（取消当前连接）」；② LOCAL 连接只能手打绝对路径
  → 编辑器给「选择目录（SAF）/ 使用内部存储 / 用已选的目录」；③ 界面上的「本地目录（绝对路径）」绑的是
  basePath，而 :app 读的是地址 host → 本地连接只留一个目录字段、隐藏根路径、校验不再管 basePath，
  并允许 `content://` 树 URI 与绝对路径两种形态。
- **M17 崩溃可见 + 列表排序（2026-10-03，0.1.8）**：真机"一播 MP4 就闪退"但没有 logcat →
  ① `CrashReporter` 抓 Java 崩溃（设备/版本 + 堆栈 + AppLog 环形缓冲最后 400 行，写 `filesDir/crashes/`）；
  ② `ApplicationExitInfo`（反射访问，编译用 android.jar 里没这个方法）在下次启动补抓**原生崩溃/ANR**；
  ③ 设置页「诊断与崩溃日志」卡片可看/复制/清空。另：浏览页新增**排序菜单**（名称/大小/修改时间/类型 + 升降序，
  目录优先、未知值恒最后、DataStore 持久化）。
- **M18 两处闪退兜底（2026-10-03，0.1.9）**：用户报"播 MP4"与"开始生成字幕"都闪退 →
  ① 先排除 native 页对齐（19 个 .so 的 ELF 段与 zip 条目全部 16 KB ✅）；
  ② 发现两处**结构性问题**：字幕服务的 scope 没有 CoroutineExceptionHandler（JNI/ffmpeg/写文件异常会冒到
  线程默认处理器 → 整机闪退）、播放页 setMedia/prepare/play 在 try 之外 →
  ③ 修：字幕服务加异常处理器 + 识别循环整体兜底（异常 → 任务记 ENGINE_UNAVAILABLE + 通知写原因）；
  播放页装载/会话绑定/字幕同步各自兜底（装载失败 → 错误卡片）。
  **根因仍待崩溃日志**（0.1.8 起自带采集）。
- **M19 播放页 UI 改造（2026-10-03，0.1.11）**：用户要求「旋转屏幕 / 字体白色 / 显示同文件夹其他文件并直接跳转」→
  ① `RotateButton` + `ResetOrientationOnLeave`（离开恢复跟随系统；Activity 已 configChanges，转屏不重建、播放不断）；
  ② 抽 `playerChipColors()`/`playerChipBorder()` 统一入口，播放页所有芯片改白字+半透明白底+白描边（原先灰字看不清）；
  ③ `openEpisodeAt(index)` + `PlaylistSheet`：底部面板列出同目录可播文件，点一行复用同一内核直接跳转。
- **M20 字幕闪退根因（2026-10-03，0.1.12）**：用户回传诊断卡截图 → 栈是
  `Service.startForeground → IActivityManager.setServiceForeground → Parcel.readException`，
  崩在 `AsrForegroundService.onCreate`。根因＝服务是 `START_STICKY`，被系统在**后台**拉起后立刻转前台，
  而 `mediaProcessing` 类型**不允许从后台进入前台**（Android 14/15+ 前台服务类型限制）→ 被拒 → 崩。
  修：① `onStartCommand` 改 `START_NOT_STICKY`（队列状态在 Room，下次进 App 显示「已中断，可续跑」）；
  ② `onCreate` 记 `foregroundReady`，失败即 `stopSelf()`；`onStartCommand` 见未前台化直接退场
  （顺带避开"未按时 startForeground"这条崩溃路径）；③ `startService` 返回成败，失败把队列停在「已暂停」。
- **M21 任务页来源跟随连接（2026-10-03，0.1.14）**：真机"从本地切到 SFTP 后任务页还是本地目录" →
  ① **信号发早了**：任务页原来跟 `currentConnectionId`（切换一开始就变），而远端后端此时还没装好，
  它立刻用 `_root.value.backend`（仍是本地）列了目录，之后不会再刷 → 改成由 `publishRoot()` 在**后端换好后**
  统一重算 `_tasksRoot`；② **路径口径错**：旧实现把 `record.basePath` 当要列的目录，而后端路径是相对 basePath 的
  （basePath=/media 时会列 /media/media）→ 统一从后端根 `""` 开始，与浏览页同口径；
  ③ 远端切不过去时来源如实退回本地。判定收敛进纯函数 `tasksRootOf`（:app +4 例）。
- **M22 任务中心"已停止"死胡同（2026-10-03，0.1.15）**：真机截图显示勾了 1 项但「加入队列并开始」是灰的且无说明 →
  根因是 `STOPPED` 的出口全被堵死：`canEnqueue` 要求非 STOPPED、`resume` 对 STOPPED 空转、
  `retry` 不动队列状态、`canResume` 不含 STOPPED —— 而 `cancelAll()` 正是置 STOPPED，于是"点过取消全部就出不来"。
  修：`resume(STOPPED)` 复活、`reviveIfStopped`（retry/enqueue 后 STOPPED→IDLE）、`canEnqueue` 去掉该条件、
  `canResume` 纳入 STOPPED，并新增 `TasksUiState.enqueueHint`（灰按钮在界面上解释原因）。
- **M23 播放页沉浸式全屏（2026-10-03，0.1.16）**：真机横屏截图显示状态栏仍占一条（浅色底在黑色播放页上很突兀）→
  `:app` 的视频路由挂 `ImmersiveWhilePlaying()`：进页面 `hide(systemBars())` +
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，离开 `show(systemBars())` 恢复；只在播放页生效。
  两侧黑边属"4:3 视频在 2.17:1 屏幕上适应显示"的正常现象，不擅自改默认，用「缩放 → 裁剪」可铺满。
- **M24 沉浸式再收一层（2026-10-03，0.1.17）**：用户要求「旋转过后留黑边最好，不要显示任务栏」→
  ① 黑边：默认档位本来就是 `ResizeMode.FIT`（适应屏幕，不裁画面），**无需改动**（想铺满仍需手动切裁切）；
  ② 任务栏：0.1.16 已在播放页 `hide(systemBars())`，本版补 `LifecycleEventEffect(ON_RESUME)` 重隐藏
  （切回桌面/解锁/上滑唤出之后系统会放回栏，只 hide 一次不够）+ **图片查看器同样全屏** +
  `remember` 缓存 insets controller。
- **M25 切换内核闪退（2026-10-03，0.1.18）**：用户报"点右上角内核还会闪退" →
  `performSwitch` 里只包了 `createEngine`，而**新内核接管现场**的整段（`observe` / `videoView()` /
  `applyRestore`（setMedia→prepare→seekTo→倍速→字幕→play）/ `bindSession`）都在兜底之外，
  切 LibVLC 时任何一步抛异常都会冒到默认处理器 → 闪退（与 0.1.9 同源的"异常逃逸路径"）。
  修：整段 try/catch（失败 → 释放半成品 + engine/output 置空 + 归约 `SwitchFailed` 显示中文原因）；
  `SwitchFailed` 补 `playing/buffering = false`；:feature:player-video +1。
- **M26 没捕捉到崩溃日志（2026-10-03，0.1.19）**：用户反馈"点内核闪退但没捕捉到日志" →
  ① `captureLastExit` 放宽口径（只跳过 `REASON_EXIT_SELF`；原生崩溃/被信号杀/低内存/未知都留报告）；
  ② 新增**面包屑**（`:core:common` 的 `Breadcrumbs` + `:app` 装 sink，**同步写盘**、留最近 40 行；
  在应用启动/开始播放/切内核各阶段打点）——原生崩溃时内存日志消失，只有它留下"最后走到哪一步"；
  ③ 诊断卡兜底：`lastExitSummary()`（现读 ApplicationExitInfo）+ `fallbackDiagnosticText(...)`（纯函数），
  没有崩溃报告也能显示「上次进程退出」+ 面包屑并支持复制/分享；:app +3。
- **M27 横屏控制层紧凑化（2026-10-03，0.1.20）**：用户问「左边为什么空」+「播放时控制层占地方太大」→
  ① 答复：左右对称的**适应黑边**（视频 ≈1.65:1 vs 屏幕 ≈2.17:1，FIT 按短边贴合，左右各 ≈15%），
  想铺满用「缩放 → 裁切填充」，默认保持 FIT（用户偏好）；
  ② 改：`Controls` 引入 `compact = 横屏`——顶栏内边距 4dp→0、底栏 8dp→2（左右 12→8）、
  播放圆钮 64dp→48dp、图标 32→26dp、上下集图标 32→26dp + 间距 20→12dp；竖屏不变；自动隐藏不变。
  当前版本 **0.1.20 / versionCode 21**，包名 `io.github.gua123.mediagate`（可覆盖安装 0.1.1–0.1.19）。
- **本机到 GitHub 的通道（2026-10-02 实测）**：系统代理写在 `/etc/profile`（`http://192.168.1.2:10810`），
  非登录 shell 的 `env` 里看不到，所以直连经常超时；给 git/curl 显式带上 `-c http.proxy=…` / `-x …` 即可。
  `api.github.com` 匿名限额会被共享出口 IP 用尽（实测 remaining=0），所以更新源用 raw/Release 资产而不是 API。
- **下一步：真机验收**（本机无 adb 设备）。需真机验证清单与已知限制见 `docs/验收记录-M2-M8.md` 第 4、5 节；
  验收记录共三份：M0 / M1 / M2-M8。
