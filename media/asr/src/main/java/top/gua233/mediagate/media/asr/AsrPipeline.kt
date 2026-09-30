package io.github.gua123.mediagate.media.asr

import io.github.gua123.mediagate.media.subtitle.SubtitleCue
import kotlin.math.min

/**
 * 一个识别窗口（**M7-B / R14**，plan 4.7 B 的「30 s 窗口 + 5 s 重叠」）。
 *
 * @property index 窗口序号（从 0 开始，时间顺序）。
 * @property startMs 窗口在整段音轨里的起始毫秒。
 * @property endMs 结束毫秒（不含）。
 * @property sampleOffset 起始采样序号（16 kHz 下 = startMs × 16）。
 * @property sampleCount 采样点数。
 */
data class AsrWindow(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val sampleOffset: Long,
    val sampleCount: Int,
) {
    /** 窗口时长（毫秒）。 */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** 时长（秒，便于日志）。 */
    val durationSeconds: Int get() = (durationMs / 1000L).toInt()
}

/**
 * 已识别的一段字幕（**绝对时间**，毫秒）。
 *
 * @property startMs 相对整段音轨的起点（含）。
 * @property endMs 终点（不含）。
 * @property text 识别文本（可能带 whisper 的前导空格，比较前请先规范化）。
 */
data class AsrSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    /** 时长（毫秒）；倒挂给 0。 */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * 一个窗口的识别结果（**窗口内相对时间**，与 [WhisperSegment] 一致）。
 *
 * 单独一个类型是为了让「窗口规划」与「时间轴合并」在测试里可以各自构造：
 * 合并逻辑只认 [window] 与 [segments]，不需要跑真的模型。
 */
data class RecognizedWindow(
    val window: AsrWindow,
    val segments: List<WhisperSegment>,
)

/**
 * 进度快照（**M7-B / R14 / R19 的总进度 + 逐项进度**）。
 *
 * @property recognizedMs 已识别时长（毫秒）。
 * @property totalMs 总时长（毫秒）；<= 0 表示未知。
 */
data class AsrProgress(
    val recognizedMs: Long,
    val totalMs: Long,
) {
    /** 0..1；**总时长未知时给 0**（不做假进度）。 */
    val fraction: Float
        get() = if (totalMs <= 0L) 0f else (recognizedMs.toDouble() / totalMs.toDouble()).coerceIn(0.0, 1.0).toFloat()

    /** 0..100 的整数百分比（通知栏直接用）。 */
    val percent: Int get() = (fraction * 100f).toInt().coerceIn(0, 100)

    /** 是否已经走完。 */
    val isComplete: Boolean get() = totalMs > 0L && recognizedMs >= totalMs

    companion object {
        /** 空进度（排队中）。 */
        val EMPTY: AsrProgress = AsrProgress(0L, 0L)
    }
}

/**
 * 字幕切分参数（**超长段切分**的口径）。
 *
 * @property maxCharsPerCue 单条字幕最多字符数（默认 42 = 两行 × 21 个汉字）。
 * @property maxDurationMs 单条字幕最长毫秒（默认 7 s，再长就该切）。
 * @property minDurationMs 时间戳倒挂时的兜底时长（默认 200 ms；宁可给一小段也别丢文本）。
 */
data class AsrCueOptions(
    val maxCharsPerCue: Int = 42,
    val maxDurationMs: Long = 7_000L,
    val minDurationMs: Long = 200L,
)

/**
 * 音转字幕的核心纯逻辑（**M7-B**：R14 分段识别 / R19 逐项进度）。
 *
 * 拆成四个**互不依赖、都能在 JVM 上穷举**的函数，真机上只剩「读 PCM → 调 JNI → 把它们串起来」：
 * 1. [planWindows]：总时长 → 窗口列表（含极短视频、超长视频、非整除时长）；
 * 2. [mergeWindows]：多个窗口的**窗口内相对**结果 → 整段音轨的**绝对**时间轴（重叠区裁剪 + 内容去重）；
 * 3. [toCues]：段数组 → SubtitleCue（去空段、单调不重叠、超长段按字符与时长切分）；
 * 4. [progressOf]：已识别时长 / 总时长 → 进度。
 *
 * 取消语义：取消时**保留部分结果**——调用方把已经识别完的窗口交给 [mergeWindows] 与 [toCues]，
 * 就能得到「已识别部分的字幕」，见 [partialCues]。
 */
object AsrPipeline {

    /** 窗口长度：30 s（plan 4.7 B）。 */
    const val DEFAULT_WINDOW_MS = 30_000L

    /** 窗口重叠：5 s（plan 4.7 B）。 */
    const val DEFAULT_OVERLAP_MS = 5_000L

    /**
     * 规划识别窗口（**纯函数**）。
     *
     * 规则：
     * - 总时长 <= 0 → 空列表（没有音轨 / 时长未知，调用方据此判「无音轨」）；
     * - 总时长 <= 一窗 → 单窗 [0, 总时长)，不做无意义的二次切分（**极短视频**）；
     * - 否则按「窗口 - 重叠」步进。步进保证了**最后一窗一定比重叠长**（否则它整段已被前一窗
     *   覆盖，单独识别只会产出重复文本，那种情况在循环条件里就不会再开新窗）；
     * - 每窗的 end 夹到总时长，采样偏移/长度按「毫秒 × 采样率 / 1000」算（**非整除时长**不会越界）。
     *
     * @throws IllegalArgumentException 窗口或重叠取值不合法（重叠必须 < 窗口）。
     */
    fun planWindows(
        totalMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        overlapMs: Long = DEFAULT_OVERLAP_MS,
        sampleRate: Int = AsrPcm.SAMPLE_RATE,
    ): List<AsrWindow> {
        require(windowMs > 0L) { "窗口长度必须为正：" + windowMs }
        require(overlapMs >= 0L && overlapMs < windowMs) { "重叠必须落在 [0, 窗口长度) 内：" + overlapMs }
        require(sampleRate > 0) { "采样率必须为正：" + sampleRate }
        if (totalMs <= 0L) return emptyList()

        val step = windowMs - overlapMs
        val starts = ArrayList<Long>()
        var start = 0L
        while (start < totalMs) {
            starts += start
            if (totalMs - start <= windowMs) break
            start += step
        }
        return starts.mapIndexed { index, windowStart ->
            val end = min(totalMs, windowStart + windowMs)
            AsrWindow(
                index = index,
                startMs = windowStart,
                endMs = end,
                sampleOffset = AsrPcm.sampleOffsetOfMs(windowStart, sampleRate),
                sampleCount = AsrPcm.sampleCountOfMs(end - windowStart, sampleRate).toInt(),
            )
        }
    }

    /** 把窗口内的相对时间段换算成整段音轨的绝对时间（**纯函数**）。 */
    fun absoluteSegments(window: AsrWindow, segments: List<WhisperSegment>): List<AsrSegment> =
        segments.map { AsrSegment(window.startMs + it.startMs, window.startMs + it.endMs, it.text) }

    /**
     * 合并多个窗口的识别结果（**纯函数**，R14 的「重叠区去重 + 合并时间轴」）。
     *
     * 两条规则：
     * 1. **按时间戳裁剪**：窗口 i（i > 0）只保留落在「起点 + 重叠/2」之后的内容。重叠区的后半段
     *    交给后一窗——后一窗在重叠区里有更长的后文上下文，识别质量更好；前半段后一窗会重新
     *    识别一遍，直接丢掉即可。
     * 2. **按内容择一**：仍落在重叠区内、且文本与已产出的某段**完全相同**（规范化空白后）
     *    并且时间相交的，判为重复，丢弃后一条。
     *
     * 最后统一**整理单调性**：后一段起点若早于前一段终点，就把前一段终点收到后一段起点；
     * 前一段因此变成空段则删掉（不再往前找，前面已经保证过单调）。
     *
     * @param overlapMs 与 [planWindows] 用的重叠一致；<= 0 表示不做重叠裁剪（只做内容去重与单调整理）。
     */
    fun mergeWindows(
        recognized: List<RecognizedWindow>,
        overlapMs: Long = DEFAULT_OVERLAP_MS,
    ): List<AsrSegment> {
        if (recognized.isEmpty()) return emptyList()
        val merged = ArrayList<AsrSegment>()
        for ((order, item) in recognized.withIndex()) {
            val window = item.window
            val cutoff = if (order == 0 || overlapMs <= 0L) window.startMs else window.startMs + overlapMs / 2
            val overlapEnd = window.startMs + overlapMs
            for (raw in absoluteSegments(window, item.segments).sortedBy { it.startMs }) {
                val text = normalizeText(raw.text)
                if (text.isEmpty() || raw.endMs <= raw.startMs) continue
                if (raw.endMs <= cutoff) continue
                val segment = if (raw.startMs < cutoff) raw.copy(startMs = cutoff) else raw
                if (segment.endMs <= segment.startMs) continue
                val inOverlap = order > 0 && overlapMs > 0L && segment.startMs < overlapEnd
                if (inOverlap && merged.any { sameText(it.text, text) && intersects(it, segment) }) continue
                merged += segment
            }
        }
        return enforceMonotonic(merged)
    }

    /**
     * 段数组 → SubtitleCue（**纯函数**，R14）。
     *
     * 依次做四件事：**去空段**（空白/零长）、**超长段切分**（按字符数与时长各切一刀，取更多的那份）、
     * **毫秒对齐**（全部 Long 毫秒，起点不早于 0）、**单调不重叠**。
     *
     * 时间戳倒挂（end <= start）的段不丢文本：给它 [AsrCueOptions.minDurationMs] 的兜底时长，
     * 之后由单调整理裁掉与后段重叠的部分。
     */
    fun toCues(segments: List<AsrSegment>, options: AsrCueOptions = AsrCueOptions()): List<SubtitleCue> {
        require(options.maxCharsPerCue > 0) { "单条最大字符数必须为正：" + options.maxCharsPerCue }
        require(options.maxDurationMs > 0L) { "单条最大时长必须为正：" + options.maxDurationMs }
        val chunks = ArrayList<SubtitleCue>()
        for (segment in segments) {
            val text = normalizeText(segment.text)
            if (text.isEmpty()) continue
            val start = segment.startMs.coerceAtLeast(0L)
            var end = segment.endMs.coerceAtLeast(0L)
            if (end <= start) end = start + options.minDurationMs
            chunks += splitCue(AsrSegment(start, end, text), options)
        }
        return enforceCueMonotonic(chunks).filter { it.endMs > it.startMs && it.text.isNotBlank() }
    }

    /**
     * 取消后的**部分结果**（R14/R19：取消与部分结果保留）。
     *
     * 把已经识别完的窗口按正常流程合并成 cue；一个窗口都没完成时返回空列表
     * （调用方据此提示「已取消，未产出字幕」而不是写一个空文件）。
     */
    fun partialCues(
        recognized: List<RecognizedWindow>,
        overlapMs: Long = DEFAULT_OVERLAP_MS,
        options: AsrCueOptions = AsrCueOptions(),
    ): List<SubtitleCue> = toCues(mergeWindows(recognized, overlapMs), options)

    /** 已识别时长 / 总时长 → 进度（**纯函数**；两者都夹到合理范围）。 */
    fun progressOf(recognizedMs: Long, totalMs: Long): AsrProgress {
        val total = totalMs.coerceAtLeast(0L)
        val done = if (total <= 0L) recognizedMs.coerceAtLeast(0L) else recognizedMs.coerceIn(0L, total)
        return AsrProgress(done, total)
    }

    /** 规范化文本：合并连续空白、去掉首尾空白（**纯函数**，比较与写出都用这一份口径）。 */
    fun normalizeText(text: String): String = text.replace(WHITESPACE, " ").trim()

    /** 两段文本规范化后是否相同（**纯函数**）。 */
    fun sameText(a: String, b: String): Boolean = normalizeText(a) == normalizeText(b)

    private val WHITESPACE = Regex("\\s+")

    /** 时间是否相交（端点相等不算，避免「首尾相接」被误判为重复）。 */
    private fun intersects(a: AsrSegment, b: AsrSegment): Boolean = a.startMs < b.endMs && b.startMs < a.endMs

    /** 单调整理：保证 start 递增、前段 end <= 后段 start、每段非空。 */
    private fun enforceMonotonic(segments: List<AsrSegment>): List<AsrSegment> {
        val out = ArrayList<AsrSegment>(segments.size)
        for (raw in segments) {
            if (raw.endMs <= raw.startMs) continue
            while (out.isNotEmpty() && out.last().endMs > raw.startMs) {
                val previous = out.removeAt(out.lastIndex)
                val trimmed = previous.copy(endMs = raw.startMs)
                if (trimmed.endMs > trimmed.startMs) {
                    out += trimmed
                    break
                }
            }
            out += raw
        }
        return out
    }

    /** cue 的单调整理（与段同一口径，只是换成了 SubtitleCue）。 */
    private fun enforceCueMonotonic(cues: List<SubtitleCue>): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>(cues.size)
        for (raw in cues.sortedBy { it.startMs }) {
            if (raw.endMs <= raw.startMs) continue
            while (out.isNotEmpty() && out.last().endMs > raw.startMs) {
                val previous = out.removeAt(out.lastIndex)
                val trimmed = previous.copy(endMs = raw.startMs)
                if (trimmed.endMs > trimmed.startMs) {
                    out += trimmed
                    break
                }
            }
            out += raw
        }
        return out
    }

    /** 超长段切分：份数取「按字符」与「按时长」的较大者，时间按字符数比例分摊。 */
    private fun splitCue(segment: AsrSegment, options: AsrCueOptions): List<SubtitleCue> {
        val text = segment.text
        val byChars = ceilDiv(text.length.toLong(), options.maxCharsPerCue.toLong())
        val byTime = ceilDiv(segment.durationMs, options.maxDurationMs)
        val parts = maxOf(1L, byChars, byTime).coerceAtMost(maxOf(1L, text.length.toLong())).toInt()
        if (parts <= 1) return listOf(SubtitleCue(segment.startMs, segment.endMs, text))

        val chunkSize = (text.length + parts - 1) / parts
        val pieces = text.chunked(chunkSize).map { it.trim() }.filter { it.isNotEmpty() }
        if (pieces.size <= 1) return listOf(SubtitleCue(segment.startMs, segment.endMs, text))

        val totalChars = pieces.sumOf { it.length }.coerceAtLeast(1)
        val result = ArrayList<SubtitleCue>(pieces.size)
        var consumed = 0
        var cursor = segment.startMs
        pieces.forEachIndexed { index, piece ->
            consumed += piece.length
            val end = if (index == pieces.lastIndex) {
                segment.endMs
            } else {
                segment.startMs + (segment.durationMs * consumed / totalChars)
            }
            val safeEnd = end.coerceIn(cursor, segment.endMs)
            if (safeEnd > cursor) result += SubtitleCue(cursor, safeEnd, piece)
            cursor = safeEnd
        }
        return result
    }

    private fun ceilDiv(value: Long, divisor: Long): Long = if (value <= 0L) 0L else (value + divisor - 1) / divisor
}
