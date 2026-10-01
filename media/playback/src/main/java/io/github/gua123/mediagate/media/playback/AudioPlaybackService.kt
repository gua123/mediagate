package io.github.gua123.mediagate.media.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import io.github.gua123.mediagate.core.common.AppLog

/**
 * 音频后台播放服务（R18：切后台 / 息屏不中断，通知栏、锁屏、耳机键可控）。
 *
 * 组成：
 * - [ExoPlayer]：唯一播放器。队列（多首连播）由 ExoPlayer 的 playlist 承担；
 * - [MediaSession]：把播放器暴露给系统（通知栏 / 锁屏 / 蓝牙 / 耳机键）；
 * - 前台服务通知：用 Media3 自带的 [DefaultMediaNotificationProvider]（频道名走本模块中文资源，R16）；
 * - 数据入口：[PlaybackHost.playbackBackend] → [BackendDataSource]，与浏览页共用同一套随机读数据层（R4）。
 *
 * 三条保播放的开关（都在 [onCreate] 里显式打开）：
 * 1. setAudioAttributes(..., handleAudioFocus = true)：**音频焦点被别的 App 抢占时自动暂停**，
 *    瞬时抢占（导航播报、来电）时自动压低音量（duck）；
 * 2. setHandleAudioBecomingNoisy(true)：**耳机拔出自动暂停**（避免外放尴尬）；
 * 3. setWakeMode(C.WAKE_MODE_LOCAL)：持有唤醒锁，CPU 不休眠（需要 WAKE_LOCK 权限，:app manifest 已声明）。
 *
 * 断点续播（R18 后半句「被系统清理后重进恢复位置」）：进曲目时从 [PlaybackProgressStore] 读一次并 seek，
 * 播放中每 [PROGRESS_SAVE_INTERVAL_MS] 写一次，暂停 / 结束 / 服务销毁时各写一次。
 * 存储实现由 :app 注入（本模块不引入 Room 表）。
 */
@UnstableApi
class AudioPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null

    private var mediaSession: MediaSession? = null

    /** 断点存储；:app 没提供时为 null（退化成不记位置，其余功能不受影响）。 */
    private var progressStore: PlaybackProgressStore? = null

    /** 服务级协程作用域（主线程），只做「定时写进度」「读进度后 seek」这类小任务。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var progressTicker: Job? = null

    private val listener = object : Player.Listener {

        /** 换曲：先按记录恢复位置（R18 断点续播），再正常起播。 */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) restoreProgress(mediaItem)
        }

        /** 暂停 / 被音频焦点抢占而暂停：立刻落一次盘，别等下一个 5 秒。 */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
        }

        /** 一曲放完（含队列结束）：落盘当前曲目位置。 */
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) saveProgress()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val host = application as? PlaybackHost
        progressStore = host?.playbackProgress

        // 每次 open 现取当前后端：服务比「当前根目录」活得久，用户换根目录不必重启服务。
        val dataSourceFactory = DataSource.Factory {
            host?.playbackBackend?.let { backend -> BackendDataSource(backend) } ?: MissingBackendDataSource()
        }

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        exoPlayer.addListener(listener)

        val session = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(openAppIntent())
            .setCallback(SessionCallback())
            .build()

        player = exoPlayer
        mediaSession = session

        // 通知渠道名用本模块中文资源（R16）；图标用自带的白色音符（通知栏小图标要求单色）
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.playback_notification_channel_name)
            .setNotificationId(NOTIFICATION_ID)
            .build()
        provider.setSmallIcon(R.drawable.ic_playback_notification)
        setMediaNotificationProvider(provider)

        startProgressTicker()
        AppLog.i(TAG, "音频后台播放服务已启动（R18）")
    }

    /** 系统（MediaController / 通知栏 / 锁屏）拿到的会话。 */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * App 被从最近任务里划掉。
     *
     * 还在播就继续（R18 要求后台不中断，澎湃 OS 下这条路径很常见）；已暂停就正常退场，
     * 不留一个空转的前台服务。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null || !current.playWhenReady) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        // 最后一次落盘：走阻塞写，保证服务真的销毁时进度已经写出去（只此一处阻塞，且是一次小写入）
        saveProgressBlocking()
        progressTicker?.cancel()
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
        AppLog.i(TAG, "音频后台播放服务已销毁")
    }

    /**
     * 会话回调：把控制器送来的条目补齐成「可播放」的条目。
     *
     * 为什么需要：MediaItem 经 MediaSession 传输时本地配置可能只剩一个 URI
     * （甚至只剩 requestMetadata.mediaUri），而播放器建 MediaSource 必须要本地配置；
     * 缺了就按 requestMetadata 重建一个，避免「点播放没反应」这类只在真机上暴露的问题。
     */
    private inner class SessionCallback : MediaSession.Callback {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { if (it.localConfiguration != null) it else rebuild(it) })
    }

    /** 按 requestMetadata 里的 URI 重建条目的本地配置；连 URI 都没有就原样返回。 */
    private fun rebuild(item: MediaItem): MediaItem {
        val uri = item.requestMetadata.mediaUri ?: return item
        return MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(uri)
            .setMediaMetadata(item.mediaMetadata)
            .build()
    }

    // ------------------------------------------------------------ 断点续播（R18）

    /** 定时落盘：只在「真的要播」的时候写，暂停时由 [listener] 兜一次。 */
    private fun startProgressTicker() {
        if (progressStore == null) return
        progressTicker = scope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                val current = player ?: continue
                if (current.playWhenReady && current.playbackState != Player.STATE_IDLE) saveProgress()
            }
        }
    }

    /** 把当前曲目与位置写回存储（失败只记日志，绝不影响播放）。 */
    private fun saveProgress() {
        val store = progressStore ?: return
        val snapshot = currentSnapshot() ?: return
        scope.launch {
            runCatching { store.save(snapshot) }
                .onFailure { AppLog.w(TAG, "写断点失败：" + snapshot.path, it) }
        }
    }

    /**
     * 服务销毁时的阻塞版落盘（best-effort）。
     *
     * 这是本服务唯一一处阻塞主线程的地方：onDestroy 之后协程作用域就要取消了，
     * 只有同步写完才能保证位置不丢。加超时兜底，绝不为一次进度写入卡住退出。
     */
    private fun saveProgressBlocking() {
        val store = progressStore ?: return
        val snapshot = currentSnapshot() ?: return
        runCatching {
            runBlocking {
                withTimeoutOrNull(BLOCKING_SAVE_TIMEOUT_MS) { store.save(snapshot) }
            }
        }.onFailure { AppLog.w(TAG, "退出时写断点失败：" + snapshot.path, it) }
    }

    /** 当前曲目的断点快照；没有在播的曲目时返回 null。 */
    private fun currentSnapshot(): PlaybackProgress? {
        val current = player ?: return null
        val item = current.currentMediaItem ?: return null
        val backendId = MediaSourceFactory.backendIdOf(item) ?: return null
        val path = MediaSourceFactory.pathOf(item) ?: return null
        return PlaybackProgress(
            backendId = backendId,
            path = path,
            positionMs = current.currentPosition.coerceAtLeast(0L),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 进入某曲目时恢复上次位置；位置太靠前（< [RESUME_MIN_POSITION_MS]）就不折腾，从头播。 */
    private fun restoreProgress(mediaItem: MediaItem) {
        val store = progressStore ?: return
        val backendId = MediaSourceFactory.backendIdOf(mediaItem) ?: return
        val path = MediaSourceFactory.pathOf(mediaItem) ?: return
        scope.launch {
            val saved = runCatching { store.load(backendId, path) }.getOrNull() ?: return@launch
            if (saved.positionMs < RESUME_MIN_POSITION_MS) return@launch
            val current = player ?: return@launch
            val duration = current.duration
            // 时长已知时别落在最后几秒（否则一进来就跳下一首）
            val target = if (duration != C.TIME_UNSET && duration > 0) {
                saved.positionMs.coerceAtMost((duration - RESUME_TAIL_GUARD_MS).coerceAtLeast(0L))
            } else {
                saved.positionMs
            }
            current.seekTo(target)
            AppLog.i(TAG, "断点续播：$path @${target}ms")
        }
    }

    /** 通知栏点回 App（单实例，不新建 Activity）。 */
    private fun openAppIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {

        const val TAG = "playback-service"

        /** 媒体类音频属性：走媒体音量、可被系统音频焦点管理（R18）。 */
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        /** 通知渠道 id / 通知 id（与 ASR 任务通知（R19）区分开）。 */
        const val NOTIFICATION_CHANNEL_ID = "mediagate_playback"
        const val NOTIFICATION_ID = 1001

        /** 播放中写断点的间隔（毫秒）。 */
        const val PROGRESS_SAVE_INTERVAL_MS = 5_000L

        /** 退出时那次阻塞落盘的超时（毫秒）。 */
        const val BLOCKING_SAVE_TIMEOUT_MS = 500L

        /** 小于这个位置不恢复（前 5 秒没意义，重头播更符合直觉）。 */
        const val RESUME_MIN_POSITION_MS = 5_000L

        /** 恢复位置距结尾不足这个值时归到「开头」附近，避免一进来就跳曲。 */
        const val RESUME_TAIL_GUARD_MS = 5_000L
    }
}
