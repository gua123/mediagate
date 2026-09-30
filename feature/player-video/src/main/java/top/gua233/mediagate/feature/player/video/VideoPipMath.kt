package io.github.gua123.mediagate.feature.player.video

import kotlin.math.abs

/**
 * 画中画的纯逻辑（**R13**）：宽高比档位、±10 秒跳转、自动进 PIP 的判定。
 *
 * 全部是纯函数（不碰 Android、不碰内核），因此可以在 JVM 单测里逐条覆盖；
 * 页面与 :app 的宿主实现共用同一份判定，不会出现"界面以为能进、宿主算出来是别的比例"。
 */
object VideoPipMath {

    /** 横屏 16:9（绝大多数视频；也是未知尺寸时的兜底档）。 */
    const val RATIO_16_9: Float = 16f / 9f

    /** 横屏 4:3（老片 / 部分直播源）。 */
    const val RATIO_4_3: Float = 4f / 3f

    /** 竖屏 9:16（手机拍摄的竖屏视频）。 */
    const val RATIO_9_16: Float = 9f / 16f

    /** 默认档位：16:9（尺寸未知时用它，PIP 窗口不至于变形得离谱）。 */
    const val DEFAULT_ASPECT_RATIO: Float = RATIO_16_9

    /**
     * Android 允许的 PIP 宽高比范围（0.4184 ~ 2.39，见 `PictureInPictureParams` 文档）。
     *
     * 超出会被系统钳住（或直接忽略整份参数），所以这里主动夹一次。
     */
    const val MIN_ASPECT_RATIO: Float = 0.4184f
    const val MAX_ASPECT_RATIO: Float = 2.39f

    /** PIP 内快退/快进的步长（R13：10 秒）。 */
    const val SEEK_STEP_MS: Long = 10_000L

    /** 判定"接近某个档位"的相对容差：±8%（16:9 与 4:3 之间留足余量，不会互相抢）。 */
    private const val RATIO_TOLERANCE = 0.08f

    /**
     * 把任意宽高比夹进系统允许的范围；非有限值（NaN / Infinity / 0）退化为 [DEFAULT_ASPECT_RATIO]。
     */
    fun clampAspectRatio(ratio: Float): Float = when {
        !ratio.isFinite() || ratio <= 0f -> DEFAULT_ASPECT_RATIO
        ratio < MIN_ASPECT_RATIO -> MIN_ASPECT_RATIO
        ratio > MAX_ASPECT_RATIO -> MAX_ASPECT_RATIO
        else -> ratio
    }

    /**
     * 视频像素尺寸 → 宽高比；尺寸未知（<= 0）时返回 null（调用方用 [DEFAULT_ASPECT_RATIO]）。
     */
    fun aspectRatioOf(width: Int, height: Int): Float? {
        if (width <= 0 || height <= 0) return null
        return clampAspectRatio(width.toFloat() / height.toFloat())
    }

    /**
     * 宽高比 → 三档之一（R13：16:9 / 4:3 / 竖屏各一档）。
     *
     * 先看竖屏（比例 < 1），再在横屏里比 4:3 与 16:9；都不像就用 [DEFAULT_ASPECT_RATIO]。
     * 这样"21:9 的宽银幕"会落到 16:9（比强行算一个系统不认的比例更稳）。
     */
    fun snapAspectRatio(ratio: Float): Float {
        val safe = clampAspectRatio(ratio)
        val candidates = listOf(RATIO_16_9, RATIO_4_3, RATIO_9_16)
        val best = candidates.minByOrNull { abs(it - safe) } ?: DEFAULT_ASPECT_RATIO
        val relative = abs(best - safe) / best
        return if (relative <= RATIO_TOLERANCE) best else DEFAULT_ASPECT_RATIO
    }

    /** 视频像素尺寸 → 三档比例（尺寸未知给 16:9）。 */
    fun snapForSize(width: Int, height: Int): Float =
        aspectRatioOf(width, height)?.let { snapAspectRatio(it) } ?: DEFAULT_ASPECT_RATIO

    /**
     * 三档比例 → 最简整数比（给 `PictureInPictureParams.setAspectRatio(Rational)` 用）。
     *
     * 系统只认整数比，所以不能直接塞 Float；不在三档里的值一律落回 16:9。
     */
    fun ratioFraction(ratio: Float): Pair<Int, Int> = when (snapAspectRatio(ratio)) {
        RATIO_4_3 -> 4 to 3
        RATIO_9_16 -> 9 to 16
        else -> 16 to 9
    }

    /**
     * 相对跳转的目标位置：当前位置 + [deltaMs]，钳在 `0..duration`（时长未知时只钳下界）。
     *
     * R13 的 PIP 快退/快进 10 秒走它——快退到负数要落到 0，快进不能越过片尾。
     */
    fun seekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val raw = positionMs.coerceAtLeast(0L) + deltaMs
        if (raw <= 0L) return 0L
        return if (durationMs > 0L) raw.coerceAtMost(durationMs) else raw
    }

    /** 快退 10 秒的目标位置（R13）。 */
    fun rewindTarget(positionMs: Long, durationMs: Long): Long =
        seekTarget(positionMs, -SEEK_STEP_MS, durationMs)

    /** 快进 10 秒的目标位置（R13）。 */
    fun forwardTarget(positionMs: Long, durationMs: Long): Long =
        seekTarget(positionMs, SEEK_STEP_MS, durationMs)

    /**
     * 是否允许"按 Home 自动进 PIP"（R13）。
     *
     * 条件：真的在播 + 已经有画面（READY）且没在切换内核/解码器（R9/R10 切换期间画面会重建，
     * 这时缩进小窗只会看到黑屏）+ 没播完。
     */
    fun autoEnterEnabled(state: VideoPlayerUiState): Boolean =
        state.playing &&
            state.status == VideoPlayerStatus.READY &&
            !state.switching &&
            !state.ended

    /**
     * 通知/PIP 里的动作按钮是否显示"暂停"（即当前在播）。
     *
     * 与 [autoEnterEnabled] 分开：暂停时动作按钮仍要能用（PIP 里点一下继续播），
     * 只是不允许"自动进入"而已——两条口径不同，混在一起会让暂停后 Home 直接缩窗。
     */
    fun showsPauseAction(state: VideoPlayerUiState): Boolean = state.playing
}
