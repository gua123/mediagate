package io.github.gua123.mediagate.feature.player.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 画中画（PIP）里可用的自定义动作（**R13**，Android 13+ 的 `RemoteAction`）。
 *
 * 只有三个：播放/暂停、快退 10 秒、快进 10 秒——与 plan 4.8 的字面要求一致。
 * 动作由 :app 侧（Activity 的 PIP 动作按钮）发出，经 [VideoPipActionSink] 回到播放页，
 * 最终驱动**当前内核**（页面才是内核的持有者）。
 */
enum class VideoPipAction {

    /** 播放 / 暂停（按当前状态取反）。 */
    TOGGLE_PLAY_PAUSE,

    /** 快退 10 秒（R13）。 */
    REWIND_10S,

    /** 快进 10 秒（R13）。 */
    FORWARD_10S,
}

/**
 * PIP 动作的落点（R13）：宿主收到系统动作后回调它，页面据此驱动当前引擎。
 *
 * 之所以是"页面注册、宿主回调"而不是"宿主直接拿引擎"：内核实例归播放页 ViewModel 所有
 * （见 [VideoPlayerViewModel]），:app 只持有宿主能力，不该反向抓内核。
 */
fun interface VideoPipActionSink {

    /** 收到一个 PIP 动作。 */
    fun onAction(action: VideoPipAction)
}

/**
 * 画中画宿主能力（**R13**）——由 :app 的 Activity 实现并注入（[VideoPlayerEnvironment.pip]）。
 *
 * 为什么必须是"宿主能力"而不是页面自己干：进/出 PIP 是 **Activity 级 API**
 * （`enterPictureInPictureMode` / `setPictureInPictureParams` / `onPictureInPictureModeChanged`），
 * :feature:player-video 是纯 Compose 模块，拿不到也不该拿 Activity。
 *
 * 约定（实现方需遵守）：
 * - 所有方法都在**主线程**调用；
 * - [enterPip] 在系统不允许（未声明 supportsPictureInPicture / 已在 PIP / 不可见）时返回 false，
 *   不抛异常；[setAutoEnterEnabled] 只是"允许自动进"，真正进入由系统在用户按 Home 时决定；
 * - [updateActions] 只更新 PIP 窗口里的动作按钮（比例与动作都通过 setPictureInPictureParams 生效）。
 */
interface VideoPipHost {

    /** 当前是否处于画中画（R13）；页面据此隐藏控制条、提示"画中画中"。 */
    val isInPip: StateFlow<Boolean>

    /**
     * 进入画中画（R13）。
     *
     * @param aspectRatio 期望的宽高比（宽 / 高，见 [VideoPipMath.clampAspectRatio]）；
     *   宿主可按视频真实尺寸再修正一次（Media3 的 PlayerView 能报出 videoSize）。
     * @return 是否真的发起了进入（系统拒绝时为 false）。
     */
    fun enterPip(aspectRatio: Float): Boolean

    /**
     * 更新 PIP 内的动作（R13：播放/暂停 + 快退 10 s + 快进 10 s）。
     *
     * @param isPlaying 当前是否在播：决定第一个按钮画"暂停"还是"播放"。
     */
    fun updateActions(isPlaying: Boolean)

    /**
     * 允许/禁止"按 Home 自动进 PIP"（R13：播放中按 Home 自动进入）。
     *
     * 播放页在**播放中**打开、暂停/结束/退出时关闭——否则用户暂停后按 Home 会莫名其妙缩成小窗。
     */
    fun setAutoEnterEnabled(enabled: Boolean)

    /**
     * 注册 PIP 动作落点（R13）；传 null 注销（页面销毁时）。
     *
     * 宿主在 PIP 动作被点击时调用 [VideoPipActionSink.onAction]。
     */
    fun setActionSink(sink: VideoPipActionSink?)
}

/**
 * 没有宿主时的空实现（纯 JVM 单测 / 非 Activity 环境）。
 *
 * 所有调用都是无副作用的空操作：[isInPip] 恒为 false、[enterPip] 恒返回 false。
 * 这样播放页在"宿主缺失"时行为不变（只是没有画中画），而不是崩溃。
 */
object NoopVideoPipHost : VideoPipHost {

    private val never = MutableStateFlow(false)

    override val isInPip: StateFlow<Boolean> = never

    override fun enterPip(aspectRatio: Float): Boolean = false

    override fun updateActions(isPlaying: Boolean) = Unit

    override fun setAutoEnterEnabled(enabled: Boolean) = Unit

    override fun setActionSink(sink: VideoPipActionSink?) = Unit
}
