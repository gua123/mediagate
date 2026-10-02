package io.github.gua123.mediagate.feature.player.video

/**
 * 播放页竖直手势的换算（**2026-10-03 用户要求**：「第一播放视频时左侧上下滑动为调整亮度…第二播放视频时
 * 右侧上下为调整音量，同时增加音量上限为 200%」）。
 *
 * 三条口径：
 * - **左 1/3 屏调亮度、右 1/3 屏调音量、中间留给横滑调进度**（横竖手势不打架，各占各的区域）；
 * - **整屏高度 = 全量程**：从底部滑到顶部正好走完 0..上限，手指不用挪好几次；
 * - 全部是纯函数：边界（越界、宽度为 0、NaN）都能用单测钉住，界面只负责把结果画出来。
 */
enum class PlayerGestureZone {

    /** 左侧：亮度。 */
    BRIGHTNESS,

    /** 右侧：音量。 */
    VOLUME,

    /** 中间：进度（沿用原来的横滑手势）。 */
    SEEK,
}

object PlayerGestureMath {

    /**
     * 依据**按下点**的横坐标判定生效区。
     *
     * 为什么按"按下点"而不是"当前点"：拖动过程中手指可以横着跑，如果跟着当前点换区，
     * 一次滑动就会一会儿调亮度一会儿调音量。
     */
    fun zoneOf(x: Float, width: Int): PlayerGestureZone {
        if (width <= 0) return PlayerGestureZone.SEEK
        // 用**整数边界**而不是浮点比例：`600f/900f > 1f - 1f/3f` 这种比较会被浮点表示坑到，
        // 判定必须落在像素边界上（单测就是这么抓到的）。
        val third = width / 3
        return when {
            x < third -> PlayerGestureZone.BRIGHTNESS
            x >= width - third -> PlayerGestureZone.VOLUME
            else -> PlayerGestureZone.SEEK
        }
    }

    /**
     * 竖直拖动 → 新值。
     *
     * **向上拖 = 变大**（屏幕上 y 向下增长，所以要取负号）；整屏高度对应整个量程。
     *
     * @param start 按下时的值。
     * @param deltaPx 累计竖直位移（正数向下）。
     * @param height 手势区高度（像素）；<=0 时原样返回 [start]。
     */
    fun applyVerticalDrag(start: Float, deltaPx: Float, height: Int, min: Float, max: Float): Float {
        if (height <= 0) return start.coerceIn(min, max)
        if (deltaPx.isNaN()) return start.coerceIn(min, max)
        val span = max - min
        val next = start - deltaPx / height.toFloat() * span
        return next.coerceIn(min, max)
    }

    /** HUD 上的一行字：亮度/音量都用百分比，音量可以超过 100%。 */
    fun label(zone: PlayerGestureZone, value: Float): String = when (zone) {
        PlayerGestureZone.BRIGHTNESS -> "亮度 " + percent(value, 1f) + "%"
        PlayerGestureZone.VOLUME -> "音量 " + percent(value, 1f) + "%"
        PlayerGestureZone.SEEK -> "进度"
    }

    /** 值 → 百分比整数（分母为量程上限）。 */
    private fun percent(value: Float, max: Float): Int =
        (value / max * 100f).coerceIn(0f, 999f).toInt()
}
