package io.github.gua123.mediagate.media.enginenative

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.core.common.ErrorText
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.asRandomAccessSource
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.EngineCapabilities
import io.github.gua123.mediagate.media.engine.EngineCatalog
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.EngineState
import io.github.gua123.mediagate.media.engine.MediaSourceRef
import io.github.gua123.mediagate.media.engine.PlayerEngine
import io.github.gua123.mediagate.media.engine.ResizeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自研引擎的 [PlayerEngine] 适配层（M4）。
 *
 * 这一层只做"翻译"：把契约里的动作翻成 [NativeEngine] 的几个 JNI 调用，
 * 把 native 的状态串翻回 [EngineState]；**播放逻辑一行都不在这里**（它在 C++ 里）。
 *
 * 与另外两个内核的关键差别：
 * - 数据不是 URL 而是**我们的数据层**：prepare 时按 [MediaSourceRef] 拿到 [StorageBackend]，
 *   开一个 RandomAccessSource 交给引擎（所以本地 / SAF / WebDAV / SFTP / FTP 是同一条路）；
 * - 画面挂在我们自己的 [SurfaceView] 上：**必须等画面绑好才能起播**（native 侧要求先有 Surface），
 *   所以"数据源准备好"与"画面到了"两件事都到齐才真正开播（见 startIfReady）；
 * - 位置 / 时长直接读 native 的原子量（不经过状态串，可以频繁调）。
 *
 * ⚠️ native 侧只有一个全局播放器（一套引擎＝一个播放核心），所以本类**同一时刻只能有一个实例**。
 */
class NativeEnginePlayer(
    private val context: Context,
    private val backendProvider: () -> StorageBackend?,
    private val io: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) : PlayerEngine {

    /**
     * 自己的作用域（主线程）：打开数据源、轮询状态都挂它，[release] 时一并取消。
     *
     * 为什么不从外面传：生命周期与播放器实例严格一致，外面传进来的作用域要么活得更久（泄漏）、
     * 要么被别人取消（播放中途停摆）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val kind: EngineKind = EngineKind.NATIVE

    /** 能力从契约层的能力表取（唯一一份声明；本模块不许自己再写一遍）。 */
    override val capabilities: EngineCapabilities = EngineCatalog.capabilitiesOf(kind)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override var currentMedia: MediaSourceRef? = null
        private set

    private var resize = ResizeMode.FIT
    override val resizeMode: ResizeMode get() = resize

    private var decoder = DecoderMode.AUTO_HW
    override val decoderMode: DecoderMode get() = decoder

    private var playbackSpeed = 1f
    override val speed: Float get() = playbackSpeed

    private var playbackVolume = 1f
    override val volume: Float get() = playbackVolume

    private var source: RandomAccessMediaSource? = null
    private var opened = false
    private var surfaceReady = false
    private var playWhenReady = false

    /**
     * 打开完成之前收到的 seek（**必须先记下来**）。
     *
     * 真实踩点：切内核 / 断点续播都是「prepare() → seekTo(上次位置)」的调用顺序，
     * 而 prepare 是异步打开数据源的（要 stat + openRead，可能几百毫秒）。
     * 如果这时直接丢掉 seek，续播位置就没了 —— R9「位置保持」当场破功。
     */
    private var pendingSeekMs: Long? = null
    private var released = false
    private var pollJob: Job? = null

    @Volatile
    private var lastStatus: NativePlayerStatus? = null

    private val surfaceView: SurfaceView by lazy {
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = attachSurface(holder)

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                    attachSurface(holder)

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    // 画面没了（切后台 / 换页）就解绑：native 侧会如实报"没有画面"，不会画到野指针上
                    surfaceReady = false
                    NativeEngine.setSurface(null)
                }
            })
        }
    }

    override fun videoView(): View = surfaceView

    override val isPlaying: Boolean
        get() = lastStatus?.state == NativeEngineStateMapper.STATE_PLAYING

    override fun setMedia(src: MediaSourceRef) {
        closeSource()
        pendingSeekMs = null
        playWhenReady = false
        currentMedia = src
        lastStatus = null
        _state.value = EngineState.Idle
    }

    override fun prepare() {
        if (released) return
        val src = currentMedia
        if (src == null) {
            _state.value = EngineState.Error("还没有选择要播放的文件")
            return
        }
        closeSource()
        _state.value = EngineState.Preparing
        scope.launch {
            val openedSource = try {
                openSource(src)
            } catch (t: Throwable) {
                AppLog.w(TAG, "自研引擎打开数据源失败", t)
                _state.value = EngineState.Error(ErrorText.of(t))
                return@launch
            }
            if (released) {
                runCatching { openedSource.close() }
                return@launch
            }
            source = openedSource
            startIfReady()
        }
    }

    override fun play() {
        playWhenReady = true
        if (opened) {
            NativeEngine.resumePlayback()
        } else {
            startIfReady()
        }
    }

    override fun pause() {
        playWhenReady = false
        if (opened) NativeEngine.pausePlayback()
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        if (opened) {
            NativeEngine.seekTo(target)
        } else {
            // 还没打开完：记下来，等 startIfReady 起播时补上
            pendingSeekMs = target
        }
    }

    override fun setSpeed(x: Float) {
        // 自研引擎还没有时间伸缩（capabilities.speed = false）：这里只记下来，
        // 由上层决定"不支持就别给这个档位"。绝不假装设成功。
        playbackSpeed = x.coerceIn(0.25f, 4.0f)
    }

    override fun setResizeMode(mode: ResizeMode) {
        resize = mode
        if (opened) NativeEngine.setScaleMode(mode.ordinal)
    }

    override fun setDecoderMode(mode: DecoderMode) {
        // 目前只有 FFmpeg 软解一条路（capabilities.decoderModes = false）：记下来即可
        decoder = mode
    }

    override fun setVolume(volume: Float) {
        playbackVolume = PlayerEngine.sanitizeVolume(volume)
        if (opened) NativeEngine.setVolume(playbackVolume)
    }

    /** 播放指标（起播 / seek 等待 / 丢帧 / 欠载 / 音画偏差）；还没出第一帧时返回 null。 */
    override fun metricsLine(): String? = lastStatus?.takeIf { it.firstFrameMs > 0 }?.metricsLine()

    override fun positionMs(): Long = if (released) 0L else NativeEngine.positionMs()

    override fun durationMs(): Long = if (released) 0L else NativeEngine.durationMs()

    override fun release() {
        if (released) return
        released = true
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
        runCatching { NativeEngine.stopPlayback() }
        runCatching { NativeEngine.setSurface(null) }
        closeSource()
        currentMedia = null
        lastStatus = null
        _state.value = EngineState.Idle
    }

    /** 诊断用：最近一次 native 状态快照（掉帧数、音画偏差都在里面）。 */
    fun lastStatusSnapshot(): NativePlayerStatus? = lastStatus

    // ------------------------------------------------------------ 内部

    private fun attachSurface(holder: SurfaceHolder) {
        surfaceReady = true
        runCatching { NativeEngine.setSurface(holder.surface) }
        startIfReady()
    }

    /**
     * 两件事到齐才真正开播：**数据源准备好了** 且 **画面绑上了**。
     *
     * native 侧在 playSource 时会检查"有没有画面"，所以顺序不能反；反过来先有画面后准备也没问题，
     * 两条路都会走到这里。
     */
    private fun startIfReady() {
        if (released || opened || !surfaceReady) return
        val ready = source ?: return
        val error = NativeEngine.playSource(ready)
        if (error.isNotEmpty()) {
            _state.value = EngineState.Error(error)
            return
        }
        opened = true
        // 起播时把档位与音量一并带上（native 默认 1.0 / 适应屏幕）
        NativeEngine.setVolume(playbackVolume)
        NativeEngine.setScaleMode(resize.ordinal)
        // 打开期间攒下的 seek：补上（R9「位置保持」/ 断点续播都靠它）
        pendingSeekMs?.let { target ->
            pendingSeekMs = null
            NativeEngine.seekTo(target)
        }
        // native 的 playSource 是"打开即播"：如果调用方要的是"准备好但先别播"（切内核时原来是暂停），
        // 这里如实按停，别让用户看到"切完内核自己播起来了"
        if (!playWhenReady) NativeEngine.pausePlayback()
        startPolling()
    }

    private suspend fun openSource(src: MediaSourceRef): RandomAccessMediaSource {
        val backend = backendProvider() ?: error("当前没有可用的连接（自研引擎拿不到数据层）")
        val entry = withContext(io) { backend.stat(src.path) }
        if (entry.isDirectory) error("这是一个目录，不能播放：" + src.path)
        val stream = withContext(io) { backend.openRead(src.path, 0, -1) }
        val knownSize = if (entry.size >= 0L) entry.size else stream.length
        return RandomAccessMediaSource(stream.asRandomAccessSource(knownSize))
    }

    private fun closeSource() {
        source?.let { runCatching { it.close() } }
        source = null
        opened = false
    }

    /** 轮询 native 状态：位置 / 时长走便宜入口，状态与诊断走状态串。 */
    private fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (released) break
                val status = NativePlayerStatus.parse(NativeEngine.playerStatus())
                lastStatus = status
                val mapped = NativeEngineStateMapper.map(status)
                if (mapped != _state.value) _state.value = mapped
            }
        }
    }

    private companion object {
        const val TAG = "NativeEnginePlayer"

        /** 状态轮询间隔：够跟上界面（进度条 / 字幕覆盖层另外直接读 positionMs）。 */
        const val POLL_INTERVAL_MS = 250L
    }
}
