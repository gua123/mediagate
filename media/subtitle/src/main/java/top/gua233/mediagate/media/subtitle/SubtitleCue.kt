package io.github.gua123.mediagate.media.subtitle

/**
 * ASS/SSA 的对齐方式（R14：ASS 只做「基础样式 + 位置提示」的降级解析）。
 *
 * 值取自 ASS 规范的 Alignment 字段（1–9，小键盘布局）；[UNKNOWN] 表示脚本没写或写坏了。
 * 播放页的覆盖层据此决定字幕贴顶 / 居中 / 贴底（默认贴底）。
 */
enum class SubtitleAlignment(val assValue: Int) {

    /** 脚本没给对齐（按贴底居中处理）。 */
    UNKNOWN(0),
    BOTTOM_LEFT(1),
    BOTTOM_CENTER(2),
    BOTTOM_RIGHT(3),
    MIDDLE_LEFT(4),
    MIDDLE_CENTER(5),
    MIDDLE_RIGHT(6),
    TOP_LEFT(7),
    TOP_CENTER(8),
    TOP_RIGHT(9),
    ;

    /** 是否贴顶（覆盖层置顶显示）。 */
    val isTop: Boolean get() = this == TOP_LEFT || this == TOP_CENTER || this == TOP_RIGHT

    /** 是否垂直居中。 */
    val isMiddle: Boolean get() = this == MIDDLE_LEFT || this == MIDDLE_CENTER || this == MIDDLE_RIGHT

    companion object {

        /** ASS Alignment 数字 → 对齐方式；越界（含 0 / 负数 / >9）给 [UNKNOWN]。 */
        fun ofAss(value: Int): SubtitleAlignment = entries.firstOrNull { it.assValue == value } ?: UNKNOWN
    }
}

/**
 * 单条字幕自带的样式信息（目前只有 ASS/SSA 的降级解析会产出，R14）。
 *
 * 这只是**脚本作者写的样式提示**，不是用户的显示设置（用户设置见 [SubtitleStyle]）：
 * 播放页可以按它决定位置（贴顶/居中）与基础字形，但字号、颜色等最终以用户设置为准。
 *
 * @property alignment 位置提示（ASS Alignment）。
 * @property fontName 脚本声明的字体名（仅展示/参考）。
 * @property fontSize 脚本声明的字号（ASS 坐标系下的值，**不是** sp）。
 * @property primaryColorArgb 脚本声明的主色（已转成 Android ARGB）。
 * @property bold 是否加粗。
 * @property italic 是否斜体。
 */
data class SubtitleCueStyle(
    val alignment: SubtitleAlignment = SubtitleAlignment.UNKNOWN,
    val fontName: String? = null,
    val fontSize: Float? = null,
    val primaryColorArgb: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/**
 * 统一的字幕条目（R14：srt / vtt / ass / ssa 解析后都是这个形状）。
 *
 * 时间轴一律用**毫秒**（[SubtitleTimeline] 的 ±0.5 s 微调、Media3 / LibVLC 的时间单位
 * 都在这一层换算好），文本一律是**纯文本**（标签、ASS 覆盖指令、HTML 实体都已清洗）。
 *
 * @property startMs 起始时间（毫秒，含）。
 * @property endMs 结束时间（毫秒，不含）。
 * @property text 纯文本内容；多行用 \n 分隔。
 * @property style 脚本自带样式提示（SRT/VTT 解析时为 null）。
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val style: SubtitleCueStyle? = null,
) {

    /** 持续时长（毫秒）；倒挂的时间轴给 0。 */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** 是否是「能显示」的条目：时间轴正向且文本非空。 */
    val isValid: Boolean get() = endMs > startMs && text.isNotBlank()

    /** [positionMs] 是否落在本条目的时间区间内（左闭右开）。 */
    fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs

    /**
     * 时间轴平移（R14 ±0.5 s 微调的基础操作）。
     *
     * 钳制规则：**负值不早于 0**。整体被推到 0 之前（end 也 <= 0）的条目由
     * [SubtitleTimeline.shift] 负责丢弃，单条平移本身只做钳制。
     */
    fun shifted(offsetMs: Long): SubtitleCue {
        if (offsetMs == 0L) return this
        val start = (startMs + offsetMs).coerceAtLeast(0L)
        val end = (endMs + offsetMs).coerceAtLeast(start)
        return copy(startMs = start, endMs = end)
    }
}
