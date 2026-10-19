package io.github.gua123.mediagate.feature.player.video

import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.EngineCapabilities
import io.github.gua123.mediagate.media.engine.EngineCatalog
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.EngineSelection
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
 * 视频播放页状态（R4 拖拽 / R9 内核 / R10 解码 / R14 字幕 / R18 断点续播）。
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
    /** 上次切 LibVLC 崩溃过（本机可能不兼容）：界面在再切之前给一句提示。 */
    val vlcSuspectCrash: Boolean = false,
    /** LibVLC 在本机能不能跑；false = 探针被带走 → 不让切（2026-10-03 用户建议）。 */
    val vlcUsable: Boolean? = null,
    /**
     * **自研引擎**在本机能不能跑（2026-10-15；与 LibVLC 同一套独立进程探针）。
     * false = 自检没通过 → 界面先拦住并说明原因，不让用户切过去踩坑。
     */
    val engineUsable: Boolean? = null,
    /** 正在跑自研引擎的自检（界面显示「正在测试…」）。 */
    val engineProbeRunning: Boolean = false,
    /** 自研引擎自检没通过的原因（中文）。 */
    val engineProbeReason: String? = null,
    /**
     * 「自研引擎播不了这个文件，已自动换回 Media3」这类**一次性说明**（用户不需要动手，但要知道为什么换了内核）。
     */
    val engineFallbackNotice: String? = null,
    /** 正在跑 LibVLC 内核探针（独立进程；界面显示「正在测试…」）。 */
    val vlcProbeRunning: Boolean = false,
    /** 探针结果文案（测试通过 / 失败原因 / 没结论）。 */
    val vlcProbeMessage: String? = null,
    /**
     * 极简模式（**2026-10-03 用户要求**：「做成极简模式」）：只留进度条与播放键，
     * 标题/内核/档位芯片全部收起（点一下仍可唤出这条细细的栏）。
     */
    val simpleMode: Boolean = false,
    /**
     * 横滑调进度中的目标位置（毫秒）；null = 没在滑。
     *
     * **2026-10-03 用户要求**：「不弹出控制也能左右滑动调整进度条」——横滑时**不**弹控制层，
     * 只在画面中央浮出一个进度 HUD（带预览帧）。
     */
    val gestureSeekMs: Long? = null,
    /** 横滑起手时的位置（算位移用）。 */
    val gestureStartMs: Long = 0L,

    /** **音量倍率**（2026-10-03 用户要求：右侧上下滑，上限 200%）。 */
    val volume: Float = 1f,

    /** **屏幕亮度**（2026-10-03 用户要求：左侧上下滑；0..1，-1 = 跟随系统）。 */
    val brightness: Float = -1f,

    /** 正在竖直拖动的是哪个区（null = 没有）；界面据此显示亮度/音量 HUD。 */
    val verticalZone: PlayerGestureZone? = null,

    /** 竖直拖动的实时值（音量倍率或亮度，看 [verticalZone]）。 */
    val verticalValue: Float = 0f,

    /** 循环方式（2026-10-03 用户要求：文件夹循环 / 单曲循环 / 随机播放）。 */
    val loopMode: VideoLoopMode = VideoLoopMode.OFF,

    /** 切后台是否自动进画中画（2026-10-03 用户要求：可设置）。 */
    val pipAutoEnter: Boolean = true,

    /** 控制层无操作多少秒后隐藏（2026-10-03 用户要求）；0 = 永不自动隐藏。 */
    val controlsHideSeconds: Int = ControlsAutoHide.DEFAULT_SECONDS,
    /** 预览帧（图片字节）；抽不到就是 null，HUD 退化成只显示时间。 */
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
    /** 时间戳重建（R3/R11）是否正在跑。 */
    val timestampRepairRunning: Boolean = false,
    /** 重建进度 0..100（ffmpeg 报不出总时长时保持 0，界面显示不确定进度）。 */
    val timestampRepairPercent: Int = 0,
    /** 重建的一次性提示（成功/失败的中文说明）；用户确认后清掉。 */
    val timestampRepairNotice: String? = null,
    /** TS 索引相关提示（R3：无 PCR 的文件拖拽会不准）；用户确认后清掉。 */
    val tsIndexNotice: String? = null,
) {

    /** 队列里有多少集。 */
    val count: Int get() = siblingPaths.size

    /**
     * 能不能给「修复时间戳」入口：TS 家族的容器、当前没在重建、也没在切内核。
     *
     * 口径与 R3/R11 一致——只有时间戳容易坏的 TS 才需要这条出口。
     */
    val canRepairTimestamps: Boolean
        get() = VideoPlayerMath.isTimestampRepairable(path) && !timestampRepairRunning && !switching

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

    /**
     * 点「内核」按钮会切到的目标内核。
     *
     * **2026-10-15 改成轮转**：Media3 → 自研引擎 → LibVLC → Media3（过渡期三个内核并存，交互上仍是一个按钮）。
     *
     * ⚠️ **只有探针「确认能用」才把 LibVLC 放进轮转**（2026-10-15 真机截图之后收紧的口径）：
     * 原来写的是「未知也放」（vlcUsable != false），而 LibVLC 在用户这台机器上**是会把 App 整个带走的**
     * （有面包屑为证），探针又改成了按需跑 ⇒ 平时就是「未知」，于是点两下内核就撞上那个吓人的
     * 「上次切到 LibVLC 时 App 直接退出了」确认框。现在改成**没被确认过就不出现在轮转里**：
     * 用户点内核只会在 Media3 与自研引擎之间轮转；真要用 LibVLC，得先让探针给出 ok。
     */
    val otherEngine: EngineKind
        get() = if (canSwitchEngine) EngineSelection.cycleNext(engineKind, canUseVlc = vlcUsable == true) else engineKind

    /**
     * **要不要显示「内核」按钮**（2026-10-15 用户拍板只保留原有引擎之后）。
     *
     * 只有一个内核时按钮没有任何意义——而"有几个内核可用"的唯一判据是
     * [EngineSelection.availableEngines]（契约层，不是界面自己数）。
     */
    val canSwitchEngine: Boolean get() = EngineSelection.canSwitchEngine()

    /** 目标内核展示名。 */
    val otherEngineLabel: String get() = otherEngine.label

    /** 当前内核**如实声明**的能力（见 EngineCatalog）：界面据此决定给不给档位。 */
    val engineCaps: EngineCapabilities get() = EngineCatalog.capabilitiesOf(engineKind)

    /** 倍速档位能不能用（自研引擎还不支持 ⇒ 界面不显示那个芯片）。 */
    val canChangeSpeed: Boolean get() = engineCaps.speed

    /** 解码档位能不能用（自研引擎目前只有软解一条路）。 */
    val canChangeDecoder: Boolean get() = engineCaps.decoderModes

    /**
     * 「这个内核少什么」的一句话说明；能力齐平时为 null。
     *
     * 口径（项目红线：**控件不可用要写原因**）：缺的档位**不显示芯片**，但原因得摆在原来那排位置上，
     * 否则用户只会觉得"倍速按钮怎么没了"。
     */
    val capsNotice: String?
        get() {
            val missing = buildList {
                if (!engineCaps.speed) add("倍速")
                if (!engineCaps.decoderModes) add("硬解档位")
            }
            return if (missing.isEmpty()) null else engineKind.label + "暂无" + missing.joinToString("与")
        }

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

    /** 切极简模式（2026-10-03 用户要求）。 */
    data class SimpleModeChanged(val on: Boolean) : VideoPlayerEvent

    /** 横滑调进度：开始（记下起点）。 */
    data class GestureSeekStarted(val startMs: Long) : VideoPlayerEvent

    /** 横滑调进度：移动到目标位置。 */
    data class GestureSeekMoved(val targetMs: Long) : VideoPlayerEvent

    /** 横滑调进度：结束（提交）或取消（回原处）。 */
    data object GestureSeekEnded : VideoPlayerEvent

    /** 预览帧就绪（抽不到就不会发这个事件）。 */

    /** LibVLC 内核探针：开始/结束（[usable] = null 表示没结论）。 */
    data class EngineProbeChanged(val running: Boolean, val usable: Boolean?, val message: String?) :
        VideoPlayerEvent

    /** 自研引擎自检通过/失败（与 [VlcProbeChanged] 同一套：跑的时候 running=true）。 */
    data class VlcProbeChanged(val running: Boolean, val usable: Boolean?, val message: String?) :
        VideoPlayerEvent

    /** 黑屏/切换提示到时间收掉。 */
    data object SwitchHintCleared : VideoPlayerEvent

    /** 自研引擎播不了 → 已自动换回 Media3（给用户一句说明，不需要他动手）。 */
    data class EngineFallbackNoticeRaised(val text: String) : VideoPlayerEvent

    data object EngineFallbackNoticeCleared : VideoPlayerEvent

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

    /**
     * 直接跳到某个位置（**R13** PIP 内的快退/快进 10 秒；通知栏/锁屏 seek 也走它）。
     *
     * 与 [SeekChanged] 的区别：那个是"拖拽中的预览比例"，这个是"已经落在内核上的绝对位置"。
     */
    data class SeekedTo(val positionMs: Long) : VideoPlayerEvent

    /** 时间戳重建（R3/R11）开始。 */
    data object TimestampRepairStarted : VideoPlayerEvent

    /** 重建进度（0..100）。 */
    data class TimestampRepairProgress(val percent: Int) : VideoPlayerEvent

    /** 重建结束（成功或失败都归位，只看 [TimestampRepairNoticeRaised] 的文案）。 */
    data object TimestampRepairFinished : VideoPlayerEvent

    /** 重建的一次性提示（中文）。 */
    data class TimestampRepairNoticeRaised(val text: String) : VideoPlayerEvent

    /** 关掉重建提示。 */
    data object TimestampRepairNoticeCleared : VideoPlayerEvent

    /** TS 索引提示（R3：无 PCR / 索引不可用）。 */
    data class TsIndexNoticeRaised(val text: String) : VideoPlayerEvent

    /** 关掉 TS 索引提示。 */
    data object TsIndexNoticeCleared : VideoPlayerEvent
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

    is VideoPlayerEvent.SimpleModeChanged -> copy(simpleMode = event.on)

    is VideoPlayerEvent.GestureSeekStarted -> copy(
        gestureStartMs = event.startMs,
        gestureSeekMs = event.startMs,
    )

    is VideoPlayerEvent.GestureSeekMoved -> copy(gestureSeekMs = event.targetMs)

    // 结束：目标位置由 ViewModel 提交给内核，这里先把拖拽态收干净
    VideoPlayerEvent.GestureSeekEnded -> copy(gestureSeekMs = null)


    is VideoPlayerEvent.VlcProbeChanged -> copy(
        vlcProbeRunning = event.running,
        vlcUsable = event.usable ?: vlcUsable,
        vlcProbeMessage = event.message,
    )

    is VideoPlayerEvent.EngineProbeChanged -> copy(
        engineProbeRunning = event.running,
        engineUsable = event.usable ?: engineUsable,
        engineProbeReason = if (event.usable == false) event.message else engineProbeReason,
    )

    is VideoPlayerEvent.EngineFallbackNoticeRaised -> copy(engineFallbackNotice = event.text)

    VideoPlayerEvent.EngineFallbackNoticeCleared -> copy(engineFallbackNotice = null)

    is VideoPlayerEvent.SwitchFailed -> copy(
        status = VideoPlayerStatus.ERROR,
        switching = false,
        switchMessage = null,
        blackoutHint = false,
        errorKind = VideoErrorKind.PLAYBACK,
        errorDetail = event.detail,
        canFallback = event.canFallback,
        // 旧内核已释放、新内核没建起来：此刻**没有任何东西在播**，
        // 不把 playing 归零的话界面会继续显示"暂停"按钮（2026-10-03 切换失败用例抓到的）
        playing = false,
        buffering = false,
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

    is VideoPlayerEvent.SeekedTo -> copy(
        // R13：跳到目标位置后进度条立刻跟上，不用等 500 ms 后的下一次采样
        positionMs = event.positionMs.coerceAtLeast(0L),
        dragPositionMs = event.positionMs.coerceAtLeast(0L),
        dragging = false,
        ended = false,
    )

    // 时间戳重建（R3/R11）：跑起来时清掉上一次的提示，结束时把进度收掉
    VideoPlayerEvent.TimestampRepairStarted -> copy(
        timestampRepairRunning = true,
        timestampRepairPercent = 0,
        timestampRepairNotice = null,
    )

    is VideoPlayerEvent.TimestampRepairProgress -> copy(
        timestampRepairPercent = event.percent.coerceIn(0, 100),
    )

    VideoPlayerEvent.TimestampRepairFinished -> copy(
        timestampRepairRunning = false,
        timestampRepairPercent = 0,
    )

    is VideoPlayerEvent.TimestampRepairNoticeRaised -> copy(
        timestampRepairRunning = false,
        timestampRepairPercent = 0,
        timestampRepairNotice = event.text,
    )

    VideoPlayerEvent.TimestampRepairNoticeCleared -> copy(timestampRepairNotice = null)

    // TS 索引（R3）：无 PCR 的提示只在拿到扫描结果时给一次
    is VideoPlayerEvent.TsIndexNoticeRaised -> copy(tsIndexNotice = event.text)

    VideoPlayerEvent.TsIndexNoticeCleared -> copy(tsIndexNotice = null)
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
