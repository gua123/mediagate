package io.github.gua123.mediagate.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.feature.player.video.VideoPlaybackHost
import io.github.gua123.mediagate.feature.player.video.VideoSession
import io.github.gua123.mediagate.media.playback.VideoPlaybackService
import io.github.gua123.mediagate.media.playback.VideoSessionHost
import io.github.gua123.mediagate.media.playback.VideoSessionSource

/**
 * 视频后台播放的**宿主实现**（**R18 视频侧** + **R19 让路**）。
 *
 * 三件事：
 * 1. **会话**：把播放页借出来的内核（[VideoSession]）包成 :media:playback 认得的
 *    [VideoSessionSource]，交给 [VideoPlaybackService] 的 MediaSession——通知栏、锁屏、蓝牙、
 *    耳机键因此能控到这个视频；
 * 2. **前台服务**：用 [MediaController] 连上服务（Media3 的标准客户端套路，与音频侧的
 *    AudioSessionController 一致）。绑定即创建服务，播放一开始 Media3 就把通知变成前台服务通知，
 *    于是切后台/息屏时进程不会被随手回收（R18 ≥30 分钟不中断）；会话收掉时释放控制器并 stopService；
 * 3. **让路**：把页面上报的"正在前台播放"透出成 [active]，由 [AppContainer] 与音频播放状态合并后
 *    喂给 ASR 的 PlaybackYieldGate（R19「播放时自动让路」）。
 *
 * 为什么播放器不搬进服务：内核实例归播放页所有（R9 换内核、R10 改档位都在页面里发生），
 * 搬走等于重写内核生命周期；本实现只借出"控制面"，画面与声音仍由页面内核负责——这也是
 * plan 4.11 里"音频轨继续输出、画面暂停渲染"最省事且不破坏页面的做法。
 *
 * 线程约定：全部在主线程（Media3 的控制器与内核都要求）。
 */
@UnstableApi
class VideoSessionController(private val context: Context) : VideoPlaybackHost, VideoSessionHost {

    private val appContext: Context = context.applicationContext

    private val _active = MutableStateFlow(false)

    /** 视频是否正在前台播放（R19 让路；容器把它并进 playbackActive）。 */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var session: VideoSession? = null

    private var source: EngineSessionSource? = null

    private var controller: MediaController? = null

    private var connecting = false

    // ------------------------------------------------------------ VideoSessionHost（服务反查）

    /** 服务侧的会话源；没有会话时为 null（服务会自己退场）。 */
    override val videoSessionSource: VideoSessionSource? get() = source

    // ------------------------------------------------------------ VideoPlaybackHost（页面调用）

    override fun setActive(active: Boolean) {
        _active.value = active
    }

    override fun bindSession(session: VideoSession?) {
        if (session == null) {
            detach()
            return
        }
        this.session = session
        source = EngineSessionSource(session)
        connectService()
    }

    // ------------------------------------------------------------ 给 :app 其余部分用

    /**
     * 当前视频画面尺寸（R13 的 PIP 比例用）；不是 Media3 的 PlayerView、或还没解出画面时为 null。
     *
     * Media3 1.11 的 PlayerView 自己不再暴露 videoSize，改为问它挂着的 Player
     * （`Player.getVideoSize()`，解码器解出第一帧后才有值）；LibVLC 的 SurfaceView 拿不到尺寸，
     * 走 null 让 PIP 用页面给的默认档（16:9）。
     */
    fun videoSize(): Pair<Int, Int>? {
        val view: View = session?.videoView ?: return null
        val player = (view as? PlayerView)?.player ?: return null
        val size = player.videoSize
        return if (size.width > 0 && size.height > 0) size.width to size.height else null
    }

    // ------------------------------------------------------------ 内部

    /** 收掉会话：先断源（服务下一拍就退场），再解绑控制器、停服务。 */
    private fun detach() {
        session = null
        source = null
        controller?.release()
        controller = null
        connecting = false
        runCatching { appContext.stopService(Intent(appContext, VideoPlaybackService::class.java)) }
            .onFailure { AppLog.w(TAG, "停止视频会话服务失败", it) }
    }

    /**
     * 连上视频会话服务（幂等）。
     *
     * 用 [MediaController] 而不是 startService：绑定即创建服务，且播放中由 Media3 自己
     * 把服务转成前台（前台服务的启动限制在"应用可见时"才放行，而这时视频正在前台播放）。
     */
    private fun connectService() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(appContext, ComponentName(appContext, VideoPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token)
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync()
        future.addListener(
            {
                connecting = false
                runCatching { future.get() }
                    .onSuccess { connected ->
                        controller = connected
                        AppLog.i(TAG, "已连接视频会话服务（R18 视频侧）")
                    }
                    .onFailure { AppLog.w(TAG, "连接视频会话服务失败", it) }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    /**
     * 把播放页的内核包成会话源（R18）。
     *
     * 每次读都现问内核（位置/是否在播都在内核里），命令也直接打到内核上——**没有第二份状态**，
     * 因此不会出现"通知栏显示在播、画面其实是暂停"的错位。
     * 所有调用都包了 runCatching：会话在收掉的瞬间可能还有一个在途的轮询。
     */
    private inner class EngineSessionSource(private val session: VideoSession) : VideoSessionSource {

        override val hasMedia: Boolean get() = true

        override val title: String get() = session.title

        override val path: String get() = session.path

        override val isPlaying: Boolean get() = runCatching { session.engine.isPlaying }.getOrDefault(false)

        override val positionMs: Long get() = runCatching { session.engine.positionMs() }.getOrDefault(0L)

        override val durationMs: Long get() = runCatching { session.engine.durationMs() }.getOrDefault(0L)

        override fun play() {
            runCatching { session.engine.play() }.onFailure { AppLog.w(TAG, "会话播放失败", it) }
        }

        override fun pause() {
            runCatching { session.engine.pause() }.onFailure { AppLog.w(TAG, "会话暂停失败", it) }
        }

        override fun seekTo(positionMs: Long) {
            runCatching { session.engine.seekTo(positionMs) }
                .onFailure { AppLog.w(TAG, "会话跳转失败", it) }
        }
    }

    private companion object {
        const val TAG = "video-session-app"
    }
}
