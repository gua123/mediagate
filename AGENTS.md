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
- **发版纪律（2026-10-03 用户要求）**：「发新版时，如果新版本和旧版本区别不大，就不要发布新版本了，等待下一次新版本一起发布」——
  单个小修复 / 文案调整 / 内部重构**不发版**：改动照常进代码、跑测试、提交，说明写进下一版草稿
  （`docs/release-notes-<下一版>.md`），攒到"新功能 or 用户能明显感知的修复 or 用户点名要的东西"再一起发；
  发版时版本号一次跳到位，仍走匿名复核（两条 raw 清单 + Range 206 + size/sha256 逐字一致）+ `update.json`。
  判断标准一句话：**用户能感觉到区别吗**。**例外（2026-10-03 用户要求）**：「如果有重大错误，在修复后立刻发布一个新版，」——
  崩溃 / 打不开 / 数据丢失 / 核心功能不可用这类**重大错误不受"攒着"约束，修好即发**；
  若 App 已经打不开，除发版外还要**直接把 APK 直链给用户**并说明别走应用内更新（0.1.26 的先例）。

## 2. 方案要点（详见 docs/plan.md）

- 系统：minSdk 33（只保澎湃 OS）/ targetSdk 36 / **compileSdk 37.2**（AGP 9 的 `compileSdk`+`compileSdkMinor`；2026 年的 androidx 强制要求 ≥37）；界面简体中文单语。
- 构建口径：**AGP 9 内置 Kotlin，禁止再声明 `org.jetbrains.kotlin.android`**（会直接构建失败）；Compose 用 `org.jetbrains.kotlin.plugin.compose`。
- 播放（**2026-10-15 定稿：只有一个内核**）：**Media3 ExoPlayer 1.11.1**，硬解优先；统一 `PlayerEngine` 抽象保留（历史包袱轻、也留了后路），
  但 `EngineSelection.availableEngines()` **只含 Media3** ⇒ 播放页不再有「内核」按钮，旧偏好会被 `resolvePreference()` 解析回 Media3，
  降级链为空；三种解码档位都在 Media3 内部实现（`FORCE_SW` 走 `softwareOnly` 筛选）。
  ⚠️ **自研引擎与 LibVLC 都已下线**（用户原话：「画面全没有，所以我想切回原先的引擎，将自研引擎和 libvlc 都去掉吧，原有引擎就可以」——
  自研引擎能切过去但真机不出画面；LibVLC 会把 App 整个带走）。结局与教训见 `docs/方案-播放引擎重写.md` §9.4i；
  代码在 git 历史里（搜 `VlcEngine(` / `NativeEnginePlayer(`），真要复活有据可查。
- 数据层：统一 `StorageBackend` → `RandomAccessSource`；Media3 `BackendDataSource` 实现远端拖拽 seek；.ts 自研索引缓存（时间 ↔ 字节偏移）让大 TS 拖拽毫秒级。
- 字幕：**已整条移除**（2026-10-19 用户要求「将字幕部分删除吧」）：外挂 srt/vtt/ass 的加载/样式/微调/写回、字幕面板与覆盖层、引擎侧字幕轨控制器、:media:subtitle 模块全部删除；音转字幕与批量任务中心更早（0.1.41）已移除。文件类型识别里的 SUBTITLE 类别保留（只用来给“不支持”的提示）。
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
    # 音转字幕（whisper）已于 0.1.41 整条移除，下面这个脚本不再需要，仅作历史保留
    bash scripts/build-whisper-android.sh      # 【已废弃】重建 ASR native 库（不进 git）
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
- **M28 libvlc 崩溃定位（2026-10-03，0.1.21）**：用户回传面包屑 → 「切换内核：→ LibVLC（已释放旧内核）」
  之后再无"新内核已创建"，紧接一次新的"应用启动" ⇒ 进程死在 `createEngine(VLC)` 内（代理或 LibVLC 实例），
  无 Java 异常 ⇒ **原生崩溃**。修/补：① 该段拆细粒度面包屑（起代理 / 代理就绪 / 建实例 / 实例好了）；
  ② 纯函数 `VlcCrashHeuristic.vlcSwitchLooksCrashed`（+5 例）+ `vlcPreviouslyCrashed`/`vlcSuspectCrash`
  → 播放页再切 LibVLC 前弹确认（"仍然切换 / 先不切"，并提示可用「解码」档位换解码方式）；
  ③ 待用户回传 `exit-…` 报告里的 native 轨迹以定位到具体 .so/函数。
- **M29 LibVLC 启动探针（2026-10-03，0.1.22）**：用户建议「启动后先测内核，跑不了就不让切，且测试本身不能崩 App」→
  ① `:media:engine` 的 `VlcProbeService` 跑在**独立进程** `:vlcprobe`（写 `start` → 建 LibVLC+MediaPlayer → 写 `ok`），
  崩了只带走它自己；② `:app` 的 `vlcProbeNow()` 轮询文件判结论（纯函数 `vlcProbeVerdict`，+5 例：
  有 ok=OK、无 ok 且崩溃类=FAILED、被回收=UNKNOWN 不误判）→ 落 DataStore；③ 界面 `vlcUsable==false` 时
  **不让切**，弹说明 + 「重新测试」，并提示用「解码」档位换解码方式；④ 启动即测一次。
  当前版本 **0.1.22 / versionCode 23**，包名 `io.github.gua123.mediagate`（可覆盖安装 0.1.1–0.1.21）。
- **M30 任务队列去重与进度文案（2026-10-03，0.1.23）**：用户任务中心截图暴露两件事——同一文件排了三遍、
  「识别中 0%」看着像卡死 → ① `AsrSelection.plan` 加 `alreadyQueued` 去重（同批内也去重，
  `AsrSelectionResult` 增 `duplicate`）、`AsrQueue.enqueue` 再兜一道（只拦未结束的同路径；
  已成功/已取消/已失败放行——那是故意重跑）、提示写「跳过重复 M 项」；
  ② 正在跑但进度为 0 时显示「准备中（加载模型…）」（small 档 400+ MB，加载期必然 0%）。
  测试：:feature:tasks +2、:media:asr +2。
- **M31 已取消任务删不掉（2026-10-03，0.1.24）**：用户问「任务列表的已取消能不能去掉」→
  根因：`clearFinished()` 只清内存快照，而 `publish()` 只 upsert 变化项、**从不删库** → 重启后 `restore()` 又读回来。
  修：`AsrDao.deleteTasks(ids)` + 纯函数 `AsrQueue.removedIds(before, after)`（+2 例）+ `AsrQueueController.remove(id)`；
  界面终态条目 ✕→**🗑 移除**，「清空已结束（N）」带数量且无可清项时禁用（`finishedCount`/`hasFinished`，+2 例）。
  当前版本 **0.1.24 / versionCode 25**，包名 `io.github.gua123.mediagate`（可覆盖安装 0.1.1–0.1.23）。
- **M32 播放页极简模式 + 横滑调进度 + 预览缩略图（2026-10-03，0.1.25）**：用户要求「做成极简模式，增加不弹出控制也能
  左右滑动调整进度条，并且增加预览缩略图」→ ① `simpleMode`（落 DataStore，只留窄底栏＝进度条 + 播放键 + 「完整」）；
  ② 画面横滑调进度（`detectHorizontalDragGestures` + 纯函数 `SeekGestureMath`，+5 例；不弹控制层，只更新中央 HUD，
  松手才 seek）；③ `previewFrame(path, positionMs)`（默认 null 优雅降级；:app 用 MMR 抽帧缩到 240px、按 5 秒桶 `LruCache(32)`）。
  ⚠️ **0.1.22–0.1.25 四个版本都是坏的**（见 M33），功能本身有效，随 0.1.26 一起可用。
- **M33 P0：更新后打不开（2026-10-03，0.1.26）**：用户反馈「更新后直接打不开了」→ 根因一行代码：0.1.22 加 LibVLC 探针时
  把 `container.vlcProbeNow()` 写在 `container = AppContainer(this)` **之前**，`lateinit` 未初始化 ⇒ `Application.onCreate`
  抛 `UninitializedPropertyAccessException` ⇒ **每次启动即崩**（0.1.22/0.1.23/0.1.24/0.1.25 全部不可用；0.1.21 及以前正常）。
  修：① 自启动动作搬进 `AppContainer` 的 `init { }`；② `Application.onCreate` 不再访问 `container`；
  ③ **只给主进程建容器**（`Application.getProcessName() != packageName` 直接返回——`:vlcprobe` 副进程不该建 Room/DataStore/服务）；
  ④ 源码级回归护栏 `:app` 的 `AppStartupOrderTest`（扫 `onCreate` 里 `container.` 是否出现在赋值之前；已实证旧源码报违规、修复后通过）；
  ⑤ 极简模式开关从主线程 `runBlocking` 读盘改 StateFlow。**交付方式特殊**：App 打不开 ⇒ 只能直接下载 APK 安装（应用内更新用不了）。
  当前版本 **0.1.26 / versionCode 27**（☠️ 仍打不开，见 M35），包名 `io.github.gua123.mediagate`。
- **M36 横屏左侧白条（2026-10-03，0.1.30 → 0.1.31）✅ 真机复验通过**：用户横屏截图最左约 150px 是
  `rgb(252,247,253)`（应用浅色背景）⇒ 根因＝外壳 `Scaffold` 的 `innerPadding` 在横屏把**刘海区 + 手势区**
  也算进去，播放页黑底整体内缩。0.1.31 四层保险：主题 `windowLayoutInDisplayCutoutMode=always`、
  播放/看图路由不吃 `innerPadding`、`Scaffold` 背后垫黑、播放期间 decorView 背景刷黑。
  **用户确认「左侧白条不在了」** ⇒ Plan B 不需要。
- **M50 放后台再回来像卡住（2026-10-03，0.1.46）✅ 真机复验通过**：用户反馈「放入后台再返回时整个软件好像未响应，网络测试/编辑点不开，浏览页无限刷新」。
  两条根因：① 网络操作**可能永远不返回**（链路断掉后阻塞式实现一直等）⇒ 新增 `TimeoutStorageBackend`（元数据 15 s / 单次读 30 s，抛中文超时，界面有重试）；
  ② 分段缓存**残留全局锁**包住整段下载（0.1.37 改按段单飞时没删干净）⇒ 一个段卡住、所有段排队 ⇒ 删掉该锁。
  **用户确认「放后台回来不卡了」**。
- **M51 缓存身份与失效（2026-10-03，0.1.47–0.1.50）**：用户做减法（去预览图 / 去并发 / 去测速），
  并追问「局域网留了缓存，切公网还要重新缓存吗」⇒ 缓存命名空间从**地址**改为**连接**（`"conn:" + record.id`），
  同连接的局域网与公网共用段缓存；过期判定升级为**文件指纹（大小+修改时间+ETag）**，复用同一次 `stat`（零额外请求）；
  缓存上限开放为 **1/2/4/8 GB**（设置页，含已用量与清空缓存）。
  **用户确认「缓存复用现在看视频很快了」** ⇒ 本轮"连接 → 播放"的已知问题全部真机复验通过。
- **M49 移除批量生成字幕（2026-10-03，0.1.41）**：用户原话「将生成字幕的功能去掉吧，手机端的性能不足以支持此功能」→
  删除三个模块 `:media:asr` / `:feature:tasks` / `:feature:asr-model`、底部导航「任务」Tab、设置页「语音识别模型」卡片、
  `AsrForegroundService`（含清单声明与通知）、`:app` 侧全部 ASR 装配（`AppContainer` 的 asr*/tasks* 成员、
  `MediaGateApplication` 的 `AsrRuntimeHost` 实现、`AsrSettings`/`AsrQueueController`/`AsrTasksHost`/`TasksRootSelection`），
  以及 whisper 的 4 个原生库（已核对包内 0 个）⇒ **APK 71.2 MB → 67.1 MB**。
  **保留**：外挂字幕（srt/vtt/ass）全链路；**数据库 asr 表保留不动**（不改 schema = 无迁移风险）。
  测试：全量 1452 例 0 失败（随模块删掉 3 例 TasksRootSelection 测试）。
- **M37 连接测试可读性（2026-10-03，0.1.32–0.1.36）**：① 成功绿/失败红 + ✓/✗ 符号（用户口径原话
  「测试成功使用绿色，失败使用红色」；判定用纯函数 `testOutcomeOf`，不再用 `startsWith` 猜）；
  ② 失败提示能照做：补 3xx/5xx/4xx 分类、UNKNOWN 摊开原始原因；③「技术详情」弹窗（可复制）；
  ④ 探针失败带**请求 URL + 状态行 + 响应片段**并自行跟随重定向；⑤「网络中途断开」独立分类 + 瞬时 IO 重试。
- **M38 缓冲并发化（2026-10-03，0.1.37）**：用户问「缓冲能否多线程」→ 口径＝流式播放同一时刻字节必须按序，
  加速只能来自**预读**与**段内并发取块**；实现＝`chunkRanges`（4 MB 段拆 4×1 MB 并发，`Semaphore(4)`）
  + **按段单飞**（原来全局串行，预读等于白做）+ **预读后 2 段**（:app 传 ioScope）；
  实测单测断言**并发峰值 ≥ 2** 且字节正确。
- **M34 让 App 自己说原因（2026-10-03，0.1.27）**：0.1.26 仍打不开、又没有 adb ⇒ 换打法：① **LibVLC 探针移出启动路径**
  （改按需触发，`AppContainer` 的 init 不再调它——"启动瞬间拉一个会原生崩溃的独立进程"本身就是打不开的一大嫌疑）；
  ② 新增**独立进程的崩溃/启动失败错误页 `CrashActivity`**（清单 `android:process=":crash"`）：未捕获异常与 `Application.onCreate`
  里的失败都把"版本 + 设备 + 异常 + 堆栈 + 面包屑 + 日志"摆到屏幕上，带「复制全部 / 分享」，主进程死掉也不影响显示；
  ③ `CrashReporter` 新增 `showStartupFailure` / `renderStartupFailure` / `renderCrash` / `versionNameOf`；④ `Application.onCreate` 的容器构造加 try/catch。
- **M35 P0 真凶：容器构造期读到未初始化的 StateFlow（2026-10-03，0.1.28）**：用户发来 0.1.27 错误页的文本——
  `阶段：容器构造 / NullPointerException / Attempt to read from field 'a84 ei3.b' on a null object reference in method 'void ph.<init>(…)'`；
  用发版时生成的 **R8 映射表** `app/build/outputs/mapping/release/mapping.txt` 反查：`ph`=AppContainer、`ei3`=ReadonlyStateFlow、
  `a84`=StateFlowImpl ⇒ **容器构造期读了未初始化 StateFlow 的 `.value`** ⇒ 精确定位到 `VideoPlayerHost` 里
  `override val vlcUsable: Boolean? = vlcUsableState.value`（而 `vlcUsableState` 声明在容器第 1018 行、宿主较早构造）
  ⇒ **这就是 0.1.22–0.1.27"打不开"的真因**（0.1.26/0.1.27 两次修补都是必要但不充分）。修：① 改成 getter；
  ② 新增源码级护栏 `StartupPropertyOrderTest`（禁「带类型标注的属性初始化器里读 `.value`」；已实证旧源码报 `AppContainer.kt:662`、修复后通过）。
  **用户真机确认："打开了"**（交付包已用 aapt2 核对 versionCode 29 / 0.1.28 与新文案）。
  当前版本 **0.1.28 / versionCode 29**，包名 `io.github.gua123.mediagate`（0.1.22–0.1.27 为坏版本，可被直接覆盖安装）。
- **本机到 GitHub 的通道（2026-10-02 实测）**：系统代理写在 `/etc/profile`（`http://192.168.1.2:10810`），
  非登录 shell 的 `env` 里看不到，所以直连经常超时；给 git/curl 显式带上 `-c http.proxy=…` / `-x …` 即可。
  `api.github.com` 匿名限额会被共享出口 IP 用尽（实测 remaining=0），所以更新源用 raw/Release 资产而不是 API。
- **下一步：真机验收**（本机无 adb 设备）。需真机验证清单与已知限制见 `docs/验收记录-M2-M8.md` 第 4、5 节；
  验收记录共三份：M0 / M1 / M2-M8。
- **新：网络加载 P1 完成（2026-10-15，用户「继续下一步，直到做完整个计划再回复我」）**：
  ① **P1.6 滑窗预取**——播放页每个采样点把位置报给宿主（新契约 `VideoPlayerEnvironment.prefetchAhead(path, positionMs, durationMs)`，默认空实现），
  宿主按**纯函数** `PrefetchWindow.decide` 决定发不发：**位置至少前进 20 秒**才发一次、抓**当前位置往后 30 秒**那一段、
  距末尾不足 10 秒不发、**大小未知不动**（与 openPlan 同一口径）；换文件重置节流。
  ② **P1.7 下一集预热**——播到 **80%** 且列表里确实有下一集时（顺序＝**浏览页当前排序**，与「下一集」按钮同一口径），
  只抓下一集的**头尾两段**，**每个文件只预热一次**，失败只记日志；连播切集不必再等第一个往返。
  ③ **P1.5「先拿索引再按索引取」有意止步**：我们没有自己的容器解析器（moov/cues 归 Media3），这一层等价于"按位置换算偏移"
  （续播/拖拽/滑窗落点都是这么算的），再往前要自己写 MP4/MKV 解析器——**只为一个往返的收益不抵成本**，先不做。
  ④ **P0 剩余「请求优先级显式化」也有意不做**：分段缓存已是**按段单飞**（播放与预取同一段是同一个 in-flight 任务，
  不会重复下载、播放不排在预取后面），再加一套优先级队列属于"为同一个保证写第二份实现"；改为用测试钉住这条保证
  （新增：固定缓存不被淘汰、取消固定后能被淘汰）。
  ⑤ **P2 作废**（native 直读缓存 / mmap / 首帧优先）：随自研引擎一起下线，我们没有解码渲染那一段了；文档保留作历史决策。
  测试：`PrefetchWindowTest` 6 例（节流 / 落点换算 / 快播完不发 / 未知不发 / 时长未知 / 下一集只预热一次）
  ＋ `SegmentedCacheBackendTest` 新增 2 例（固定保护 / 取消固定后可淘汰）。文档：`docs/方案-网络加载增强.md` 的 P1 落地结论表 + P2 作废说明。
- **改：缓存相关入口收到一页（2026-10-15 用户要求「将添加文件夹进队列和清理缓存界面放到一块」）**：
  ① 缓存明细页头部新增「**添加文件夹到队列**」→ 进入新的**挑文件夹页** `CacheFolderPickerRoutes`（:app 的 `CacheFolderPickerScreen`）：
  只列目录、点进去看下一层、每行右侧「加入队列」＝把那个文件夹里的视频（含子目录）排队，另有「把当前这个文件夹加进队列」；
  统计与确认复用与浏览页长按同一套（`collectCacheTargets` + `CacheEnqueueDialog`）⇒ 两条入口**同一套口径**，不会漂移；
  ② 浏览页长按**保留**（"正好在看着这个文件夹"的场景）——两条入口分工：长按＝就地，缓存页＝集中管理；
  ③ 护栏 `CacheEntryWiringTest`：钉住"缓存明细页的入口真的接上导航""挑选页注册在导航表里""浏览页长按仍能入队 + 文件夹仍走递归统计"
  （**已实证能红**：删掉缓存明细页那行导航接线 → 测试失败；这类"参数加了/按钮画了但没接上"编译器管不了）。
- **新：预约缓存（手动批量缓存，2026-10-15 用户要求「增加手动批量多个文件的缓存方式，可以让我不打开对应文件夹就可以缓存视频」）**：
  ① **入口＝浏览页长按一行**：文件 → 缓存这个视频；**文件夹 → 递归统计里面的视频（含子目录，上限 300 个）** →
  确认框「将缓存 N 个视频」→「加入队列」——**不用进那个文件夹**（:feature:browser 加 `onLongPressEntry`；
  :app 的 `collectCacheTargets` 做递归枚举，只收视频，超限如实提示）；
  ② **缓存队列** `CacheQueueController`（:app）：单并发、可暂停/继续/逐条取消/清掉已结束，
  **播放时自动让路**（`playbackActive` 为真就不开新段，每 2 秒看一眼；让路期间暂停会立刻收工）；
  ③ **手动缓存的文件被固定**（`SegmentedCacheBackend.setPinned` + 目录里的 `.pin` 边车）：
  淘汰分三轮——① 跳过"正在读 + 固定"，② 跳过"固定"，③ **只剩固定缓存时不再淘汰**（只记日志）——
  **手动点的东西被悄悄清掉比占空间更糟**；④ 缓存明细页新增「缓存队列」区（进行中/排队/已完成/失败 + 进度）与「手动缓存」标记 + 取消固定。
  ⚠️ **已知边界（v1）**：队列在内存里，**杀进程后队列丢失**（已下好的段与固定标记都还在，重新长按即可续）；
  没有前台服务通知，App 被系统回收时下载会停。测试：`CacheQueueControllerTest` 9 例（顺序 / 去重 / 失败继续 / 让路 / 暂停继续 / 取消 / 已整份跳过 / 清结束 / 进度文案），全量 **1599 用例 0 失败**。
- **新：缓存明细页（2026-10-15 用户要求「关于缓存部分，是否可以增加一个可视化界面，告诉我哪些缓存过，哪些没有」）**：
  ① 缓存目录名是 \`namespace|path\` 的哈希 ⇒ **加了 \`.path\` 边车**把原始路径落盘（老缓存没有它 → 界面如实显示「旧版本缓存，名称未知」，
  但照样能删）；② \`SegmentedCacheBackend.cachedFiles()\` 列出每个已缓存文件（显示名 / 已缓存字节 / 段数 / 覆盖率），
  \`removeCached(entry)\` 按**目录名**删（名称未知也能删）；③ :app 新增「缓存明细」页（设置页 → 网络与缓存 → 「查看缓存明细」）：
  头部合计 + 上限、逐条「已缓存 8.0 MB · 2 段 · 占该文件 8%」+ 路径 + 单条删除、清空全部；文案是**纯函数**（\`CacheDetailText\`，5 条单测），
  总大小未知时**不显示百分比**（不许瞎算）。全量 **1590 用例 0 失败**。
- **修：通知栏一直没有媒体通知（2026-10-15 用户反馈「现在点开有封面的视频快了很多，这很好，但是通知栏还是没有」）**：
  两条链路一起修——① **点视频时漏申请通知权限**（音频那条路一直在申请，视频没有；Android 13+ 没有 POST_NOTIFICATIONS
  就**什么通知都不显示**，媒体通知也一样）⇒ 视频点击分支补 `ensureNotificationPermission()`；
  ② **让 App 自己说原因**：`VideoSessionController.diagnostic()` 输出「视频会话：控制器 已连接/连接失败:原因 · 会话源 有/无 · 通知权限 已授予/未授予」，
  接进设置页诊断卡（与引擎自述同屏），连接成功/失败各打一条面包屑——通知栏不出现有三条互相独立的链路（权限/控制器/会话源），
  没有 adb 时这一行截图就能定位是哪一条断的。
- **用户拍板：自研引擎与 LibVLC 双双下线，只保留原有引擎（2026-10-15）**：用户原话「**画面全没有，所以我想切回原先的引擎，
  将自研引擎和 libvlc 都去掉吧，原有引擎就可以。**」⇒ `EngineSelection.availableEngines()` 只含 Media3（"有几个内核可用"的唯一判据）、
  `canSwitchEngine()` 为 false ⇒ 播放页**不再有「内核」按钮**；`resolvePreference()` 把旧偏好解析回 Media3；`fallbackFrom()` 恒 null
  （不再有"改用 X 续播"）；三种解码档位都在 Media3 内部实现（强制软解走 softwareOnly 筛选）；`createEngine` 对已下线内核**回落**而不是抛异常；
  VLC / 自研引擎的构造代码已删（复活看 git 历史），自研引擎探针不再启动时跑。结局与两个坑见 `docs/方案-播放引擎重写.md` §9.4i，
  计划口径同步 `docs/plan.md` §4.6 的变更注记。全量 **1580 用例 0 失败**（三内核时代的 7 条轮转/降级用例改写成单内核契约）。
- **真机确认：自研引擎切过去了（2026-10-15 用户截图）**：播放页右上角芯片显示「内核：自研引擎」⇒ 会话 action 那个修复生效，
  自研引擎第一次真正跑在用户机器上。同图还暴露一个交互坑：**点内核轮到 LibVLC 会弹出吓人的「上次切到 LibVLC 时 App 直接退出了」确认框**，
  因为探针改成按需跑之后 vlcUsable 常态是「未知」，而旧口径「未知也放」把它留在了轮转里 ⇒ 收紧为**只有探针确认能用（vlcUsable == true）才进轮转**，
  默认只在 Media3 ⇄ 自研引擎之间轮转（护栏：`VideoPlayerUiStateTest` 三条：两档轮转 / 确认后回到三档 / 未知与不可用都跳过）。
- **真机确认：加载变快 + 亮度音量正常（2026-10-15）**：用户原话「**刚刚测试下来，新文件加载明显变快了**，亮度和音量的调整也都正常了」⇒
  网络加载 P0 的「更早开始取」（打开即预取首段+尾段+续播落点 / 打开文件夹自动缓存 / 列表封面空闲预热）**验收通过**；
  亮度（落窗口）与音量（0–100% 内核 + >100% 系统音效链）分层实现在真机正常。结论写进 `docs/方案-网络加载增强.md` 的 P0 真机结论一节。
- **真机事故：切自研引擎切不过去（2026-10-15，0.1.66 → 0.1.67 修复）**：用户点内核轮到自研引擎，画面立刻退回 Media3；
  面包屑指出 `Failed to resolve SessionToken for …VideoPlaybackService`。两条根因：① **视频服务在清单里缺 Media3 的
  会话 action**（音频那个从 M1 起就有，视频这个从 M8-A 起就没有 ⇒ **视频通知栏/锁屏控制其实一直没连上**，起播那条路把异常吞了）；
  ② **换内核那条路没兜住这个附属失败** ⇒ 自研引擎被判成「播不了」⇒ 触发自动回退。
  修：清单补 action（exported 仍 false）+ 会话兜底收进 `bindSession` 一个函数（起播/换集/换内核全覆盖，失败只记日志与面包屑）。
  护栏：`:app` 的 `SessionServiceDeclarationTest`（源码里的 SessionToken 调用点 ↔ 清单交叉核）+ 播放页一条"会话绑不上也要换内核成功"；
  并修掉**护栏假绿两连**（Gradle 因清单不是测试输入而跳过测试 ⇒ 已声明 `src/main/java`、`src/main/res`、清单为 Test 输入；
  护栏最初按服务名抓清单块抓到注释里的名字 ⇒ 改按 `android:name` 属性锚定）。全量 **1581 用例 0 失败**。
- **R21 设置页信息架构（2026-10-15）**：用户要求「优化一下设置页面的信息排列等，加入到计划中」→
  设置页从"卡片按时间顺序追加"改成 **6 组按使用频率排**：连接 → 播放与后台 → 网络与缓存 → 更新 → 安全与凭据 → **诊断与日志**；
  每组一行小标题 + 一句说明、整行可点折叠（**只有诊断组默认折叠**）；4 条只读说明**贴回它解释的那一组**
  （不再单开「网络切换策略」卡）；没内容的组不渲染空卡片。实现＝`:feature:settings` 的 `SettingsLayout.kt`
  （`SettingsGroup` 枚举：**声明顺序即页面顺序** + `settingsGroupOrder()` 纯函数 + `SettingsSections` 具名插槽 +
  `SettingsNote.group` 归属），护栏 `SettingsLayoutTest`（顺序写死 / 只有诊断折叠 / 标题说明不重复 / 空组不渲染 /
  **源码级**：不许回到 `extraSections` 老写法、每组分支里必须有真内容——三条都实证能红）。
- **自研引擎（2026-10-06 起，当前最大在办事项）**：M1 骨架 ✅ → M2 出画面 ✅ →
  **M3 连续播放 ✅（视频帧循环 `a3dc53e` + 音频与音画同步）** → M4 接进 `PlayerEngine` 契约（播放页真正用它）→ M5 覆盖打满。
  · 纯逻辑（pts 换算 / 时间轴归一 / 同步判据 / 音频时钟 / PCM 环形缓冲 / AVIO seek 换算）抽在 `av_sync.h`，
  用 `bash scripts/test-engine-native-host.sh` 在开发机直跑（104 项断言，含真并发用例），并被 `gradlew test` 自动带上；
  · ⚠️ 改了 `media/engine-native/src/{main,test}/cpp` 下的文件就会触发这些测试（已在 build.gradle.kts 里声明成 Test 输入）；
  · **M3 已完整**：视频帧循环 + 音频/音画同步 + **接我们的数据层**（`NativeMediaSource` / `RandomAccessMediaSource`：
    FFmpeg 的读/seek 经 JNI 回调进 Kotlin，落回 `StorageBackend → RandomAccessSource` ⇒ 本地/SAF/WebDAV/SFTP/FTP 对引擎是同一条路，
    分段缓存与超时层也都在链路上）；`JNI_OnLoad` 存 `JavaVM`，native 播放线程按需 Attach/Detach；
  · **M4 上半 ✅**：`NativeEnginePlayer` 实现 `PlayerEngine` 契约（只做翻译，播放逻辑仍在 C++）：数据层直给、
    自己的 `SurfaceView`（数据源与画面都到齐才起播）、状态串→`EngineState` 轮询、位置/时长走便宜入口；
    契约新增 **`EngineCapabilities` 能力矩阵**（自研引擎如实声明：倍速 ✗ / 解码档位 ✗ / 缩放四档 ✓ / 音量 >100% ✓，
    旧内核默认全支持 ⇒ 不变行为）；缩放四档由 native 纯函数 `scaleTarget` 算；`EngineKind.NATIVE` 已进 `createEngine`；
  · **M4 下半 ✅**：播放页「内核」按钮改成**三档轮转**（Media3 → 自研引擎 → LibVLC → Media3，LibVLC 跑不了就跳过），
    切前弹一句能力说明（暂无双倍速/硬解档位，倍速会如实回到 1.0×），不支持的档位不显示芯片但把原因写在芯片行；
    能力表收归契约层 `EngineCatalog.capabilitiesOf(kind)`（**只此一份**，界面能在切换前查）；
  · ⚠️ **两个坑已记档**：① 打开完成前的 seek 会被丢掉（切内核/续播都是 prepare→seekTo 的顺序）⇒ 现在会补；
    ② **R8 会把 JNI 按名字找的 `read`/`length`/`lastError` 改名** ⇒ 只在 release 包炸（模块已带 `consumer-rules.pro` keep 规则 + 源码护栏）；
  · **M4 收尾 ✅**（保护性三件）：① **独立进程探针** `:engineprobe`（`nativeSelfCheck` 真开一次 H264/AAC 解码器 + swscale/swresample + **AAudio 开流**；崩了只带走探针，主进程据此拦住切换并给「重新测试」）；
    ② **打开失败自动回退**：自研引擎播不了 ⇒ 自动换回 Media3 继续 + 一句说明（每个文件只做一次）；
    ③ **诊断可见**：诊断卡显示「自研引擎探针：通过（FFmpeg 版本 / 解码 h264,aac / 音频 48000 Hz）」或没通过的原因；
  · **播放指标可见 ✅**（网络加载方案 P0.4）：native 量**起播耗时**（第一帧送屏结算）与 **seek 等待**，
    契约加 `PlayerEngine.metricsLine()`（默认 null 不改旧内核），播放页每个文件上报一次，设置页诊断卡显示最近 5 条
    （"起播 380 ms · seek 等待 120 ms · 丢帧 0 · 音频欠载 0 · 音画偏差 -12 ms"）；
  · **结局（2026-10-15）**：修好会话 action 后用户真机把内核轮到自研引擎——**芯片显示对了，但画面全没有** ⇒ 用户拍板「**画面全没有，
    所以我想切回原先的引擎，将自研引擎和 libvlc 都去掉吧，原有引擎就可以**」⇒ 两个内核一起下线，只保留 Media3（见上面那条）。
    **这条线到此收束**：M1–M4 的成果留在代码与文档里（§9 全节 + git 历史），不再继续 M5；
    自研引擎的教训（真机不出画面这一环在开发机上无从验证）值得记住：**"能在开发机验证的部分"做得再全，也替代不了端到端那一环**。
  · 全量单测 **1580 例 0 失败（31 模块）**。
- **字幕整条移除（2026-10-19，用户要求「将字幕部分删除吧,删除后直接发新版本」）**：删除 :media:subtitle 模块；播放页去掉字幕面板 / 覆盖层 / 字幕芯片与 subtitleCue 热流；契约层去掉 subtitles 宿主与字幕偏好（开关/样式/偏移）；撤销引擎侧字幕轨控制器（PlayerEngine.subtitles / ExoPlayerEngine 外挂轨装配 / VlcEngine slave 与 setSpuDelay / 切内核的快照与恢复）；删掉对应单测（字幕流程、引擎字幕控制器）并清理 strings 里 35 条字幕文案。**边界**：文件类型识别里的 SUBTITLE 类别保留（只给“不支持”提示）；数据库 schema 未动。全量 **1455 用例 0 失败**（比删除前少 154 例，全是字幕相关）。预览版 0.1.73 / versionCode 74。
- **预约缓存加前台服务 + 已下线引擎全部清理（2026-10-19，用户要求「仍然待定的现在就做：1.需要加前台服务 2.全部清理掉」）**：
  ① **前台服务** CacheDownloadService（dataSync 类型、START_NOT_STICKY、startForeground 失败即 stopSelf）：
  通知栏显示「第 n/N 个 · pct% · 文件名」+ 暂停/继续，队列空了自动退场；队列**持久化**到 DataStore（只存待处理项），
  启动时 restoreCacheQueue() 恢复，杀进程后能续下（已下好的段与 .pin 固定都还在）。新增 CacheQueueNotification（纯函数文案）
  与 CacheQueueCodec（序列化），各有单测。 ② **清理**：删除 :media/engine-native（含 FFmpeg 原生库）、:media/proxy（回环代理，
  MediaUriCodec 搬进 :media:engine）、VlcEngine / VlcProbeService / NativeEngineProbeService、探针结论判定与测试（VlcProbeVerdict /
  EngineProbeVerdict / VlcCrashHeuristic）、AppContainer 里的探针与代理装配、RootSettings 的探针键、清单里的两个探针服务；
  EngineKind 保留历史枚举值（解析旧偏好用），播放页里不可达的引擎/探针分支暂留（编译依赖它，属纯代码瘦身）。
  ③ 启动护栏 AppStartupOrderTest 改成盯「AppContainer 自己恢复队列」+「已下线探针不许回来」（已实证能红）。
  证据：全量 **1364 用例 0 失败**（较 0.1.73 少 91 例，全部是随模块/探针删除的测试）；release 包 0.1.74 / versionCode 75，**APK 87 MB → 24 MB**（12 个原生库全部 16 KB 对齐）。
- **预约缓存改为只温开头（2026-10-20，用户要求「下载缓存的功能，需要下载的是未观看过的视频文件，加载开头一段时间的缓存，而不是缓存该文件的全部，以提高初次打开时的观看体验」）**：
  新增纯函数 CacheHeadPlan（目标＝min(文件大小, 12 MB)，小文件整份；大小未知时目标固定 12 MB）；CacheQueueController 只下到目标字节即完成，
  **不再逐个固定**（开头缓存按普通 LRU 管理）；CacheTask 新增 targetBytes，进度/通知/队列页都改为相对「要缓存的那一段」；队列页加一句说明。
  测试：CacheHeadPlanTest 4 例 ＋ 队列「大文件只下开头」1 例，全量 **1369 用例 0 失败**。release 包 0.1.75 / versionCode 76。
- **网络加载提速：块级可见 + 并行预读（2026-10-20，用户反馈「nPlayer 加载快一倍、已看过的部分回拖不卡」）**：
  ① SegmentedCacheBackend 的段下载改成**边下边读**（SegmentJob + onBytes/awaitBytes：写一块、读者就能读一块，
  下完再原子改名），拖动落点只等 64 KiB 而不是整段 4 MB；② 预读 2→4 段、CachedStream 常驻 RandomAccessFile；
  ③ 滑窗预取一次 4 段（PrefetchWindow.AHEAD_SEGMENTS）且 prefetchParallel 2 连接并发，落点预取同样并发。
  测试：新增「整段还没下完就能读到已到达字节」用例；全量 **1370 用例 0 失败**。release 包 0.1.76 / versionCode 77。
- **播放缓冲时间 / 网络统计 / 缓存关闭与磁盘余量（2026-10-20，0.1.77；用户「将后续都做完」）**：
  ① 新增 PlaybackBufferPolicy（纯函数：3/5/10/20/30 秒、默认 5 秒）→ ExoPlayerEngine 用 DefaultLoadControl 的
  setBufferDurationsMs（最小=目标一半、最大=目标 8 倍且 60–300 秒、起播/恢复=目标）；偏好存 buffer_seconds，设置页「播放」区块可改。
  ② 网络统计：:media:engine 的 NetworkStatsProvider 接口 + :media:playback 的 NetworkMeter（字节/阻塞耗时），
  BackendDataSource 在 open/read 计数，ExoPlayerEngine.metricsLine() 输出「网络 X MB · Y s（Z MB/s）」，进诊断卡。
  ③ 缓存上限补 0（关闭）/128 MB/256 MB 档；CacheBudget 纯函数按磁盘剩余留 25%（不足一个段则关闭），
  AppContainer 建远端缓存前用 StatFs 计算，0 时不套缓存层。测试：PlaybackBufferPolicyTest / NetworkMeterTest / CacheBudgetTest，
  全量 **1381 用例 0 失败**。release 包 0.1.77 / versionCode 78。
- **公网返回上一级一直转圈（2026-10-24，0.1.78；用户提问「为什么在我使用公网的时候，会出现返回上一个文件夹一直转圈的情况？」）**：
  根因＝TimeoutStorageBackend 的超时只包在协程层，**协程取消打断不了阻塞式网络调用**——实测「2000 ms 阻塞调用套 100 ms 超时」
  实际 2059 ms 才返回；公网下请求卡在 DNS/TLS/服务端不响应时，界面就永远停在 LOADING。
  修：阻塞调用放进独立作用域（CoroutineScope(SupervisorJob() + Dispatchers.IO)），调用方用 withTimeout { worker.await() }
  **到点必返回**（元数据 15 s / 单次读 30 s），超时抛中文 StorageException.Timeout；被放弃的调用由底层 socket 超时收尾（无法强杀线程）。
  新增 BrowserErrorKind.TIMEOUT + 界面「网络超时：15 秒没有响应」+ 重试与换网提示；测试：阻塞假后端两例（列目录/读取必须 <1.2 s 返回，
  而底层睡 2 s）＋ 浏览器超时归类一例；全量 **1384 用例 0 失败**。release 包 0.1.78 / versionCode 79。
