package io.github.gua123.mediagate.media.engine

import android.content.Context
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
import org.videolan.libvlc.interfaces.IMedia
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.media.proxy.LoopbackHttpProxy
import io.github.gua123.mediagate.media.proxy.MediaUriCodec

/**
 * LibVLC（libvlc-all 3.7.6）兜底内核——plan 4.6 引擎对照表第二行（R9/R10/R3）。
 *
 * 三个关键点：
 * 1. **媒体源走本机回环 HTTP 代理**（[LoopbackHttpProxy]）：`BaseUrl + MediaUri 编码` →
 *    `http://127.0.0.1:<port>/m/<backendId>/<path>`，字节仍由同一个 StorageBackend 经
 *    `openRead` 供给，于是 LibVLC 不需要认识 WebDAV/SFTP/FTP，也没有第二套取数逻辑；
 *    代理支持 Range，VLC 的拖拽 seek 因此可用（R4）。
 * 2. **硬解/软解**：媒体级 `avcodec-hw=any|none` + `Media.setHWDecoderEnabled(enabled, force)`
 *    （libvlc-android 的设备自适应封装，会再追加 `:codec=...`）；LibVLC 实例本身也按档位带上
 *    `--avcodec-hw=...` 实例选项。改档位时重建 LibVLC 实例（选项是实例级的），位置/倍速/播放
 *    状态由本类恢复——这是真机能验的部分，本轮只保证编译与逻辑正确（见报告的真机清单）。
 * 3. **视频输出**：挂到外部传入的 [SurfaceView] / [TextureView]（`IVLCVout`），
 *    Compose 侧用 AndroidView 传入；[videoView] 返回同一个 View。
 *
 * 线程：所有公开方法都在主线程调用（LibVLC 约定），[MediaPlayer] 的事件回调也在主线程。
 *
 * @param context 任意 Context（内部取 applicationContext）。
 * @param proxy 回环代理（由 App 层创建并共享给「分享播放地址」）。
 * @param initialDecoderMode 初始解码档位。
 * @param videoView 初始视频输出视图（可后置用 [attachVideoOutput] 挂）。
 */
class VlcEngine(
    context: Context,
    private val proxy: LoopbackHttpProxy,
    initialDecoderMode: DecoderMode = DecoderMode.AUTO_HW,
    videoView: View? = null,
) : PlayerEngine {

    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override val kind: EngineKind = EngineKind.VLC

    private val subtitles = DefaultSubtitleTrackController { applySubtitleState(it) }

    private var mode: DecoderMode = initialDecoderMode

    override val decoderMode: DecoderMode get() = mode

    private var resize: ResizeMode = ResizeMode.FIT

    override val resizeMode: ResizeMode get() = resize

    private var playbackSpeed: Float = EngineSwitchPlanner.DEFAULT_SPEED

    override val speed: Float get() = playbackSpeed

    private var media: MediaSourceRef? = null

    override val currentMedia: MediaSourceRef? get() = media

    override val isPlaying: Boolean get() = player.isPlaying

    /** 视频输出视图（SurfaceView / TextureView），由调用方提供。 */
    private var attachedView: View? = videoView

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
        val vout = player.vlcVout
        when (view) {
            null -> {
                if (vout.areViewsAttached()) vout.detachViews()
            }

            is SurfaceView -> vout.setVideoView(view)
            is TextureView -> vout.setVideoView(view)

            else -> {
                AppLog.w(TAG, "LibVLC 视频输出只支持 SurfaceView / TextureView：" + view.javaClass.name)
                return
            }
        }
        attachedView = view
        if (view != null && !vout.areViewsAttached()) vout.attachViews()
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
        // 字幕设置要跟着新媒体走（slave 是媒体级的）
        applySubtitleState(subtitles.state.value)
    }

    /** 造一个带解码与缓存选项的 VLC 媒体。 */
    private fun createMedia(url: String): Media = Media(libVlc, Uri.parse(url)).apply {
        addOption(":network-caching=1500")
        addOption(":file-caching=1500")
        // 显式 avcodec-hw 开关（任务口径）：none = 软解，any = 硬解优先
        addOption(mode.vlcAvcodecHwOption)
        // 设备自适应封装：enabled=false 时追加 :codec=all（真软解），true 时按机型追加 mediacodec/iomx
        setHWDecoderEnabled(mode.vlcHwDecoderEnabled, mode.vlcHwDecoderForce)
    }

    override fun prepare() {
        // LibVLC 没有独立的 prepare：play() 时才真正打开媒体；这里只推进状态，真实进度由事件回调更新
        if (media != null) _state.value = EngineState.Preparing
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        if (player.isPlaying) player.pause()
    }

    override fun seekTo(positionMs: Long) {
        // 第二个参数 true = fast seek（关键帧对齐，VLC 自己选最近可解码点）
        player.setTime(positionMs.coerceAtLeast(0L), true)
    }

    override fun setSpeed(x: Float) {
        playbackSpeed = EngineSwitchPlanner.sanitizeSpeed(x)
        player.rate = playbackSpeed
    }

    override fun setResizeMode(mode: ResizeMode) {
        resize = mode
        player.setVideoScale(mode.toVlcScaleType())
    }

    override fun setDecoderMode(mode: DecoderMode) {
        if (mode == this.mode) return
        this.mode = mode
        AppLog.i(TAG, "LibVLC 解码模式切换为「" + mode.label + "」：avcodec-hw 是实例级选项，重建 LibVLC 管线")
        rebuildPipeline()
    }

    override fun subtitles(): SubtitleTrackController = subtitles

    override fun positionMs(): Long = player.time.coerceAtLeast(0L)

    override fun durationMs(): Long = player.length.coerceAtLeast(0L)

    override fun release() {
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
        media = null
        attachedView = null
        _state.value = EngineState.Idle
    }

    // ------------------------------------------------------------------ 内部实现

    private fun buildLibVlc(mode: DecoderMode): LibVLC = LibVLC(
        appContext,
        arrayListOf(
            "--no-video-title-show",
            "--no-snapshot-preview",
            "--network-caching=1500",
            "--file-caching=1500",
            // 实例级软/硬解开关（媒体级还有一份，双保险）
            mode.vlcInstanceOption,
        ),
    )

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
            MediaPlayer.Event.Opening, MediaPlayer.Event.Buffering -> _state.value = EngineState.Preparing
            MediaPlayer.Event.Playing,
            MediaPlayer.Event.Paused,
            MediaPlayer.Event.SeekableChanged,
            MediaPlayer.Event.LengthChanged,
            -> _state.value = EngineState.Ready

            MediaPlayer.Event.EndReached -> _state.value = EngineState.Ended
            MediaPlayer.Event.EncounteredError -> _state.value = EngineState.Error(
                "LibVLC 无法播放该媒体（" + (media?.displayName ?: "未知媒体") + "），可尝试改解码模式或换回 Media3 内核",
            )

            else -> Unit
        }
    }

    /** 把字幕状态应用到 LibVLC（R14；这里的时间轴微调是真生效的）。 */
    private fun applySubtitleState(state: SubtitleState) {
        // 外挂字幕：作为 slave 挂到当前媒体（地址同样经回环代理，VLC 自己按 Range 拉取）
        val uri = state.uri
        if (uri != null) {
            val parsed = MediaUriCodec.parse(uri)
            val url = parsed?.let { proxy.playUrl(it.backendId, it.path) }
            if (url == null) {
                AppLog.w(TAG, "外挂字幕地址无法识别：" + uri)
            } else {
                try {
                    player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(url), true)
                } catch (t: RuntimeException) {
                    AppLog.w(TAG, "挂载外挂字幕失败：" + uri, t)
                }
            }
        }
        // 开关：-1 = 关字幕，0 = 自动选轨（VLC 的既定语义）
        player.setSpuTrack(if (state.enabled) 0 else -1)
        // 时间轴微调：LibVLC 的单位是微秒，正数 = 字幕延后，与 SubtitleState.offsetMs 同向
        player.setSpuDelay(state.offsetMs * 1000L)
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
