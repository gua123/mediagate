package io.github.gua123.mediagate.media.engine

import android.content.Context
import android.graphics.Color
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.media.proxy.MediaUriCodec

/**
 * Media3（ExoPlayer 1.11.1）内核——默认档，plan 4.6 引擎对照表第一行（R9/R10/R4/HLS）。
 *
 * **数据源走注入的 [DataSource.Factory]**：生产环境由 App 层传
 * `Media3PlaybackDataSourceFactory`（media:playback 的 `BackendDataSourceFactory`，本地/远端同一套，
 * 支持 Range 拖拽 seek）。这样做的原因是模块依赖方向：`:media:playback` 已经依赖 `:media:engine`，
 * engine 不能再反向依赖 playback（会形成循环），所以数据源以构造参数注入而不是在此 new 出来。
 *
 * 硬解 / 软解（R10）落地方式与**限制**：
 * - [DecoderMode.AUTO_HW]：`MediaCodecSelector.DEFAULT`（Media3 自身硬解优先）+ 允许解码器回退；
 * - [DecoderMode.FORCE_SW]：只保留 `MediaCodecInfo.softwareOnly` 的解码器（c2.android.* / OMX.google.*）。
 *   Android 平台**没有**「关闭硬解」的公开开关，设备若一个软件解码器都没有（本项目未内置
 *   media3 FFmpeg 解码扩展 FFmpegDecoder），就只能退回默认顺序——此时会把情况写进
 *   [decoderReport] 的 note 并打日志（R10「做不到就记录并回报」）。真要软解请切 [VlcEngine]；
 * - [DecoderMode.FORCE_HW]：Android 同样没有「只许硬解」的开关，能做的是
 *   `setEnableDecoderFallback(false)`（不许换用别的解码器）+ 默认选择器（优先硬解）。
 *
 * 解码模式是**渲染器构建期参数**，改档位必须重建 ExoPlayer：本类会保留位置/倍速/播放状态并自动恢复
 * （切档位瞬间有一次短暂黑屏，R10 验收时注意）。
 *
 * 线程：所有公开方法都在主线程调用（Media3 约定）；数据层的阻塞读发生在 Media3 自己的加载线程里。
 *
 * @param context 任意 Context（内部取 applicationContext）。
 * @param dataSourceFactory 数据源工厂（生产传 BackendDataSourceFactory）。
 */
@UnstableApi
class ExoPlayerEngine(
    context: Context,
    private val dataSourceFactory: DataSource.Factory,
) : PlayerEngine {

    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override val kind: EngineKind = EngineKind.MEDIA3

    private val subtitles = DefaultSubtitleTrackController { applySubtitleState(it) }

    private var mode: DecoderMode = DecoderMode.AUTO_HW

    override val decoderMode: DecoderMode get() = mode

    private var resize: ResizeMode = ResizeMode.FIT

    override val resizeMode: ResizeMode get() = resize

    private var playbackSpeed: Float = EngineSwitchPlanner.DEFAULT_SPEED

    override val speed: Float get() = playbackSpeed

    /** 音量倍率（0..200%）；>100% 的部分由 [loudness] 增益补齐。 */
    private var playbackVolume: Float = 1f

    override val volume: Float get() = playbackVolume

    /** 软件增益（LoudnessEnhancer）：100% 以上才用得到，懒建、释放时清掉。 */
    private var loudness: LoudnessEnhancer? = null

    private var media: MediaSourceRef? = null

    override val currentMedia: MediaSourceRef? get() = media

    override val isPlaying: Boolean get() = player.isPlaying

    /** 已经写进当前 MediaItem 的外挂字幕地址（避免重复 setMediaItem 把位置冲掉）。 */
    private var appliedSubtitleUri: String? = null

    /**
     * 最近一次解码落地报告（R10「做不到就记录并回报」）。
     *
     * [DecoderMode.FORCE_SW] 下由解码器选择回调在播放线程更新（字段是 volatile），
     * 其余档位在 [setMedia] 时给出「MediaCodec 默认顺序」的说明。
     */
    @Volatile
    var decoderReport: DecoderReport? = null
        private set

    private val listener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = when (playbackState) {
                Player.STATE_IDLE -> if (media == null) EngineState.Idle else EngineState.Preparing
                Player.STATE_BUFFERING -> EngineState.Preparing
                Player.STATE_READY -> EngineState.Ready
                Player.STATE_ENDED -> EngineState.Ended
                else -> _state.value
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.e(TAG, "Media3 播放失败：" + error.errorCodeName, error)
            _state.value = EngineState.Error(describeError(error), error)
        }
    }

    private var player: ExoPlayer = buildPlayer(mode)

    private var playerView: PlayerView? = null

    // ------------------------------------------------------------------ 引擎动作

    override fun setMedia(src: MediaSourceRef) {
        media = src
        appliedSubtitleUri = subtitles.state.value.uri
        player.setMediaItem(buildMediaItem(src, subtitles.state.value))
        // 视频源始终走注入的数据源工厂（本地/远端同一套，R4 拖拽 seek 依赖它做 Range 重开）
        player.setPlaybackParameters(PlaybackParameters(playbackSpeed))
        decoderReport = defaultDecoderReport(mode)
        _state.value = EngineState.Preparing
    }

    override fun prepare() {
        if (media == null) {
            AppLog.w(TAG, "还没有装载媒体，prepare 被忽略")
            return
        }
        player.prepare()
        _state.value = EngineState.Preparing
    }

    override fun play() {
        player.play()
        // 声轨在真正开播后才有 sessionId：把 >100% 的增益在这里补一次
        applyBoost(playbackVolume)
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(clampPosition(positionMs))
    }

    override fun setSpeed(x: Float) {
        playbackSpeed = EngineSwitchPlanner.sanitizeSpeed(x)
        player.setPlaybackParameters(PlaybackParameters(playbackSpeed))
    }

    /**
     * 音量：0~100% 交给 ExoPlayer；100%~200% 用 [LoudnessEnhancer] 加增益。
     *
     * 为什么不用 ExoPlayer 直接放大：它只支持 0..1（再大就是削波失真），
     * 超过原始音量的部分**只能靠系统音效链**——LoudnessEnhancer 正是干这个的（200% ≈ +6 dB）。
     */
    override fun setVolume(volume: Float) {
        val v = PlayerEngine.sanitizeVolume(volume)
        playbackVolume = v
        player.volume = v.coerceAtMost(1f)
        applyBoost(v)
    }

    /** 把 100% 以上的部分换算成 dB 增益；声轨还没建立时先不发（[play] 里会再补一次）。 */
    private fun applyBoost(volume: Float) {
        val gainDb = if (volume <= 1f) 0 else (20.0 * log10(volume.toDouble())).roundToInt()
        try {
            val sessionId = player.audioSessionId
            if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
            val enhancer = loudness ?: LoudnessEnhancer(sessionId).also {
                it.setEnabled(true)
                loudness = it
            }
            enhancer.setTargetGain(gainDb)
        } catch (t: RuntimeException) {
            // 个别机型/音轨不支持音效链：退化成"最大 100%"，不影响播放
            AppLog.w(TAG, "音量增益不可用（仍按 100% 播放）", t)
        }
    }

    override fun setResizeMode(mode: ResizeMode) {
        resize = mode
        playerView?.setResizeMode(mode.toMedia3ResizeMode())
    }

    override fun setDecoderMode(mode: DecoderMode) {
        if (mode == this.mode) return
        this.mode = mode
        decoderReport = defaultDecoderReport(mode)
        AppLog.i(TAG, "Media3 解码模式切换为「" + mode.label + "」：渲染器是构建期参数，重建播放器")
        rebuildPlayer()
    }

    override fun subtitles(): SubtitleTrackController = subtitles

    override fun videoView(): View {
        val existing = playerView ?: PlayerView(appContext).apply {
            setUseController(false)
            setShutterBackgroundColor(Color.TRANSPARENT)
            setPlayer(this@ExoPlayerEngine.player)
        }.also { playerView = it }
        existing.setResizeMode(resize.toMedia3ResizeMode())
        return existing
    }

    override fun positionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    override fun durationMs(): Long {
        val duration = player.duration
        // C.TIME_UNSET = 时长未知（直播/尚未解析完），对上层统一成 0
        return if (duration == C.TIME_UNSET || duration < 0) 0L else duration
    }

    override fun release() {
        media = null
        appliedSubtitleUri = null
        try {
            loudness?.release()
        } catch (t: RuntimeException) {
            AppLog.w(TAG, "释放音量增益时出错", t)
        }
        loudness = null
        player.removeListener(listener)
        player.release()
        playerView?.setPlayer(null)
        _state.value = EngineState.Idle
    }

    /** 当前 ExoPlayer 实例（诊断/测试用；不要留存引用）。 */
    fun playerInstance(): ExoPlayer = player

    // ------------------------------------------------------------------ 内部实现

    private fun buildPlayer(mode: DecoderMode): ExoPlayer {
        val renderers = DefaultRenderersFactory(appContext)
            // 强制硬解时不许回退到别的解码器；其余档位允许（保证能播是第一位）
            .setEnableDecoderFallback(mode.media3EnableDecoderFallback)
            .setMediaCodecSelector(codecSelectorFor(mode))
        val built = ExoPlayer.Builder(appContext, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // R18：后台播放时保持网络与 CPU（服务被切后台后不中断）
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        built.addListener(listener)
        return built
    }

    /** 解码模式 → Media3 解码器选择器。 */
    private fun codecSelectorFor(mode: DecoderMode): MediaCodecSelector {
        if (!mode.preferSoftwareDecoder) return MediaCodecSelector.DEFAULT
        // FORCE_SW：只留 softwareOnly 的解码器；一个都没有时退回默认顺序并记录（R10 如实上报）
        return object : MediaCodecSelector {
            override fun getDecoderInfos(
                mimeType: String,
                requiresSecureDecoder: Boolean,
                requiresTunnelingDecoder: Boolean,
            ): List<MediaCodecInfo> {
                val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder,
                )
                val selection = DecoderSelection.select(
                    DecoderMode.FORCE_SW,
                    all.map { CodecCandidate(it.name, it.softwareOnly) },
                )
                val chosen = selection.ordered.mapNotNull { candidate ->
                    all.firstOrNull { it.name == candidate.name }
                }
                val note = DecoderSelection.note(DecoderMode.FORCE_SW, selection)
                decoderReport = DecoderReport(
                    requested = DecoderMode.FORCE_SW,
                    applied = chosen.firstOrNull()?.name ?: "无可用解码器",
                    softwareApplied = chosen.firstOrNull()?.softwareOnly == true,
                    note = note,
                )
                if (note != null) AppLog.w(TAG, note)
                return chosen.ifEmpty { all }
            }
        }
    }

    /** 非强制软解档位的说明（真硬解是否生效由 MediaCodec 自己决定，这里只说明选择策略）。 */
    private fun defaultDecoderReport(mode: DecoderMode): DecoderReport = DecoderReport(
        requested = mode,
        applied = if (mode == DecoderMode.FORCE_HW) {
            "MediaCodec 默认顺序（硬解优先，关闭解码器回退）"
        } else {
            "MediaCodec 默认顺序（硬解优先，可回退）"
        },
        softwareApplied = false,
        note = null,
    )

    /** 重建播放器（改解码模式）：保留位置/倍速/播放状态/媒体后恢复。 */
    private fun rebuildPlayer() {
        val previous = player
        val position = previous.currentPosition.coerceAtLeast(0L)
        val playWhenReady = previous.playWhenReady
        previous.removeListener(listener)
        previous.release()
        player = buildPlayer(mode)
        playerView?.setPlayer(player)
        val current = media
        if (current == null) {
            _state.value = EngineState.Idle
            return
        }
        appliedSubtitleUri = subtitles.state.value.uri
        player.setMediaItem(buildMediaItem(current, subtitles.state.value))
        player.setPlaybackParameters(PlaybackParameters(playbackSpeed))
        player.prepare()
        if (position > 0L) player.seekTo(position)
        player.playWhenReady = playWhenReady
        _state.value = EngineState.Preparing
    }

    private fun buildMediaItem(src: MediaSourceRef, subtitle: SubtitleState): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(src.uri)
            .setMediaId(src.path)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(src.displayName)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
        val subtitleUri = subtitle.uri
        if (subtitleUri != null) {
            // 外挂字幕（R14）：Media3 把它当成 MediaItem 的一部分，所以换字幕要重装 MediaItem
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUri))
                        .setMimeType(subtitleMimeType(subtitleUri))
                        .setLabel(subtitle.label ?: "外挂字幕")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        return builder.build()
    }

    /** 把字幕状态应用到 Media3。 */
    private fun applySubtitleState(state: SubtitleState) {
        val current = media
        if (current != null && state.uri != appliedSubtitleUri) {
            // 只有外挂轨真的换了才重装 MediaItem（重装会丢位置，这里自己恢复）
            val position = player.currentPosition.coerceAtLeast(0L)
            val playWhenReady = player.playWhenReady
            appliedSubtitleUri = state.uri
            player.setMediaItem(buildMediaItem(current, state))
            player.prepare()
            if (position > 0L) player.seekTo(position)
            player.playWhenReady = playWhenReady
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !state.enabled)
            .build()
        // 时间轴微调：Media3 1.11 没有字幕延迟 API（Cue 时间轴不可平移）。偏移量保留在
        // SubtitleTrackController 里，由播放页在渲染 Cue 时应用（R14 的 ±0.5 s 步进），
        // 换内核时也靠它保持（R9）。这里只提示一次，避免真机验收时以为开关没生效。
        if (state.offsetMs != 0L) {
            AppLog.i(TAG, "字幕偏移 " + state.offsetMs + "ms 由 UI 渲染 Cue 时应用（Media3 无字幕延迟 API）")
        }
    }

    /** 字幕文件 → Media3 的 mime（决定用哪个解析器）。 */
    private fun subtitleMimeType(uri: String): String {
        val path = MediaUriCodec.parse(uri)?.path ?: uri
        return when (path.substringAfterLast('.', "").lowercase()) {
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun clampPosition(positionMs: Long): Long {
        val duration = durationMs()
        val nonNegative = positionMs.coerceAtLeast(0L)
        return if (duration > 0L) nonNegative.coerceAtMost(duration) else nonNegative
    }

    private fun describeError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> "本机解码器无法解码该媒体（" + error.errorCodeName + "），可在播放页切到 LibVLC 内核重试"

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在或已被移走（" + error.errorCodeName + "）"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取权限（" + error.errorCodeName + "）"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "连不上远端（" + error.errorCodeName + "）"
        else -> "播放失败：" + error.errorCodeName
    }

    /** [ResizeMode] → Media3 的 AspectRatioFrameLayout 档位。 */
    private fun ResizeMode.toMedia3ResizeMode(): Int = when (this) {
        ResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        ResizeMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        ResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        // Media3 没有「原始尺寸」档，退化为适应屏幕（LibVLC 侧才有 SURFACE_ORIGINAL）
        ResizeMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private companion object {
        const val TAG = "engine"
    }
}
