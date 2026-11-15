# 0.1.101 发布说明（候选，待真机验证）

> 2026-11-12 用户要求：「将视频播放引擎更改为libvlc，并设置为硬解模式，然后根据连接的局域网或公网改变缓存的配置」。
> 本次为**候选包**：代码、单测、构建都过了，但 LibVLC 在这台 Xiaomi / Android 17 真机上能否初始化还需要你装一次验证。

## 0.1.101 公网冷启动/目录自愈（2026-11-15 用户反馈）

- **现象**：公网重开软件后目录读不出、连接显示断开；手动在连接页重连即恢复。
- **冷启动补连**：DataStore/连接表/网络现场三条异步流首帧可能不齐，只在本地根目录停住；启动后 12 秒内每秒补一次 applyCurrentConnection，直到远端 root 建立。
- **目录自愈**：浏览器 list 加 15s 硬超时；TIMEOUT/UNKNOWN 时调 `recoverRemoteRoot()` 重建当前连接并保留当前目录（同一 label 不跳根），同一目录只自愈一次。
- **全网络直读**：`LoopbackHttpProxy(directRead = { true })`，LAN/WAN 都绕过分段缓存，避免 SFTP SegmentJob/通道池卡死。

## 0.1.100 回退 TextureView（2026-11-15 真机）

- 0.1.99 的 SurfaceView 方案在 HyperOS 上 `player.play()` 后没有 Opening/Vout 事件，代理 Broken pipe，无法起播；已回退 TextureView。
- 0.1.98 的“只用 LibVLC / 切连接不继承本地目录”保留；LAN 数据层的 header flush / cachedSize / SFTP 元数据修复保留。
- TextureView 的偶发 Opaque Vout 失败仍存在，后续单独处理；本版先恢复可播状态。

## 0.1.99 视频输出切回 SurfaceView（2026-11-15 真机 Vout 失败）

- **真机**：0.1.98 在 HyperOS 上偶发 `libvlc video output: video output creation failed` / `Opaque Vout request failed`，随后一直缓冲。
- **依据**：官方 java_sample 的 `USE_TEXTURE_VIEW` 默认 false（SurfaceView）；TextureView 走 GL 合成，硬解/Vout 兼容性更差。
- **修**：`VlcVideoSurface` 从 TextureView 改回 SurfaceView；FIT/CROP/STRETCH/ORIGINAL 通过 SurfaceView 布局尺寸实现，容器 clipChildren；窗口尺寸/onNewVideoLayout 逻辑保留。

## 0.1.98 用户三条验收口径（2026-11-15）

- **只用 LibVLC 硬解**：移除 12 秒起播看门狗→Media3 自动切换；LAN 播放保持 LibVLC（探针失败才内部兜底）。
- **切换连接不继承本地目录**：`BrowserViewModel` 首帧遇到远端 root（RootModeKind.NONE）时忽略 `lastPlayed/lastBrowsed`，只用路由显式路径或根目录；本地 root 仍恢复记忆目录。
- **拖动尽快续播**：0.1.97 已修代理响应头 flush / SFTP 元数据通道 / cachedSize；本条继续真机测 seek。

## 0.1.97 代理响应头立即 flush（2026-11-15 真机定位）

- **真机**：cachedSize 已命中（379,850,513），代理不再 stat，但 `curl` 25 秒仍 0 字节；VLC 12 秒后报 `cannot seek`、`no moov`。
- **根因**：`LoopbackHttpProxy` 的 `BufferedOutputStream` 在写响应头后没有 flush，头被 64 KiB 缓冲压住；body 第一段（SFTP 分段读）慢时，VLC 根本看不到 `Content-Range/Content-Length`。
- **修**：`writeHead()` 写完立即 `flush()`，头先到、body 后到。

## 0.1.96 代理用 cachedSize 跳过 stat（2026-11-15 真机验证）

- 0.1.94/0.1.95 后仍复现代理 504：VLC 每个 Range 前 `backend.stat`，SFTP 元数据通道在播放分段读下仍可能被 JSch/Session 阻塞。
- **修**：`StorageBackend.cachedSize(path)`；`SegmentedCacheBackend` 在 `list`/`stat`/`openRead` 时把文件大小记入 stampCache；回环代理命中 cachedSize 就**完全跳过 stat**，直接用缓存大小算 Range/Content-Range/Content-Length。
- 目录列表已经拿到的 362.3 MB 现在直接用于 VLC 的尾部 moov Range，不再发 SFTP 属性请求。

## 0.1.95 SFTP 元数据独立通道（2026-11-15 真机验证）

- 0.1.94 给 stat 加了 attrs 缓存，但首次 stat 仍可能被播放中的 4 条分段读通道饿死，代理 504 复现。
- **修**：`SftpChannelPool.useMetadata()`——元数据在同一 SSH Session 上开一条**不占 maxChannels 许可**的临时通道，用完即断；`attrsOf` 走它。数据流仍受 maxChannels 限制，stat/list/属性查询不再被饿死。

## 0.1.94 SFTP stat 缓存（2026-11-15 真机定位）

- **真机日志**：VLC 报 `cannot seek (to offset 375146658)`、`MP4 plugin discarded (no moov...)`；代理随后 `504`，`MG/storage-timeout` 报「取属性 ... 硬超时 15000ms」。
- **根因**：`SftpStorageBackend.stat()` 没走 `attrsOf` 缓存，VLC 每个 Range 请求前都 stat 一次；播放中的分段读占满 4 条 SFTP 通道，stat 拿不到通道，15s 超时，代理 504，VLC 拿不到 MP4 尾部 moov。
- **修**：`stat()` 改用 `attrsOf`（128 条 LRU，openRead 早已在用），重复 stat 不再借通道。

## 0.1.93 后台回前台自动重建远端连接（2026-11-15 反馈）

- **现象**：App 放后台再打开，LAN 连接断开，必须重启 App 才恢复。
- **原因**：后台冻结/VPN/系统休眠会让 WebDAV/SFTP/FTP 的 socket 静默死掉；网络现场没变，NetworkCallback 不触发，旧后端之后每次 list/read 都失败。
- **修**：MainActivity 记录 onPause/onResume；后台超过 2 秒且**没有音频/视频正在播**时，回到前台主动清掉 remoteSignature 并重建远端后端。浏览器拿到新 backend 会自动重列当前目录。播放中不拆流；PIP/转屏/权限框不足 2 秒不会重建。

## 0.1.92 Media3 兜底绕过分段缓存（2026-11-15 反馈）

- **现象**：LibVLC 12 秒无画面自动切 Media3 后，提示已出现，但 Media3 仍「正在缓冲…」0:00。
- **诊断**：缓存 0/79、0.0 MB/s；Media3 很可能 join 到了 LibVLC 留下的同一个卡死 SegmentJob（分段单飞）。
- **修**：`SegmentedCacheBackend.openReadDirect()`；Media3 兜底内核改用直读 delegate 的包装后端（仍保留 TimeoutStorageBackend 硬截止），不再等卡死的分段任务。
- 下一步可据此区分：Media3 直读能播 = 分段缓存/代理问题；仍卡 = 远端后端本身问题。

## 0.1.91 按官方 sample 补窗口尺寸/布局回调（2026-11-15）

对照 VideoLAN 官方 LibVLC Android sample（java_sample 的 `mMediaPlayer.attachViews(mVideoLayout, ...)` 与 `updateVideoSurfaces()`）：

- 官方在播放前/布局变化时调用 `IVLCVout.setWindowSize(sw, sh)`；
- 官方用 `attachViews(OnNewVideoLayoutListener)` 接收 `visibleWidth/visibleHeight/sar`，再按 View 尺寸布局；
- 本项目之前只 `setVideoView(textureView)` + `attachViews()`，**没有 setWindowSize，也没有 onNewVideoLayout**，部分机型/硬解路径可能因此拿不到窗口尺寸而停在 buffering/0:00。

本次补齐：

- `VlcVideoSurface` 每次布局变化 → `player.vlcVout.setWindowSize(w, h)`；
- `attachViews(newVideoLayoutListener)` 接收官方回调，按 visibleW/H + SAR 更新自算缩放；
- 保留自算 FIT/CROP/STRETCH/ORIGINAL，不依赖 VLC 的 setScale。

## 0.1.90 LibVLC 起播超时自动兜底（2026-11-15 反馈）

- **现象**：局域网播放页显示「正在缓冲…」、0:00、--:--，队列已加载（第 2/19 集），但 LibVLC 一直不出画面。
- **原因**：VLC 在 LAN/代理这条链上可能长时间停在 buffering 且不报错。
- **修**：LibVLC 起播看门狗——12 秒内仍 0:00、未播放、处于 buffering 时，自动切到 Media3（走自己的 BackendDataSource，不经过回环代理），并提示「LibVLC 起播超时，已自动改用 Media3 继续」。只做一次；本地/已出画面不触发。

## 0.1.89 局域网起播不再等列目录（2026-11-15 反馈）

- **现象**：局域网播放页一直转圈（LOADING）、0:00、--:--；本地文件正常。
- **原因**：旧 `load()` 先 `siblings()` 列同目录、再建内核；LAN/WebDAV 目录慢或卡住时，播放页根本轮不到 LibVLC。
- **修**：先给单文件队列并立即起播，**起播后**再补同目录队列；列目录失败只记日志，不影响播放。上一集/下一集会在队列返回后可用。

## 0.1.88 局域网特殊字符路径修正（2026-11-15 反馈）

- **现象**：本地视频（竖屏/横屏）能播；局域网文件不行，文件名如 `#Aki秋水 小年夜迟到的老公.mp4`。
- **原因**：回环代理地址把路径按百分号编码，文件名里的 `#` 变成 `%23`；部分 HTTP 客户端/URL 解析器仍会把 `#` 当 fragment，路径被截断，代理 stat 404。
- **修**：LibVLC 专用 `playUrlSafe`——路径含 `#`/`?`/`%` 时改用 base64url token（`/m/t.<token>`），token 只含 `A-Z a-z 0-9 - _`，不经过特殊字符；普通路径仍保持可读 legacy 形态，分享/FFmpeg 路径不变。

## 0.1.86 缩放不生效修正（2026-11-12 截图反馈）

- **现象**：本地视频播放位置仍不对，底部「缩放」四档切换不起作用。
- **原因**：0.1.85/0.1.86 依赖 VLC 的 `VideoHelper/setVideoScale` 做 fit/crop；部分机型/vout 下它不生效，
  画面仍按原始尺寸/位置画，缩放档位只改了标签。
- **修**：新增 `VlcVideoSurface`（TextureView 容器），**缩放完全由 app 自己算**：
  TextureView 布局尺寸 = 视频原始宽高，再用 View 的 scaleX/scaleY 做
  FIT=min 等比、CROP=max 等比、STRETCH=非等比、ORIGINAL=1:1；容器 `clipChildren=true` 裁掉超出部分。
  这样缩放不再经过 VLC，四档一定能生效。视频轨道尺寸从 `currentVideoTrack` 取，Vout/ESAdded 时重排。

## 0.1.85 真机黑屏修正（2026-11-12 截图反馈）

- **现象**：进入播放页黑屏、进度 0:00、时长 --:--、中间是播放键；没有错误面板。
- **原因**：0.1.85 换成 VLCVideoLayout 后，在 Compose 的 AndroidView 还没把 layout 挂到窗口时就把 layout 交给了 VLC，
  内部 SurfaceView 没有有效 surface，VLC 停在起播前（不报错）。
- **修**：等 VLCVideoLayout `onAttachedToWindow` 后再 `player.attachViews`；attach 前收到的 `play()` 记成
  pendingPlay，attach 后自动起播；`Vout` 后再套一次缩放档位。这样默认「适应屏幕」也能在窗口尺寸确定后生效。

## 0.1.84 候选的真机修正（2026-11-12 截图反馈）

- **视频只画在左上角、按原始像素大小**：0.1.84 用裸 SurfaceView 当全屏容器，绕过了 libvlc-android 的
  `VideoHelper`，视频按 720×1280 之类的原始尺寸画在 (0,0)，下面/右边被裁掉。改为官方
  **VLCVideoLayout + MediaPlayer.attachViews(layout, null, false, false)**，由它处理尺寸、等比缩放与居中。
- **播放中一直显示“正在缓冲…”**：VLC 在 Playing 之后还会补发一次 `Buffering(100)`，旧映射把任何
  Buffering 都当 Preparing，状态被永久拉回缓冲中。现在 `buffering >= 100` 视为 Ready，只有 <100 才显示缓冲。

## 新：视频播放引擎改为 LibVLC 3.7.7

- 视频默认走 **LibVLC 3.7.7**；Media3 退居**内部兜底**，不再出现在「内核」切换入口里。
- **数据层复用**：恢复 :media:proxy 回环 HTTP 代理——VLC 只看 URL，字节仍由同一个 StorageBackend 供给，
  本地 / WebDAV / SFTP / FTP 共用一套取数与 Range 拖拽 seek，段缓存、鉴权、单地址连接模型都不变。
- **独立进程探针**：第一次起播先拉起 :vlcprobe，子进程建 LibVLC + MediaPlayer；主进程只读
  start / ok / done 三个文件。探针失败（含原生崩溃）⇒ 本次自动回落 Media3，并弹一次提示，
  **不会再把整个 App 带走**。
- **硬解**：Media.setHWDecoderEnabled(true, true) ⇒ 实际选项 :codec=mediacodec_ndk,all；
  「强制软解」档位仍可切到 :codec=all。
- **缓存按连接类型**：局域网 network-caching=1500 ms、公网 4000 ms、本地/未知 1000 ms
  （VlcCachePolicy 纯函数，带单测；随实际选中的地址标签走）。
- **音频焦点**：LibVLC 不会自己申请/释放音频焦点，本次在引擎里补上：来电或其它播放器抢焦点时暂停、
  焦点回来自动续播、被要求 duck 时压到 20%。

## 体积

- arm64 的 libvlc.so 45.9 MB + libc++_shared 1.3 MB；release APK 从 24 MB 涨到 **68 MB**。
- libvlc.so 已按 16 KB 页对齐（LOAD Align=0x4000），符合新系统要求。

## 验证

- 全量 JVM 单测 **1438 通过 / 1 跳过 / 0 失败**（含新增 VlcCachePolicyTest、更新内核选择/降级/播放页用例；0.1.85 只改渲染容器与缓冲事件，未新增用例）；
- :app:assembleDebug 与 :app:assembleRelease 均通过（R8 keep 规则已补 org.videolan.libvlc.**）；
- **真机待验证**：LibVLC 能否初始化、首帧、硬解、拖拽 seek、后台播放/通知栏、画中画比例（VLC 的 SurfaceView 不报视频尺寸，PIP 暂按 16:9）。

## 已知边界

- 音频播放仍走 Media3，不受本次改动影响；
- 画中画比例对 LibVLC 只能给默认 16:9，后续可在引擎里补 video size 回调；
- 若探针判定本机不可用，会静默回落到 Media3（只弹一次提示），此时行为与 0.1.83 相同。
