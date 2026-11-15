# LibVLC for Android：集成、缓存参数与硬解说明

> 整理日期：2026-11-12 ｜ 适用：libvlc-all 3.7.x（稳定线）、VLC 4.0.0-eap（预览线）
> 证据口径：官方仓库源码 + Maven 制品实测（本机核验 3.7.6 AAR 的 javap/字节码）+ 官方 Wiki 参数页 + mediagate 0.1.21–0.1.83 的历史记录。
> 本文只做资料整理与判断，不改 mediagate 代码。文中「实测」指 2026-11-12 本次核对；「本项目实测」指 mediagate 旧版本记录。

---

## 0. 速查

| 项 | 结论 |
| --- | --- |
| 依赖坐标 | org.videolan.android:libvlc-all:3.7.7（Maven Central，最新稳定） |
| 体积 | 3.7.6 AAR 89 MB；解包后 jni 共 201 MB（4 个 ABI）；arm64 三件套：libvlc.so 46.0 MB、libc++_shared.so 9.3 MB、libvlcjni.so 92 KB |
| 硬解开关 | Media.setHWDecoderEnabled(true/false, force)。3.x 下分别落地为 **:codec=mediacodec_ndk,all** 与 **:codec=all** |
| 缓存 | network-caching / file-caching 默认 1000 ms；live / disc 默认 300 ms；范围 0–60000 ms。本质是 PTS 延迟（预缓冲），不是磁盘缓存 |
| 最常见的误解 | --avcodec-hw 在 Android 上不是 MediaCodec 的开关；它选的是 VAAPI / DXVA2 这类 hw decoder 模块 |
| 资料可达性 | 官方 GitLab 匿名访问被要求登录；doxygen 旧链接 404；Wiki 参数页可用；Maven 可查 |

---

## 1. 官方资料地图（2026-11-12 可达性核验）

| 资料 | 地址 | 实测 | 结论 / 替代 |
| --- | --- | --- | --- |
| Android 官方仓库（原址） | https://code.videolan.org/videolan/libvlc-android | HTTP 200，但页面是 GitLab 登录页；GitLab API 查该 project 返回 404 | 匿名打不开；改读 GitHub 镜像 |
| Android 仓库（GitHub） | https://github.com/videolan/vlc-android | HTTP 200；最近提交 2026-11-09；默认分支 master；可见分支含 3.0.x…3.5.x、4.0、master | 代码、分支、README 均可匿名读 |
| VLC 核心 | https://github.com/videolan/vlc | HTTP 200 | 参数定义、缓存与 MediaCodec 实现都在这 |
| Maven 制品 | https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/ | HTTP 200；版本 3.4.0 → 3.7.7，另有 4.0.0-eap29 | 3.7.7 为最新稳定 |
| Doxygen 旧链接 | https://www.videolan.org/developers/vlc/doc/doxygen/html/group__libvlc.html | **404** | 整站只有 index 能开（且标注 4.0.0-dev），子页面基本全 404 |
| Wiki 命令行参数 | https://wiki.videolan.org/VLC_command-line_help/ | HTTP 200，73 KB | 可用；取值示例是桌面构建（含 d3d11va / mmdevice） |
| Wiki 内存管理 | https://wiki.videolan.org/LibVLC_Memory_Management/ | HTTP 200 | 可用 |
| Wiki 媒体列表管理 | https://wiki.videolan.org/LibVLC_Media_List_Management/ | HTTP 200 | 可用 |
| Android 编译 | https://wiki.videolan.org/AndroidCompile/ | HTTP 200 | 讲从源码编译 VLC，不是 AAR 接入 |
| Android Studio 示例页 | https://wiki.videolan.org/LibVLC_SampleCode_AndroidStudio/ | **404** | 已不存在 |
| 官方示例仓库 | https://code.videolan.org/videolan/libvlc-android-samples | 机器人校验 / 需登录；GitLab API 匿名检索不到该命名空间下的项目 | 以 vlc-android 应用源码 + AAR 内 API 为样例 |

### 1.1 三个「官方入口」的现状

1. **code.videolan.org** 仍是 VideoLAN 的官方开发站，但 2026-11-12 匿名访问会被要求登录（页面 200 但标题是 「Sign in · GitLab」），API 也查不到项目。
2. **github.com/videolan/vlc-android** 是 VideoLAN 组织名下的公开仓库，仍在活跃提交（2026-11-09），Android 代码、README、分支都能直接读，是当前实际可用的代码入口。
3. **github.com/videolan/vlc** 对应 VLC 核心；本文用 3.0.x 分支核对参数定义与 MediaCodec 实现（3.7.6 的 libvlc.so 版本串显示核心为 3.0.24-beta1）。

### 1.2 文档现状

- 你给的 Doxygen 链接（group__libvlc.html）已 404；本次还试了 libvlc.html、libvlc_8h.html、annotated.html、files.html、modules.html，全部 404。官方 API 文档目前等于「只有首页」。
- Wiki 的「命令行参数」页是唯一还能用的完整参数清单，但它按桌面构建生成：avcodec-hw 的取值写成 {any,d3d11va,dxva2,none}，Android 的 MediaCodec 并不在这个列表里（见第 5 节）。
- 示例代码页 404、示例仓库需登录；可靠样例是 vlc-android 应用本身的源码（application 模块）与 AAR 里的 Java API。

### 1.3 取参数的可靠顺序

1. **Maven AAR 的 Java API / 字节码**：版本与默认行为最准（本文用本机缓存的 3.7.6 AAR 核对）。
2. **对应分支/tag 的源码**：参数定义、默认值、读取点。
3. **Wiki 参数页**：补齐文字含义，但取值要按平台校正。
4. 社区文章只作线索，不作依据。

---

## 2. 版本与制品

### 2.1 Maven 版本线

- 3.x 序列：3.4.0–3.4.9、3.5.1、3.6.0–3.6.5、3.7.0–**3.7.7**。
- 另有 **4.0.0-eap29**（预览线，Maven metadata 的 latest/release 都指向它，2026-10-01 发布）。
- 3.7.7 的 AAR：92,576,688 字节（约 88.3 MiB），Last-Modified 2026-10-01 11:55:59 GMT。
- 4.0.0-eap29 的 AAR：108,942,111 字节。
- Maven 上只有 libvlc-all、medialibrary-all、remote-access 三个 artifact，**没有按 ABI 拆分的 artifact**；要减体积只能靠 Gradle 的 abiFilters。

### 2.2 AAR 解剖（3.7.6 实测）

| 项 | 数值 |
| --- | --- |
| AAR 大小 | 89 MB |
| 解包后 jni 合计 | 201 MB |
| 每 ABI 的 jni | armeabi-v7a 39 MB、x86 49 MB、arm64-v8a 53 MB、x86_64 61 MB |
| .so 数量 | 12 个（4 ABI × 3 个） |
| arm64 三件套 | libvlc.so 46,014,480 B；libc++_shared.so 9,290,184 B；libvlcjni.so 94,440 B |
| 资源 | assets/lua（Lua 脚本：meta 解析等）、assets/hrtfs（3D 音频 HRTF） |
| Manifest | package=org.videolan，minSdkVersion=17 |
| 16 KB 页对齐 | 实测 libvlc.so 的 LOAD 段 Align = 0x4000（符合 Android 15+ 的 16 KB page size 要求） |
| 核心版本串 | 3.0.24-beta1 |

> 本项目的旧数据：0.1.76 前后的 release APK 约 74 MB，native 占 64 MB，其中 libvlc.so 46 MB；包内 19 个 .so 全部 16 KB 对齐（见 docs/验收记录-M2-M8.md）。0.1.77 把 LibVLC 与自研引擎一起下线后，APK 从 87 MB 降到约 24 MB（其中自研引擎还带走两份 FFmpeg 库，不能全算在 LibVLC 头上）。

### 2.3 3.7.6 的 Java API（javap 实测）

    org.videolan.libvlc.LibVLC
      LibVLC(Context, List<String>) / LibVLC(Context)
      static version() / majorVersion() / compiler() / changeset()
      setUserAgent(name, http)

    org.videolan.libvlc.Media implements IMedia
      Media(ILibVLC, Uri) / Media(ILibVLC, String) / Media(ILibVLC, FileDescriptor)
      addOption(String) / setHWDecoderEnabled(boolean, boolean)
      setDefaultMediaPlayerOptions() / setEventListener(IMedia.EventListener)

    org.videolan.libvlc.MediaPlayer
      MediaPlayer(ILibVLC) / MediaPlayer(IMedia)
      getVLCVout() / setMedia(IMedia) / play() / pause() / stop()
      setVideoScale(ScaleType) / getCurrentVideoTrack()
      setEventListener(MediaPlayer.EventListener)

    org.videolan.libvlc.interfaces
      ILibVLC / IMedia / IVLCVout / IMedia.EventListener

    org.videolan.libvlc.util
      VLCVideoLayout（FrameLayout，可交给 MediaPlayer.attachViews 管理）
      DisplayManager / MediaBrowser / HWDecoderUtil

要点：

- 3.7.x 把接口抽到了 **org.videolan.libvlc.interfaces** 包（ILibVLC / IMedia / IVLCVout），实现类仍在 org.videolan.libvlc。
- 视频输出有两条路：低层 IVLCVout.setVideoView(SurfaceView/TextureView) + attachViews()；或 MediaPlayer.attachViews(VLCVideoLayout, DisplayManager, boolean, boolean)。
- MediaPlayer.Event 常量：MediaChanged、Opening、Buffering、Playing、Paused、Stopped、EndReached、EncounteredError、TimeChanged、PositionChanged、SeekableChanged、PausableChanged、LengthChanged、Vout、ESAdded、ESDeleted、ESSelected、RecordChanged。

### 2.4 VLC 4.0 预览线

- 4.0.0-eap29 仍在 Maven 发布，AAR 约 109 MB；但它是 EAP，不是稳定版。
- 3.7.6 的字节码里已经按 LibVLC.majorVersion() 分支：major 不是 3 时，setHWDecoderEnabled(false, …) 改为加 **:no-hw-dec**（见 5.2）。
- 4.0 的 API/默认值本次未完整核对（下载 109 MB 制品太慢），不要把它当「3.7.7 的平替」直接上生产。

---

## 3. Android 集成

### 3.1 依赖与打包

    dependencies {
        implementation("org.videolan.android:libvlc-all:3.7.7")
    }

    android {
        defaultConfig {
            ndk { abiFilters += listOf("arm64-v8a") }   // 自用 sideload，只打 64 位
        }
    }

- AAR 自带 4 个 ABI，不裁剪就是 200 MB 级别的 native；只留 arm64 后约 55 MB（libvlc.so 46 MB + libc++_shared 9.3 MB）。
- AAR 没有 consumer proguard 文件。JNI 侧按类名/方法名找 Java 类，release 构建建议自行加 keep 规则（本项目旧版能跑，但不能假设 R8 永远不误删）。
- 3.7.6 的 arm64 libvlc.so 已满足 16 KB 对齐；升级版本后要复验（readelf -l 看 LOAD 的 Align）。

### 3.2 最小可播代码（3.7.x）

    // 1) 引擎实例：选项是实例级的，重资源，建议一个播放页一个
    val libVlc = LibVLC(context, arrayListOf(
        "--no-video-title-show",
        "--network-caching=1500",
        "--file-caching=1500",
    ))

    // 2) 播放器 + 媒体
    val player = MediaPlayer(libVlc)
    val media = Media(libVlc, Uri.parse(url)).apply {
        addOption(":network-caching=1500")      // 媒体级覆盖（可选）
        setHWDecoderEnabled(true, false)        // 3.x → :codec=mediacodec_ndk,all
    }
    player.setEventListener { event -> /* 见 3.4 */ }
    player.media = media                        // setMedia(IMedia)
    media.release()                             // MediaPlayer 已持引用

    // 3) 视频输出
    val vout = player.vlcVout
    vout.setVideoView(surfaceView)              // SurfaceView 或 TextureView
    vout.attachViews()

    // 4) 播放与释放
    player.play()
    // ...
    player.setEventListener(null)
    player.detachViews()
    player.release()
    libVlc.release()

补充：

- 3.7.6 的 LibVLC 构造器会自动补两个默认项（除非调用方已设置）：--aout=opensles 或 --aout=android_audiotrack；--android-display-chroma RV16。
- 首次创建 LibVLC 会加载 libvlc.so、初始化插件与 Lua，耗时明显；不要在列表滚动或启动首帧里同步建。
- 所有 LibVLC/MediaPlayer 调用按约定放主线程；事件回调也在主线程。

### 3.3 视频输出：两条路

| 方式 | 用法 | 适用 |
| --- | --- | --- |
| 低层 IVLCVout | setVideoView(SurfaceView/TextureView) + attachViews() / detachViews() | Compose/自定义布局，本项目旧实现走这条 |
| VLCVideoLayout | 把 VLCVideoLayout 放进布局，MediaPlayer.attachViews(layout, displayManager, …) | 想少写 Surface 生命周期代码 |

注意：

- SurfaceView 对 direct rendering 更友好；TextureView 走 GL 合成，兼容性好但多一次拷贝。
- attachViews 之后等 VLC 的 onSurfacesCreated 回调再 play 最稳（Surface 可以晚于 attachViews 创建，VLC 自己会接 SurfaceHolder 回调）；退出页面先 detachViews 再 release，避免回调打到已销毁的 View。
- 屏切/分屏/PiP 时 Surface 会重建，需要按 VLC 的 Callback（onSurfacesCreated/onSurfacesDestroyed）重新挂。

### 3.3.1 官方 sample 的窗口尺寸与 onNewVideoLayout（2026-11-15 补）

官方 java_sample 的 `JavaActivity`（VLCVideoLayout 版本，commit 3ee4daad）在 `onStart` 里：

```java
mMediaPlayer.attachViews(mVideoLayout, null, ENABLE_SUBTITLES, USE_TEXTURE_VIEW); // 默认 USE_TEXTURE_VIEW=false
mMediaPlayer.play();
```

它的 `updateVideoSurfaces()` 在每次布局变化/onNewVideoLayout 时做两件本项目以前漏掉的事：

1. `mMediaPlayer.getVLCVout().setWindowSize(sw, sh)`——把当前显示窗口尺寸告诉 VLC；
2. 用 `onNewVideoLayout(vlcVout, width, height, visibleWidth, visibleHeight, sarNum, sarDen)`
   拿 visible 尺寸和 SAR，再算 View 的布局尺寸（FIT/CROP/ORIGINAL 都是在这里算）。

低层 `IVLCVout` 路径对应的是 `vlcVout.attachViews(this)`（带 `OnNewVideoLayoutListener`），
而不是只 `attachViews()`。只 attach 不 setWindowSize，部分机型的 TextureView/硬解 vout
可能拿不到窗口尺寸，表现为一直 buffering/0:00（2026-11-15 真机 LAN 截图）。

本项目 0.1.91 起在 `VlcVideoSurface` 布局变化时调用 `setWindowSize(w,h)`，并用
`vout.attachViews(newVideoLayoutListener)` 接收 visibleW/H + SAR，再走自算缩放。
来源：
- https://code.videolan.org/kansas1/libvlc-android-samples/-/commit/3ee4daad61a62cd291086a7136837dc047a6c598
- https://code.videolan.org/videolan/vlc-android/-/commit/b6d6c2608d9d1f6b5eece4d1652314949f4aa16b


### 3.4 事件与状态映射

| MediaPlayer.Event | 含义 | 我们旧实现的映射（VlcEngine） |
| --- | --- | --- |
| Opening / Buffering | 打开/缓冲 | Preparing |
| Playing / Paused / SeekableChanged / LengthChanged | 可播/暂停/元数据就绪 | Ready |
| EndReached | 播完 | Ended |
| EncounteredError | 解码/打开失败 | Error（提示换解码档位或换回 Media3） |
| TimeChanged / PositionChanged | 进度 | 更新进度条 |
| Vout | 视频输出已建立 | 可用于首帧/黑屏诊断 |
| ESAdded / ESDeleted / ESSelected | 轨道增删选 | 选音轨/看是否选到视频轨 |

### 3.5 线程、生命周期与崩溃边界

- 主线程调用：LibVLC、MediaPlayer、Media 的公开方法都按主线程约定使用；事件回调也在主线程。
- 释放顺序：setEventListener(null) → detachViews() → MediaPlayer.release() → LibVLC.release()。
- **最危险的边界是 LibVLC 初始化**：失败是 native 崩溃，Java 层 try/catch 抓不住（本项目 0.1.21 真机实证）。需要隔离进程探针，见 3.6。
- 切换解码档位（avcodec-hw / mediacodec-dr 这类实例级选项）要重建 LibVLC 实例，代价是黑屏 + 重新缓冲；位置/倍速/播放状态要自己保存再恢复。

### 3.6 本项目实测（0.1.21 → 0.1.77）

| 版本 | 事件 |
| --- | --- |
| 0.1.21 | 真机上点右上角内核切 LibVLC 直接闪退；日志停在「切换内核 → LibVLC」，没有「新内核已创建」，判定死在 AppContainer.createEngine(VLC) 里的 LibVLC 初始化 |
| 0.1.22 | 加独立进程 :vlcprobe 探针：子进程建 LibVLC + MediaPlayer，落文件，主进程只读结论；不可用时禁止切换 |
| 0.1.26 | 修探针调用被插到容器赋值之前的问题（启动即崩） |
| 0.1.76 前后 | release APK 74 MB、native 64 MB、libvlc.so 46 MB，19 个 .so 全部 16 KB 对齐 |
| 0.1.77 | 按用户要求下线 LibVLC 与自研引擎，APK 87 → 24 MB；引擎抽象保留，回环代理删除 |

结论：LibVLC 在我们的真机（Xiaomi 25102RK69C / Android 17）上存在**初始化期原生崩溃**风险，不是代码写错能完全规避的；这也是当初做独立进程探针的原因。

### 3.7 若重新引入：最小可回退路线（建议，不是承诺）

1. **M1（1–2 人日）**：独立进程探针 + 单文件本地播放，只求「能出第一帧」；跑不通就退回 Media3，沉没成本极小。
2. **M2（2–3 人日）**：接回环 HTTP 代理（Range + 现有 StorageBackend），WebDAV/SFTP/FTP 全部复用现有数据层与段缓存。
3. **M3（1–2 人日）**：解码档位（硬解优先 / 强制软解 / 强制硬解）+ 播放页内核切换 + 诊断日志。
4. 每步都保留「Media3 默认」开关；不做默认内核替换。

---

## 4. 缓存参数

### 4.1 四个 caching 参数（VLC 3.0.x 源码）

| 参数 | 默认值 | 范围 | 作用对象 | 读取点（源码） |
| --- | --- | --- | --- | --- |
| network-caching | 1000 ms（CLOCK_FREQ/1000） | 0–60000 | 网络资源（HTTP/FTP 等） | modules/access/http/access.c、ftp.c、file.c(远端) |
| file-caching | 1000 ms（CLOCK_FREQ/1000） | 0–60000 | 本地文件 | modules/access/file.c |
| live-caching | 300 ms（DEFAULT_PTS_DELAY/1000） | 0–60000 | 摄像头/麦克风 | 采集类 access |
| disc-caching | 300 ms（DEFAULT_PTS_DELAY/1000） | 0–60000 | 光盘 | 光盘 access |

计算依据：include/vlc_config.h 里 CLOCK_FREQ = 1,000,000（µs），DEFAULT_PTS_DELAY = 3×CLOCK_FREQ/10 = 300,000 µs = 300 ms。

> 注意：网上常能看到「live-caching 默认 3000」的说法；按 3.0.x / 3.0.21 源码，默认值是 300 ms。以源码为准。

### 4.2 它到底缓存了什么

源码链路很直接：

- HTTP access 在 STREAM_GET_PTS_DELAY 里返回 1000 × network-caching（单位 µs）；
- FTP、file（远端路径）同样读 network-caching；本地文件读 file-caching；
- 这个值最终成为输入的 **PTS 延迟**：播放时间轴整体后移，demuxer/解码器因此可以提前读一段数据。

含义：

- 它买的是「抗抖动的时间」，不是「固定 MB 数」；同样 3000 ms，高码率占的内存远大于低码率。
- 值越大：越抗卡顿，但首帧更慢、seek 更迟钝、切集更慢；值越小反之。
- 0 合法：表示不加额外延迟，弱网会更容易 Buffering。
- **不是磁盘缓存**：进程退出/换媒体就没了；没有持久化、没有 LRU、没有段文件。要「看过的存下来」必须另做（我们的 SegmentedCache 就是另一层）。
- 它是 access 层的延迟；VLC 的 demuxer 还会自己预读，两者叠加，实际内存占用不能用 caching 一个数推算。

### 4.3 Android 层的默认值

3.7.6 的 Media.setHWDecoderEnabled 在「开启硬解且调用方没显式设过缓存」时，会补：

    :file-caching=1500
    :network-caching=1500

也就是说，**Android 上只要你启用硬解，默认缓存会从 1000 ms 变成 1500 ms**（除非你先在媒体上 addOption 覆盖）。源码对应 3.0.x Media.java 的 mFileCachingSet / mNetworkCachingSet 逻辑，3.7.6 字节码一致。

> 坑：这个「是否已设置」的标志只看**媒体级** addOption。只给 LibVLC 实例加 --network-caching=3000 时，媒体级的 1500 仍可能把实例值盖掉（按 VLC 的选项优先级，媒体项覆盖实例项，需真机复核）。要自定义缓存，就在建 Media 后先 addOption(":network-caching=…")，再 setHWDecoderEnabled。

另外 LibVLC 构造器会自动补音频输出与视频 chroma 默认项（见 3.2），这些也会影响实际观感，但和缓存无关。

### 4.4 官方 App 怎么做（VLC 4 master 的 VLCOptions.kt）

- networkCaching 从设置读取，coerceIn(0, 60000)；**只有大于 0 才追加 --network-caching=N**（0 = 用 VLC 默认）。
- 另外还会带：--avcodec-skiploopfilter N、--avcodec-skip-frame 0|2、--avcodec-skip-idct 0|2（去块/跳帧省性能）、--audio-resampler soxr、--audiotrack-session-id、--vout=gles2|android_display。
- 硬解档位：disabled → setHWDecoderEnabled(false,false)；full → (true,true)；decoding（只硬解、渲染走 CPU）→ (true,true) + **:no-mediacodec-dr** + :no-omxil-dr。

### 4.5 调参建议

| 场景 | network-caching | 理由 / 代价 |
| --- | --- | --- |
| 局域网 WebDAV/SFTP，求秒开 | 1000–2000 | 默认 1000 已够；再大拖慢 seek |
| 公网/高抖动 | 3000–5000 | 抗抖动；首帧和拖拽变慢 |
| 弱网、直播 | 5000–10000 | 先保证不断流；内存上升 |
| 纯本地文件 | file-caching 1000 即可 | 本地不需要大缓冲 |
| 调试卡顿 | 逐步加 1000，观察 Buffering 事件频率 | 不要一次加到 60000 |
| 需要精确定位/逐帧 | 0–500 | 代价是更容易卡 |

实际取值要按码率与真机测；同样的 3000 ms 对 2 Mbps 和 40 Mbps 是两种内存量级。

### 4.6 与 Media3、我们的磁盘缓存的关系

| 维度 | Media3 ExoPlayer | LibVLC |
| --- | --- | --- |
| 缓冲控制 | DefaultLoadControl 的 bufferForPlaybackMs / minBufferMs 等时间阈值 | network-caching / file-caching 的 PTS 延迟 |
| 持久缓存 | 无内置磁盘缓存；我们在 DataSource 下做了 SegmentedCache（段文件 + LRU） | 无磁盘缓存；只能靠外部代理/缓存层 |
| 直接播远端 | 我们的 BackendDataSource 统一取数 | VLC 自带 HTTP/FTP/SFTP 访问，若直接给远端 URL 会绕开我们的数据层 |

因此：**重新引入 LibVLC 时，必须继续走回环 HTTP 代理**（旧实现就是这么做的），否则：
- 我们的段缓存、鉴权、重试、Range 策略全部失效；
- VLC 的 SFTP/FTP 实现与我们的连接模型（单地址/单网络类型）不一致；
- 播放进度拖拽的随机读行为不可控。

### 4.7 其它相关旋钮（官方 Wiki 参数页）

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| --http-reconnect | 关闭 | 断流自动重连 |
| --http-continuous | 关闭 | 持续读取不断更新的资源（不适合普通文件） |
| --http-forward-cookies | 开启 | 跨重定向转发 Cookie |
| --http-referrer / --http-user-agent | 空 | 自定义 Referer / UA |
| --rtsp-tcp | 关闭 | RTP over RTSP(TCP) |
| --clock-jitter | 5000（源码值，按 µs 折算约 5 ms） | 允许的输入延迟抖动 |
| --drop-late-frames | 开启 | 丢掉迟到的帧 |
| --skip-frames | 开启（默认，针对 MPEG2 丢帧） | 性能不足时丢帧 |
| --avcodec-threads | 0 | 0 = 自动 |
| --audio-time-stretch | 开启 | 变速不变调 |
| --network-synchronisation | 关闭 | 网络时钟同步 |

---

## 5. 硬件解码

### 5.1 Android 解码模块的真相

VLC 3.0.x 的 MediaCodec 解码器不在 modules/codec/mediacodec，而在 **modules/codec/omxil/mediacodec.c**（外加 mediacodec_ndk.c / mediacodec_jni.c）：

- 注册能力是 **video decoder**，优先级 0，源码注释写明「Only enabled via commandline arguments」；
- 两个 shortcut：**mediacodec_ndk**（API 21+，走 AImageReader / Surface）与 **mediacodec_jni**（旧 Java MediaCodec 路径）；
- 3.7.6 的 Media.getMediaCodecModule()：Lollipop 及以上返回 mediacodec_ndk，否则 mediacodec_jni；
- 模块参数：mediacodec-dr 默认 true、mediacodec-audio 默认 false、mediacodec-tunneled-playback 默认 false。

### 5.2 开关矩阵（3.7.x）

| 目标 | 正确写法 | 实际生成的媒体选项 | 备注 |
| --- | --- | --- | --- |
| 硬解优先（默认） | setHWDecoderEnabled(true, false) | :codec=mediacodec_ndk,all；未设置时补 :file-caching=1500、:network-caching=1500 | 官方 VLC App 的 automatic |
| 强制硬解 | setHWDecoderEnabled(true, true) | 同上；旧设备额外加 iomx, | 现代 Android 黑名单基本为空，force 差异有限 |
| 强制软解 | setHWDecoderEnabled(false, false) | :codec=all | 这才是 Android 上关硬解的主开关 |
| 手动点名 | addOption(":codec=mediacodec_ndk,mediacodec_jni,all") | — | 特定机型/调试 |
| 实例级 | LibVLC(ctx, listOf("--codec=...")) | 全局 | 改档位要重建实例 |
| VLC 4 路径 | setHWDecoderEnabled(false, …) | :no-hw-dec | 3.7.6 字节码已按 majorVersion 分支 |

「:codec=all」不是「所有解码器随便挑」那么简单：Wiki 原文说 codec 是优先列表，写成 mediacodec_ndk,all 就是「先试 MediaCodec NDK，失败再按默认顺序找其它」；写成 all 就是「不点名 MediaCodec，走默认顺序」，因此不会选中优先级 0 的 MediaCodec 模块。

### 5.3 为什么 --avcodec-hw 不管 Android 硬解

- avcodec-hw 的定义在 modules/codec/avcodec/avcodec.c:140，类型是 module，能力名 **hw decoder**。
- 本次核对的 3.0.x 树里，注册 hw decoder 能力的是 d3d11va、dxva2、vaapi（都是桌面路径）；Android 的 MediaCodec 注册的是 video decoder，不在这个能力里。
- 所以：
  - --avcodec-hw=none 只会关掉 libavcodec 的 hw 加速封装（Android 上本来就没有），**不会**关掉 MediaCodec；
  - --avcodec-hw=any 也不会让 MediaCodec 生效；真正让 MediaCodec 生效的是 codec 列表。
- 我们旧版 DecoderMode 同时写了 :avcodec-hw 和 setHWDecoderEnabled，属于「双保险」；**真正起作用的是后者**。文档里要写清楚，避免以后只改 avcodec-hw 却发现硬解没变。

### 5.4 mediacodec-dr / tunneled / audio

| 选项 | 默认 | 含义 | 什么时候改 |
| --- | --- | --- | --- |
| --mediacodec-dr | true | direct rendering：解码器直接写 Surface（opaque buffer），零拷贝、省电 | 需要截图/录屏/滤镜/部分字幕时关掉 |
| --mediacodec-audio | false | 用 MediaCodec 解音频（实验性） | 一般不动，音频仍走 ffmpeg |
| --mediacodec-tunneled-playback | false | 隧道播放（音视频同步交给系统） | 特定机型的 4K/HDR 可试，默认别开 |

VLC App 的「硬件解码（只解码）」模式就是 setHWDecoderEnabled(true,true) + :no-mediacodec-dr + :no-omxil-dr：解码仍用硬件，但输出到 CPU 帧，兼容性换性能。

### 5.5 怎么确认真的硬解了

1. **logcat**：把 VLC 日志调到调试级后搜 MediaCodec via NDK opened / MediaCodec via JNI opened（源码里就是这两句 msg_Dbg）；软解会看到 ffmpeg/avcodec 的日志，没有这两句。
2. **功耗/CPU**：同一段 4K 视频对比 Media3 与 LibVLC 的 CPU 占用和温度，硬解通常明显更低。
3. **事件**：ESAdded/ESSelected 只能告诉你选了什么轨道，不能区分硬软；getCurrentVideoTrack() 同理（fourcc 不区分硬软）。
4. **失败回退**：codec 列表里带 all 时，MediaCodec 初始化失败会自动回退到软解——表现是「能播但很烫」，需要看日志确认。

### 5.6 常见坑

1. **初始化原生崩溃**：真机实证（0.1.21），Java 抓不住；必须独立进程探针。
2. **首帧黑屏**：attachViews 早于 Surface 就绪、或 Surface 尺寸为 0；先等 surfaceCreated。
3. **切档位要不要重建**：媒体级 :codec 只影响当前 Media，换一份 Media 即可；实例级 --avcodec-hw / --codec / --mediacodec-dr 才需要重建 LibVLC + MediaPlayer。我们旧实现用的是实例级，所以整条管线重建。
4. **10bit/HDR/AV1/杜比视界**：不同机型的 MediaCodec 支持差异大，失败会回退软解；要按真机验证。
5. **4K 高码率 + 小缓存**：频繁 Buffering；先加 network-caching 再怀疑解码器。
6. **16 KB 页**：3.7.6 arm64 已对齐；升级版本必须复验，否则 16 KB 页设备（Android 15 起部分机型）可能装不上或起不来。
7. **体积**：只打 arm64；AAR 自带 4 ABI，别把 200 MB native 全打进 APK。
8. **截图/录屏/字幕**：需要 CPU 帧时关 dr，否则拿到黑图/空帧。
9. **音频输出**：3.x 默认 opensles/audiotrack，4.x 默认 aaudio（官方 App 注释）；机型差异要真机听。
10. **seek 精度**：caching 越大，seek 后重新缓冲越久；VLC 的 fast seek 会按关键帧对齐。

### 5.7 与 Media3 硬解的对照

| 维度 | Media3 ExoPlayer | LibVLC 3.7.x |
| --- | --- | --- |
| 硬解选择 | MediaCodecSelector + setEnableDecoderFallback | setHWDecoderEnabled → :codec=mediacodec_ndk,all |
| 强制软解 | 只挑 software-only 解码器（设备可能没有） | :codec=all（自带 ffmpeg 软解） |
| 强制硬解 | 没有官方「只准硬解」开关，只能关 fallback | (true,true) + iomx（旧设备） |
| HDR/隧道 | 取决于 MediaCodec 与设备 | mediacodec-tunneled-playback 默认关 |
| 缓存 | LoadControl 时间阈值 + 我们的磁盘段缓存 | PTS 延迟，无磁盘缓存 |
| 体积 | 小 | arm64 约 +55 MB |
| 崩溃面 | Java 层为主 | 原生 .so（初始化期最危险） |

---

## 6. 结论与建议

1. **资料可用性**：2026-11-12 这个时间点，官方 GitLab 匿名不可用、Doxygen 子页全 404；能用的官方资料是 GitHub 镜像、Maven 制品、Wiki 参数页。本文的版本/默认值以 3.7.6 AAR 与 3.0.x 源码为准。
2. **集成成本是真实存在的**：arm64 约 55 MB 原生库、4 个 ABI 的 AAR 200 MB、初始化期原生崩溃风险（我们真机踩过）。这不是「加一行依赖」的事。
3. **收益也明确**：自带完整软解与更宽的格式覆盖、硬解开关粒度更细；音量按百分比计，我们旧实现用到 200%。若目标是「Media3 播不了的兜底」，LibVLC 仍是成熟选择。
4. **不建议**：把 LibVLC 设为默认内核；直接上 4.0.0-eap；不做进程隔离探针就切内核。
5. **如果要做**：按 3.7 的 M1→M3 走，先量「到底哪些文件 Media3 播不了」，再决定是否值得为它付 55 MB 与崩溃风险。
6. **数据层必须复用**：LibVLC 重新引入时坚持走回环 HTTP 代理 + 现有 SegmentedCache；否则缓存/鉴权/单地址连接模型全部失效。

---

## 7. 待核实（会变的事实）

- 3.7.7 与 3.7.6 的 API/行为差异（本文只完整核对了 3.7.6 制品；3.7.7 因下载慢未拉全）。
- 3.7.7 内置的 VLC 核心版本（3.7.6 为 3.0.24-beta1）。
- 4.0.0-eap29 的 API 稳定性、:no-hw-dec 行为、默认音频输出。
- Android 17（SDK 37）真机上的 MediaCodec 兼容性（需要真机，本机无设备）。
- live-caching / disc-caching 在 3.7.x 设备上的实际值（本文按 3.0.x 源码推为 300 ms，未在设备上读回）。
- Doxygen 站点是否恢复、官方 sample 仓库是否重新公开。
- Maven metadata 的 latest/release 指向 EAP；选稳定版时要显式指定 3.7.7。

---

## 8. 参考链接

官方仓库与制品：

- https://github.com/videolan/vlc-android
- https://github.com/videolan/vlc
- https://code.videolan.org/videolan/libvlc-android （匿名需登录）
- https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/
- https://search.maven.org/artifact/org.videolan.android/libvlc-all

官方文档与 Wiki：

- https://wiki.videolan.org/VLC_command-line_help/
- https://wiki.videolan.org/LibVLC_Memory_Management/
- https://wiki.videolan.org/LibVLC_Media_List_Management/
- https://wiki.videolan.org/AndroidCompile/
- https://www.videolan.org/developers/vlc/doc/doxygen/html/index.html （仅首页）
- https://www.videolan.org/vlc/libvlc.html

关键源码：

- https://github.com/videolan/vlc-android/blob/3.0.x/libvlc/src/org/videolan/libvlc/Media.java
- https://github.com/videolan/vlc-android/blob/3.0.x/libvlc/src/org/videolan/libvlc/LibVLC.java
- https://github.com/videolan/vlc/blob/3.0.x/src/libvlc-module.c
- https://github.com/videolan/vlc/blob/3.0.x/include/vlc_config.h
- https://github.com/videolan/vlc/blob/3.0.x/modules/codec/omxil/mediacodec.c
- https://github.com/videolan/vlc/blob/3.0.x/modules/codec/avcodec/avcodec.c
- https://github.com/videolan/vlc/blob/3.0.x/modules/access/http/access.c
- https://github.com/videolan/vlc/blob/3.0.x/modules/access/file.c
- https://github.com/videolan/vlc/blob/3.0.x/modules/video_output/android/display.c

---

## 附录 A：源码行号索引（2026-11-12 核对）

| 事实 | 位置 |
| --- | --- |
| MediaCodec 模块选择 | vlc-android 3.0.x libvlc/.../Media.java L730-732 |
| setHWDecoderEnabled 逻辑 | 同文件 L740-776 |
| 缓存参数定义与默认 | vlc 3.0.x src/libvlc-module.c L1864-1902 |
| CLOCK_FREQ / DEFAULT_PTS_DELAY | vlc 3.0.x include/vlc_config.h L46、L68 |
| network-caching 读取 | modules/access/http/access.c L96-98；modules/access/file.c L356-360；modules/access/ftp.c L1127 |
| MediaCodec 能力/默认参数 | modules/codec/omxil/mediacodec.c L174-207 |
| avcodec-hw 定义 | modules/codec/avcodec/avcodec.c L140 |
| android-display-chroma | modules/video_output/android/display.c L48-68 |
| 3.7.6 行为 | 本机 AAR classes.jar 的 javap 字节码（Media.setHWDecoderEnabled / LibVLC 构造器） |
| 官方 App 选项 | vlc-android master application/resources/.../VLCOptions.kt L120、L163-167、L172、L294-312 |

## 附录 B：本项目版本对照

| 版本 | 播放内核 | 关键数据 |
| --- | --- | --- |
| 0.1.20 之前 | Media3 | —— |
| 0.1.21 | Media3 + LibVLC 3.7.6 | 真机切内核闪退（LibVLC 初始化） |
| 0.1.22 | 同上 + :vlcprobe 探针 | 不可用则禁止切换 |
| 0.1.26 | 修探针顺序 bug | 启动不再崩 |
| 0.1.76 前后 | Media3 + LibVLC + 自研引擎 | APK 74 MB，libvlc.so 46 MB，19 个 .so 16 KB 对齐 |
| 0.1.77 | 仅 Media3 | APK 87 → 24 MB，LibVLC 与自研引擎下线 |
| 0.1.83（当前） | 仅 Media3 1.11.1 | 计划文本 R9 仍写「LibVLC 兜底」，与实际实现不一致 |

---

（完）
