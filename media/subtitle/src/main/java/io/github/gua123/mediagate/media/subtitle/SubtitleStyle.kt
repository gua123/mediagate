package io.github.gua123.mediagate.media.subtitle

/**
 * 字幕显示样式（R14：字号 / 颜色 / 描边 / 底部边距 / 加粗 / 斜体）。
 *
 * 这是**用户设置**（持久化在播放页偏好里），与字幕文件自带的 [SubtitleCueStyle] 分开：
 * 文件里写的样式只作提示（位置/字形），最终以用户设置为准。
 *
 * 字段覆盖两层需要：
 * - Media3 CaptionStyleCompat 能表达的部分（前景色、描边色/宽度、加粗斜体）；
 * - 我们自己覆盖层需要的部分（sp 字号、底部边距）。
 *
 * 越界值一律用 [clamped] 夹住（NaN 回默认），构造时不做校验——UI 拖动滑块的过程中
 * 会短暂出现中间值，夹取放在使用点更合适。
 *
 * @property fontSizeSp 字号（sp）。
 * @property textColorArgb 文字颜色（ARGB）。
 * @property outlineWidthDp 描边宽度（dp）；0 = 不描边。
 * @property outlineColorArgb 描边颜色（ARGB）。
 * @property bottomMarginDp 距画面底部的边距（dp）。
 * @property bold 是否加粗。
 * @property italic 是否斜体。
 */
data class SubtitleStyle(
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
    val textColorArgb: Int = DEFAULT_TEXT_COLOR,
    val outlineWidthDp: Float = DEFAULT_OUTLINE_WIDTH_DP,
    val outlineColorArgb: Int = DEFAULT_OUTLINE_COLOR,
    val bottomMarginDp: Float = DEFAULT_BOTTOM_MARGIN_DP,
    val bold: Boolean = false,
    val italic: Boolean = false,
) {

    /** 是否所有数值字段都在合法区间内（UI 可据此禁用/提示）。 */
    val isValid: Boolean get() = this == clamped()

    /** 越界钳制：字号 / 描边 / 边距夹到区间，NaN 回默认值。 */
    fun clamped(): SubtitleStyle = copy(
        fontSizeSp = clamp(fontSizeSp, MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP),
        outlineWidthDp = clamp(outlineWidthDp, MIN_OUTLINE_WIDTH_DP, MAX_OUTLINE_WIDTH_DP, DEFAULT_OUTLINE_WIDTH_DP),
        bottomMarginDp = clamp(bottomMarginDp, MIN_BOTTOM_MARGIN_DP, MAX_BOTTOM_MARGIN_DP, DEFAULT_BOTTOM_MARGIN_DP),
    )

    companion object {

        /** 默认字号（sp）。 */
        const val DEFAULT_FONT_SIZE_SP = 18f

        /** 最小字号（sp）。 */
        const val MIN_FONT_SIZE_SP = 10f

        /** 最大字号（sp）。 */
        const val MAX_FONT_SIZE_SP = 48f

        /** 默认描边宽度（dp）：细描边保证白字在任何画面上都可读。 */
        const val DEFAULT_OUTLINE_WIDTH_DP = 1.5f

        /** 最小描边（0 = 不描边）。 */
        const val MIN_OUTLINE_WIDTH_DP = 0f

        /** 最大描边（dp）。 */
        const val MAX_OUTLINE_WIDTH_DP = 6f

        /** 默认底部边距（dp）。 */
        const val DEFAULT_BOTTOM_MARGIN_DP = 24f

        /** 最小底部边距（dp）。 */
        const val MIN_BOTTOM_MARGIN_DP = 0f

        /** 最大底部边距（dp）；再大就顶到画面中间了。 */
        const val MAX_BOTTOM_MARGIN_DP = 240f

        /** 默认文字颜色（不透明白）。 */
        const val DEFAULT_TEXT_COLOR = 0xFFFFFFFF.toInt()

        /** 默认描边颜色（不透明黑）。 */
        const val DEFAULT_OUTLINE_COLOR = 0xFF000000.toInt()

        /** 默认样式。 */
        val DEFAULT = SubtitleStyle()

        /** 文字色板（白 / 黄 / 青 / 绿 / 品红）；R14 要求颜色可调。 */
        val TEXT_COLORS: List<Int> = listOf(
            0xFFFFFFFF.toInt(),
            0xFFFFEB3B.toInt(),
            0xFF80DEEA.toInt(),
            0xFFA5D6A7.toInt(),
            0xFFF48FB1.toInt(),
        )

        /** 描边色板（黑 / 深灰 / 深蓝）。 */
        val OUTLINE_COLORS: List<Int> = listOf(
            0xFF000000.toInt(),
            0xFF424242.toInt(),
            0xFF1A237E.toInt(),
        )

        private fun clamp(value: Float, min: Float, max: Float, fallback: Float): Float =
            if (value.isNaN()) fallback else value.coerceIn(min, max)
    }
}
