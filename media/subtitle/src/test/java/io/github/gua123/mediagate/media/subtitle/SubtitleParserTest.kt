package io.github.gua123.mediagate.media.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外挂字幕解析（R14：SRT / WebVTT 完整覆盖，ASS/SSA 基础样式降级，坏数据不崩且计数）。 */
class SubtitleParserTest {

    // ------------------------------------------------------------------ SRT

    @Test
    fun parsesSimpleSrt() {
        val text = """
            1
            00:00:01,000 --> 00:00:03,500
            第一行

            2
            00:00:04,000 --> 00:00:05,000
            第二行
        """.trimIndent()

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals(2, result.count)
        assertEquals(0, result.dropped)
        assertEquals(SubtitleFormat.SRT, result.format)
        assertEquals(SubtitleCue(1_000L, 3_500L, "第一行"), result.cues[0])
        assertEquals("第二行", result.cues[1].text)
        assertEquals(5_000L, result.totalDurationMs)
        assertFalse(result.hasIssues)
    }

    @Test
    fun toleratesBomAndCrlf() {
        val text = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\n你好\r\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals(1, result.count)
        assertEquals("你好", result.cues[0].text)
        assertEquals(0, result.dropped)
    }

    @Test
    fun ignoresBrokenIndexLines() {
        // 第一条缺序号、第二条序号是文字——序号行整块忽略，不影响解析
        val text = "00:00:01,000 --> 00:00:02,000\nA\n\nabc\n00:00:03,000 --> 00:00:04,000\nB\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals(2, result.count)
        assertEquals(0, result.dropped)
        assertEquals(listOf("A", "B"), result.cues.map { it.text })
    }

    @Test
    fun sortsOutOfOrderBlocksByStartTime() {
        val text = "5\n00:00:09,000 --> 00:00:10,000\n后\n\n1\n00:00:01,000 --> 00:00:02,000\n前\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals(listOf("前", "后"), result.cues.map { it.text })
        assertEquals(listOf(1_000L, 9_000L), result.cues.map { it.startMs })
    }

    @Test
    fun parsesLastBlockWithoutTrailingBlankLine() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n结尾块"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals(1, result.count)
        assertEquals("结尾块", result.cues[0].text)
    }

    @Test
    fun dropsReversedTimelineBlock() {
        val text = "1\n00:00:05,000 --> 00:00:01,000\n倒挂\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertTrue(result.isEmpty)
        assertEquals(1, result.dropped)
        assertTrue(result.hasIssues)
    }

    @Test
    fun dropsZeroLengthBlock() {
        val result = SubtitleParser.parse("1\n00:00:02,000 --> 00:00:02,000\n零长\n", SubtitleFormat.SRT)

        assertTrue(result.isEmpty)
        assertEquals(1, result.dropped)
    }

    @Test
    fun dropsBlockWithoutTimingLine() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nA\n\n99\n\n2\n00:00:03,000 --> 00:00:04,000\nB\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals(2, result.count)
        assertEquals(1, result.dropped)
    }

    @Test
    fun dropsBlockWithEmptyText() {
        val result = SubtitleParser.parse("1\n00:00:01,000 --> 00:00:02,000\n\n", SubtitleFormat.SRT)

        assertTrue(result.isEmpty)
        assertEquals(1, result.dropped)
    }

    @Test
    fun keepsOverlappingCues() {
        val text = "1\n00:00:01,000 --> 00:00:05,000\n长\n\n2\n00:00:02,000 --> 00:00:03,000\n叠\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals(2, result.count)
        assertEquals(0, result.dropped)
        assertEquals(listOf(1_000L, 2_000L), result.cues.map { it.startMs })
    }

    @Test
    fun joinsMultipleTextLines() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n第一行\n第二行\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals("第一行\n第二行", result.cues[0].text)
    }

    @Test
    fun stripsHtmlTagsAndEntities() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n<i>斜体</i> &amp; <font color=\"#fff\">彩色</font>\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals("斜体 & 彩色", result.cues[0].text)
    }

    @Test
    fun truncatesOverlongLine() {
        val long = "x".repeat(SubtitleParser.MAX_LINE_CHARS + 20)
        val text = "1\n00:00:01,000 --> 00:00:02,000\n" + long + "\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals(1, result.count)
        assertEquals(1, result.truncatedLines)
        assertEquals(SubtitleParser.MAX_LINE_CHARS, result.cues[0].text.length)
        assertTrue(result.hasIssues)
    }

    @Test
    fun limitsCueLineCount() {
        val lines = (1..25).joinToString("\n") { "行" + it }
        val text = "1\n00:00:01,000 --> 00:00:02,000\n" + lines + "\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals(SubtitleParser.MAX_LINES_PER_CUE, result.cues[0].text.split('\n').size)
        assertEquals(5, result.truncatedLines)
    }

    @Test
    fun countsAllKindsOfDamageInOneFile() {
        val long = "y".repeat(SubtitleParser.MAX_LINE_CHARS + 1)
        val text = "坏块没有时间轴\n\n1\n00:00:05,000 --> 00:00:01,000\n倒挂\n\n2\n00:00:06,000 --> 00:00:07,000\n" +
            long + "\n\n3\n00:00:08,000 --> 00:00:09,000\n好\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.SRT)

        assertEquals(2, result.count)
        assertEquals("好", result.cues[1].text)
        assertEquals(2, result.dropped)
        assertEquals(1, result.truncatedLines)
    }

    @Test
    fun parsesTimecodeVariants() {
        assertEquals(3_723_456L, SubtitleParser.parseTimecodeMs("01:02:03,456"))
        assertEquals(3_723_456L, SubtitleParser.parseTimecodeMs("01:02:03.456"))
        assertEquals(62_500L, SubtitleParser.parseTimecodeMs("01:02.500"))
        assertEquals(1_500L, SubtitleParser.parseTimecodeMs("0:00:01.50"))
        assertEquals(500L, SubtitleParser.parseTimecodeMs("00:00:00.5"))
        assertEquals(0L, SubtitleParser.parseTimecodeMs("00:00:00,000"))
        assertEquals(90_000L, SubtitleParser.parseTimecodeMs("1:30"))
        assertEquals(3_600_000L, SubtitleParser.parseTimecodeMs("1:00:00"))
    }

    @Test
    fun rejectsBrokenTimecode() {
        assertNull(SubtitleParser.parseTimecodeMs(""))
        assertNull(SubtitleParser.parseTimecodeMs("abc"))
        assertNull(SubtitleParser.parseTimecodeMs("00:00:01:12"))
        assertNull(SubtitleParser.parseTimecodeMs("1:2:3:4"))
        assertNull(SubtitleParser.parseTimecodeMs("00:0a:01"))
        assertNull(SubtitleParser.parseTimecodeMs("00:00:01,x"))
    }

    // ------------------------------------------------------------------ WebVTT

    @Test
    fun vttParsesHeaderIdentifierAndCueSettings() {
        val text = """
            WEBVTT
            Kind: captions
            Language: zh

            cue-1
            00:00:01.000 --> 00:00:03.000 line:0 position:50%
            你好

            00:00:04.000 --> 00:00:05.000
            再见
        """.trimIndent()

        val result = SubtitleParser.parse(text, SubtitleFormat.VTT)

        assertEquals(2, result.count)
        assertEquals(0, result.dropped)
        assertEquals(SubtitleFormat.VTT, result.format)
        assertEquals(SubtitleCue(1_000L, 3_000L, "你好"), result.cues[0])
        assertEquals("再见", result.cues[1].text)
    }

    @Test
    fun vttMissingEndTimeUsesDefaultDuration() {
        val result = SubtitleParser.parse("WEBVTT\n\n00:00:01.000 -->\n你好\n", SubtitleFormat.VTT)

        assertEquals(1, result.count)
        assertEquals(1_000L, result.cues[0].startMs)
        assertEquals(1_000L + SubtitleParser.DEFAULT_CUE_MS, result.cues[0].endMs)
    }

    @Test
    fun vttSkipsNoteStyleAndRegionBlocks() {
        val text = """
            WEBVTT

            NOTE 这是注释

            STYLE
            ::cue { color: white }

            REGION
            id:r1

            00:00:01.000 --> 00:00:02.000
            Hi
        """.trimIndent()

        val result = SubtitleParser.parse(text, SubtitleFormat.VTT)

        assertEquals(1, result.count)
        assertEquals(0, result.dropped)
        assertEquals("Hi", result.cues[0].text)
    }

    @Test
    fun vttWithoutHeaderStillParses() {
        val result = SubtitleParser.parse("00:00:01.000 --> 00:00:02.000\nHi\n", SubtitleFormat.VTT)

        assertEquals(1, result.count)
    }

    @Test
    fun sniffDetectsVttAndAss() {
        assertEquals(SubtitleFormat.VTT, SubtitleParser.sniff("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n"))
        assertEquals(SubtitleFormat.ASS, SubtitleParser.sniff("[Script Info]\nTitle: x\n[Events]\n"))
        assertEquals(SubtitleFormat.ASS, SubtitleParser.sniff("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,hi\n"))
        assertEquals(SubtitleFormat.SRT, SubtitleParser.sniff("普通文本\n1\n00:00:01,000 --> 00:00:02,000\nA\n"))
    }

    @Test
    fun parseByFileNameUsesExtension() {
        assertEquals(
            SubtitleFormat.VTT,
            SubtitleParser.parse("00:00:01.000 --> 00:00:02.000\nHi\n", "a.vtt").format,
        )
        assertEquals(
            SubtitleFormat.SRT,
            SubtitleParser.parse("1\n00:00:01,000 --> 00:00:02,000\nHi\n", "a.srt").format,
        )
        // 扩展名不认识时按内容嗅探
        assertEquals(
            SubtitleFormat.VTT,
            SubtitleParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n", "a.txt").format,
        )
    }

    // ------------------------------------------------------------------ ASS / SSA

    private val assHeader = """
        [Script Info]
        Title: 示例
        PlayResX: 1920
        PlayResY: 1080

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,微软雅黑,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,2,10,10,20,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent()

    @Test
    fun assParsesBasicStyleAndAlignment() {
        val text = assHeader + "\nDialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,第一句\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.ASS)

        assertEquals(1, result.count)
        assertEquals(0, result.dropped)
        val cue = result.cues[0]
        assertEquals(1_000L, cue.startMs)
        assertEquals(3_500L, cue.endMs)
        assertEquals("第一句", cue.text)
        assertEquals("微软雅黑", cue.style?.fontName)
        assertEquals(48f, cue.style?.fontSize)
        assertEquals(true, cue.style?.bold)
        assertEquals(SubtitleAlignment.BOTTOM_CENTER, cue.style?.alignment)
        assertEquals(0xFFFFFFFF.toInt(), cue.style?.primaryColorArgb)
    }

    @Test
    fun assStripsOverrideTagsAndKeepsCommasInText() {
        val text = assHeader +
            "\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\pos(960,1000)}你好，世界，再见\\N第二行\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.ASS)

        assertEquals("你好，世界，再见\n第二行", result.cues[0].text)
    }

    @Test
    fun assCommentLinesAreSkipped() {
        val text = assHeader +
            "\nComment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,注释不该出现\n" +
            "Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,正片\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.ASS)

        assertEquals(1, result.count)
        assertEquals("正片", result.cues[0].text)
        assertEquals(0, result.dropped)
    }

    @Test
    fun assAnOverrideChangesAlignment() {
        val text = assHeader + "\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\an8}顶部字幕\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.ASS)

        assertEquals("顶部字幕", result.cues[0].text)
        assertEquals(SubtitleAlignment.TOP_CENTER, result.cues[0].style?.alignment)
        assertTrue(result.cues[0].style?.alignment?.isTop == true)
    }

    @Test
    fun assDropsDialogueWithBrokenTimecode() {
        val text = assHeader +
            "\nDialogue: 0,xx:yy,0:00:02.00,Default,,0,0,0,,坏时间\n" +
            "Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,好的\n"

        val result = SubtitleParser.parse(text, SubtitleFormat.ASS)

        assertEquals(1, result.count)
        assertEquals("好的", result.cues[0].text)
        assertEquals(1, result.dropped)
    }

    @Test
    fun assWithoutFormatLineUsesDefaultColumns() {
        val text = """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,默认列序
        """.trimIndent()

        val result = SubtitleParser.parse(text, SubtitleFormat.ASS)

        assertEquals(1, result.count)
        assertEquals("默认列序", result.cues[0].text)
        assertNull(result.cues[0].style)
    }

    @Test
    fun ssaUsesMarkedColumnLayout() {
        val text = """
            [Script Info]
            ScriptType: v4.00

            [V4 Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding
            Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,-1,0,1,2,0,2,10,10,10,0,1

            [Events]
            Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: Marked=0,0:00:01.00,0:00:02.50,Default,,0,0,0,,SSA 文本
        """.trimIndent()

        val result = SubtitleParser.parse(text, SubtitleFormat.SSA)

        assertEquals(1, result.count)
        assertEquals(SubtitleFormat.SSA, result.format)
        assertEquals("SSA 文本", result.cues[0].text)
        assertEquals(1_000L, result.cues[0].startMs)
        assertEquals(2_500L, result.cues[0].endMs)
        assertEquals(SubtitleAlignment.BOTTOM_CENTER, result.cues[0].style?.alignment)
    }

    @Test
    fun assColorConvertsToArgb() {
        assertEquals(0xFFFFFFFF.toInt(), SubtitleMarkup.parseAssColor("&H00FFFFFF"))
        assertEquals(0xFFFF0000.toInt(), SubtitleMarkup.parseAssColor("&H000000FF"))
        assertEquals(0x7FFFFFFF.toInt(), SubtitleMarkup.parseAssColor("&H80FFFFFF"))
        assertNull(SubtitleMarkup.parseAssColor("&HZZZZZZ"))
        assertNull(SubtitleMarkup.parseAssColor(""))
    }

    @Test
    fun garbageInputDoesNotCrash() {
        val result = SubtitleParser.parse("!!!\n???\n\n\n乱码一行\n", SubtitleFormat.SRT)

        assertTrue(result.isEmpty)
        assertEquals(2, result.dropped)
        assertEquals(SubtitleFormat.SRT, result.format)
    }

    @Test
    fun emptyInputGivesEmptyResult() {
        val result = SubtitleParser.parse("", SubtitleFormat.SRT)

        assertTrue(result.isEmpty)
        assertEquals(0, result.dropped)
        assertEquals(0L, result.totalDurationMs)
    }
}
