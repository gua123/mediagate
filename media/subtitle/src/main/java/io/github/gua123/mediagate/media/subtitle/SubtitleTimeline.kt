package io.github.gua123.mediagate.media.subtitle

import java.util.Locale

/**
 * 字幕时间轴微调（R14：±0.5 s 步进、任意毫秒偏移、边界钳制）。
 *
 * 全部是**纯函数**：不碰播放器、不碰 IO，因此「点一下 +0.5 秒之后 cue 落在哪」可以在
 * JVM 单测里逐条覆盖。播放页只负责把结果交给覆盖层渲染。
 *
 * 与内核的关系：Media3 1.11 没有字幕延迟 API，LibVLC 有 setSpuDelay；为了让两个内核表现一致，
 * **偏移一律在渲染层应用**（[shift] 平移 cue 列表），[MIN_OFFSET_MS] / [MAX_OFFSET_MS]
 * 与 :media:engine 的 SubtitleState 边界保持一致（±60 秒）。
 */
object SubtitleTimeline {

    /** 步进档位：±0.5 秒（R14）。 */
    const val STEP_MS = 500L

    /** 偏移下限：字幕最多提前 60 秒。 */
    const val MIN_OFFSET_MS = -60_000L

    /** 偏移上限：字幕最多延后 60 秒。 */
    const val MAX_OFFSET_MS = 60_000L

    /** 把偏移钳到 [MIN_OFFSET_MS]..[MAX_OFFSET_MS]（越界不报错，直接夹住）。 */
    fun clampOffset(offsetMs: Long): Long = offsetMs.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)

    /** 按步进调整偏移：[steps] 为正 = 字幕延后（+0.5 秒/步），为负 = 提前。 */
    fun step(offsetMs: Long, steps: Int, stepMs: Long = STEP_MS): Long =
        clampOffset(offsetMs + steps.toLong() * stepMs)

    /** 该偏移的秒数文案（如 +0.5 / -1.5 / 0.0）；单位与中文提示由界面拼（R16）。 */
    fun formatOffsetSeconds(offsetMs: Long): String =
        String.format(Locale.US, "%+.1f", offsetMs / 1000.0)

    /**
     * 平移整份 cue 列表（R14 微调的核心操作）。
     *
     * 规则：
     * - **负值不早于 0**：平移后起点为负的条目被钳到 0；
     * - 整体被推到 0 之前（结束时间也 <= 0）的条目**丢弃**——它已经不可能被显示；
     * - 结果按起始时间升序排序（平移可能改变重叠关系，排序保证 [activeCue] 语义稳定）。
     *
     * @param offsetMs 偏移毫秒；正数 = 字幕延后。
     */
    fun shift(cues: List<SubtitleCue>, offsetMs: Long): List<SubtitleCue> {
        if (offsetMs == 0L) return cues
        val moved = ArrayList<SubtitleCue>(cues.size)
        for (cue in cues) {
            val shifted = cue.shifted(offsetMs)
            if (shifted.endMs <= 0L) continue
            moved += shifted
        }
        return moved.sortedBy { it.startMs }
    }

    /**
     * 当前播放位置命中的 cue（左闭右开）；没有命中返回 null。
     *
     * 时间轴重叠时取**最后一条**（起始时间更晚的那条），与「后写的字幕覆盖前面的」直觉一致。
     * 列表不要求有序：这里做一次线性扫描（字幕条目量级很小，渲染层还会缓存平移结果）。
     */
    fun activeCue(cues: List<SubtitleCue>, positionMs: Long): SubtitleCue? {
        var hit: SubtitleCue? = null
        for (cue in cues) {
            if (cue.contains(positionMs)) hit = cue
        }
        return hit
    }
}
