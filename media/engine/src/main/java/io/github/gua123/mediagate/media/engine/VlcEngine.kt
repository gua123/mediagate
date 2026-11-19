package io.github.gua123.mediagate.media.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlinx.coroutines.flow.StateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.core.common.redactPath
import io.github.gua123.mediagate.core.common.redactProxyUrl
import io.github.gua123.mediagate.media.proxy.LoopbackHttpProxy
import io.github.gua123.mediagate.media.proxy.MediaUriCodec
import kotlin.math.roundToInt

/**
 * LibVLC（libvlc-all 3.7.7）视频内核——2026-11-12 用户要求「将视频播放引擎更改为 libvlc，并设置为硬解模式」。
 * Media3 只在 LibVLC 初始化探针失败时作为内部兜底（见 :app 的 createEngine）。
 *
 * 三个关键点：
 * 1. **媒体源走本机回环 HTTP 代理**（[LoopbackHttpProxy]）：`BaseUrl + MediaUri 编码` →
 *    `http://127.0.0.1:<port>/m/<backendId>/<path>`，字节仍由同一个 StorageBackend 经
 *    `openRead` 供给，于是 LibVLC 不需要认识 WebDAV/SFTP/FTP，也没有第二套取数逻辑；
 *    代理支持 Range，VLC 的拖拽 seek 因此可用（R4）。
 * 2. **硬解/软解**：真正生效的是 `Media.setHWDecoderEnabled(enabled, force)`（Android 上会落成
 *    `:codec=mediacodec_ndk,all`，关硬解则是 `:codec=all`）；`--avcodec-hw` 只是桌面 hw decoder
 *    的模块选择，不控制 Android MediaCodec（见 docs/LibVLC-Android-集成与参数.md §5）。
 *    改档位时重建 LibVLC 实例（选项是实例级的），位置/倍速/播放状态由本类恢复。
 * 3. **缓存**：按连接网络（LAN / WAN）取 [VlcCacheConfig]，见 [VlcCachePolicy]——局域网 1500 ms、
 *    公网 4000 ms；这是 LibVLC 的时间型预缓冲，不是磁盘缓存。
 * 4. **视频输出**：默认用 [VlcVideoSurface]（TextureView 容器，App 自己算 FIT/CROP/STRETCH/ORIGINAL）；
 *    官方 [VLCVideoLayout] 仍兼容。按官方 sample 补了 `IVLCVout.setWindowSize` 与
 *    `attachViews(OnNewVideoLayoutListener)`（visibleW/H + SAR），否则部分机型/硬解 vout
 *    可能拿不到窗口尺寸而停在 buffering。低层 [SurfaceView] 只兼容，**不要拿裸 SurfaceView
 *    当全屏容器**——实测会按视频原始像素画在左上角。
 *
 * 线程：所有公开方法都在主线程调用（LibVLC 约定），[MediaPlayer] 的事件回调也在主线程。
 *
 * @param context 任意 Context（内部取 applicationContext）。
 * @param proxy 回环代理（由 App 层创建并共享给「分享播放地址」）。
 * @param initialDecoderMode 初始解码档位（默认 [DecoderMode.AUTO_HW]；用户要求硬解，本类把“自动/强制硬解”都按硬解走）。
 * @param cache LibVLC 缓存档位（按连接网络类型算，见 [VlcCachePolicy]）。
 * @param videoView 初始视频输出视图（可后置用 [attachVideoOutput] 挂）。
 */
class VlcEngine(
    context: Context,
    private val proxy: LoopbackHttpProxy,
    initialDecoderMode: DecoderMode = DecoderMode.AUTO_HW,
    private val cache: VlcCacheConfig = VlcCachePolicy.forNetwork(null),
    videoView: View? = null,
) : PlayerEngine {

    private val appContext: Context = context.applicationContext

    /**
     * 内核状态的唯一写入口（见 [EngineStateHolder]）：事件走 [moveTo]，换源/释放走 [resetTo]。
     *
     * 本类里**不再直接碰 MutableStateFlow**——上一轮把事件回调改走 latch 之后，setMedia /
     * prepare / play / release 还各留着一处裸写，prepare() 尤其危险（能冲掉 Ended）。把 flow
     * 收进 holder 之后，"绕过 latch"在类型层面就写不出来了（2026-11-20 审计收口）。
     */
    private val stateHolder = EngineStateHolder()

    override val state: StateFlow<EngineState> = stateHolder.state

    override val kind: EngineKind = EngineKind.VLC

    private var mode: DecoderMode = initialDecoderMode

    override val decoderMode: DecoderMode get() = mode

    private var resize: ResizeMode = ResizeMode.FIT

    override val resizeMode: ResizeMode get() = resize

    private var playbackSpeed: Float = EngineSwitchPlanner.DEFAULT_SPEED

    override val speed: Float get() = playbackSpeed

    /** 音量倍率（0..200%）：LibVLC 音量单位就是百分比，原生支持到 200。 */
    private var playbackVolume: Float = 1f

    override val volume: Float get() = playbackVolume

    // ---- 音频焦点（Media3 会自己管；LibVLC 不会，换内核后不能把这行为弄丢） ----

    private val audioManager: AudioManager? =
        appContext.getSystemService(AudioManager::class.java)

    private val focusRequest: AudioFocusRequest? = runCatching {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change -> onAudioFocusChange(change) }
            .build()
    }.getOrNull()

    private var hasAudioFocus = false

    /** 因来电/其它应用临时抢焦点而暂停：焦点回来时自动续播。 */
    private var resumeOnFocusGain = false

    /** 被系统要求压低音量（duck）。 */
    private var duckedByFocus = false

    private var media: MediaSourceRef? = null
    /**
     * 是否已 release（2026-11-18 真机崩溃后加）。
     *
     * LibVLC 的 MediaPlayer 被 release 后 native 对象就没了，但 Kotlin 侧字段还留着旧引用；
     * 此时任何 isPlaying / setTime / time / length 都会抛
     * IllegalStateException: can not get VLCObject instance（native use-after-release），
     * 主线程直接被杀，表现为闪退回桌面。所以所有对外入口都要先看这个标志。
     */
    @Volatile
    private var released = false


    override val currentMedia: MediaSourceRef? get() = media

    override val isPlaying: Boolean get() = !released && player.isPlaying

    /** 默认视频输出：官方 VLCVideoLayout（首帧/Vout 最稳，2026-11-15 真机验证）。 */
    private val defaultVideoLayout: VLCVideoLayout = VLCVideoLayout(appContext)

    /** 视频输出视图（VLCVideoLayout / VlcVideoSurface / SurfaceView / TextureView）。 */
    private var attachedView: View? = videoView ?: defaultVideoLayout

    /** 视频输出是否已经真正挂到窗口/播放器（Compose 里 AndroidView 稍后才 attach）。 */
    private var videoOutputAttached = false

    /** 视频输出无响应时的重挂次数（TextureView 在旋转/首帧时偶发 Opaque Vout 失败）。 */
    private var outputRetryCount = 0

    private val outputRetryRunnable = Runnable { retryVideoOutputIfNeeded() }

    /** 视频视图还没 attach 时收到的 play()：等 attach 完再起播，否则 VLC 会停在黑屏 0:00。 */
    private var pendingPlay = false

    /** TextureView 还没 available 时轮询等它（50 ms 一次），可用后自动起播。 */
    private val pendingPlayRunnable = object : Runnable {
        override fun run() {
            if (!pendingPlay) return
            val surface = attachedView as? VlcVideoSurface ?: return
            if (videoOutputAttached && surface.textureView.isAvailable) {
                pendingPlay = false
                AppLog.i(TAG, "视频输出已就绪，开始起播（之前收到过 play）")
                player.play()
            } else {
                surface.postDelayed(this, 50)
            }
        }
    }

    /** 容器尺寸变化时把窗口尺寸告诉 VLC（官方 sample 的 updateVideoSurfaces 同款做法）。 */
    private val surfaceLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateVideoWindowSize()
    }

    /** VlcVideoSurface 挂到窗口后才把 TextureView 交给 VLC（否则 SurfaceTexture 监听可能错过）。 */
    private val surfaceAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            if (v is VlcVideoSurface) attachSurfaceIfReady(v)
        }

        override fun onViewDetachedFromWindow(v: View) {
            videoOutputAttached = false
        }
    }

    /** 官方 onNewVideoLayout：拿 visibleWidth/Height + SAR，比自己从 currentVideoTrack 推更准。 */
    private val newVideoLayoutListener = object : IVLCVout.OnNewVideoLayoutListener {
        override fun onNewVideoLayout(
            vlcVout: IVLCVout,
            width: Int,
            height: Int,
            visibleWidth: Int,
            visibleHeight: Int,
            sarNum: Int,
            sarDen: Int,
        ) {
            val surface = attachedView as? VlcVideoSurface ?: return
            val visibleW = if (visibleWidth > 0) visibleWidth else width
            val visibleH = if (visibleHeight > 0) visibleHeight else height
            if (visibleW <= 0 || visibleH <= 0) return
            // 显示宽高按 SAR 修正（官方 sample 的 ar 计算同源）
            val displayW = if (sarNum > 0 && sarDen > 0) {
                (visibleW.toLong() * sarNum / sarDen).toInt().coerceAtLeast(1)
            } else {
                visibleW
            }
            surface.setVideoSize(displayW, visibleH)
            updateVideoWindowSize()
        }
    }

    /** VLCVideoLayout 从 Compose 树里 attach 上来时再挂给 VLC（此时内部 SurfaceView 才有窗口）。 */
    private val attachStateListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            if (v is VLCVideoLayout) attachLayoutIfReady(v)
        }

        override fun onViewDetachedFromWindow(v: View) {
            videoOutputAttached = false
        }
    }

    /** LibVLC 实例；解码档位是实例级选项，切档位要重建（见 [setDecoderMode]）。 */
    private var libVlc: LibVLC = buildLibVlc(mode)

    private val listener = MediaPlayer.EventListener { event -> onVlcEvent(event) }

    private var player: MediaPlayer = buildPlayer()

    init {
        attachedView?.let { attachVideoOutput(it) }
    }

    // ------------------------------------------------------------------ 视频输出

    /**
     * 挂载/卸载视频输出（SurfaceView 或 TextureView）。
     *
     * @param view null = 解绑当前视图。
     */
    fun attachVideoOutput(view: View?) {
        (attachedView as? VLCVideoLayout)?.let { layout ->
            layout.removeOnAttachStateChangeListener(attachStateListener)
            layout.removeOnLayoutChangeListener(surfaceLayoutListener)
        }
        (attachedView as? VlcVideoSurface)?.let { surface ->
            surface.removeOnLayoutChangeListener(surfaceLayoutListener)
            surface.removeOnAttachStateChangeListener(surfaceAttachListener)
        }
        val vout = player.vlcVout
        videoOutputAttached = false
        when (view) {
            null -> {
                if (vout.areViewsAttached()) player.detachViews()
                attachedView = null
                return
            }

            // 自算缩放容器：VLC 只负责把画面画进 TextureView，缩放由外层 View 做
            is VlcVideoSurface -> {
                attachedView = view
                view.setResizeMode(resize)
                view.addOnLayoutChangeListener(surfaceLayoutListener)
                // 必须等 View attach 到窗口后再交给 VLC；否则 SurfaceTexture 回调可能错过，
                // play() 后没有任何 VLC 事件（2026-11-15 真机）。
                if (view.isAttachedToWindow) attachSurfaceIfReady(view)
                else view.addOnAttachStateChangeListener(surfaceAttachListener)
                return
            }

            // 兼容旧路径：官方 VLCVideoLayout（内部会按视频尺寸/缩放档位设置 SurfaceView）
            // **不能在这里立刻 attach**：Compose 的 AndroidView 还没把 layout 挂到窗口，
            // 内部 SurfaceView 没有有效 surface，VLC 会停在 0:00 黑屏；等 onViewAttachedToWindow。
            is VLCVideoLayout -> {
                attachedView = view
                view.addOnLayoutChangeListener(surfaceLayoutListener)
                if (view.isAttachedToWindow) attachLayoutIfReady(view)
                else view.addOnAttachStateChangeListener(attachStateListener)
                return
            }

            is SurfaceView -> {
                vout.setVideoView(view)
                if (!vout.areViewsAttached()) vout.attachViews()
                videoOutputAttached = true
            }

            is TextureView -> {
                vout.setVideoView(view)
                if (!vout.areViewsAttached()) vout.attachViews()
                videoOutputAttached = true
            }

            else -> {
                AppLog.w(TAG, "LibVLC 视频输出只支持 VLCVideoLayout / SurfaceView / TextureView：" + view.javaClass.name)
                return
            }
        }
        attachedView = view
    }

    /** VLCVideoLayout 已 attach 到窗口：把它挂给 MediaPlayer（内部 SurfaceView 这时才有 surface）。 */
    private fun attachLayoutIfReady(view: VLCVideoLayout) {
        if (attachedView !== view) return
        // 官方 sample 的顺序：先把容器尺寸交给 VLC，再 attach；否则 SurfaceView 可能按
        // 视频原始像素 1280x720 贴在左上角（2026-11-15 真机截图）。
        updateVideoWindowSize()
        if (!player.vlcVout.areViewsAttached()) {
            try {
                player.attachViews(view, null, false, false)
            } catch (t: Throwable) {
                AppLog.e(TAG, "LibVLC 视频输出挂载失败", t)
                moveTo(EngineState.Error("LibVLC 视频输出挂载失败：" + ErrorText.of(t, "未知原因"), t))
                pendingPlay = false
                return
            }
        }
        videoOutputAttached = player.vlcVout.areViewsAttached()
        updateVideoWindowSize()
        applyVideoScale()
        if (pendingPlay) {
            pendingPlay = false
            AppLog.i(TAG, "视频视图已挂载，开始起播（之前收到过 play）")
            player.play()
        }
    }

    /** 首帧阶段 4 秒还没有 Vout/Playing：重挂一次视频输出（不换 Media3）。 */
    private fun scheduleOutputRetry() {
        val view = attachedView ?: return
        view.removeCallbacks(outputRetryRunnable)
        view.postDelayed(outputRetryRunnable, 3_000L)
    }

    private fun retryVideoOutputIfNeeded() {
        val view = attachedView ?: return
        if (player.isPlaying || outputRetryCount >= 3) return
        outputRetryCount++
        AppLog.w(TAG, "视频输出 3 秒无响应，重挂载一次（" + outputRetryCount + "/3）")
        runCatching { player.detachViews() }
        videoOutputAttached = false
        when (view) {
            is VLCVideoLayout -> {
                attachLayoutIfReady(view)
                if (videoOutputAttached) {
                    player.play()
                    scheduleOutputRetry()
                }
            }
            is VlcVideoSurface -> {
                if (view.isAttachedToWindow) attachSurfaceIfReady(view)
                else view.addOnAttachStateChangeListener(surfaceAttachListener)
                if (videoOutputAttached && view.textureView.isAvailable) {
                    player.play()
                    scheduleOutputRetry()
                }
            }
        }
    }

    /** VlcVideoSurface 已挂到窗口：把 TextureView 交给 VLC，并补发等待中的 play。 */
    private fun attachSurfaceIfReady(view: VlcVideoSurface) {
        if (attachedView !== view) return
        val vout = player.vlcVout
        if (!vout.areViewsAttached()) {
            vout.setVideoView(view.textureView)
            // 官方 sample：attachViews(listener) 才能收到 onNewVideoLayout（visibleW/H + SAR）
            vout.attachViews(newVideoLayoutListener)
        }
        videoOutputAttached = vout.areViewsAttached()
        applyVideoScale()
        updateVideoWindowSize()
        if (pendingPlay && view.textureView.isAvailable) {
            pendingPlay = false
            AppLog.i(TAG, "视频输出已挂载，开始起播")
            player.play()
            scheduleOutputRetry()
        }
    }

    /** 把当前容器尺寸告诉 VLC（官方 sample 在 updateVideoSurfaces 里先 setWindowSize）。 */
    private fun updateVideoWindowSize() {
        val view = attachedView ?: return
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return
        runCatching { player.vlcVout.setWindowSize(width, height) }
    }

    /** 把当前缩放档位重新套给 VLC（Vout/attach 后再套一次，避免拿到 0 尺寸时算错）。 */
    private fun applyVideoScale() {
        runCatching { player.setVideoScale(resize.toVlcScaleType()) }
    }

    /** 从 MediaPlayer 的当前视频轨道取原始宽高，交给自算缩放容器。 */
    private fun updateVideoSize() {
        val surface = attachedView as? VlcVideoSurface ?: return
        val track = runCatching { player.currentVideoTrack }.getOrNull() ?: return
        if (track.width > 0 && track.height > 0) {
            // 手机竖拍视频常带 90° 旋转 metadata（orientation 5/6），显示宽高要交换
            val rotated = track.orientation == 5 || track.orientation == 6
            val width = if (rotated) track.height else track.width
            val height = if (rotated) track.width else track.height
            surface.setVideoSize(width, height)
        }
    }

    override fun videoView(): View? = attachedView

    // ------------------------------------------------------------------ 引擎动作

    override fun setMedia(src: MediaSourceRef) {
        media = src
        // 伪 URI → 回环地址：VLC 只会吃 URL，数据层复用全靠这一步（plan 4.6）
        val url = proxy.playUrlSafe(src.backendId, src.path)
        // 2026-11-18 日志脱敏：「/m/ 之后的 token」按 base64url 解开就是 sftp://user@内网IP:端口/完整路径，
        // 原样落日志等于把内网地址与目录结构一起送出去（截图 / 崩溃报告 / 分享日志都会带走它）。
        // 只留 scheme、端口、载荷长度，再附一个**打码后**的文件名：排障要的是「哪个文件」，不是「哪台机器的哪个目录」。
        AppLog.d(TAG, "LibVLC 播放地址：" + redactProxyUrl(url) + " · 文件 " + redactPath(src.path))
        val vlcMedia = createMedia(url)
        try {
            player.media = vlcMedia
        } catch (t: IllegalStateException) {
            // 极端情况下（MediaPlayer 已释放）不要崩在播放页，上报成引擎错误由上层提示
            moveTo(EngineState.Error("LibVLC 装载媒体失败：" + ErrorText.of(t, "未知原因"), t))
            vlcMedia.release()
            return
        }
        // MediaPlayer 已经持有 Media 的引用，这里释放本地引用（libvlc-android 的既定用法）
        vlcMedia.release()
        AppLog.i(TAG, "setMedia 完成，准备 play")
        // 换源＝新的一次播放：视频输出重试预算跟着重置（否则上一个文件用完 3 次后新源不再自愈）
        outputRetryCount = 0
        // 换源是**允许离开 Ended** 的两条明确路径之一（见 [EngineStateLatch.reset]）：换片了就不该再是"播完了"，
        // 所以这里走显式重置；普通事件一律没有这个待遇
        resetTo(EngineState.Preparing)
    }

    /** 造一个带解码与缓存选项的 VLC 媒体。 */
    private fun createMedia(url: String): Media {
        val safe = cache.sanitized()
        return Media(libVlc, Uri.parse(url)).apply {
            // 网络缓存按连接类型取（LAN 1500 / WAN 4000，见 VlcCachePolicy）；经回环代理时以它为准
            addOption(":network-caching=" + safe.networkCachingMs)
            addOption(":file-caching=" + safe.fileCachingMs)
            // Android 硬解的真正开关：false = :codec=all（软解），true = :codec=mediacodec_ndk,all。
            // 2026-11-12 用户要求「设置为硬解模式」⇒ 自动 / 强制硬解两档都按 force 走（Android 上
            // force 只影响极老的机型黑名单，正常机型等价于硬解优先）。
            val hardware = mode != DecoderMode.FORCE_SW
            setHWDecoderEnabled(hardware, hardware)
        }
    }

    override fun prepare() {
        // LibVLC 没有独立的 prepare：play() 时才真正打开媒体；这里只推进状态，真实进度由事件回调更新。
        //
        // **必须是事件（moveTo），不能是重置**：prepare() 会被"换集 / 换内核恢复"在 setMedia 之后调，
        // 那时状态本来就是 Preparing，走事件等于空操作；万一有人在 Ended 之后单独调它（例如将来加
        // 一个"准备播放"的入口），终态也必须留住——一旦这里把 Ended 冲成 Preparing，[play] 就会
        // 以为"还没播完"，跳过 [restartAfterEnded]，而 LibVLC 的输入线程早已结束，点播放变成静默无反应。
        if (media != null) moveTo(EngineState.Preparing)
    }

    override fun play() {
        if (released) return
        // **播完之后再播放**（单曲循环 / 播完手动点播放）：LibVLC 的输入线程在播完时就结束了，
        // 此时 player.play() 只是给一个已死的输入线程发 PLAYING_S，什么都不会发生
        //（VLC 3.0 `lib/media_player.c`：p_thread 非空就只 input_Control(INPUT_SET_STATE)）。
        // 必须重新装载媒体，让 LibVLC 再起一个新的输入线程。
        if (state.value is EngineState.Ended) restartAfterEnded()
        if (!requestAudioFocus()) {
            moveTo(EngineState.Error("音频焦点被其它应用占用，暂时不能播放（可稍后再试）"))
            return
        }
        // Compose 还没把视频视图挂上：先记下，等 attach / TextureView available 后再真正起播。
        // 否则 VLC 可能停在 0:00 黑屏（真机截图：标题/控制都在、进度 0:00、时长 --:--）。
        val surface = attachedView as? VlcVideoSurface
        if (surface != null && (!videoOutputAttached || !surface.textureView.isAvailable)) {
            pendingPlay = true
            // 事件而非重置：等待输出就绪只是"还没起来"，不构成"这一遍重新开始"
            moveTo(EngineState.Preparing)
            AppLog.i(TAG, "视频输出尚未就绪，等待 attach/available 后起播")
            surface.postDelayed(pendingPlayRunnable, 50)
            return
        }
        if (attachedView is VLCVideoLayout && !videoOutputAttached) {
            pendingPlay = true
            moveTo(EngineState.Preparing)
            AppLog.i(TAG, "视频视图尚未挂载，等待 attach 后起播")
            return
        }
        AppLog.i(TAG, "调用 player.play()")
        player.play()
        scheduleOutputRetry()
    }

    /**
     * 播完之后的"从头再来"：重新装载同一个媒体。
     *
     * `libvlc_media_player_set_media` 会先 release 掉旧输入线程（stop + close），
     * 再由 `play()` 新建一个输入 —— 这是 LibVLC 里唯一可靠的"播完再播"路径，
     * 单曲循环与"播完点播放"都靠它（2026-11-20）。
     */
    private fun restartAfterEnded() {
        val src = media ?: return
        AppLog.i(TAG, "播完后重新起播：重新装载媒体（LibVLC 的输入线程在播完时已结束）")
        setMedia(src)
    }

    override fun pause() {
        if (released) return
        pendingPlay = false
        attachedView?.removeCallbacks(outputRetryRunnable)
        (attachedView as? VlcVideoSurface)?.removeCallbacks(pendingPlayRunnable)
        if (player.isPlaying) player.pause()
        abandonAudioFocus()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        // 播完之后输入线程已经结束，setTime 落不到任何东西上；真正让画面回来的是
        // [play] 里的"重新装载媒体"（2026-11-20）。这里直接不动，避免对已结束的输入做无用调用。
        if (state.value is EngineState.Ended) return
        // 第二个参数 true = fast seek（关键帧对齐，VLC 自己选最近可解码点）
        player.setTime(positionMs.coerceAtLeast(0L), true)
    }

    override fun setSpeed(x: Float) {
        playbackSpeed = EngineSwitchPlanner.sanitizeSpeed(x)
        player.rate = playbackSpeed
    }

    /**
     * 音量：LibVLC 自己就支持 0..200（单位是百分比），所以 200% 上限在这里是原生能力，
     * 不需要像 Media3 那样外挂音效增益。
     */
    override fun setVolume(volume: Float) {
        playbackVolume = PlayerEngine.sanitizeVolume(volume)
        applyVolumeToPlayer()
    }

    /** 把 [playbackVolume]（必要时乘上 duck 系数）写到 LibVLC。 */
    private fun applyVolumeToPlayer() {
        val effective = if (duckedByFocus) playbackVolume * 0.2f else playbackVolume
        try {
            player.volume = (effective * 100f).roundToInt().coerceIn(0, 200)
        } catch (t: RuntimeException) {
            AppLog.w(TAG, "设置 LibVLC 音量失败", t)
        }
    }

    /** 申请音频焦点；系统不给就不播（与 Media3 的行为一致）。 */
    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val request = focusRequest ?: return true
        val result = audioManager?.requestAudioFocus(request) ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        hasAudioFocus = false
        focusRequest?.let { runCatching { audioManager?.abandonAudioFocusRequest(it) } }
    }

    /** 焦点变化：来电/其它播放器抢走时暂停，回来后自动续播；duck 时压低到 20%。 */
    private fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                duckedByFocus = false
                applyVolumeToPlayer()
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    player.play()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                hasAudioFocus = false
                if (player.isPlaying) player.pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (player.isPlaying) {
                    resumeOnFocusGain = true
                    player.pause()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (playbackVolume > 0.2f) {
                    duckedByFocus = true
                    applyVolumeToPlayer()
                }
            }
        }
    }

    override fun setResizeMode(mode: ResizeMode) {
        resize = mode
        (attachedView as? VlcVideoSurface)?.setResizeMode(mode)
        // 旧 VLCVideoLayout 路径仍交给 VLC；自算缩放路径不依赖它
        runCatching { player.setVideoScale(mode.toVlcScaleType()) }
    }

    override fun setDecoderMode(mode: DecoderMode) {
        if (mode == this.mode) return
        this.mode = mode
        AppLog.i(TAG, "LibVLC 解码模式切换为「" + mode.label + "」：avcodec-hw 是实例级选项，重建 LibVLC 管线")
        rebuildPipeline()
    }

    /** 诊断卡一行：这一场用的是硬解还是软解、网络缓存多少。 */
    override fun metricsLine(): String =
        "LibVLC · " + mode.label + " · 网络缓存 " + cache.sanitized().networkCachingMs + " ms"

    override fun positionMs(): Long = player.time.coerceAtLeast(0L)

    override fun durationMs(): Long = player.length.coerceAtLeast(0L)

    override fun recoverVideoOutput() {
        val view = attachedView ?: return
        val wasPlaying = player.isPlaying
        AppLog.i(TAG, "后台回前台：重新挂载视频输出（wasPlaying=" + wasPlaying + "）")
        runCatching { player.detachViews() }
        videoOutputAttached = false
        pendingPlay = wasPlaying
        when (view) {
            is VLCVideoLayout -> attachLayoutIfReady(view)
            is VlcVideoSurface -> attachSurfaceIfReady(view)
        }
    }

    override fun release() {
        if (released) return
        released = true
        pendingPlay = false
        attachedView?.removeCallbacks(outputRetryRunnable)
        (attachedView as? VlcVideoSurface)?.let { surface ->
            surface.removeCallbacks(pendingPlayRunnable)
            surface.removeCallbacks(outputRetryRunnable)
            surface.removeOnLayoutChangeListener(surfaceLayoutListener)
            surface.removeOnAttachStateChangeListener(surfaceAttachListener)
        }
        videoOutputAttached = false
        (attachedView as? VLCVideoLayout)?.let { layout ->
            layout.removeOnAttachStateChangeListener(attachStateListener)
            layout.removeOnLayoutChangeListener(surfaceLayoutListener)
        }
        try {
            player.setEventListener(null)
            player.detachViews()
            player.release()
        } catch (t: RuntimeException) {
            AppLog.w(TAG, "释放 MediaPlayer 时出错", t)
        }
        try {
            libVlc.release()
        } catch (t: RuntimeException) {
            AppLog.w(TAG, "释放 LibVLC 时出错", t)
        }
        abandonAudioFocus()
        media = null
        attachedView = null
        // 释放是离开 Ended 的另一条明确路径：没有媒体了，回 Idle 才是实话（见 [EngineStateLatch.reset]）
        resetTo(EngineState.Idle)
    }

    // ------------------------------------------------------------------ 内部实现

    private fun buildLibVlc(mode: DecoderMode): LibVLC {
        val safe = cache.sanitized()
        return LibVLC(
            appContext,
            arrayListOf(
                "--no-video-title-show",
                "--no-snapshot-preview",
                "--network-caching=" + safe.networkCachingMs,
                "--file-caching=" + safe.fileCachingMs,
            ),
        )
    }

    private fun buildPlayer(): MediaPlayer = MediaPlayer(libVlc).apply {
        setEventListener(listener)
    }

    /**
     * 重建 LibVLC 管线（改解码档位）：保留位置/倍速/播放状态/媒体与视图挂载。
     *
     * 代价：一次完整的解码器重启（短暂黑屏 + 重新缓冲），属用户主动改档位的低频操作。
     */
    private fun rebuildPipeline() {
        val position = positionMs()
        val wasPlaying = player.isPlaying
        val currentSpeed = playbackSpeed
        val currentMedia = media
        try {
            player.setEventListener(null)
            player.detachViews()
            player.release()
        } catch (t: RuntimeException) {
            AppLog.w(TAG, "重建前释放 MediaPlayer 出错", t)
        }
        libVlc.release()
        libVlc = buildLibVlc(mode)
        player = buildPlayer()
        attachedView?.let { attachVideoOutput(it) }
        if (currentMedia != null) {
            setMedia(currentMedia)
            player.rate = currentSpeed
            if (position > 0L) seekTo(position)
            if (wasPlaying) play()
        } else {
            // 重建管线时没有媒体：既没有"在播"也没有"播完"，只能是 Idle（显式重置）
            resetTo(EngineState.Idle)
        }
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> moveTo(EngineState.Preparing)

            // Vout 出现后视频尺寸/画面窗口才稳定：自算缩放容器拿到轨道尺寸，重排一次
            MediaPlayer.Event.Vout -> {
                (attachedView as? VlcVideoSurface)?.removeCallbacks(outputRetryRunnable)
                updateVideoSize()
                updateVideoWindowSize()
                applyVideoScale()
            }

            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESSelected,
            -> updateVideoSize()

            // 100 = 缓冲完成。VLC 在 Playing 之后还可能补发一次 Buffering(100)，
            // 如果一律当 Preparing，界面会一直停在“正在缓冲…”（真机截图复现）。
            MediaPlayer.Event.Buffering -> moveTo(
                if (event.buffering >= 100f) EngineState.Ready else EngineState.Preparing,
            )
            MediaPlayer.Event.Playing -> {
                (attachedView as? VlcVideoSurface)?.removeCallbacks(outputRetryRunnable)
                moveTo(EngineState.Ready)
            }

            // **Stopped 不能盖掉 Ended**（2026-11-20）：LibVLC 播完时同一个输入线程会先发
            // EndReached、紧接着发 Stopped（INPUT_EVENT_DEAD），见 [moveTo] 的说明。
            MediaPlayer.Event.Paused,
            MediaPlayer.Event.Stopped,
            MediaPlayer.Event.SeekableChanged,
            MediaPlayer.Event.LengthChanged,
            -> moveTo(EngineState.Ready)

            MediaPlayer.Event.EndReached -> {
                abandonAudioFocus()
                // 这条日志是"播完钩子真的到了"的现场证据（真机排障要它）
                AppLog.i(TAG, "LibVLC 播完（EndReached）：位置 " + positionMs() + " / 时长 " + durationMs())
                moveTo(EngineState.Ended)
            }
            MediaPlayer.Event.EncounteredError -> moveTo(
                EngineState.Error(
                    "LibVLC 无法播放该媒体（" + (media?.displayName ?: "未知媒体") + "），可尝试改解码模式或换回 Media3 内核",
                ),
            )

            else -> Unit
        }
    }

    /**
     * **内核事件**的唯一入口：播完是终态，普通事件不许把它冲掉。
     *
     * 现场链路（VLC 3.0，`src/input/input.c` 的 `Run()`）：
     * `MainLoop()` → `End()` → `input_ChangeState(END_S)` ⇒ `libvlc_MediaPlayerEndReached`
     * → （同一线程紧接着）`input_SendEventDead()` ⇒ `libvlc_MediaPlayerStopped`。
     * 以前 `Stopped` 直接写 Ready，播放页 500 ms 一轮的采样永远看不到 Ended，
     * 「开着循环，播完也不自动下一集」的根因就在这里。
     *
     * 适用：LibVLC 回调、prepare()/play() 的"输出未就绪"、各类失败上报。
     * 不适用：换源与释放——那两条是"这一遍结束了/换了一个"，走 [resetTo]。
     */
    private fun moveTo(next: EngineState) = stateHolder.event(next)

    /**
     * **显式重置**的唯一入口：只有 [setMedia]（换片 → Preparing）与 [release]（释放 → Idle）能调。
     *
     * 这是离开 [EngineState.Ended] 的合法方式；别的调用点想让状态动，请走 [moveTo]。
     * 特别地："播完再播"不要在这里重置——交给 [play] 里的 [restartAfterEnded]（重新装载同一个媒体），
     * 否则 Ended 被提前清掉，play() 会跳过重启、对着已死的输入线程发 PLAYING_S（静默无反应）。
     */
    private fun resetTo(next: EngineState) = stateHolder.reset(next)

    /** [ResizeMode] → LibVLC 的画面缩放档位。 */
    private fun ResizeMode.toVlcScaleType(): MediaPlayer.ScaleType = when (this) {
        ResizeMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        ResizeMode.CROP -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
        ResizeMode.STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
        ResizeMode.ORIGINAL -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
    }

    private companion object {
        const val TAG = "engine"
    }
}
