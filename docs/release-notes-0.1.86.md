# 0.1.86 发布说明（候选，待真机验证）

> 2026-11-12 用户要求：「将视频播放引擎更改为libvlc，并设置为硬解模式，然后根据连接的局域网或公网改变缓存的配置」。
> 本次为**候选包**：代码、单测、构建都过了，但 LibVLC 在这台 Xiaomi / Android 17 真机上能否初始化还需要你装一次验证。

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
