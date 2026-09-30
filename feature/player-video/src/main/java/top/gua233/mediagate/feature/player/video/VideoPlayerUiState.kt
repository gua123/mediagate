package io.github.gua123.mediagate.feature.player.video

import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.EngineSwitchPlanner
import io.github.gua123.mediagate.media.engine.ResizeMode
import io.github.gua123.mediagate.media.engine.SwitchPlan

/** 播放页状态机（R9/R10/R18）。 */
enum class VideoPlayerStatus {

    /** 正在列同目录 / 建内核 / 起播。 */
    LOADING,

    /** 内核已就绪（是否在播看 [VideoPlayerUiState.playing]）。 */
    READY,

    /** 失败（分类见 [VideoErrorKind]）。 */
    ERROR,
}

/** 失败分类：存储类失败按语义子类分（R2），内核解码类失败统一 [PLAYBACK]。 */
enum class VideoErrorKind {
    ACCESS_DENIED,
    NOT_FOUND,
    NOT_SUPPORTED,
    NETWORK,
    AUTH,

    /** 内核上报的播放/解码失败（R10 自动降级的触发点）。 */
    PLAYBACK,

    UNKNOWN,
}

/**
 * 视频播放页状态（R4 拖拽 / R9 内核 / R10 解码 / R18 断点续播）。
 *
 * 不可变 + 纯数据：所有变更都走 [reduce]，因此「状态迁移」可以在纯 JVM 单测里逐条覆盖。
 * **注意**：视频输出视图（PlayerView / SurfaceView）不在这里，它由 ViewModel 的另一条流
 * 单独暴露（View 不是纯数据，放进来会破坏归约的纯粹性）。
 */
data class VideoPlayerUiState(
    /** 状态机。 */
    val status: VideoPlayerStatus = VideoPlayerStatus.LOADING,
    /** 当前播放的后端内路径。 */
    val path: String = "",
    /** 当前条目的展示名（文件名）。 */
    val title: String = "",
    /** 当前内核（R9）。 */
    val engineKind: EngineKind = EngineKind.MEDIA3,
    /** 当前解码档位（R10）。 */
    val decoderMode: DecoderMode = DecoderMode.AUTO_HW,
    /** 当前画面缩放档位。 */
    val resizeMode: ResizeMode = ResizeMode.FIT,
    /** 当前倍速。 */
    val speed: Float = VideoPlayerMath.DEFAULT_SPEED,
    /** 是否正在播放。 */
    val playing: Boolean = false,
    /** 是否在缓冲/准备中。 */
    val buffering: Boolean = false,
    /** 是否已播到结尾。 */
    val ended: Boolean = false,
    /** 当前播放位置（毫秒）。 */
    val positionMs: Long = 0L,
    /** 总时长（毫秒）；未知为 0。 */
    val durationMs: Long = 0L,
    /** 用户是否正在拖拽进度条（拖拽中不被播放进度覆盖，R4）。 */
    val dragging: Boolean = false,
    /** 拖拽中的目标位置（毫秒）。 */
    val dragPositionMs: Long = 0L,
    /** 是否正在切换内核/解码器（R9/R10：切换期间显示「正在切换解码器…」）。 */
    val switching: Boolean = false,
    /** 切换中的说明文案（由 [EngineSwitchPlanner.describe] 生成）。 */
    val switchMessage: String? = null,
    /** 同内核重建解码器时的「短暂黑屏」提示（R10）。 */
    val blackoutHint: Boolean = false,
    /** 失败分类；[VideoPlayerStatus.ERROR] 时非空。 */
    val errorKind: VideoErrorKind? = null,
    /** 具体错误信息（后端或内核给出，可为 null）。 */
    val errorDetail: String? = null,
    /** 失败是否还能一键切 LibVLC 续播（plan 4.6 自动降级链）。 */
    val canFallback: Boolean = false,
    /** 是否正在提示「已从上次位置继续播放」（R18）。 */
    val resumeHint: Boolean = false,
    /** 同目录上下集队列（视频 + 音频，按名称排序）。 */
    val siblingPaths: List<String> = emptyList(),
    /** 当前是第几集（0 基）。 */
    val siblingIndex: Int = 0,
) {

    /** 队列里有多少集。 */
    val count: Int get() = siblingPaths.size

    /** 当前集号（从 1 开始）；队列为空时为 0。 */
    val position: Int get() = if (siblingPaths.isEmpty()) 0 else siblingIndex + 1

    /** 是否有可切换的上下集（至少两集）。 */
    val canSwitch: Boolean get() = siblingPaths.size > 1

    /** 能否切下一集。 */
    val canNext: Boolean get() = VideoPlayerMath.canGoNext(siblingIndex, siblingPaths.size)

    /** 能否切上一集。 */
    val canPrevious: Boolean get() = VideoPlayerMath.canGoPrevious(siblingIndex)

    /** 进度条要显示的毫秒数：拖拽中优先显示拖到的位置（R4）。 */
    val displayPositionMs: Long get() = if (dragging) dragPositionMs else positionMs

    /** 进度条比例（0..1）。 */
    val progress: Float get() = VideoPlayerMath.seekRatio(displayPositionMs, durationMs)

    /** 已播放时长文案（m:ss）。 */
    val positionText: String get() = VideoPlayerMath.formatDuration(displayPositionMs)

    /** 总时长文案；未知时给占位符，而不是看起来像「0 秒」。 */
    val durationText: String
        get() = if (durationMs <= 0L) VideoPlayerMath.UNKNOWN_TIME else VideoPlayerMath.formatDuration(durationMs)

    /** 倍速文案（1×）。 */
    val speedLabel: String get() = VideoPlayerMath.speedLabel(speed)

    /** 当前内核展示名（Media3 / LibVLC）。 */
    val engineLabel: String get() = engineKind.label

    /** 点「内核」按钮会切到的目标内核（R9：Media3 与 LibVLC 互切）。 */
    val otherEngine: EngineKind
        get() = if (engineKind == EngineKind.MEDIA3) EngineKind.VLC else EngineKind.MEDIA3

    /** 目标内核展示名。 */
    val otherEngineLabel: String get() = otherEngine.label

    /** 解码档位展示名（R10，中文来自 media:engine 的档位定义）。 */
    val decoderLabel: String get() = decoderMode.label

    /** 缩放档位展示名。 */
    val resizeLabel: String get() = resizeMode.label
}

/** 状态事件：ViewModel 只把外部结果翻译成事件，状态迁移全在 [reduce] 里。 */
sealed interface VideoPlayerEvent {

    /** 开始加载某个路径（进入页面 / 重试）。 */
    data class LoadStarted(val path: String) : VideoPlayerEvent

    /** 同目录队列列好了（还没起播）。 */
    data class SiblingsLoaded(val paths: List<String>, val index: Int) : VideoPlayerEvent

    /** 切到队列里的另一集（复用同一个内核，不重建）。 */
    data class EpisodeOpened(val paths: List<String>, val index: Int) : VideoPlayerEvent

    /** 内核已建好并装载了媒体。 */
    data class EngineAttached(val kind: EngineKind, val decoderMode: DecoderMode) : VideoPlayerEvent

    /** 内核轮询：位置 / 时长 / 播放中 / 缓冲 / 结束。 */
    data class Tick(
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val buffering: Boolean,
        val ended: Boolean,
    ) : VideoPlayerEvent

    /** 倍速变了。 */
    data class SpeedChanged(val speed: Float) : VideoPlayerEvent

    /** 缩放档位变了。 */
    data class ResizeModeChanged(val mode: ResizeMode) : VideoPlayerEvent

    /** 解码档位选择变了但还没落到内核上（例如内核还没建出来）。 */
    data class DecoderModeChanged(val mode: DecoderMode) : VideoPlayerEvent

    /** 切换开始：状态进入「正在切换解码器」（R9/R10）。 */
    data class SwitchPlanned(val plan: SwitchPlan) : VideoPlayerEvent

    /** 切换完成：新内核已按恢复清单起来。 */
    data class SwitchCompleted(
        val kind: EngineKind,
        val decoderMode: DecoderMode,
        val positionMs: Long,
        val playing: Boolean,
    ) : VideoPlayerEvent

    /** 切换失败（建不出新内核）。 */
    data class SwitchFailed(val detail: String?, val canFallback: Boolean) : VideoPlayerEvent

    /** 黑屏/切换提示到时间收掉。 */
    data object SwitchHintCleared : VideoPlayerEvent

    /** 播放失败（内核错误或存储错误）。 */
    data class Failed(val kind: VideoErrorKind, val detail: String?, val canFallback: Boolean) : VideoPlayerEvent

    /** 读到了断点续播位置（R18）。 */
    data class ResumeAvailable(val positionMs: Long) : VideoPlayerEvent

    /** 续播提示到时间收掉。 */
    data object ResumeHintCleared : VideoPlayerEvent

    /** 开始拖拽进度条（R4）。 */
    data object SeekStarted : VideoPlayerEvent

    /** 拖拽中（ratio 是 0..1 的目标比例）。 */
    data class SeekChanged(val ratio: Float) : VideoPlayerEvent

    /** 松手：把拖到的位置提交给内核。 */
    data object SeekFinished : VideoPlayerEvent
}

/**
 * 状态归约（纯函数）：新状态 = 旧状态.reduce(事件)。
 *
 * 不抛异常、不碰 IO、不依赖 Android，因此所有分支都能在 JVM 单测里覆盖。
 *
 * 拖拽与播放进度的关系（R4）：
 * - 拖拽中：状态照旧吸收内核轮询来的真实 [VideoPlayerUiState.positionMs]，但**页面显示的是**
 *   [VideoPlayerUiState.displayPositionMs]（拖到的位置），所以进度条不会被回弹；
 * - 松手：把拖到的位置当作当前位置（ViewModel 负责真正 seek）。
 */
fun VideoPlayerUiState.reduce(event: VideoPlayerEvent): VideoPlayerUiState = when (event) {
    is VideoPlayerEvent.LoadStarted -> copy(
        status = VideoPlayerStatus.LOADING,
        path = event.path,
        title = VideoPlayerMath.fileNameOf(event.path),
        playing = false,
        buffering = false,
        ended = false,
        positionMs = 0L,
        durationMs = 0L,
        dragging = false,
        dragPositionMs = 0L,
        switching = false,
        switchMessage = null,
        blackoutHint = false,
        errorKind = null,
        errorDetail = null,
        canFallback = false,
        resumeHint = false,
    )

    is VideoPlayerEvent.SiblingsLoaded -> withQueue(event.paths, event.index)

    is VideoPlayerEvent.EpisodeOpened -> withQueue(event.paths, event.index).copy(
        status = VideoPlayerStatus.READY,
        playing = false,
        buffering = true,
        ended = false,
        positionMs = 0L,
        durationMs = 0L,
        dragging = false,
        dragPositionMs = 0L,
        errorKind = null,
        errorDetail = null,
        canFallback = false,
        resumeHint = false,
    )

    // 装载内核后紧接着就起播（见 ViewModel.open），这里直接给界面一个"在播"的即时反馈，
    // 不用等 500 ms 后的第一次采样；真实状态随后由 Tick 覆盖
    is VideoPlayerEvent.EngineAttached -> copy(
        status = VideoPlayerStatus.READY,
        engineKind = event.kind,
        decoderMode = event.decoderMode,
        playing = true,
        buffering = true,
        ended = false,
        errorKind = null,
        errorDetail = null,
        canFallback = false,
    )

    is VideoPlayerEvent.Tick -> copy(
        positionMs = event.positionMs.coerceAtLeast(0L),
        durationMs = event.durationMs.coerceAtLeast(0L),
        playing = event.playing,
        buffering = event.buffering,
        ended = event.ended,
    )

    is VideoPlayerEvent.SpeedChanged -> copy(speed = event.speed)

    is VideoPlayerEvent.ResizeModeChanged -> copy(resizeMode = event.mode)

    is VideoPlayerEvent.DecoderModeChanged -> copy(decoderMode = event.mode)

    is VideoPlayerEvent.SwitchPlanned -> copy(
        switching = true,
        switchMessage = EngineSwitchPlanner.describe(event.plan),
        blackoutHint = event.plan.rebuildOnly,
        errorKind = null,
        errorDetail = null,
        canFallback = false,
    )

    is VideoPlayerEvent.SwitchCompleted -> copy(
        status = VideoPlayerStatus.READY,
        engineKind = event.kind,
        decoderMode = event.decoderMode,
        positionMs = event.positionMs.coerceAtLeast(0L),
        playing = event.playing,
        buffering = true,
        ended = false,
        switching = false,
        switchMessage = null,
        errorKind = null,
        errorDetail = null,
        canFallback = false,
        dragging = false,
        dragPositionMs = 0L,
    )

    is VideoPlayerEvent.SwitchFailed -> copy(
        status = VideoPlayerStatus.ERROR,
        switching = false,
        switchMessage = null,
        blackoutHint = false,
        errorKind = VideoErrorKind.PLAYBACK,
        errorDetail = event.detail,
        canFallback = event.canFallback,
    )

    VideoPlayerEvent.SwitchHintCleared -> copy(
        switching = false,
        switchMessage = null,
        blackoutHint = false,
    )

    is VideoPlayerEvent.Failed -> copy(
        status = VideoPlayerStatus.ERROR,
        errorKind = event.kind,
        errorDetail = event.detail,
        canFallback = event.canFallback,
    )

    is VideoPlayerEvent.ResumeAvailable -> copy(
        resumeHint = true,
        positionMs = event.positionMs.coerceAtLeast(0L),
    )

    VideoPlayerEvent.ResumeHintCleared -> copy(resumeHint = false)

    VideoPlayerEvent.SeekStarted -> copy(dragging = true, dragPositionMs = positionMs)

    is VideoPlayerEvent.SeekChanged -> copy(
        dragging = true,
        dragPositionMs = VideoPlayerMath.positionOfRatio(event.ratio, durationMs),
    )

    VideoPlayerEvent.SeekFinished -> copy(
        dragging = false,
        positionMs = dragPositionMs,
        ended = false,
    )
}

/** 换队列/换集：路径与集号一起更新，集号越界会被钳进合法范围。 */
private fun VideoPlayerUiState.withQueue(paths: List<String>, index: Int): VideoPlayerUiState {
    if (paths.isEmpty()) return copy(siblingPaths = emptyList(), siblingIndex = 0)
    val clamped = index.coerceIn(0, paths.lastIndex)
    val path = paths[clamped]
    return copy(
        siblingPaths = paths,
        siblingIndex = clamped,
        path = path,
        title = VideoPlayerMath.fileNameOf(path),
    )
}

/** 存储/内核异常 → 页面错误分类（纯函数）。 */
object VideoPlayerErrors {

    /** 把 [StorageException] 的语义子类翻译成页面分类；不认识的一律 [VideoErrorKind.UNKNOWN]。 */
    fun classify(throwable: Throwable): VideoErrorKind = when (throwable) {
        is StorageException.AccessDenied -> VideoErrorKind.ACCESS_DENIED
        is StorageException.NotFound -> VideoErrorKind.NOT_FOUND
        is StorageException.NotSupported -> VideoErrorKind.NOT_SUPPORTED
        is StorageException.Network -> VideoErrorKind.NETWORK
        is StorageException.Auth -> VideoErrorKind.AUTH
        else -> VideoErrorKind.UNKNOWN
    }
}
