package io.github.gua123.mediagate.media.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 字幕时间轴微调（R14：±0.5 s 步进、任意毫秒偏移、负值不早于 0、cues 平移与排序）。 */
class SubtitleTimelineTest {

    private val cues = listOf(
        SubtitleCue(1_000L, 2_000L, "A"),
        SubtitleCue(3_000L, 4_000L, "B"),
    )

    @Test
    fun stepAddsHalfASecond() {
        assertEquals(500L, SubtitleTimeline.step(0L, 1))
        assertEquals(1_000L, SubtitleTimeline.step(500L, 1))
        assertEquals(0L, SubtitleTimeline.step(500L, -1))
    }

    @Test
    fun stepSupportsMultipleStepsAndCustomStepSize() {
        assertEquals(1_500L, SubtitleTimeline.step(0L, 3))
        assertEquals(2_000L, SubtitleTimeline.step(0L, 1, stepMs = 2_000L))
        assertEquals(-1_500L, SubtitleTimeline.step(0L, -3))
    }

    @Test
    fun stepClampsAtBothBounds() {
        assertEquals(SubtitleTimeline.MAX_OFFSET_MS, SubtitleTimeline.step(SubtitleTimeline.MAX_OFFSET_MS, 1))
        assertEquals(SubtitleTimeline.MIN_OFFSET_MS, SubtitleTimeline.step(SubtitleTimeline.MIN_OFFSET_MS, -1))
        assertEquals(SubtitleTimeline.MAX_OFFSET_MS, SubtitleTimeline.clampOffset(999_999L))
        assertEquals(SubtitleTimeline.MIN_OFFSET_MS, SubtitleTimeline.clampOffset(-999_999L))
    }

    @Test
    fun formatOffsetSecondsUsesOneDecimal() {
        assertEquals("+0.5", SubtitleTimeline.formatOffsetSeconds(500L))
        assertEquals("-1.5", SubtitleTimeline.formatOffsetSeconds(-1_500L))
        assertEquals("+0.0", SubtitleTimeline.formatOffsetSeconds(0L))
        assertEquals("+60.0", SubtitleTimeline.formatOffsetSeconds(60_000L))
    }

    @Test
    fun shiftMovesCuesLater() {
        val shifted = SubtitleTimeline.shift(cues, 500L)

        assertEquals(listOf(1_500L, 3_500L), shifted.map { it.startMs })
        assertEquals(listOf(2_500L, 4_500L), shifted.map { it.endMs })
        assertEquals(listOf("A", "B"), shifted.map { it.text })
    }

    @Test
    fun shiftClampsNegativeResultsAtZero() {
        val shifted = SubtitleTimeline.shift(cues, -1_500L)

        assertEquals(2, shifted.size)
        assertEquals(0L, shifted[0].startMs)
        assertEquals(500L, shifted[0].endMs)
        assertEquals("A", shifted[0].text)
        assertEquals(1_500L, shifted[1].startMs)
    }

    @Test
    fun shiftDropsCuesPushedBeforeZero() {
        val shifted = SubtitleTimeline.shift(cues, -5_000L)

        assertTrue(shifted.isEmpty())
    }

    @Test
    fun shiftResortsByStartTime() {
        val reversed = listOf(
            SubtitleCue(5_000L, 6_000L, "后"),
            SubtitleCue(1_000L, 2_000L, "前"),
        )

        val shifted = SubtitleTimeline.shift(reversed, 500L)

        assertEquals(listOf("前", "后"), shifted.map { it.text })
        assertEquals(listOf(1_500L, 5_500L), shifted.map { it.startMs })
    }

    @Test
    fun shiftByZeroReturnsTheSameList() {
        assertSame(cues, SubtitleTimeline.shift(cues, 0L))
    }

    @Test
    fun activeCueUsesHalfOpenInterval() {
        val active = SubtitleTimeline.activeCue(cues, 1_000L)

        assertEquals("A", active?.text)
        assertEquals("A", SubtitleTimeline.activeCue(cues, 1_999L)?.text)
        assertNull(SubtitleTimeline.activeCue(cues, 2_000L))
        assertNull(SubtitleTimeline.activeCue(cues, 0L))
        assertNull(SubtitleTimeline.activeCue(emptyList(), 1_000L))
    }

    @Test
    fun activeCueTakesTheLaterOneWhenOverlapping() {
        val overlapping = listOf(
            SubtitleCue(1_000L, 5_000L, "长"),
            SubtitleCue(2_000L, 3_000L, "叠"),
        )

        assertEquals("叠", SubtitleTimeline.activeCue(overlapping, 2_500L)?.text)
        assertEquals("长", SubtitleTimeline.activeCue(overlapping, 4_000L)?.text)
    }

    @Test
    fun shiftedCueNeverStartsBeforeZero() {
        assertEquals(0L, SubtitleCue(100L, 900L, "早").shifted(-5_000L).startMs)
        val cue = SubtitleCue(100L, 900L, "早").shifted(-500L)
        assertEquals(0L, cue.startMs)
        assertEquals(400L, cue.endMs)
        assertEquals(0L, SubtitleCue(0L, 0L, "空").shifted(-1L).startMs)
    }

    @Test
    fun cueHelpersReportDurationAndValidity() {
        assertEquals(1_000L, SubtitleCue(1_000L, 2_000L, "A").durationMs)
        assertEquals(0L, SubtitleCue(2_000L, 1_000L, "A").durationMs)
        assertTrue(SubtitleCue(1_000L, 2_000L, "A").isValid)
        assertTrue(!SubtitleCue(2_000L, 1_000L, "A").isValid)
        assertTrue(!SubtitleCue(1_000L, 2_000L, "  ").isValid)
    }
}
