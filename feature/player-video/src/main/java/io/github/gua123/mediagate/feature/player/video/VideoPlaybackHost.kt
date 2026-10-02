package io.github.gua123.mediagate.feature.player.video

import android.view.View
import io.github.gua123.mediagate.media.engine.PlayerEngine

/**
 * 当前视频会话（**R18**：通知栏 / 锁屏 / 蓝牙的控制对象）。
 *
 * 为什么把内核本体也带上：后台会话要能"真的控到这个视频"，而内核实例归播放页所有
 * （[VideoPlayerViewModel] 造它、用它、释放它）。会话只是**借用**——页面释放内核前会先
 * `bindSession(null)`，宿主不会拿到一个已经 release 的内核。
 *
 * @property engine 当前内核（Media3 / LibVLC，同一抽象）。
 * @property title 展示名（文件名，R16 不额外拼英文）。
 * @property backendId 后端 id（断点续播的 key 之一，R18）。
 * @property path 后端内路径。
 * @property videoView 当前内核的画面视图（Media3 是 PlayerView、LibVLC 是 SurfaceView）。
 *   :app 的宿主用它取视频真实尺寸来定 PIP 比例（R13），拿不到时为 null（退化成 16:9）。
 */
data class VideoSession(
    val engine: PlayerEngine,
    val title: String,
    val backendId: String,
    val path: String,
    val videoView: View?,
)

/**
 * 视频后台播放与让路的宿主能力（**R18 视频侧 / R19 播放让路**）。
 *
 * 由 :app 实现并注入（[VideoPlayerEnvironment.playback]）。这里只放**两件页面必须告诉宿主的事**：
 *
 * 1. **会话**（[bindSession]）：切后台/息屏后通知栏、锁屏、蓝牙要继续控这个视频，
 *    所以 :app 要拿着"当前在播的是哪一条、用的是哪个内核"去建 MediaSession
 *    （:media:playback 的 `VideoPlaybackService`）并在换集/换内核/退出时更新或收掉；
 * 2. **让路**（[setActive]）：R19 要求"检测到前台正在播放时 ASR 自动让路"，
 *    视频页进入播放状态时置 true、暂停/退出/切集时置 false
 *    （:app 把它与音频播放状态合并后喂给 PlaybackYieldGate）。
 *
 * 线程约定：两个方法都在主线程调用，实现不得做阻塞 IO（起服务只发 Intent）。
 */
/** 通知栏/锁屏会打到播放页上的两个动作。 */
interface VideoSessionActionSink {

    /** 上一集（队列里往前一个）。 */
    fun onPrevious()

    /** 下一集（队列里往后一个）。 */
    fun onNext()
}

interface VideoPlaybackHost {

    /**
     * 视频是否正在前台播放（R19 让路闸门）。
     *
     * 口径：**真的在播**为 true；暂停、播完、加载失败、退出页面都为 false。
     */
    fun setActive(active: Boolean)

    /**
     * 绑定/更新当前视频会话（R18）；传 null 表示**收掉会话**（换集前的空档、释放内核、退出播放页）。
     *
     * 实现应当：非 null 时确保视频会话服务已起（通知栏可控），null 时停掉它并释放唤醒锁。
     */
    fun bindSession(session: VideoSession?)

    /**
     * **通知栏/锁屏「上一集 / 下一集」的落点**（2026-10-03 用户要求：「始终在状态栏中可以控制
     * 上一个下一个，暂停/播放」）。
     *
     * 页面在挂载时注册、退出时传 null 注销；命令从服务 → 宿主 → 这里 → 页面的队列逻辑
     * （因此"下一集"遵守排序与循环规则，和页面上点按钮完全一致）。默认什么都不做。
     */
    fun setSessionActionSink(sink: VideoSessionActionSink?) = Unit
}

/**
 * 没有宿主时的空实现（纯 JVM 单测 / 非 :app 环境）：不建会话、不参与让路。
 */
object NoopVideoPlaybackHost : VideoPlaybackHost {

    override fun setActive(active: Boolean) = Unit

    override fun bindSession(session: VideoSession?) = Unit
}
