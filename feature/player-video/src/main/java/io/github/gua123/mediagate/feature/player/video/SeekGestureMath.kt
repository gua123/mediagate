package io.github.gua123.mediagate.feature.player.video

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 横滑调进度的换算（**纯函数**，2026-10-03 用户要求：「不弹出控制也能左右滑动调整进度条」）。
 *
 * 口径：
 * - 整屏宽横滑 ≈ ±[FULL_WIDTH_SPAN_MS]；短视频按"片子本身的一半"封顶，免得一划就跳到头；
 * - 起点是**按下时的播放位置**（不是 0），划多少算多少，松开才真正 seek；
 * - 结果夹在 0..duration 内。
 */
object SeekGestureMath {

    /** 整屏宽对应的最大跨度（毫秒）。3 分钟对大多数片子手感合适。 */
    const val FULL_WIDTH_SPAN_MS: Long = 3 * 60 * 1_000L

    /** 跨度下限：再短的片子也别做成"一划一大截"。 */
    private const val MIN_SPAN_MS: Long = 10 * 1_000L

    /** 这条视频"整屏宽"对应多少毫秒。 */
    fun spanFor(durationMs: Long): Long {
        if (durationMs <= 0L) return FULL_WIDTH_SPAN_MS
        return min(FULL_WIDTH_SPAN_MS, max(durationMs / 2, MIN_SPAN_MS))
    }

    /**
     * 按下位置 + 横向位移比例 → 目标位置。
     *
     * @param startMs 按下时的播放位置。
     * @param dxFraction 横向位移占画面宽度的比例（右为正）。
     * @param durationMs 总时长；<=0 时原样返回 [startMs]（不知道往哪划）。
     */
    fun targetMs(startMs: Long, dxFraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return startMs
        val delta = (spanFor(durationMs) * dxFraction).roundToLong()
        return (startMs + delta).coerceIn(0L, durationMs)
    }

    /** 位移提示文案（+01:23 / -00:45），拖拽时显示在 HUD 上。 */
    fun deltaLabel(deltaMs: Long): String {
        val sign = if (deltaMs < 0) "-" else "+"
        val total = abs(deltaMs) / 1000
        return sign + "%02d:%02d".format(total / 60, total % 60)
    }
}
