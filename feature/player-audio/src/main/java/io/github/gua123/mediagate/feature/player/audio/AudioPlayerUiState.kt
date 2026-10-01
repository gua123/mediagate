package io.github.gua123.mediagate.feature.player.audio

import io.github.gua123.mediagate.data.storage.api.StorageException

/** 播放页状态机（R1 音频 / R18）。 */
enum class AudioPlayerStatus {

    /** 正在列同目录音频 / 等后台服务连上。 */
    LOADING,

    /** 队列已就绪（是否正在播放看 [AudioPlayerUiState.playing]）。 */
    READY,

    /** 失败（分类见 [AudioPlayerErrorKind]）。 */
    ERROR,
}

/** 失败分类（对应 [StorageException] 的语义子类），页面按分类给中文原因。 */
enum class AudioPlayerErrorKind {
    ACCESS_DENIED,
    NOT_FOUND,
    NOT_SUPPORTED,
    NETWORK,
    AUTH,
    UNKNOWN,
}

/**
 * 音频播放页状态（R1 音频 / R18）。
 *
 * 不可变 + 纯数据：所有变更都走 [reduce]，因此「状态迁移」可以在纯 JVM 单测里覆盖。
 * [queue] 是**播放队列**（来自后台服务，已按目录顺序排好），[index] 是当前曲目下标。
 */
data class AudioPlayerUiState(
    /** 状态机。 */
    val status: AudioPlayerStatus = AudioPlayerStatus.LOADING,
    /** 播放队列。 */
    val queue: List<AudioTrack> = emptyList(),
    /** 当前曲目下标。 */
    val index: Int = 0,
    /** 是否正在播放。 */
    val playing: Boolean = false,
    /** 是否在缓冲。 */
    val buffering: Boolean = false,
    /** 当前播放位置（毫秒）。 */
    val positionMs: Long = 0L,
    /** 当前曲目时长（毫秒）；未知为 0。 */
    val durationMs: Long = 0L,
    /** 播放速度倍率。 */
    val speed: Float = AudioPlayerMath.DEFAULT_SPEED,
    /** 循环模式。 */
    val repeatMode: AudioRepeatMode = AudioRepeatMode.OFF,
    /** 当前曲目的封面；拿不到为 null（页面画渐变底 + 首字母）。 */
    val cover: AudioCover? = null,
    /** 已经为哪一首曲子加载/尝试过封面（避免同一首反复加载）。 */
    val coverPath: String? = null,
    /** 失败分类；[AudioPlayerStatus.ERROR] 时非空。 */
    val errorKind: AudioPlayerErrorKind? = null,
    /** 后端或服务的具体错误信息（诊断用，可为 null）。 */
    val errorDetail: String? = null,
    /** 用户是否正在拖拽进度条（拖拽中不被播放进度覆盖）。 */
    val dragging: Boolean = false,
    /** 拖拽中的目标位置（毫秒）。 */
    val dragPositionMs: Long = 0L,
) {

    /** 当前曲目；队列为空时为 null。 */
    val track: AudioTrack? get() = queue.getOrNull(index)

    /** 当前曲目展示名。 */
    val title: String get() = track?.title.orEmpty()

    /** 当前曲目路径。 */
    val path: String get() = track?.path.orEmpty()

    /** 队列里是否有内容。 */
    val hasQueue: Boolean get() = queue.isNotEmpty()

    /** 能否切上一首 / 下一首（至少两首才显示成可用）。 */
    val canSwitch: Boolean get() = queue.size > 1

    /** 进度条要显示的毫秒数：拖拽中优先显示拖到的位置。 */
    val displayPositionMs: Long get() = if (dragging) dragPositionMs else positionMs

    /** 进度条比例（0..1）。 */
    val progress: Float get() = AudioPlayerMath.seekRatio(displayPositionMs, durationMs)

    /** 已播放时长文案（m:ss）。 */
    val positionText: String get() = AudioPlayerMath.formatDuration(displayPositionMs)

    /** 总时长文案；时长未知（0）时给占位符，而不是看起来像「0 秒的歌」。 */
    val durationText: String
        get() = if (durationMs <= 0L) AudioPlayerMath.UNKNOWN_TIME else AudioPlayerMath.formatDuration(durationMs)

    /** 倍速文案（1×）。 */
    val speedLabel: String get() = AudioPlayerMath.speedLabel(speed)

    /** 队列位置文案用的序号（从 1 开始）；队列为空时为 0。 */
    val position: Int get() = if (queue.isEmpty()) 0 else index + 1

    /** 队列总数。 */
    val count: Int get() = queue.size
}

/** 状态事件：ViewModel 只把外部结果翻译成事件，状态迁移全在 [reduce] 里。 */
sealed interface AudioPlayerEvent {

    /** 开始加载某个路径所在的目录（进入页面 / 重试）。 */
    data class LoadStarted(val path: String) : AudioPlayerEvent

    /** 同目录音频列好了（还没开始播）；[audioCount] 是队列长度。 */
    data class SiblingsLoaded(val audioCount: Int) : AudioPlayerEvent

    /** 后台服务推来的播放状态。 */
    data class SnapshotChanged(val snapshot: AudioPlaybackSnapshot) : AudioPlayerEvent

    /** 封面加载结束（成功或失败都发一次，[cover] 为 null 表示没有封面）。 */
    data class CoverLoaded(val path: String, val cover: AudioCover?) : AudioPlayerEvent

    /** 加载失败（已分类）。 */
    data class LoadFailed(val kind: AudioPlayerErrorKind, val detail: String? = null) : AudioPlayerEvent

    /** 开始拖拽进度条。 */
    data object SeekStarted : AudioPlayerEvent

    /** 拖拽中（[ratio] 是 0..1 的目标比例）。 */
    data class SeekChanged(val ratio: Float) : AudioPlayerEvent

    /** 松手：把拖到的位置提交给播放器。 */
    data object SeekFinished : AudioPlayerEvent
}

/**
 * 状态归约（纯函数）：新状态 = 旧状态.reduce(事件)。
 *
 * 不抛异常、不碰 IO、不依赖 Android，方便单测覆盖所有分支。
 *
 * 拖拽（[AudioPlayerEvent.SeekChanged] / [AudioPlayerEvent.SeekFinished]）与播放进度的关系：
 * - 拖拽中：状态照旧吸收服务推来的真实 [AudioPlayerUiState.positionMs]，但**页面显示的是
 *   [AudioPlayerUiState.displayPositionMs]**（拖到的位置），所以进度条不会被回弹；
 * - 松手：把拖到的位置提交给播放器（ViewModel 负责），并乐观地把它当作当前位置。
 */
fun AudioPlayerUiState.reduce(event: AudioPlayerEvent): AudioPlayerUiState = when (event) {
    is AudioPlayerEvent.LoadStarted -> copy(
        status = AudioPlayerStatus.LOADING,
        errorKind = null,
        errorDetail = null,
    )

    is AudioPlayerEvent.SiblingsLoaded -> copy(
        status = AudioPlayerStatus.READY,
        errorKind = null,
        errorDetail = null,
    )

    is AudioPlayerEvent.SnapshotChanged -> {
        val snapshot = event.snapshot
        copy(
            queue = snapshot.queue,
            index = if (snapshot.queue.isEmpty()) 0 else snapshot.index.coerceIn(0, snapshot.queue.lastIndex),
            playing = snapshot.playing,
            buffering = snapshot.buffering,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            speed = snapshot.speed,
            repeatMode = snapshot.repeatMode,
            errorDetail = snapshot.errorMessage ?: errorDetail,
            // 服务已经有队列时，本地状态机随之进入 READY（列目录那一步可能还没回来）
            status = if (snapshot.queue.isEmpty()) status else AudioPlayerStatus.READY,
        )
    }

    is AudioPlayerEvent.CoverLoaded -> copy(
        cover = if (event.path == coverPath) event.cover else cover,
    )

    is AudioPlayerEvent.LoadFailed -> copy(
        status = AudioPlayerStatus.ERROR,
        errorKind = event.kind,
        errorDetail = event.detail,
    )

    AudioPlayerEvent.SeekStarted -> copy(dragging = true, dragPositionMs = positionMs)

    is AudioPlayerEvent.SeekChanged -> copy(
        dragging = true,
        dragPositionMs = AudioPlayerMath.positionOfRatio(event.ratio, durationMs),
    )

    AudioPlayerEvent.SeekFinished -> copy(
        dragging = false,
        positionMs = dragPositionMs,
    )
}

/** 后端异常 → 页面错误分类（R1，纯函数）。 */
object AudioPlayerErrors {

    /** 把 [StorageException] 的语义子类翻译成页面分类；不认识的一律 [AudioPlayerErrorKind.UNKNOWN]。 */
    fun classify(throwable: Throwable): AudioPlayerErrorKind = when (throwable) {
        is StorageException.AccessDenied -> AudioPlayerErrorKind.ACCESS_DENIED
        is StorageException.NotFound -> AudioPlayerErrorKind.NOT_FOUND
        is StorageException.NotSupported -> AudioPlayerErrorKind.NOT_SUPPORTED
        is StorageException.Network -> AudioPlayerErrorKind.NETWORK
        is StorageException.Auth -> AudioPlayerErrorKind.AUTH
        else -> AudioPlayerErrorKind.UNKNOWN
    }
}
