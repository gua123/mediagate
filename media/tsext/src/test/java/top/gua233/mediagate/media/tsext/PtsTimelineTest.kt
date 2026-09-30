package io.github.gua123.mediagate.media.tsext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PtsTimeline] 的 JVM 单测（R3/R4）：33 位回绕、拼接流重置、单调性与快照恢复。
 */
class PtsTimelineTest {

    @Test
    fun `普通递增直接换算`() {
        val timeline = PtsTimeline()
        assertEquals(0L, timeline.map(0L))
        assertEquals(1_000L, timeline.map(90_000L))
        assertEquals(2_000L, timeline.map(180_000L))
        assertEquals(0, timeline.wraps)
        assertEquals(0, timeline.resets)
    }

    @Test
    fun `三十三位回绕后时间继续向前`() {
        val timeline = PtsTimeline()
        val nearMax = PtsTimeline.WRAP_TICKS - 90_000L
        val before = timeline.map(nearMax)
        assertEquals(95_443_717L - 1_000L, before)
        // 回绕到 0：应补偿一整圈，时间不减
        val afterWrap = timeline.map(0L)
        assertTrue("回绕后不应倒退：$afterWrap < $before", afterWrap >= before)
        assertEquals(1, timeline.wraps)
        // 回绕之后继续正常前进
        assertEquals(afterWrap + 1_000L, timeline.map(90_000L))
    }

    @Test
    fun `拼接流重置保持单调`() {
        val timeline = PtsTimeline()
        assertEquals(10_000L, timeline.map(900_000L))
        // 时间戳跳回 1 秒（拼接流）：不倒退，接在上一毫秒之后
        val afterReset = timeline.map(90_000L)
        assertEquals(10_000L, afterReset)
        assertEquals(1, timeline.resets)
        assertEquals(11_000L, timeline.map(180_000L))
    }

    @Test
    fun `重复时间戳不会倒退`() {
        val timeline = PtsTimeline()
        assertEquals(1_000L, timeline.map(90_000L))
        assertEquals(1_000L, timeline.map(90_000L))
        assertEquals(1_000L, timeline.map(90_001L))
        assertEquals(1_000L, timeline.map(90_044L))
        assertEquals(1_001L, timeline.map(90_090L))
    }

    @Test
    fun `快照恢复后映射结果与不中断完全一致`() {
        val sequence = listOf(0L, 90_000L, 180_000L, 270_000L, PtsTimeline.WRAP_TICKS - 90_000L, 0L, 90_000L, 180_000L)
        val uninterrupted = PtsTimeline()
        val expected = sequence.map { uninterrupted.map(it) }

        val firstHalf = PtsTimeline()
        val head = sequence.take(4).map { firstHalf.map(it) }
        val restored = PtsTimeline.restore(firstHalf.snapshot())
        val tail = sequence.drop(4).map { restored.map(it) }

        assertEquals(expected, head + tail)
        assertEquals(uninterrupted.snapshot().lastTicks, restored.snapshot().lastTicks)
        assertEquals(uninterrupted.snapshot().lastMs, restored.snapshot().lastMs)
    }

    @Test
    fun `掩码处理超出三十三位的输入`() {
        val timeline = PtsTimeline()
        assertEquals(0L, timeline.map(PtsTimeline.WRAP_TICKS))
        assertEquals(1_000L, timeline.map(PtsTimeline.WRAP_TICKS + 90_000L))
        assertEquals(90_000L, timeline.lastRawTicks())
    }

    @Test
    fun `初始状态没有上一个时间戳`() {
        val timeline = PtsTimeline()
        assertEquals(null, timeline.lastRawTicks())
        val empty = PtsTimelineState.EMPTY
        assertEquals(false, empty.hasLast)
        assertEquals(0L, PtsTimeline.restore(empty).map(0L))
    }
}
