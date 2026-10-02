package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 横滑调进度的换算（2026-10-03 用户要求：不弹控制也能左右滑动调进度）。
 */
class SeekGestureMathTest {

    private val hour = 60 * 60 * 1_000L

    @Test
    fun `整屏宽横滑大致是设定跨度`() {
        val duration = 2 * hour
        // 长视频：整屏宽 = FULL_WIDTH_SPAN_MS（3 分钟）
        assertEquals(3 * 60 * 1_000L, SeekGestureMath.spanFor(duration))
        assertEquals(0L, SeekGestureMath.targetMs(0L, -1f, duration))
        assertEquals(3 * 60 * 1_000L, SeekGestureMath.targetMs(0L, 1f, duration))
    }

    @Test
    fun `短视频按半片封顶，避免一划到头`() {
        val duration = 60_000L
        // 60 秒的片子：整屏宽 = 30 秒
        assertEquals(30_000L, SeekGestureMath.spanFor(duration))
        assertEquals(duration, SeekGestureMath.targetMs(45_000L, 1f, duration))
    }

    @Test
    fun `结果夹在 0 到时长之间`() {
        val duration = 10 * 60 * 1_000L
        assertEquals(0L, SeekGestureMath.targetMs(10_000L, -1f, duration))
        assertEquals(duration, SeekGestureMath.targetMs(duration - 1_000L, 1f, duration))
    }

    @Test
    fun `时长未知时不动（避免乱跳）`() {
        assertEquals(12_345L, SeekGestureMath.targetMs(12_345L, 0.5f, 0L))
    }

    @Test
    fun `位移文案带符号与零填充`() {
        assertEquals("+01:23", SeekGestureMath.deltaLabel(83_000L))
        assertEquals("-00:45", SeekGestureMath.deltaLabel(-45_000L))
        assertEquals("+00:00", SeekGestureMath.deltaLabel(0L))
    }
}
