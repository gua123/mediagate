package io.github.gua123.mediagate.media.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.gua123.mediagate.core.common.AppLog

/**
 * 视频会话的**播放源**（R18 视频侧）。
 *
 * 为什么不是直接吃 :media:engine 的 `PlayerEngine`：本模块只提供"会话 + 通知"这一层，
 * 播放本体归播放页（内核实例由 :feature:player-video 的 ViewModel 持有、由 :app 造出来）。
 * 用一个 8 个成员的窄接口把两边隔开，本模块就不必依赖 :media:engine，
 * 将来换内核（Media3 / LibVLC / 别的）也不用改这里。
 *
 * 线程约定：**全部在主线程调用**（与 Media3 的 Player 一致）。
 */
interface VideoSessionSource {

    /** 当前是否有可会话的视频（false = 会话还在、但没东西可播）。 */
    val hasMedia: Boolean

    /** 展示名（通知栏标题）。 */
    val title: String

    /** 后端内路径（mediaId，便于排查）。 */
    val path: String

    /** 是否正在播放。 */
    val isPlaying: Boolean

    /** 当前播放位置（毫秒）。 */
    val positionMs: Long

    /** 总时长（毫秒）；未知为 0。 */
    val durationMs: Long

    /**
     * 通知栏 / 锁屏封面（**R18**）；null = 没有封面，系统退回默认图标。
     *
     * 由 :app 从缩略图缓存里取现成的字节（**不为了封面去抽帧**——列表里滚过的条目通常已经有了）。
     */
    val artwork: ByteArray? get() = null

    /** 播放（通知栏 / 锁屏 / 耳机键的"播放"）。 */
    fun play()

    /** 暂停（通知栏 / 锁屏 / 耳机键的"暂停"；音频焦点被抢占也走它）。 */
    fun pause()

    /** 定位到 [positionMs]（通知栏进度拖拽、锁屏快退快进）。 */
    fun seekTo(positionMs: Long)
}

/**
 * 视频后台播放的宿主（R18）：系统创建的服务反查 Application 拿"当前会话源"。
 *
 * 与 [PlaybackHost] 同一套路（服务拿不到构造注入，只能从 Application 反查），
 * 依赖方向仍是 :app → :media:playback。
 */
interface VideoSessionHost {

    /**
     * 当前视频会话源；**null = 没有会话**（播放页已退出 / 内核已释放），服务会自己退场。
     */
    val videoSessionSource: VideoSessionSource?
}

/**
 * 视频后台播放服务（**R18 视频侧**）：切后台 / 息屏后播放不中断，通知栏与锁屏可控。
 *
 * 设计取舍（为什么是"只做会话与通知"）：
 * - **播放器不在这里**。视频由播放页的内核（Media3 / LibVLC，见 :media:engine）持有并输出音频轨；
 *   把播放器搬进服务意味着把内核所有权从页面挪走（要动 :media:engine 与 :feature:player-video 的
 *   生命周期），本轮边界不允许，而且页面切后台时**本来就不会释放内核**（见 VideoPlayerViewModel.onCleared）。
 * - 因此本服务用 [SimpleBasePlayer] 做一层**代理播放器**：它的状态读自 [VideoSessionHost] 给的
 *   [VideoSessionSource]，收到的命令（播放/暂停/seek）再写回同一个源——系统看到的是一个正常的
 *   Media3 会话，于是通知栏、锁屏、蓝牙、耳机键全部可用（R18「通知栏与锁屏可控」）。
 * - **前台服务**：由 :app 在视频起播时拉起；播放中 Media3 会把通知变成前台服务通知，
 *   进程因此在切后台/息屏时不会被随手回收（R18 要求 ≥30 分钟不中断）。
 * - **唤醒锁**：息屏后 CPU 若休眠，解码线程会被冻住（画面没了、声音也断）。:media:engine 的
 *   视频内核没有开 `setWakeMode`（那是音频服务里对 ExoPlayer 做的），所以这里在播放期间
 *   持一把 `PARTIAL_WAKE_LOCK`，暂停/退出立刻释放。
 * - **画面**：不做任何"停渲染"的额外处理——息屏时系统销毁 surface，解码器继续输出音频；
 *   回到前台 surface 重建，画面自己回来（正是 R18 要的"音频轨继续输出，画面暂停渲染"）。
 *
 * 真机才能验证的：澎湃 OS 下的后台存活时长、息屏 30 分钟不中断、锁屏/蓝牙控制。
 */
@UnstableApi
class VideoPlaybackService : MediaSessionService() {

    private val handler = Handler(Looper.getMainLooper())

    private var sessionPlayer: VideoSessionPlayer? = null

    private var mediaSession: MediaSession? = null

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 会话状态轮询（1 秒一次）。
     *
     * 为什么轮询而不是让页面推：位置与"是否在播"在**内核**里，Media3 的 Player 又要周期性
     * 位置更新才会让通知栏进度条动起来；1 秒一次的开销可以忽略（页面自己采样是 500 ms）。
     * 同时它承担"会话没了就退场"和"唤醒锁跟着播放状态走"两件事。
     */
    private val poller = object : Runnable {
        override fun run() {
            val source = currentSource()
            if (source == null) {
                AppLog.i(TAG, "视频会话已收掉，服务退场（R18）")
                stopSelf()
                return
            }
            syncWakeLock(source.isPlaying)
            sessionPlayer?.refresh()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = VideoSessionPlayer(::currentSource)
        val session = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent())
            .build()
        sessionPlayer = player
        mediaSession = session

        // 通知渠道名用本模块中文资源（R16）；图标复用音频侧那张单色小图标
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.video_playback_channel_name)
            .setNotificationId(NOTIFICATION_ID)
            .build()
        provider.setSmallIcon(R.drawable.ic_playback_notification)
        setMediaNotificationProvider(provider)

        handler.post(poller)
        AppLog.i(TAG, "视频后台播放服务已启动（R18 视频侧）")
    }

    /** 系统（通知栏 / 锁屏 / 蓝牙 / 耳机键）绑定的会话。 */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * App 被从最近任务里划掉：没在播就正常退场；在播也不留一个空转的前台服务
     * （页面已经销毁，代理播放器再挂着只会显示一个点不动的通知）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val source = currentSource()
        if (source == null || !source.isPlaying) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        releaseWakeLock()
        mediaSession?.release()
        mediaSession = null
        sessionPlayer?.release()
        sessionPlayer = null
        super.onDestroy()
        AppLog.i(TAG, "视频后台播放服务已销毁")
    }

    // ---------------------------------------------------------------- 内部

    /** 当前会话源（:app 通过 Application 反查给出）。 */
    private fun currentSource(): VideoSessionSource? =
        (application as? VideoSessionHost)?.videoSessionSource

    /**
     * 唤醒锁跟随播放状态（R18 息屏不中断）。
     *
     * 用带超时的 acquire（[WAKE_LOCK_TIMEOUT_MS]）兜底：万一某条异常路径没释放，
     * 系统也会自己收回，不会留下一个永远亮着的锁。
     */
    private fun syncWakeLock(playing: Boolean) {
        if (playing) acquireWakeLock() else releaseWakeLock()
    }

    private fun acquireWakeLock() {
        val current = wakeLock
        if (current?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        val lock = current ?: manager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
        wakeLock = lock
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
            .onFailure { AppLog.w(TAG, "获取播放唤醒锁失败（省电策略可能拦截）", it) }
    }

    private fun releaseWakeLock() {
        val current = wakeLock ?: return
        if (current.isHeld) runCatching { current.release() }
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

        const val TAG = "video-session"

        /** 通知渠道 id / 通知 id（与音频后台播放、ASR 任务通知都区分开）。 */
        const val CHANNEL_ID = "mediagate_video_playback"
        const val NOTIFICATION_ID = 1002

        /** 会话轮询间隔（毫秒）：通知栏进度与播放状态刷新频率。 */
        const val POLL_INTERVAL_MS = 1_000L

        /** 唤醒锁超时（毫秒）：兜底用，正常路径都会在暂停/退出时释放。 */
        const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1_000L

        const val WAKE_LOCK_TAG = "mediagate:video-playback"
    }
}

/**
 * 代理播放器（R18）：把 [VideoSessionSource] 包装成一个 Media3 的 [Player] 给 MediaSession 用。
 *
 * 只实现"读状态 + 播放/暂停/seek"这几件事：
 * - [getState] 每次都按源的当前值现算（位置由 Media3 自己按 [SimpleBasePlayer.State] 里的
 *   内容位置插值，所以通知栏进度条会自己走）；
 * - 命令落到源上，然后立刻 [refresh] 一次，让 MediaSession 把新状态广播给通知栏/LockScreen。
 */
@UnstableApi
internal class VideoSessionPlayer(
    private val sourceProvider: () -> VideoSessionSource?,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    /** 让 Media3 重新读一次 [getState] 并广播（位置/播放状态/换视频时调用）。 */
    fun refresh() {
        invalidateState()
    }

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        val source = sourceProvider()
        if (source == null || !source.hasMedia) {
            // 没有会话对象：IDLE + 空播放列表（通知栏不会显示进度，但服务仍活着）
            return builder.setPlaybackState(Player.STATE_IDLE).build()
        }
        val duration = source.durationMs
        val artwork = source.artwork
        val metadata = MediaMetadata.Builder().setTitle(source.title)
        if (artwork != null && artwork.isNotEmpty()) {
            metadata.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        val item = MediaItem.Builder()
            .setMediaId(source.path)
            .setUri(source.path)
            .setMediaMetadata(metadata.build())
            .build()
        val data = MediaItemData.Builder(source.path)
            .setMediaItem(item)
            .setDurationUs(if (duration > 0L) duration * 1_000L else C.TIME_UNSET)
            .setIsSeekable(duration > 0L)
            .build()
        return builder
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(source.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setIsLoading(false)
            .setPlaylist(listOf(data))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(source.positionMs.coerceAtLeast(0L))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        sourceProvider()?.let { source ->
            if (playWhenReady) source.play() else source.pause()
        }
        refresh()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (positionMs != C.TIME_UNSET) {
            sourceProvider()?.seekTo(positionMs.coerceAtLeast(0L))
        }
        refresh()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        refresh()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        sourceProvider()?.pause()
        refresh()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {

        /** 锁屏/PIP 快进快退的步长：与 R13 的 10 秒保持一致。 */
        const val SEEK_INCREMENT_MS = 10_000L

        /**
         * 会话对外暴露的能力。
         *
         * 刻意**不含**播放列表增删（COMMAND_CHANGE_MEDIA_ITEMS）：队列归播放页管，
         * 外部（通知栏/车机）不该往会话里塞它播不了的东西。
         */
        val AVAILABLE_COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
