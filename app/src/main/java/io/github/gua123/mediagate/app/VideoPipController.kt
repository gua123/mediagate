package io.github.gua123.mediagate.app

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import io.github.gua123.mediagate.R
import io.github.gua123.mediagate.core.common.AppLog
import io.github.gua123.mediagate.feature.player.video.VideoPipAction
import io.github.gua123.mediagate.feature.player.video.VideoPipActionSink
import io.github.gua123.mediagate.feature.player.video.VideoPipMath

/**
 * Activity 侧的画中画能力（**R13**）——由 [VideoPipController] 实现，[AppContainer] 持有。
 *
 * 为什么要这层接口：进/出 PIP 只有 Activity 能做，而 Activity 会被系统重建、页面（ViewModel）
 * 却活在导航栈里。容器持有"当前 Activity 的桥"，页面拿到的始终是同一个 `VideoPipHost`，
 * Activity 重建时把桥换掉即可（见 [AppContainer.attachPipBridge]）。
 */
interface VideoPipBridge {

    /** 当前是否在画中画里（Activity 现场值）。 */
    val isInPip: Boolean

    /** 进入画中画；系统拒绝返回 false。 */
    fun enterPip(aspectRatio: Float): Boolean

    /** 更新 PIP 动作按钮（播放/暂停 + 快退/快进 10 秒）。 */
    fun updateActions(isPlaying: Boolean)

    /** 打开/关闭"按 Home 自动进 PIP"。 */
    fun setAutoEnterEnabled(enabled: Boolean)

    /** 注册动作落点（页面 → 内核）。 */
    fun setActionSink(sink: VideoPipActionSink?)
}

/**
 * 画中画的 Activity 级实现（**R13**）。
 *
 * 职责（全部是"只有 Activity 才能做的事"）：
 * 1. `enterPictureInPictureMode(PictureInPictureParams)`：比例取视频真实尺寸（Media3 的 PlayerView
 *    能报 videoSize）按 [VideoPipMath] 归到 16:9 / 4:3 / 竖屏三档；取不到就用页面给的提示值（16:9）；
 * 2. `setPictureInPictureParams`：动作按钮（R13 的播放/暂停 + 快退/快进 10 秒，Android 13+ 显示）
 *    与 `setAutoEnterEnabled`（播放中按 Home 自动进入）；
 * 3. PIP 动作的回路：`RemoteAction` 用 **PendingIntent.getBroadcast** 发到本 Activity 动态注册的
 *    接收器（PIP 期间 Activity 一定活着，所以不需要常驻的 manifest 接收器），再回调页面的
 *    [VideoPipActionSink]，最终打到当前内核上。
 *
 * 真机才能验证的：PIP 小窗的真实比例与动作按钮样式、澎湃 OS 对 PIP 的额外限制。
 *
 * @param activity 宿主 Activity（PIP 的 API 全在它身上）。
 * @param videoSize 当前视频的像素尺寸提供者（取不到给 null）；用于把 PIP 比例定到真实宽高比。
 */
class VideoPipController(
    private val activity: ComponentActivity,
    private val videoSize: () -> Pair<Int, Int>? = { null },
) : VideoPipBridge {

    private var autoEnterEnabled = false

    private var playing = false

    private var sink: VideoPipActionSink? = null

    /** 页面给的期望比例（拿不到视频真实尺寸时用它）。 */
    private var ratioHint = VideoPipMath.DEFAULT_ASPECT_RATIO

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(EXTRA_ACTION)) {
                EXTRA_TOGGLE -> sink?.onAction(VideoPipAction.TOGGLE_PLAY_PAUSE)
                EXTRA_REWIND -> sink?.onAction(VideoPipAction.REWIND_10S)
                EXTRA_FORWARD -> sink?.onAction(VideoPipAction.FORWARD_10S)
            }
        }
    }

    init {
        // 只收本应用（同一 PendingIntent 身份）发的广播；RECEIVER_NOT_EXPORTED 是 Android 13+ 的要求
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(ACTION_PIP),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override val isInPip: Boolean get() = activity.isInPictureInPictureMode

    override fun enterPip(aspectRatio: Float): Boolean {
        ratioHint = aspectRatio
        if (activity.isInPictureInPictureMode) return false
        val params = params(resolveRatio())
        return runCatching { activity.enterPictureInPictureMode(params) }
            .onFailure { AppLog.w(TAG, "进入画中画失败（系统拒绝或未声明 supportsPictureInPicture）", it) }
            .getOrDefault(false)
    }

    /**
     * 播放中按 Home 的兜底路径（R13）。
     *
     * Android 12+ 上了 `setAutoEnterEnabled(true)` 之后系统会自己进 PIP；这里再显式进一次是为了
     * **行为确定**（部分 ROM 的自动进入会被省电策略拦下），并且只在页面开了自动进入时做——
     * 暂停状态下按 Home 不该缩成小窗，那会让人以为 App 卡住了。
     */
    fun onUserLeaveHint(): Boolean = if (autoEnterEnabled) enterPip(ratioHint) else false

    override fun updateActions(isPlaying: Boolean) {
        playing = isPlaying
        applyParams()
    }

    override fun setAutoEnterEnabled(enabled: Boolean) {
        autoEnterEnabled = enabled
        applyParams()
    }

    override fun setActionSink(sink: VideoPipActionSink?) {
        this.sink = sink
    }

    /** Activity 回调：PIP 状态变化（进/出都刷新一次参数，动作按钮跟着进出）。 */
    fun onPipModeChanged(inPip: Boolean) {
        AppLog.i(TAG, if (inPip) "已进入画中画（R13）" else "已退出画中画（R13）")
        applyParams()
    }

    /** Activity 销毁：注销接收器，断开动作落点（幂等）。 */
    fun release() {
        runCatching { activity.unregisterReceiver(receiver) }
            .onFailure { AppLog.w(TAG, "注销画中画动作接收器失败", it) }
        sink = null
    }

    // ---------------------------------------------------------------- 内部

    /** 真实宽高比优先，其次页面给的提示值（R13：16:9 / 4:3 / 竖屏各一档）。 */
    private fun resolveRatio(): Float {
        val size = runCatching { videoSize() }.getOrNull()
        return if (size == null) {
            VideoPipMath.snapAspectRatio(ratioHint)
        } else {
            VideoPipMath.snapForSize(size.first, size.second)
        }
    }

    /** 参数变化就推给系统；"不在 PIP 又不需要自动进"时不动系统（省得每次状态变化都调一次）。 */
    private fun applyParams() {
        if (!activity.isInPictureInPictureMode && !autoEnterEnabled) return
        runCatching { activity.setPictureInPictureParams(params(resolveRatio())) }
            .onFailure { AppLog.w(TAG, "更新画中画参数失败", it) }
    }

    private fun params(ratio: Float): PictureInPictureParams {
        val (width, height) = VideoPipMath.ratioFraction(ratio)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .setAutoEnterEnabled(autoEnterEnabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // R13：Android 13 起 PIP 窗口里才显示自定义动作
            builder.setActions(pipActions())
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** 三个动作：暂停/播放（按当前状态）+ 快退 10 秒 + 快进 10 秒（R13）。 */
    private fun pipActions(): List<RemoteAction> = listOf(
        remoteAction(
            iconRes = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            titleRes = if (playing) R.string.video_pip_pause else R.string.video_pip_play,
            requestCode = REQUEST_TOGGLE,
            extra = EXTRA_TOGGLE,
        ),
        remoteAction(
            iconRes = android.R.drawable.ic_media_rew,
            titleRes = R.string.video_pip_rewind,
            requestCode = REQUEST_REWIND,
            extra = EXTRA_REWIND,
        ),
        remoteAction(
            iconRes = android.R.drawable.ic_media_ff,
            titleRes = R.string.video_pip_forward,
            requestCode = REQUEST_FORWARD,
            extra = EXTRA_FORWARD,
        ),
    )

    /**
     * 一个 PIP 动作按钮。
     *
     * 图标刻意用系统自带的媒体图标（`android.R.drawable.ic_media_*`）：RemoteAction 在 PIP 里只有
     * 十几 dp，自带图标是单色、风格统一、各 ROM 都认，不必为此多带四份矢量资源。
     * 文案（播放/暂停/快退 10 秒/快进 10 秒）走本模块中文资源（R16）。
     */
    private fun remoteAction(iconRes: Int, titleRes: Int, requestCode: Int, extra: String): RemoteAction {
        val intent = Intent(ACTION_PIP)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_ACTION, extra)
        val pending = PendingIntent.getBroadcast(
            activity,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = activity.getString(titleRes)
        return RemoteAction(Icon.createWithResource(activity, iconRes), title, title, pending)
    }

    private companion object {

        const val TAG = "video-pip"

        /** PIP 动作广播（只在应用内，接收器动态注册）。 */
        const val ACTION_PIP = "io.github.gua123.mediagate.action.PIP"

        const val EXTRA_ACTION = "pip_action"
        const val EXTRA_TOGGLE = "toggle"
        const val EXTRA_REWIND = "rewind"
        const val EXTRA_FORWARD = "forward"

        /** 三个动作各自的 requestCode：PendingIntent 的相等性不看 extras，只能靠它区分。 */
        const val REQUEST_TOGGLE = 10
        const val REQUEST_REWIND = 11
        const val REQUEST_FORWARD = 12
    }
}
