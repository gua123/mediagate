package io.github.gua123.mediagate.feature.player.video

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.PlayerEngine
import io.github.gua123.mediagate.media.playback.PlaybackProgressStore
import io.github.gua123.mediagate.media.subtitle.SubtitleStyle

/**
 * 视频播放页的持久化偏好（R9 内核 / R10 解码模式 / R14 字幕）。
 *
 * 三件事：**上次用的是哪个内核**（下次进页面直接用它）、**解码档位**（默认 AUTO_HW = 硬解优先）、
 * **字幕设置**（R14：开关 / 样式 / 时间轴微调——微调完写回，下次进来仍是用户调好的样子）。
 * 接口留给 :app 用 DataStore 实现（本模块不碰 DataStore，也不碰 Context）；
 * R14 的字幕设置写进**同一个偏好文件**，只做加法，老键位不受影响。
 *
 * 线程约定：[StateFlow] 是热流，可在任意线程读（ViewModel 进页面时直接读 .value 初始化状态）；
 * 写方法都是挂起函数，内部自己切 IO。
 */
interface VideoPlayerPreferences {

    /** 上次选择的内核（R9）；没有记录时为 [EngineKind.MEDIA3]（默认内核）。 */
    val engine: StateFlow<EngineKind>

    /** 上次选择的解码模式（R10）；没有记录时为 [DecoderMode.AUTO_HW]（默认硬解优先）。 */
    val decoderMode: StateFlow<DecoderMode>

    /** 记住内核选择（用户手动切换内核时写）。 */
    suspend fun setEngine(kind: EngineKind)

    /** 记住解码档位（用户改档位时写，R10 要求持久化）。 */
    suspend fun setDecoderMode(mode: DecoderMode)

    /**
     * 极简模式（**2026-10-03 用户要求**）：只留进度条与播放键。默认关；实现方负责持久化。
     */
    val simpleMode: Boolean get() = false

    /** 记住极简模式（默认什么都不做，JVM 单测与没有持久化的场景行为不变）。 */
    suspend fun setSimpleMode(on: Boolean) = Unit

    /**
     * 记住的音量倍率（**2026-10-03 用户要求**：右侧上下滑调音量，上限 200%）。
     *
     * 默认 1.0（原始音量）；实现方负责持久化，下次播放沿用。
     */
    val playerVolume: Float get() = 1f

    /** 记住音量（拖动结束才写，避免每一帧都落盘）。 */
    suspend fun setPlayerVolume(volume: Float) = Unit

    /**
     * 记住的屏幕亮度（**2026-10-03 用户要求**：左侧上下滑调亮度，退出播放要恢复、下次播放要记住）。
     *
     * 取值 0..1；**-1 = 跟随系统**（用户没调过，或退出播放页后已恢复）。
     */
    val screenBrightness: Float get() = -1f

    /** 记住亮度（同样是拖动结束才写）。 */
    suspend fun setScreenBrightness(brightness: Float) = Unit

    /**
     * 循环方式（**2026-10-03 用户要求**：「没有此文件夹循环、单曲循环、随机播放」）。
     *
     * 默认 [VideoLoopMode.OFF]（播完就停，与旧版行为一致）；实现方负责持久化。
     */
    val loopMode: VideoLoopMode get() = VideoLoopMode.OFF

    /** 记住循环方式。 */
    suspend fun setLoopMode(mode: VideoLoopMode) = Unit

    /**
     * **切后台时是否自动进入画中画**（2026-10-03 用户要求：「可以设置软件在后台时是否显示画中画」）。
     *
     * 默认 true（与旧行为一致）；关掉之后按 Home/切后台只是继续后台播放，不会有小窗。
     */
    val pipAutoEnterEnabled: Boolean get() = true

    /** 记住画中画开关。 */
    suspend fun setPipAutoEnter(enabled: Boolean) = Unit

    /**
     * **控制层"无操作多少秒后隐藏"**（2026-10-03 用户要求：「无操作多少秒时才隐藏，有操作时不能隐藏」）。
     *
     * 取值见 [ControlsAutoHide.SECONDS_CHOICES]（3/5/10，0 = 永不自动隐藏）；默认 3 秒（与旧版一致）。
     * **有操作时永远不隐藏**是行为规则，不走这个配置。
     */
    val controlsHideSecondsValue: Int get() = ControlsAutoHide.DEFAULT_SECONDS

    /** 记住控制层隐藏延时。 */
    suspend fun setControlsHideSeconds(seconds: Int) = Unit

    /** 字幕总开关（R14）；默认关。 */
    val subtitleEnabled: StateFlow<Boolean>

    /** 字幕显示样式（R14：字号 / 颜色 / 描边 / 底部边距 / 加粗 / 斜体）。 */
    val subtitleStyle: StateFlow<SubtitleStyle>

    /** 字幕时间轴微调（R14），毫秒；正数 = 字幕延后，默认 0。 */
    val subtitleOffsetMs: StateFlow<Long>

    /** 记住字幕开关（R14）。 */
    suspend fun setSubtitleEnabled(enabled: Boolean)

    /** 记住字幕样式（R14：字号/颜色/描边/边距等改动都会写一次）。 */
    suspend fun setSubtitleStyle(style: SubtitleStyle)

    /** 记住字幕时间轴微调（R14：±0.5 s 步进的结果要能跨会话保留）。 */
    suspend fun setSubtitleOffsetMs(offsetMs: Long)
}

/**
 * 视频播放页需要的宿主能力（R4 拖拽 / R9 多内核 / R10 硬软解 / R18 断点续播）。
 *
 * 为什么是接口：:feature:player-video 不能反向依赖 :app（内核实例要用 :app 的回环代理、
 * Media3 数据源工厂与 Context 才能造出来）。由 AppContainer 实现本接口，在导航宿主处用
 * [LocalVideoPlayerEnvironment] 注入一次（手写 DI，不引 Hilt）。
 *
 * 边界：本模块只发命令、收状态，**不认识 Media3 / LibVLC**——两个内核都被 :app 包在
 * [PlayerEngine] 抽象后面，页面拿到的是统一的 StateFlow 状态机。
 */
interface VideoPlayerEnvironment {

    /**
     * 当前根目录的存储后端（R12）；尚未选择根目录时为 null
     * （此时列同目录失败并给中文提示，而不是崩溃）。
     */
    val backend: StateFlow<StorageBackend?>

    /**
     * 按内核种类建一个内核实例（R9）：:app 负责 ExoPlayerEngine 的数据源工厂、
     * VlcEngine 的回环代理与视频输出视图。
     *
     * 为什么是挂起函数：建内核要用到只在 IO 上可取的东西（读偏好、起回环代理）。
     * 实现应在**主线程**上完成真正的构造（Media3 / LibVLC 都要求主线程）。
     *
     * @param kind 目标内核。
     */
    suspend fun createEngine(kind: EngineKind): PlayerEngine

    /**
     * 回环代理基址（http://127.0.0.1:端口）；null = 代理不可用。
     *
     * 播放在 Media3 出错时要不要给出「一键切 LibVLC」动作取决于它（LibVLC 只能吃 URL）。
     */
    val proxyBaseUrl: String?

    /**
     * 上次切到 LibVLC 是不是把进程带走过（**2026-10-03 真机**：面包屑停在"切换内核：→ LibVLC"）。
     *
     * 为 true 时，界面在"再切一次 LibVLC"之前先解释一句——那条路已知会把 App 弄死时，
     * 不该让用户闷头再踩一次。默认 false（JVM 单测与没有诊断数据时行为不变）。
     */
    val vlcPreviouslyCrashed: Boolean get() = false

    /**
     * LibVLC 在本机能不能跑（**2026-10-03 用户建议**：启动时先测，不能跑就不让切）。
     *
     * null = 还没结论；false = 探针被原生崩溃带走 → 界面**不让切**到 LibVLC 并说明原因。
     */
    val vlcUsable: Boolean? get() = null

    /** 重新测一次（提示对话框里的「重新测试」）。默认什么都不做。 */
    fun retestVlc() = Unit

    /**
     * **打开即预取**（2026-10-03 用户反馈「新视频在内网第一次打开会缓存很久」）。
     *
     * 播放页一确定要播这个文件就调它：宿主在后台把**开头几段**先拉进分段缓存，
     * 于是"起播时才开始拉第一段"变成"打开的过程中已经在拉"——第一帧来得更快。
     * 默认什么都不做（JVM 单测与没有缓存层的场景行为不变）。
     */
    fun prefetchHead(path: String) = Unit


    /** 断点续播存储（R18）：进入时读一次、播放中每 5 秒写一次、退出时再写一次。 */
    val progress: PlaybackProgressStore

    /** 内核与解码档位的持久化（R9/R10）＋ 字幕设置（R14）。 */
    val preferences: VideoPlayerPreferences

    /**
     * 画中画宿主能力（**R13**）：进/出 PIP、PIP 内动作、按 Home 自动进入、是否在 PIP 中。
     *
     * 默认 [NoopVideoPipHost]（没有 Activity 的场景，例如 JVM 单测）：页面行为不变，只是没有小窗。
     */
    val pip: VideoPipHost get() = NoopVideoPipHost

    /**
     * 视频后台播放与让路宿主（**R18 视频侧 / R19 让路**）：会话绑定 + "正在播放"上报。
     *
     * 默认 [NoopVideoPlaybackHost]（JVM 单测）：不建 MediaSession、不参与让路。
     */
    val playback: VideoPlaybackHost get() = NoopVideoPlaybackHost

    /**
     * 字幕能力（R14）：:app 用 :media:subtitle 装配（定位器 / 解析器 / 写回 + 本地兜底）。
     *
     * 本模块只发命令：列同目录、读解析、写回；远端与本地由同一个 StorageBackend 承担。
     */
    val subtitles: SubtitleHost

    /**
     * 列出 [path] 所在目录里的**视频与音频**（R1 上下集队列）。
     *
     * 排序口径与浏览页/音频页一致（按名称大小写不敏感），过滤与排序由
     * [VideoPlayerMath.playableEntries] 统一负责，:app 只需把目录项交出来。
     *
     * @throws io.github.gua123.mediagate.data.storage.api.StorageException 列目录失败（无权限 / 不存在 / 网络…）。
     */
    suspend fun siblings(path: String): List<RemoteEntry>

    /**
     * 时间戳重建（**R3 / R11**）：把 [path] 这条视频用 FFmpeg 重写一份时间戳正常的副本。
     *
     * 为什么放在宿主：重建要「当前后端的随机读 + FFmpeg 会话 + 一个播放器能读到的落点」，
     * 这三样都只有 :app 拿得到。实现负责把产物接到**视频专用的"修复根目录"后端**上，
     * 于是调用方只要 reload([TimestampRepairOutcome.Repaired.path]) 就能播修好的版本。
     *
     * 默认实现返回失败：JVM 单测与没有宿主的场景不会因为少实现一个方法而编译不过。
     *
     * @param onProgress 0..100（ffmpeg 报不出总时长时可能一直是 0）。
     */
    suspend fun repairTimestamps(path: String, onProgress: (Int) -> Unit): TimestampRepairOutcome =
        TimestampRepairOutcome.Failed("当前环境不支持时间戳重建")

    /**
     * 退出"修复根目录"，把视频后端切回正常的当前根目录。
     *
     * 播放页销毁时调用（离开播放页就恢复原状），避免用户回到浏览页时看到修复缓存目录。
     */
    fun exitRepairRoot() = Unit

    /**
     * 准备 TS 索引（**R3 / R4**）：扫（或读缓存）出"时间 ↔ 关键帧字节偏移"表。
     *
     * 只有 TS 家族需要；实现应当**限制扫描量**（索引是加速手段，不该把整部片子读完）。
     * 默认返回 null（不适用 / 拿不到），调用方按"没有索引"处理。
     */
    suspend fun prepareTsIndex(path: String): TsIndexInfo? = null

    /**
     * 按索引预取拖拽落点附近的数据（**R4**，best-effort）。
     *
     * 拿不到索引或没有分段缓存时**什么都不做**（不是错误）。
     */
    suspend fun prefetchSeek(path: String, positionMs: Long) = Unit
}

/**
 * TS 索引的准备结果（**R3 / R4**）。
 *
 * @param hasPcr 文件里有没有 PCR——false 就是 R3 说的"无 PCR 样本"，拖拽一定不准，界面要提示。
 * @param keyframeCount 索引里的关键帧数。
 * @param complete 是否扫到文件尾（false = 只扫了一段，够用来预取）。
 */
data class TsIndexInfo(
    val hasPcr: Boolean,
    val keyframeCount: Int,
    val complete: Boolean,
)

/** 时间戳重建的结果（**R3/R11**）。 */
sealed interface TimestampRepairOutcome {

    /**
     * 重建成功。
     *
     * @param path **修复根目录内**的相对路径（宿主已经把视频后端切到那个根，直接 reload 即可）。
     */
    data class Repaired(val path: String) : TimestampRepairOutcome

    /** 重建失败（中文原因，直接展示）。 */
    data class Failed(val message: String) : TimestampRepairOutcome
}

/**
 * 由 :app 在导航宿主处提供的视频播放依赖（手写 DI 的注入点）。
 *
 * 取值失败说明忘了在 MediaGateApp() 里用 AppContainer 提供
 * LocalVideoPlayerEnvironment（R9/R10）。
 */
val LocalVideoPlayerEnvironment = staticCompositionLocalOf<VideoPlayerEnvironment> {
    error("LocalVideoPlayerEnvironment 未注入：请在 :app 的 MediaGateApp() 里用 AppContainer 提供（R9/R10）")
}
