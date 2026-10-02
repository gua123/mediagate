package io.github.gua123.mediagate.feature.player.audio

import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.MediaKindGuesser
import io.github.gua123.mediagate.core.model.RemoteEntry
import java.util.Locale

/**
 * 音频播放页的纯逻辑（R1 音频 / R18）：进度格式化、seek 比例换算、队列下标轮转、倍速档位。
 *
 * 全部是**无状态纯函数**，不碰 Android、不碰 IO，可直接 JVM 单测——
 * 页面上那些「点一下会发生什么」的判断都从这里出，避免散落在组合函数里。
 */
object AudioPlayerMath {

    /** 未知时长的占位文案（与 R16 中文界面一致，数字本身与语言无关）。 */
    const val UNKNOWN_TIME = "--:--"

    /** 倍速档位（R1 音频页的 0.5 / 1 / 1.5 / 2 四档）。 */
    val SPEEDS: List<Float> = listOf(0.5f, 1f, 1.5f, 2f)

    /** 默认倍速。 */
    const val DEFAULT_SPEED = 1f

    /** 封面加载的目标宽度（像素）：音频封面在播放页是小图，没必要按原图解。 */
    const val COVER_TARGET_WIDTH = 512

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
     * 时长未知（<= 0）时给 0：进度条停在最左边且禁用，而不是乱跳。
     */
    fun seekRatio(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** 进度条比例 → 目标播放位置（毫秒）；时长未知时给 0。 */
    fun positionOfRatio(ratio: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (ratio.coerceIn(0f, 1f) * durationMs.toFloat()).toLong().coerceIn(0L, durationMs)
    }

    /**
     * 下一首的下标。
     *
     * [AudioRepeatMode.ALL] 时从最后一首回到第一首；[AudioRepeatMode.OFF] / [AudioRepeatMode.ONE]
     * 时停在最后一首（[AudioRepeatMode.ONE] 只影响自动重播，手动切歌仍按顺序，见枚举注释）。
     * 队列为空返回 0；下标越界先钳进合法范围再算。
     */
    fun nextIndex(index: Int, size: Int, repeatMode: AudioRepeatMode): Int {
        if (size <= 0) return 0
        val current = index.coerceIn(0, size - 1)
        return when (repeatMode) {
            AudioRepeatMode.ALL -> (current + 1) % size
            AudioRepeatMode.OFF, AudioRepeatMode.ONE -> (current + 1).coerceAtMost(size - 1)
        }
    }

    /** 上一首的下标；[AudioRepeatMode.ALL] 时从第一首绕回最后一首，否则停在第一首。 */
    fun previousIndex(index: Int, size: Int, repeatMode: AudioRepeatMode): Int {
        if (size <= 0) return 0
        val current = index.coerceIn(0, size - 1)
        return when (repeatMode) {
            AudioRepeatMode.ALL -> (current - 1 + size) % size
            AudioRepeatMode.OFF, AudioRepeatMode.ONE -> (current - 1).coerceAtLeast(0)
        }
    }

    /** 循环模式轮转：顺序 → 列表循环 → 单曲循环 → 顺序（按一次换一档）。 */
    fun nextRepeatMode(mode: AudioRepeatMode): AudioRepeatMode = when (mode) {
        AudioRepeatMode.OFF -> AudioRepeatMode.ALL
        AudioRepeatMode.ALL -> AudioRepeatMode.ONE
        AudioRepeatMode.ONE -> AudioRepeatMode.OFF
    }

    /** 下一档倍速（0.5 → 1 → 1.5 → 2 → 0.5）；当前值不在档位里时回到默认 1×。 */
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

    /**
     * 目录项 → 音频队列（R1）：只留音频文件，按名称（大小写不敏感）排序。
     *
     * ⚠️ 名称排序只是**兜底**：调用方（:app）会再按**用户选定的排序设置**排一次
     * （2026-10-03 用户要求「播放时的列表也需要按照新的排序」）。
     */
    fun audioEntries(entries: List<RemoteEntry>): List<RemoteEntry> = entries
        .filterNot { it.isDirectory }
        .filter { MediaKindGuesser.guess(it.name) == MediaKind.AUDIO }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /** 路径在列表里的下标；找不到返回 -1。 */
    fun indexOfPath(paths: List<String>, path: String): Int = paths.indexOf(path)

    /** 路径 → 展示名（取最后一段）。 */
    fun fileNameOf(path: String): String = path.substringAfterLast('/')
}
