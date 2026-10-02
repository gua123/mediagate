package io.github.gua123.mediagate.feature.player.video

import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.MediaKindGuesser
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.ResizeMode
import java.util.Locale

/**
 * 视频播放页的纯逻辑（R4 拖拽 / R9 内核切换 / R10 解码档位 / R16 中文口径）。
 *
 * 全部是**无状态纯函数**：不碰 Android、不碰 IO、不认识播放器实例，因此可以逐条 JVM 单测。
 * 页面上「点一下会发生什么」（倍速轮转、解码档位轮转、上下集边界、进度换算、同目录队列）
 * 都只在这里定义一次，组合函数与 ViewModel 只调用它们。
 */
object VideoPlayerMath {

    /** 未知时长的占位文案。 */
    const val UNKNOWN_TIME = "--:--"

    /** 倍速档位（0.5 / 1 / 1.5 / 2，与音频页同一口径）。 */
    val SPEEDS: List<Float> = listOf(0.5f, 1f, 1.5f, 2f)

    /** 默认倍速。 */
    const val DEFAULT_SPEED = 1f

    /** 解码档位轮转顺序（R10）：自动（硬解优先）→ 强制软解 → 强制硬解 → 自动。 */
    val DECODER_MODES: List<DecoderMode> = listOf(
        DecoderMode.AUTO_HW,
        DecoderMode.FORCE_SW,
        DecoderMode.FORCE_HW,
    )

    /** 画面缩放档位轮转顺序。 */
    val RESIZE_MODES: List<ResizeMode> = listOf(
        ResizeMode.FIT,
        ResizeMode.CROP,
        ResizeMode.STRETCH,
        ResizeMode.ORIGINAL,
    )

    /**
     * 毫秒 → 时长文案。
     *
     * 规则：负数（未知时长）给 [UNKNOWN_TIME]；不足 1 小时给 m:ss；1 小时以上给 h:mm:ss。
     */
    fun formatDuration(millis: Long): String {
        if (millis < 0) return UNKNOWN_TIME
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * 播放位置 → 进度条比例（0..1）。
     *
     * 时长未知（<= 0）时给 0：进度条停在最左边并禁用，而不是乱跳（远端起播阶段常见）。
     */
    fun seekRatio(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** 进度条比例 → 目标播放位置（毫秒）；时长未知时给 0（R4 拖拽的换算口径）。 */
    fun positionOfRatio(ratio: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (ratio.coerceIn(0f, 1f) * durationMs.toFloat()).toLong().coerceIn(0L, durationMs)
    }

    /** 下一档倍速（0.5 → 1 → 1.5 → 2 → 0.5）；当前值不在档位里时回到默认 1 倍。 */
    fun nextSpeed(current: Float): Float {
        val index = SPEEDS.indexOfFirst { it == current }
        if (index < 0) return DEFAULT_SPEED
        return SPEEDS[(index + 1) % SPEEDS.size]
    }

    /** 倍速文案：1× / 0.5× / 1.5× / 2×（整数不带小数点）。 */
    fun speedLabel(speed: Float): String {
        val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
        return text + "×"
    }

    /** 下一档解码模式（R10；点一次换一档）。 */
    fun nextDecoderMode(current: DecoderMode): DecoderMode = nextIn(DECODER_MODES, current)

    /** 下一档缩放模式。 */
    fun nextResizeMode(current: ResizeMode): ResizeMode = nextIn(RESIZE_MODES, current)

    /**
     * 目录项 → 上下集队列（R1）：只留视频与音频（播放页能放的都算一集），
     * 剔除目录、图片、字幕与其它文件，并按名称大小写不敏感排序。
     *
     * ⚠️ 这里的名称排序只是**兜底**：真正的播放顺序由调用方（:app）在过滤之后再按
     * **用户选定的排序设置**排一次（2026-10-03 用户要求「播放时的列表也需要按照新的排序」）。
     * 改这里时别以为顺序就定死了——两处都排，最终以调用方那次为准。
     */
    fun playableEntries(entries: List<RemoteEntry>): List<RemoteEntry> = entries
        .filterNot { it.isDirectory }
        .filter {
            val kind = MediaKindGuesser.guess(it.name)
            kind == MediaKind.VIDEO || kind == MediaKind.AUDIO
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /** 在路径列表里找下标；找不到返回 -1。 */
    fun indexOfPath(paths: List<String>, path: String): Int = paths.indexOf(path)

    /** 能否切下一集（最后一集 / 空队列都不行）。 */
    fun canGoNext(index: Int, count: Int): Boolean = count > 0 && index in 0 until count - 1

    /** 能否切上一集。 */
    fun canGoPrevious(index: Int): Boolean = index > 0

    /** 下一集下标；已在最后一集返回 null（页面据此不动，按钮置灰）。 */
    fun nextIndex(index: Int, count: Int): Int? = if (canGoNext(index, count)) index + 1 else null

    /** 上一集下标；已在第一集返回 null。 */
    fun previousIndex(index: Int): Int? = if (canGoPrevious(index)) index - 1 else null

    /** 路径 → 展示名（最后一段）。 */
    fun fileNameOf(path: String): String = path.substringAfterLast('/')

    /**
     * 时间戳容易坏的容器（TS 家族）：只有这些才给「修复时间戳」入口（R3/R11）。
     *
     * 判据是扩展名而不是内容——内容探测要读文件，而入口必须在拉起播放页时就决定显不显示。
     */
    private val TIMESTAMP_FRAGILE_EXTENSIONS: Set<String> = setOf("ts", "m2ts", "mts", "tp", "trp")

    /** 这条路径是否值得提供「修复时间戳」。 */
    fun isTimestampRepairable(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in TIMESTAMP_FRAGILE_EXTENSIONS

    /** 列表轮转：命中就取下一项，未命中回到第一项。 */
    private fun <T> nextIn(values: List<T>, current: T): T {
        val index = values.indexOf(current)
        if (index < 0) return values.first()
        return values[(index + 1) % values.size]
    }
}
