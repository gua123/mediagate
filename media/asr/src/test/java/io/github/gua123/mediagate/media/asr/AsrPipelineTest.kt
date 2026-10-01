package io.github.gua123.mediagate.media.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AsrPipeline] 的 JVM 单测（**M7-B / R14 / R19**）。
 *
 * 覆盖：分段计划（极短视频 / 超长视频 / 非整除时长 / 非法参数）、重叠区去重合并
 * （时间裁剪、内容重复、时间交叠、单调整理）、Cue 生成与切分、进度换算、取消后部分结果。
 */
class AsrPipelineTest {

    // ---------------------------------------------------------------- 分段计划

    @Test
    fun planWindows_returnsEmptyForNonPositiveDuration() {
        assertTrue(AsrPipeline.planWindows(0L).isEmpty())
        assertTrue(AsrPipeline.planWindows(-1_000L).isEmpty())
    }

    @Test
    fun planWindows_singleWindowForVeryShortVideo() {
        val windows = AsrPipeline.planWindows(2_000L)
        assertEquals(1, windows.size)
        val only = windows.single()
        assertEquals(0L, only.startMs)
        assertEquals(2_000L, only.endMs)
        assertEquals(0L, only.sampleOffset)
        assertEquals(32_000, only.sampleCount)
        assertEquals(2, only.durationSeconds)
    }

    @Test
    fun planWindows_singleWindowWhenDurationEqualsWindow() {
        val windows = AsrPipeline.planWindows(30_000L)
        assertEquals(1, windows.size)
        assertEquals(30_000L, windows.single().endMs)
    }

    @Test
    fun planWindows_twoWindowsWithFiveSecondOverlapForThirtyHalfSeconds() {
        val windows = AsrPipeline.planWindows(30_500L)
        assertEquals(2, windows.size)
        assertEquals(0L, windows[0].startMs)
        assertEquals(30_000L, windows[0].endMs)
        assertEquals(25_000L, windows[1].startMs)
        assertEquals(30_500L, windows[1].endMs)
        // 尾窗 5.5 s > 5 s 重叠，说明它确实带了新内容
        assertTrue(windows[1].durationMs > AsrPipeline.DEFAULT_OVERLAP_MS)
    }

    @Test
    fun planWindows_handlesNonDivisibleDuration() {
        val total = 61_234L
        val windows = AsrPipeline.planWindows(total)
        assertEquals(3, windows.size)
        assertEquals(listOf(0L, 25_000L, 50_000L), windows.map { it.startMs })
        assertEquals(total, windows.last().endMs)
        // 采样偏移与非整除时长严格对应
        windows.forEach { window ->
            assertEquals(window.startMs * 16, window.sampleOffset)
            assertEquals(((window.endMs - window.startMs) * 16).toInt(), window.sampleCount)
        }
        assertTrue(windows.last().durationMs > 0L)
        assertTrue(windows.last().endMs <= total)
    }

    @Test
    fun planWindows_coversTwoHourVideoWithoutGaps() {
        val total = 2L * 60 * 60 * 1000
        val windows = AsrPipeline.planWindows(total)
        assertEquals(288, windows.size)
        assertEquals(0L, windows.first().startMs)
        assertEquals(total, windows.last().endMs)
        windows.forEachIndexed { index, window ->
            assertEquals(index, window.index)
            if (index > 0) {
                // 相邻窗口正好重叠 5 s（步进 = 25 s）
                assertEquals(windows[index - 1].endMs - 5_000L, window.startMs)
            }
            assertTrue(window.durationMs in 1..30_000L)
        }
    }

    @Test
    fun planWindows_rejectsIllegalParameters() {
        assertThrows { AsrPipeline.planWindows(1_000L, windowMs = 0L) }
        assertThrows { AsrPipeline.planWindows(1_000L, windowMs = 5_000L, overlapMs = 5_000L) }
        assertThrows { AsrPipeline.planWindows(1_000L, overlapMs = -1L) }
        assertThrows { AsrPipeline.planWindows(1_000L, sampleRate = 0) }
    }

    @Test
    fun absoluteSegments_shiftsWindowRelativeTimes() {
        val window = AsrWindow(1, 25_000L, 55_000L, 400_000L, 480_000)
        val shifted = AsrPipeline.absoluteSegments(window, listOf(WhisperSegment(1_000L, 3_000L, " 你好")))
        assertEquals(1, shifted.size)
        assertEquals(26_000L, shifted.single().startMs)
        assertEquals(28_000L, shifted.single().endMs)
        assertEquals(" 你好", shifted.single().text)
    }

    // ---------------------------------------------------------------- 重叠合并

    @Test
    fun mergeWindows_keepsSingleWindowAsIs() {
        val window = AsrWindow(0, 0L, 30_000L, 0L, 480_000)
        val merged = AsrPipeline.mergeWindows(
            listOf(RecognizedWindow(window, listOf(WhisperSegment(0L, 2_000L, "第一句")))),
        )
        assertEquals(1, merged.size)
        assertEquals(0L, merged.single().startMs)
        assertEquals(2_000L, merged.single().endMs)
    }

    @Test
    fun mergeWindows_dropsSegmentsFullyInsideFirstHalfOfOverlap() {
        val first = AsrWindow(0, 0L, 30_000L, 0L, 480_000)
        val second = AsrWindow(1, 25_000L, 55_000L, 400_000L, 480_000)
        val merged = AsrPipeline.mergeWindows(
            listOf(
                RecognizedWindow(first, listOf(WhisperSegment(20_000L, 24_000L, "前窗尾部"))),
                // 绝对时间 25_000..26_000，完全落在重叠前半段（< 27_500）→ 丢
                RecognizedWindow(second, listOf(WhisperSegment(0L, 1_000L, "重叠前半段"))),
            ),
        )
        assertEquals(listOf("前窗尾部"), merged.map { it.text })
    }

    @Test
    fun mergeWindows_clipsSegmentCrossingTheCutoff() {
        val first = AsrWindow(0, 0L, 30_000L, 0L, 480_000)
        val second = AsrWindow(1, 25_000L, 55_000L, 400_000L, 480_000)
        val merged = AsrPipeline.mergeWindows(
            listOf(
                RecognizedWindow(first, emptyList()),
                // 绝对 26_000..29_000，跨过 cutoff 27_500 → 起点被裁到 27_500
                RecognizedWindow(second, listOf(WhisperSegment(1_000L, 4_000L, "跨界句"))),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(27_500L, merged.single().startMs)
        assertEquals(29_000L, merged.single().endMs)
    }

    @Test
    fun mergeWindows_removesContentDuplicateInsideOverlap() {
        val first = AsrWindow(0, 0L, 30_000L, 0L, 480_000)
        val second = AsrWindow(1, 25_000L, 55_000L, 400_000L, 480_000)
        val merged = AsrPipeline.mergeWindows(
            listOf(
                RecognizedWindow(first, listOf(WhisperSegment(27_000L, 29_500L, "同一句话"))),
                RecognizedWindow(second, listOf(WhisperSegment(2_500L, 4_500L, " 同一句话 "))),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("同一句话", AsrPipeline.normalizeText(merged.single().text))
    }

    @Test
    fun mergeWindows_keepsSameTextWhenTimesDoNotIntersect() {
        val first = AsrWindow(0, 0L, 30_000L, 0L, 480_000)
        val second = AsrWindow(1, 25_000L, 55_000L, 400_000L, 480_000)
        val merged = AsrPipeline.mergeWindows(
            listOf(
                RecognizedWindow(first, listOf(WhisperSegment(1_000L, 2_000L, "好的"))),
                RecognizedWindow(second, listOf(WhisperSegment(20_000L, 21_000L, "好的"))),
            ),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun mergeWindows_skipsBlankSegments() {
        val window = AsrWindow(0, 0L, 30_000L, 0L, 480_000)
        val merged = AsrPipeline.mergeWindows(
            listOf(
                RecognizedWindow(
                    window,
                    listOf(
                        WhisperSegment(0L, 1_000L, "   "),
                        WhisperSegment(1_000L, 2_000L, "有内容"),
                        WhisperSegment(2_000L, 2_000L, "零长"),
                    ),
                ),
            ),
        )
        assertEquals(listOf("有内容"), merged.map { it.text })
    }

    @Test
    fun mergeWindows_trimsEarlierSegmentWhenTimesOverlap() {
        val first = AsrWindow(0, 0L, 30_000L, 0L, 480_000)
        val second = AsrWindow(1, 25_000L, 55_000L, 400_000L, 480_000)
        val merged = AsrPipeline.mergeWindows(
            listOf(
                RecognizedWindow(first, listOf(WhisperSegment(25_000L, 30_000L, "前段"))),
                RecognizedWindow(second, listOf(WhisperSegment(4_000L, 6_000L, "后段"))),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(25_000L, merged[0].startMs)
        assertEquals(29_000L, merged[0].endMs)
        assertEquals(29_000L, merged[1].startMs)
        assertTrue(merged[0].endMs <= merged[1].startMs)
    }

    @Test
    fun mergeWindows_dropsEarlierSegmentFullySwallowedByLaterOne() {
        val first = AsrWindow(0, 0L, 30_000L, 0L, 480_000)
        val second = AsrWindow(1, 25_000L, 55_000L, 400_000L, 480_000)
        val merged = AsrPipeline.mergeWindows(
            listOf(
                RecognizedWindow(first, listOf(WhisperSegment(27_600L, 27_900L, "被吃掉"))),
                RecognizedWindow(second, listOf(WhisperSegment(2_000L, 9_000L, "后段很长"))),
            ),
        )
        assertEquals(listOf("后段很长"), merged.map { it.text })
        assertEquals(27_500L, merged.single().startMs)
        assertEquals(34_000L, merged.single().endMs)
    }

    @Test
    fun mergeWindows_returnsEmptyForNoWindows() {
        assertTrue(AsrPipeline.mergeWindows(emptyList()).isEmpty())
    }

    @Test
    fun mergeWindows_withoutOverlapKeepsEverythingOrdered() {
        val window = AsrWindow(0, 0L, 30_000L, 0L, 480_000)
        val merged = AsrPipeline.mergeWindows(
            listOf(
                RecognizedWindow(
                    window,
                    listOf(
                        WhisperSegment(5_000L, 6_000L, "第二句"),
                        WhisperSegment(1_000L, 2_000L, "第一句"),
                    ),
                ),
            ),
            overlapMs = 0L,
        )
        assertEquals(listOf("第一句", "第二句"), merged.map { it.text })
    }

    // ---------------------------------------------------------------- Cue 生成

    @Test
    fun toCues_convertsSegmentsKeepingMillisecondTimeline() {
        val cues = AsrPipeline.toCues(
            listOf(AsrSegment(1_500L, 3_000L, " 你好世界 "), AsrSegment(3_000L, 4_200L, "第二句")),
        )
        assertEquals(2, cues.size)
        assertEquals(1_500L, cues[0].startMs)
        assertEquals(3_000L, cues[0].endMs)
        assertEquals("你好世界", cues[0].text)
        assertEquals(3_000L, cues[1].startMs)
        assertEquals(4_200L, cues[1].endMs)
    }

    @Test
    fun toCues_dropsBlankAndKeepsMonotonic() {
        val cues = AsrPipeline.toCues(
            listOf(
                AsrSegment(0L, 1_000L, "   "),
                AsrSegment(2_000L, 3_000L, "有效"),
                AsrSegment(2_500L, 3_500L, "交叠"),
            ),
        )
        assertEquals(listOf("有效", "交叠"), cues.map { it.text })
        assertTrue(cues[0].endMs <= cues[1].startMs)
    }

    @Test
    fun toCues_splitsOverlongTextByCharacterCount() {
        val text = "一二三四五六七八九十".repeat(6) // 60 个字符
        val cues = AsrPipeline.toCues(
            listOf(AsrSegment(0L, 6_000L, text)),
            AsrCueOptions(maxCharsPerCue = 20, maxDurationMs = 7_000L),
        )
        assertEquals(3, cues.size)
        assertEquals(text, cues.joinToString("") { it.text })
        assertTrue(cues.all { it.text.length <= 20 })
        assertEquals(0L, cues.first().startMs)
        assertEquals(6_000L, cues.last().endMs)
        for (index in 1 until cues.size) {
            assertEquals(cues[index - 1].endMs, cues[index].startMs)
        }
    }

    @Test
    fun toCues_splitsOverlongDurationEvenWhenTextIsShort() {
        val cues = AsrPipeline.toCues(
            listOf(AsrSegment(0L, 21_000L, "短文本")),
            AsrCueOptions(maxCharsPerCue = 42, maxDurationMs = 7_000L),
        )
        assertEquals(3, cues.size)
        assertTrue(cues.all { it.endMs - it.startMs <= 7_000L })
        assertEquals(21_000L, cues.last().endMs)
    }

    @Test
    fun toCues_givesFallbackDurationForInvertedTimestamps() {
        val cues = AsrPipeline.toCues(
            listOf(AsrSegment(5_000L, 5_000L, "零长但有字")),
            AsrCueOptions(minDurationMs = 200L),
        )
        assertEquals(1, cues.size)
        assertEquals(5_000L, cues.single().startMs)
        assertEquals(5_200L, cues.single().endMs)
    }

    @Test
    fun toCues_clampsNegativeStartToZero() {
        val cues = AsrPipeline.toCues(listOf(AsrSegment(-500L, 1_000L, "负起点")))
        assertEquals(1, cues.size)
        assertEquals(0L, cues.single().startMs)
    }

    @Test
    fun toCues_rejectsIllegalOptions() {
        assertThrows { AsrPipeline.toCues(emptyList(), AsrCueOptions(maxCharsPerCue = 0)) }
        assertThrows { AsrPipeline.toCues(emptyList(), AsrCueOptions(maxDurationMs = 0L)) }
    }

    // ---------------------------------------------------------------- 取消与部分结果

    @Test
    fun partialCues_keepsRecognizedWindowsOnly() {
        val first = AsrWindow(0, 0L, 30_000L, 0L, 480_000)
        val second = AsrWindow(1, 25_000L, 55_000L, 400_000L, 480_000)
        val partial = AsrPipeline.partialCues(
            listOf(RecognizedWindow(first, listOf(WhisperSegment(1_000L, 3_000L, "只跑完第一窗")))),
        )
        assertEquals(1, partial.size)
        assertEquals("只跑完第一窗", partial.single().text)
        // 第二窗没跑完 → 不参与合并，也就没有它的内容
        assertFalse(partial.any { it.text.contains("第二窗") })

        val full = AsrPipeline.partialCues(
            listOf(
                RecognizedWindow(first, listOf(WhisperSegment(1_000L, 3_000L, "只跑完第一窗"))),
                RecognizedWindow(second, listOf(WhisperSegment(40_000L, 42_000L, "第二窗内容"))),
            ),
        )
        assertEquals(2, full.size)
    }

    @Test
    fun partialCues_returnsEmptyWhenNothingRecognized() {
        assertTrue(AsrPipeline.partialCues(emptyList()).isEmpty())
    }

    // ---------------------------------------------------------------- 进度

    @Test
    fun progressOf_computesFractionAndPercent() {
        val progress = AsrPipeline.progressOf(15_000L, 60_000L)
        assertEquals(0.25f, progress.fraction, 0.0001f)
        assertEquals(25, progress.percent)
        assertFalse(progress.isComplete)
    }

    @Test
    fun progressOf_clampsAndHandlesUnknownTotal() {
        assertEquals(1.0f, AsrPipeline.progressOf(90_000L, 60_000L).fraction, 0.0001f)
        assertEquals(0.0f, AsrPipeline.progressOf(-5L, 60_000L).fraction, 0.0001f)
        assertEquals(0.0f, AsrPipeline.progressOf(5_000L, 0L).fraction, 0.0001f)
        assertEquals(0, AsrPipeline.progressOf(5_000L, 0L).percent)
        assertTrue(AsrPipeline.progressOf(60_000L, 60_000L).isComplete)
    }

    @Test
    fun progressOf_emptyIsZero() {
        assertEquals(0.0f, AsrProgress.EMPTY.fraction, 0.0001f)
        assertFalse(AsrProgress.EMPTY.isComplete)
    }

    // ---------------------------------------------------------------- 文本规范化

    @Test
    fun normalizeText_collapsesWhitespace() {
        assertEquals("你好 世界", AsrPipeline.normalizeText("  你好\n\t世界  "))
        assertTrue(AsrPipeline.sameText("你好", " 你好 "))
        assertFalse(AsrPipeline.sameText("你好", "你好吗"))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("期望抛 IllegalArgumentException，但没有")
        } catch (expected: IllegalArgumentException) {
            // 符合预期
        }
    }
}
