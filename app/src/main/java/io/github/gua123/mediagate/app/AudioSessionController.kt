package io.github.gua123.mediagate.app

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.data.storage.api.StorageBackend
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.media.thumbnail.ThumbnailRepository
import io.github.gua123.mediagate.feature.player.audio.AudioPlaybackSnapshot
import io.github.gua123.mediagate.feature.player.audio.AudioRepeatMode
import io.github.gua123.mediagate.feature.player.audio.AudioTrack
import io.github.gua123.mediagate.media.playback.AudioPlaybackService
import io.github.gua123.mediagate.media.playback.MediaSourceFactory

/**
 * 音频后台播放的**客户端**（R18）：连接 [AudioPlaybackService] 的 MediaSession，发命令、收状态。
 *
 * 为什么放在 :app：Media3 的 [MediaController] / [SessionToken] 是"进程内应用侧"的东西，
 * :feature:player-audio 只认自己的 [AudioPlayerEnvironment] 接口（不反向依赖 :app，也不认识 Media3）。
 *
 * 线程模型：控制器一建好就固定挂在主 Looper 上（Media3 的要求），因此所有命令都通过
 * [scope]（Main.immediate）发出；对外暴露的只有一个纯数据 [StateFlow]。
 *
 * 状态更新有两个来源：
 * 1. [listener]：播放器事件（切歌、暂停、缓冲、错误…）——事件驱动，实时；
 * 2. 定时器（每 [POSITION_TICK_MS] 一次，只在播放中）：Media3 默认**不**推送周期性的位置更新，
 *    进度条要动就得自己按节拍取一次 [Player.getCurrentPosition]。
 *
 * @param context 应用上下文（连服务用）。
 * @param backendFlow 当前根目录后端（R12）；用它把路径编成 MediaItem（伪 URI，见 MediaSourceFactory）。
 */
@UnstableApi
class AudioSessionController(
    private val context: Context,
    private val backendFlow: StateFlow<StorageBackend?>,
    /** 缩略图仓库（取通知栏封面用，R18）；null = 不提供封面。 */
    private val thumbnailRepository: ThumbnailRepository? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(AudioPlaybackSnapshot())

    /** 页面消费的播放状态。 */
    val state: StateFlow<AudioPlaybackSnapshot> = _state.asStateFlow()

    /** 当前后端（透传给页面取封面）。 */
    val backend: StateFlow<StorageBackend?> get() = backendFlow

    private var controller: MediaController? = null

    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }
    }

    init {
        connect()
        startTicker()
    }

    /**
     * 播放给定的队列（R18）。
     *
     * @param paths 后端内路径列表。
     * @param startIndex 起始下标（越界会被钳住）。
     * @throws StorageException.AccessDenied 尚未选择根目录（没有后端就编不出地址）。
     * @throws IllegalStateException 后台服务迟迟连不上。
     */
    suspend fun play(paths: List<String>, startIndex: Int) {
        val backend = backendFlow.value ?: throw StorageException.AccessDenied("尚未选择媒体根目录")
        // 通知栏/锁屏封面（R18）：取"最近出过的缩略图"——列表里滚过的条目通常已经有图，
        // 这里**不为了封面去抽帧**（那会在起播路径上多打一次远端）
        val items = paths.map {
            MediaSourceFactory.mediaItem(
                path = it,
                backend = backend,
                artwork = thumbnailRepository?.recentThumbnail(it),
            )
        }
        val target = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        withContext(Dispatchers.Main.immediate) {
            val controller = awaitController()
            controller.setMediaItems(items, target, 0L)
            controller.prepare()
            controller.play()
            publish(controller)
        }
    }

    /** 跳到队列第 [index] 首并继续播放。 */
    fun playAt(index: Int) {
        val controller = controller ?: return
        scope.launch {
            controller.seekTo(index.coerceAtLeast(0), 0L)
            controller.play()
            publish(controller)
        }
    }

    /** 播放 / 暂停（播放中再点就是暂停）。 */
    fun togglePlayPause() {
        val controller = controller ?: return
        scope.launch {
            if (controller.isPlaying) {
                controller.pause()
            } else {
                if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                controller.play()
            }
            publish(controller)
        }
    }

    /** 跳到指定位置（毫秒）。 */
    fun seekTo(positionMs: Long) {
        val controller = controller ?: return
        scope.launch {
            controller.seekTo(positionMs.coerceAtLeast(0L))
            publish(controller)
        }
    }

    /** 设置倍速（0.25×–4×，越界钳住）。 */
    fun setSpeed(speed: Float) {
        val controller = controller ?: return
        scope.launch { controller.setPlaybackSpeed(speed.coerceIn(MIN_SPEED, MAX_SPEED)) }
    }

    /** 设置循环模式。 */
    fun setRepeatMode(mode: AudioRepeatMode) {
        val controller = controller ?: return
        scope.launch { controller.setRepeatMode(mode.toPlayerRepeatMode()) }
    }

    // ------------------------------------------------------------------ 内部

    /** 连接后台服务；失败只提示，不影响 App 其余功能（R18 是可缺省能力）。 */
    private fun connect() {
        val token = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { connected ->
                        controller = connected
                        connected.addListener(listener)
                        publish(connected)
                        AppLog.i(TAG, "已连接音频后台播放服务（R18）")
                    }
                    .onFailure { error ->
                        AppLog.w(TAG, "连接音频后台播放服务失败", error)
                        _state.value = AudioPlaybackSnapshot(
                            connected = false,
                            errorMessage = "音频后台服务未连接",
                        )
                    }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** 等控制器连上（首次进播放页时可能还在连接中）。 */
    private suspend fun awaitController(): MediaController {
        controller?.let { return it }
        repeat(CONNECT_WAIT_STEPS) {
            delay(CONNECT_WAIT_STEP_MS)
            controller?.let { return it }
        }
        throw IllegalStateException("音频后台服务未连接")
    }

    /** 播放中的位置节拍（Media3 默认不推周期位置更新，进度条靠自己取）。 */
    private fun startTicker() {
        ticker = scope.launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                val current = controller ?: continue
                if (current.isPlaying) publish(current)
            }
        }
    }

    /** 把 MediaController 的状态压成 [AudioPlaybackSnapshot]（页面只认这个）。 */
    private fun publish(player: Player) {
        val queue = (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            val path = MediaSourceFactory.pathOf(item).orEmpty()
            val title = item.mediaMetadata.title?.toString().orEmpty().ifEmpty { path.substringAfterLast('/') }
            AudioTrack(path = path, title = title)
        }
        val duration = player.duration
        _state.value = AudioPlaybackSnapshot(
            queue = queue,
            index = player.currentMediaItemIndex.coerceAtLeast(0),
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            ready = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration == C.TIME_UNSET || duration <= 0L) 0L else duration,
            speed = player.playbackParameters.speed,
            repeatMode = player.repeatMode.toAudioRepeatMode(),
            connected = true,
            errorMessage = player.playerError?.let { "播放失败（" + it.errorCodeName + "）" },
        )
    }

    /** Media3 循环模式 → 页面枚举。 */
    private fun Int.toAudioRepeatMode(): AudioRepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> AudioRepeatMode.ONE
        Player.REPEAT_MODE_ALL -> AudioRepeatMode.ALL
        else -> AudioRepeatMode.OFF
    }

    /** 页面枚举 → Media3 循环模式。 */
    private fun AudioRepeatMode.toPlayerRepeatMode(): Int = when (this) {
        AudioRepeatMode.OFF -> Player.REPEAT_MODE_OFF
        AudioRepeatMode.ONE -> Player.REPEAT_MODE_ONE
        AudioRepeatMode.ALL -> Player.REPEAT_MODE_ALL
    }

    private companion object {

        const val TAG = "audio-session"

        /** 位置节拍间隔（毫秒）：进度条刷新频率，够顺滑又不浪费电。 */
        const val POSITION_TICK_MS = 500L

        /** 首次连接服务的等待：最多 [CONNECT_WAIT_STEPS] × [CONNECT_WAIT_STEP_MS] 毫秒。 */
        const val CONNECT_WAIT_STEPS = 20
        const val CONNECT_WAIT_STEP_MS = 100L

        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
    }
}
