package io.github.gua123.mediagate.media.subtitle

/**
 * 一次解析的结果（R14：外挂字幕解析 + 容错统计）。
 *
 * 播放页既需要 [cues]（渲染与微调），也需要 [dropped] / [truncatedLines] 来说明
 * 「这份字幕有坏数据」——**绝不因为坏数据静默丢内容**。
 *
 * @property cues 解析出的条目，已按起始时间升序排序；时间轴重叠的条目都会保留。
 * @property format 实际使用的解析格式。
 * @property dropped 被丢弃的坏块数量（无时间轴、时间倒挂、空文本等）。
 * @property truncatedLines 被截断的超长行数量（单行超过 [SubtitleParser.MAX_LINE_CHARS]）。
 */
data class SubtitleParseResult(
    val cues: List<SubtitleCue>,
    val format: SubtitleFormat,
    val dropped: Int = 0,
    val truncatedLines: Int = 0,
) {

    /** 条目数。 */
    val count: Int get() = cues.size

    /** 是否一条都没解析出来。 */
    val isEmpty: Boolean get() = cues.isEmpty()

    /** 字幕覆盖到的时间轴末端（毫秒）；空字幕为 0。 */
    val totalDurationMs: Long get() = cues.maxOfOrNull { it.endMs } ?: 0L

    /** 是否有容错信息要提示用户（R14 不静默丢弃）。 */
    val hasIssues: Boolean get() = dropped > 0 || truncatedLines > 0
}

/**
 * 外挂字幕解析器（R14：至少覆盖 SRT 与 WebVTT；ASS/SSA 解析基础样式行并降级为纯文本 + 位置提示）。
 *
 * 设计口径：
 * - **纯函数**：输入文本 + 格式，输出 [SubtitleParseResult]，不碰 IO、不碰 Android，JVM 可测；
 * - **容错优先**：BOM、CRLF、序号错乱、缺序号、时间轴重叠、缺失结尾空行、超长行、坏时间码
 *   一律不抛异常——坏块计入 [SubtitleParseResult.dropped]，超长行计入
 *   [SubtitleParseResult.truncatedLines]，其余照常解析；
 * - **降级为纯文本**：SRT/VTT 的 HTML 标签、ASS 的换行转义与覆盖指令都会清洗掉，
 *   用户看到的是可读文本；ASS 的样式行则保留成 [SubtitleCueStyle]（位置提示）。
 */
object SubtitleParser {

    /** 单行文本上限（字符）；超长行截断并计数，避免一条坏行把渲染拖垮。 */
    const val MAX_LINE_CHARS = 500

    /** 单条字幕最多保留的行数。 */
    const val MAX_LINES_PER_CUE = 20

    /** WebVTT 允许省略结束时间，此时按「起始 + 该值」兜底（毫秒）。 */
    const val DEFAULT_CUE_MS = 2_000L

    private const val ARROW = "-->"

    /** 按显式格式解析（格式已知时的入口）。 */
    fun parse(text: String, format: SubtitleFormat): SubtitleParseResult {
        val lines = SubtitleTextCodec.normalize(text).split('\n')
        return when (format) {
            SubtitleFormat.SRT -> parseTimedBlocks(lines, SubtitleFormat.SRT, vtt = false)
            SubtitleFormat.VTT -> parseTimedBlocks(lines, SubtitleFormat.VTT, vtt = true)
            SubtitleFormat.ASS, SubtitleFormat.SSA -> parseAss(lines, format)
            // 纯文本稿不是字幕源（[SubtitleFormat.SOURCE_FORMATS] 里没有它）；真被传进来就如实返回空
            SubtitleFormat.TXT -> SubtitleParseResult(format = format, cues = emptyList())
        }
    }

    /** 按文件名（扩展名）解析；扩展名不认识时用 [sniff] 嗅探内容。 */
    fun parse(text: String, fileName: String): SubtitleParseResult =
        parse(text, SubtitleFormat.fromFileName(fileName) ?: sniff(text))

    /** 只看内容猜格式：WEBVTT 头 → VTT；ASS/SSA 段名或 Dialogue 行 → ASS；其余按 SRT。 */
    fun sniff(text: String): SubtitleFormat {
        val head = SubtitleTextCodec.normalize(text).take(SNIFF_CHARS)
        return when {
            head.contains("WEBVTT", ignoreCase = true) -> SubtitleFormat.VTT
            head.contains("[Script Info]", ignoreCase = true) ||
                head.contains("[Events]", ignoreCase = true) ||
                head.contains("Dialogue:", ignoreCase = true) -> SubtitleFormat.ASS

            else -> SubtitleFormat.SRT
        }
    }

    // ------------------------------------------------------------ SRT / WebVTT（块结构相同）

    /**
     * SRT / WebVTT 共用的块解析。
     *
     * 两者都是「空行分块 + 时间轴行 + 文本行」，差别只在：VTT 有 WEBVTT 头与 NOTE/STYLE/REGION
     * 元数据块、时间码用点、允许省略结束时间。序号行（SRT）与 cue 标识行（VTT）都**整块忽略**，
     * 所以序号错乱/重复/缺失都不影响解析。
     */
    private fun parseTimedBlocks(
        lines: List<String>,
        format: SubtitleFormat,
        vtt: Boolean,
    ): SubtitleParseResult {
        val cues = mutableListOf<SubtitleCue>()
        var dropped = 0
        var truncated = 0
        var index = 0
        if (vtt) index = skipVttHeader(lines)
        while (index < lines.size) {
            while (index < lines.size && lines[index].isBlank()) index++
            if (index >= lines.size) break
            val block = mutableListOf<String>()
            // 读到空行或文件末尾都算一个块结束：缺少结尾空行的最后一块也能解析
            while (index < lines.size && lines[index].isNotBlank()) {
                block += lines[index]
                index++
            }
            if (vtt && isVttMetadataBlock(block)) continue
            val arrowAt = block.indexOfFirst { it.contains(ARROW) }
            if (arrowAt < 0) {
                dropped++
                continue
            }
            val timing = parseTimingLine(block[arrowAt])
            if (timing == null) {
                dropped++
                continue
            }
            val cleaned = normalizeCueLines(block.drop(arrowAt + 1).map { SubtitleMarkup.cleanHtml(it) })
            truncated += cleaned.second
            val text = cleaned.first.joinToString("\n")
            if (text.isEmpty()) {
                dropped++
                continue
            }
            cues += SubtitleCue(startMs = timing.first, endMs = timing.second, text = text)
        }
        return SubtitleParseResult(
            cues = cues.sortedBy { it.startMs },
            format = format,
            dropped = dropped,
            truncatedLines = truncated,
        )
    }

    /** 跳过 WEBVTT 头与紧随其后的头部信息行（Kind: / Language: 等）。 */
    private fun skipVttHeader(lines: List<String>): Int {
        var index = 0
        while (index < lines.size && lines[index].isBlank()) index++
        if (index < lines.size && lines[index].trim().startsWith("WEBVTT", ignoreCase = true)) {
            index++
            while (index < lines.size && lines[index].isNotBlank()) index++
        }
        return index
    }

    /** VTT 的 NOTE / STYLE / REGION 块不是字幕，整块跳过（不当成坏数据计数）。 */
    private fun isVttMetadataBlock(block: List<String>): Boolean {
        val head = block.firstOrNull { it.isNotBlank() }?.trim()?.uppercase() ?: return false
        return head.startsWith("NOTE") || head.startsWith("STYLE") || head.startsWith("REGION")
    }

    /**
     * 时间轴行 → (start, end)。
     *
     * 容错点：结束时间缺失（VTT 合法写法）按 [DEFAULT_CUE_MS] 兜底；VTT 时间码后可跟 cue settings
     * （line:0 position:50% 等），只取第一个 token；end <= start 视为坏数据。
     */
    private fun parseTimingLine(line: String): Pair<Long, Long>? {
        val arrow = line.indexOf(ARROW)
        if (arrow < 0) return null
        val start = parseTimecodeMs(line.substring(0, arrow)) ?: return null
        val right = line.substring(arrow + ARROW.length).trim()
        val token = right.split(' ', '\t').firstOrNull { it.isNotBlank() }
        val end = if (token == null) start + DEFAULT_CUE_MS else (parseTimecodeMs(token) ?: return null)
        if (end <= start) return null
        return start to end
    }

    // ------------------------------------------------------------ ASS / SSA（段 + Format 行）

    private fun parseAss(lines: List<String>, format: SubtitleFormat): SubtitleParseResult {
        val cues = mutableListOf<SubtitleCue>()
        val styles = mutableMapOf<String, SubtitleCueStyle>()
        var styleFormat: List<String>? = null
        var eventFormat: List<String>? = null
        var section = ""
        var dropped = 0
        var truncated = 0

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1)
            val inStyles = section.contains("styles")
            val inEvents = section.contains("events")
            when {
                // Format 行声明的是列名（Layer/Start/Text…），统一小写后再按名字取列
                inStyles && key == "format" -> styleFormat = splitFields(value).map { it.lowercase() }

                inStyles && key == "style" -> {
                    val names = styleFormat ?: DEFAULT_STYLE_FORMAT
                    val fields = splitFields(value, names.size)
                    val nameIndex = names.indexOf("name")
                    val name = if (nameIndex >= 0) fields.getOrNull(nameIndex).orEmpty().trim() else ""
                    if (name.isNotEmpty()) styles[name.lowercase()] = readStyle(names, fields)
                }

                inEvents && key == "format" -> eventFormat = splitFields(value).map { it.lowercase() }

                // Comment 行是注释字幕（不显示），整行跳过且不算坏数据
                inEvents && key == "comment" -> Unit

                inEvents && key == "dialogue" -> {
                    val names = eventFormat
                        ?: if (format == SubtitleFormat.SSA) DEFAULT_SSA_EVENT_FORMAT else DEFAULT_ASS_EVENT_FORMAT
                    val fields = splitFields(value, names.size)
                    val startIndex = names.indexOf("start")
                    val endIndex = names.indexOf("end")
                    val textIndex = names.indexOf("text")
                    val start = if (startIndex >= 0) parseTimecodeMs(fields.getOrNull(startIndex).orEmpty()) else null
                    val end = if (endIndex >= 0) parseTimecodeMs(fields.getOrNull(endIndex).orEmpty()) else null
                    if (startIndex < 0 || endIndex < 0 || textIndex < 0 || start == null || end == null || end <= start) {
                        dropped++
                        continue
                    }
                    val rawText = fields.getOrNull(textIndex).orEmpty()
                    val cleaned = normalizeCueLines(SubtitleMarkup.cleanAss(rawText).split('\n'))
                    truncated += cleaned.second
                    val text = cleaned.first.joinToString("\n")
                    if (text.isEmpty()) {
                        dropped++
                        continue
                    }
                    val styleIndex = names.indexOf("style")
                    val styleName = if (styleIndex >= 0) fields.getOrNull(styleIndex)?.trim()?.lowercase() else null
                    val base = if (styleName != null) styles[styleName] else null
                    val override = SubtitleMarkup.alignmentOverride(rawText)
                    val style = when {
                        base == null && override == null -> null
                        base == null -> SubtitleCueStyle(alignment = override ?: SubtitleAlignment.UNKNOWN)
                        override == null -> base
                        else -> base.copy(alignment = override)
                    }
                    cues += SubtitleCue(startMs = start, endMs = end, text = text, style = style)
                }
            }
        }
        return SubtitleParseResult(
            cues = cues.sortedBy { it.startMs },
            format = format,
            dropped = dropped,
            truncatedLines = truncated,
        )
    }

    /** 从样式行的字段里只取我们关心的部分（R14「基础样式」的边界）。 */
    private fun readStyle(names: List<String>, fields: List<String>): SubtitleCueStyle {
        fun field(name: String): String? {
            val index = names.indexOf(name)
            return if (index >= 0) fields.getOrNull(index) else null
        }
        return SubtitleCueStyle(
            alignment = field("alignment")?.trim()?.toIntOrNull()?.let { SubtitleAlignment.ofAss(it) }
                ?: SubtitleAlignment.UNKNOWN,
            fontName = field("fontname")?.trim()?.takeIf { it.isNotEmpty() },
            fontSize = field("fontsize")?.trim()?.toFloatOrNull(),
            primaryColorArgb = field("primarycolour")?.trim()?.let { SubtitleMarkup.parseAssColor(it) },
            bold = SubtitleMarkup.isAssTrue(field("bold")),
            italic = SubtitleMarkup.isAssTrue(field("italic")),
        )
    }

    // ------------------------------------------------------------ 公共小工具

    /**
     * 文本行清洗：去首尾空白、超长截断、去掉首尾空行、限制行数。
     *
     * @return (清洗后的行, 被截断的行数)。
     */
    private fun normalizeCueLines(lines: List<String>): Pair<List<String>, Int> {
        var truncated = 0
        val out = mutableListOf<String>()
        for (line in lines) {
            val plain = line.trim()
            if (plain.length > MAX_LINE_CHARS) {
                truncated++
                out += plain.take(MAX_LINE_CHARS)
            } else {
                out += plain
            }
        }
        while (out.isNotEmpty() && out.first().isEmpty()) out.removeAt(0)
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        if (out.size > MAX_LINES_PER_CUE) {
            truncated += out.size - MAX_LINES_PER_CUE
            while (out.size > MAX_LINES_PER_CUE) out.removeAt(out.lastIndex)
        }
        return out to truncated
    }

    /** 逗号分列；[limit] > 0 时限制列数（Text 是 ASS 的最后一个字段，允许含逗号）。 */
    private fun splitFields(value: String, limit: Int = 0): List<String> {
        val parts = if (limit > 0) value.split(',', limit = limit) else value.split(',')
        return parts.map { it.trim() }
    }

    /**
     * 时间码 → 毫秒。
     *
     * 支持：`HH:MM:SS,mmm`（SRT）、`HH:MM:SS.mmm`（VTT）、`MM:SS.mmm`（VTT 省略小时）、
     * `H:MM:SS.cc`（ASS 的百分秒）。小数位数按位权换算（1 位 ×100、2 位 ×10、3 位 ×1），
     * 所以 ASS 的 0:00:01.50 与 SRT 的 00:00:01,500 都得到 1500 ms。
     *
     * @return 毫秒；格式不认识（字段不是数字、列数不是 2~3）返回 null。
     */
    fun parseTimecodeMs(raw: String): Long? {
        val value = raw.trim().trim('<', '>').trim()
        if (value.isEmpty()) return null
        val separator = value.lastIndexOfAny(charArrayOf('.', ','))
        val timePart = if (separator >= 0) value.substring(0, separator) else value
        val fraction = if (separator >= 0) value.substring(separator + 1) else ""
        if (fraction.any { !it.isDigit() }) return null
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            3 -> fraction.toLong()
            else -> fraction.take(3).toLong()
        }
        val parts = timePart.split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.trim() }
        if (numbers.any { it.isEmpty() || it.any { ch -> !ch.isDigit() } }) return null
        val seconds = numbers.last().toLong()
        val minutes = numbers[numbers.size - 2].toLong()
        val hours = if (numbers.size == 3) numbers[0].toLong() else 0L
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    /** 内容嗅探的取样长度。 */
    private const val SNIFF_CHARS = 4_096

    /** 样式段缺 Format 行时的默认列序（ASS 与 SSA 在我们读取的列上一致）。 */
    private val DEFAULT_STYLE_FORMAT = listOf(
        "name", "fontname", "fontsize", "primarycolour", "secondarycolour", "tertiarycolour",
        "backcolour", "bold", "italic", "underline", "strikeout", "scalex", "scaley", "spacing",
        "angle", "borderstyle", "outline", "shadow", "alignment", "marginl", "marginr", "marginv",
        "alphalevel", "encoding",
    )

    /** 事件段缺 Format 行时的默认列序（ASS V4+：Layer 开头）。 */
    private val DEFAULT_ASS_EVENT_FORMAT = listOf(
        "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text",
    )

    /** 事件段缺 Format 行时的默认列序（SSA V4：Marked 开头）。 */
    private val DEFAULT_SSA_EVENT_FORMAT = listOf(
        "marked", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text",
    )
}

/**
 * 字幕标记清洗（R14：一律降级为纯文本）。
 *
 * SRT/VTT：去掉 i / font 之类的 HTML 标签并还原常见实体；
 * ASS/SSA：去掉 pos 之类的覆盖指令，换行转义还原成换行、空格转义还原成空格。
 */
internal object SubtitleMarkup {

    private val HTML_TAG = Regex("<[^>]*>")
    private val ASS_OVERRIDE = Regex("\\{[^}]*\\}")
    private val ASS_ALIGNMENT = Regex("\\\\an([1-9])")

    /** 取 ASS 文本里的对齐覆盖（位置提示）；没有则 null。 */
    fun alignmentOverride(raw: String): SubtitleAlignment? {
        val value = ASS_ALIGNMENT.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return SubtitleAlignment.ofAss(value)
    }

    /** SRT / WebVTT 行清洗：去标签 + 还原实体。 */
    fun cleanHtml(raw: String): String = unescape(HTML_TAG.replace(raw, ""))

    /** ASS / SSA 行清洗：去覆盖指令 + 还原换行/空格转义 + 还原实体。 */
    fun cleanAss(raw: String): String = unescape(
        ASS_OVERRIDE.replace(raw, "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " "),
    )

    /** ASS 的 -1 / 1 / true 都算真。 */
    fun isAssTrue(raw: String?): Boolean {
        val value = raw?.trim()?.lowercase() ?: return false
        return value == "-1" || value == "1" || value == "true" || value == "yes"
    }

    /**
     * ASS 颜色 AA BB GG RR 十六进制 → Android ARGB。
     *
     * ASS 的 alpha 与 Android 相反（ASS 的 00 = 完全不透明），这里换算成 255 = 不透明。
     * 解析不出来（不是十六进制）返回 null。
     */
    fun parseAssColor(raw: String): Int? {
        val value = raw.trim().removePrefix("&H").removePrefix("&h").removeSuffix("&").trim()
        if (value.isEmpty() || value.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
        val number = value.toLongOrNull(16) ?: return null
        val alpha = ((number shr 24) and 0xFF).toInt()
        val blue = ((number shr 16) and 0xFF).toInt()
        val green = ((number shr 8) and 0xFF).toInt()
        val red = (number and 0xFF).toInt()
        return (((255 - alpha) and 0xFF) shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun unescape(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
