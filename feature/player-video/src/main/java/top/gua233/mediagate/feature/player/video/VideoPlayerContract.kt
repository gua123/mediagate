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
