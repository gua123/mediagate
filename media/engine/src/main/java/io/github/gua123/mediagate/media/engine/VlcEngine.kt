package io.github.gua123.mediagate.media.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.libvlc.interfaces.IMedia
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.ErrorText
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
 * 4. **视频输出**：优先用 libvlc-android 官方的 [VLCVideoLayout]（FrameLayout 封装，内部处理
 *    视频尺寸/等比缩放/居中）；未传入视图时本类自己建一个。低层 [SurfaceView] / [TextureView]
 *    仍兼容，但**不要再拿裸 SurfaceView 当全屏容器**——实测会按视频原始像素画在左上角。
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

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    override val state: StateFlow<EngineState> = _state.asStateFlow()

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

    override val currentMedia: MediaSourceRef? get() = media

    override val isPlaying: Boolean get() = player.isPlaying

    /** 默认视频输出：**自己算缩放**的 TextureView 容器（不依赖 VLC 的 fit）。 */
    private val defaultVideoSurface: VlcVideoSurface = VlcVideoSurface(appContext)

    /** 视频输出视图（VlcVideoSurface / VLCVideoLayout / SurfaceView / TextureView）。 */
    private var attachedView: View? = videoView ?: defaultVideoSurface

    /** 视频输出是否已经真正挂到窗口/播放器（Compose 里 AndroidView 稍后才 attach）。 */
    private var videoOutputAttached = false

    /** 视频视图还没 attach 时收到的 play()：等 attach 完再起播，否则 VLC 会停在黑屏 0:00。 */
    private var pendingPlay = false

    /** TextureView 还没 available 时轮询等它（50 ms 一次），可用后自动起播。 */
    private val pendingPlayRunnable = object : Runnable {
        override fun run() {
            if (!pendingPlay) return
            val surface = attachedView as? VlcVideoSurface ?: return
            if (surface.textureView.isAvailable) {
                pendingPlay = false
                AppLog.i(TAG, "TextureView 已就绪，开始起播（之前收到过 play）")
                player.play()
            } else {
                surface.postDelayed(this, 50)
            }
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
        (attachedView as? VLCVideoLayout)?.removeOnAttachStateChangeListener(attachStateListener)
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
                vout.setVideoView(view.textureView)
                if (!vout.areViewsAttached()) vout.attachViews()
                videoOutputAttached = true
                return
            }

            // 兼容旧路径：官方 VLCVideoLayout（内部会按视频尺寸/缩放档位设置 SurfaceView）
            // **不能在这里立刻 attach**：Compose 的 AndroidView 还没把 layout 挂到窗口，
            // 内部 SurfaceView 没有有效 surface，VLC 会停在 0:00 黑屏；等 onViewAttachedToWindow。
            is VLCVideoLayout -> {
                attachedView = view
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
        if (!player.vlcVout.areViewsAttached()) {
            try {
                player.attachViews(view, null, false, false)
            } catch (t: Throwable) {
                AppLog.e(TAG, "LibVLC 视频输出挂载失败", t)
                _state.value = EngineState.Error("LibVLC 视频输出挂载失败：" + ErrorText.of(t, "未知原因"), t)
                pendingPlay = false
                return
            }
        }
        videoOutputAttached = player.vlcVout.areViewsAttached()
        applyVideoScale()
        if (pendingPlay) {
            pendingPlay = false
            AppLog.i(TAG, "视频视图已挂载，开始起播（之前收到过 play）")
            player.play()
        }
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
        val url = proxy.playUrl(src.backendId, src.path)
        AppLog.d(TAG, "LibVLC 播放地址：" + url)
        val vlcMedia = createMedia(url)
        try {
            player.media = vlcMedia
        } catch (t: IllegalStateException) {
            // 极端情况下（MediaPlayer 已释放）不要崩在播放页，上报成引擎错误由上层提示
            _state.value = EngineState.Error("LibVLC 装载媒体失败：" + ErrorText.of(t, "未知原因"), t)
            vlcMedia.release()
            return
        }
        // MediaPlayer 已经持有 Media 的引用，这里释放本地引用（libvlc-android 的既定用法）
        vlcMedia.release()
        _state.value = EngineState.Preparing
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
        // LibVLC 没有独立的 prepare：play() 时才真正打开媒体；这里只推进状态，真实进度由事件回调更新
        if (media != null) _state.value = EngineState.Preparing
    }

    override fun play() {
        if (!requestAudioFocus()) {
            _state.value = EngineState.Error("音频焦点被其它应用占用，暂时不能播放（可稍后再试）")
            return
        }
        // Compose 还没把视频视图挂上：先记下，等 attach / TextureView available 后再真正起播。
        // 否则 VLC 可能停在 0:00 黑屏（真机截图：标题/控制都在、进度 0:00、时长 --:--）。
        val surface = attachedView as? VlcVideoSurface
        if (surface != null && !surface.textureView.isAvailable) {
            pendingPlay = true
            _state.value = EngineState.Preparing
            AppLog.i(TAG, "TextureView 尚未 available，等待后起播")
            surface.postDelayed(pendingPlayRunnable, 50)
            return
        }
        if (attachedView is VLCVideoLayout && !videoOutputAttached) {
            pendingPlay = true
            _state.value = EngineState.Preparing
            AppLog.i(TAG, "视频视图尚未挂载，等待 attach 后起播")
            return
        }
        player.play()
    }

    override fun pause() {
        pendingPlay = false
        (attachedView as? VlcVideoSurface)?.removeCallbacks(pendingPlayRunnable)
        if (player.isPlaying) player.pause()
        abandonAudioFocus()
    }

    override fun seekTo(positionMs: Long) {
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

    override fun release() {
        pendingPlay = false
        (attachedView as? VlcVideoSurface)?.removeCallbacks(pendingPlayRunnable)
        videoOutputAttached = false
        (attachedView as? VLCVideoLayout)?.removeOnAttachStateChangeListener(attachStateListener)
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
        _state.value = EngineState.Idle
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
            _state.value = EngineState.Idle
        }
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> _state.value = EngineState.Preparing

            // Vout 出现后视频尺寸/画面窗口才稳定：自算缩放容器拿到轨道尺寸，重排一次
            MediaPlayer.Event.Vout -> {
                updateVideoSize()
                applyVideoScale()
            }

            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESSelected,
            -> updateVideoSize()

            // 100 = 缓冲完成。VLC 在 Playing 之后还可能补发一次 Buffering(100)，
            // 如果一律当 Preparing，界面会一直停在“正在缓冲…”（真机截图复现）。
            MediaPlayer.Event.Buffering -> _state.value =
                if (event.buffering >= 100f) EngineState.Ready else EngineState.Preparing
            MediaPlayer.Event.Playing,
            MediaPlayer.Event.Paused,
            MediaPlayer.Event.SeekableChanged,
            MediaPlayer.Event.LengthChanged,
            -> _state.value = EngineState.Ready

            MediaPlayer.Event.EndReached -> {
                abandonAudioFocus()
                _state.value = EngineState.Ended
            }
            MediaPlayer.Event.EncounteredError -> _state.value = EngineState.Error(
                "LibVLC 无法播放该媒体（" + (media?.displayName ?: "未知媒体") + "），可尝试改解码模式或换回 Media3 内核",
            )

            else -> Unit
        }
    }

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
