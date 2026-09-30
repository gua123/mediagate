package io.github.gua123.mediagate.feature.player.audio

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.media.thumbnail.ThumbnailRepository

/**
 * 队列里的一首（R1 音频 / R18 后台播放）。
 *
 * 由 :app 从 MediaController 的 timeline 映射而来：本模块**不认识 Media3**，
 * 页面只消费 [AudioTrack] 与 [AudioPlaybackSnapshot]。
 *
 * @property path 后端内路径（与 StorageBackend 同口径，也用于取封面）。
 * @property title 展示名（通知栏与页面标题都是它，通常是文件名）。
 */
data class AudioTrack(val path: String, val title: String)

/**
 * 循环模式（R1 音频页的「循环模式」按钮）。
 *
 * 语义与主流播放器一致：
 * - [OFF] 顺序播放，列表放完就停；
 * - [ALL] 列表循环；
 * - [ONE] 单曲循环（**只影响一首放完后的自动重播**；手动按上一首/下一首仍按顺序走）。
 */
enum class AudioRepeatMode {
    OFF,
    ALL,
    ONE,
}

/**
 * 播放状态快照（R18）：:app 把 MediaController 的状态压缩成这个不可变快照，
 * 页面只认它，因此服务/控制器怎么实现都与 UI 无关（也便于 JVM 单测造假数据）。
 *
 * @property queue 当前播放队列（顺序即播放顺序）。
 * @property index 当前曲目在 [queue] 里的下标。
 * @property playing 是否正在播放（MediaController.isPlaying）。
 * @property buffering 是否在缓冲（准备中/卡顿）。
 * @property ready 播放器是否已就绪（能取到时长）。
 * @property positionMs 当前播放位置（毫秒）。
 * @property durationMs 当前曲目时长（毫秒）；未知为 0。
 * @property speed 播放速度倍率。
 * @property repeatMode 循环模式。
 * @property connected MediaController 是否已连上后台服务。
 * @property errorMessage 播放错误（已中文化或来自服务端），没有为 null。
 */
data class AudioPlaybackSnapshot(
    val queue: List<AudioTrack> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ready: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val repeatMode: AudioRepeatMode = AudioRepeatMode.OFF,
    val connected: Boolean = false,
    val errorMessage: String? = null,
) {

    /** 当前曲目；队列为空时为 null。 */
    val current: AudioTrack? get() = queue.getOrNull(index)
}

/**
 * 音频播放页需要的宿主能力（R1 音频 / R18 后台播放）。
 *
 * 为什么是接口：:feature:player-audio 不能反向依赖 :app；由 AppContainer 实现本接口并在
 * 导航宿主里用 [LocalAudioPlayerEnvironment] 注入一次（手写 DI，不引 Hilt）。
 * **MediaController / SessionToken / 前台服务全在 :app 侧**，本模块只发命令、收状态。
 *
 * 线程约定：挂起函数内部自己切 IO；[state] / [backend] 是热流，页面用
 * collectAsStateWithLifecycle 订阅，组合函数里不做任何 IO。
 */
interface AudioPlayerEnvironment {

    /** 播放状态（来自 MediaController；未连上服务时也会发 [AudioPlaybackSnapshot.connected] = false）。 */
    val state: StateFlow<AudioPlaybackSnapshot>

    /** 当前根目录的存储后端（R12）；尚未选择时为 null（此时封面拿不到，页面用渐变兜底）。 */
    val backend: StateFlow<StorageBackend?>

    /** 缩略图仓库（R5）：音频走内嵌封面那一支，拿不到就由页面画首字母。 */
    val thumbnails: ThumbnailRepository

    /**
     * 列出 [path] 所在目录里的**音频**（同目录队列，按名称排序）。
     *
     * 浏览页点某一首进来时，队列就是它所在的目录——与浏览页看到的顺序一致（同一后端、同一排序口径）。
     *
     * @throws io.github.gua123.mediagate.data.storage.api.StorageException 列目录失败（无权限 / 不存在 / 网络…）。
     */
    suspend fun audioSiblings(path: String): List<RemoteEntry>

    /**
     * 用给定队列开始播放（R18：交给后台服务的播放列表，多首连续播放由服务侧承担）。
     *
     * @param paths 后端内路径列表（顺序即播放顺序）。
     * @param startIndex 从第几首开始（越界会被钳到合法范围）。
     */
    suspend fun play(paths: List<String>, startIndex: Int)

    /** 跳到队列第 [index] 首（上一首 / 下一首按钮用它，下标轮转由页面纯逻辑算好）。 */
    fun playAt(index: Int)

    /** 播放 / 暂停切换。 */
    fun togglePlayPause()

    /** 跳到指定位置（毫秒）。 */
    fun seekTo(positionMs: Long)

    /** 设置播放速度倍率。 */
    fun setSpeed(speed: Float)

    /** 设置循环模式。 */
    fun setRepeatMode(mode: AudioRepeatMode)
}

/**
 * 由 :app 在导航宿主处提供的音频播放依赖（手写 DI 的注入点）。
 *
 * 取值失败说明忘了在 MediaGateApp() 里
 * CompositionLocalProvider(LocalAudioPlayerEnvironment provides container)。
 */
val LocalAudioPlayerEnvironment = staticCompositionLocalOf<AudioPlayerEnvironment> {
    error("LocalAudioPlayerEnvironment 未注入：请在 :app 的 MediaGateApp() 里用 AppContainer 提供（R18）")
}
