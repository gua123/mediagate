package io.github.gua123.mediagate.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** [AdaptiveReadAhead] 的纯逻辑单测：只收缩、不越权，seek 时先少抓。 */
class AdaptiveReadAheadTest {

    @Test
    fun `没有吞吐样本时保持用户上限（旧行为）`() {
        assertEquals(4, AdaptiveReadAhead.segments(0L, seekedRecently = false, capSegments = 4))
        assertEquals(2, AdaptiveReadAhead.segments(0L, seekedRecently = false, capSegments = 2))
    }

    @Test
    fun `弱网只抓一段`() {
        assertEquals(1, AdaptiveReadAhead.segments(100L * 1024L, seekedRecently = false, capSegments = 4))
    }

    @Test
    fun `中等吞吐抓两段`() {
        assertEquals(2, AdaptiveReadAhead.segments(1L * 1024L * 1024L, seekedRecently = false, capSegments = 4))
    }

    @Test
    fun `较快吞吐抓三段，很快才用满上限`() {
        assertEquals(3, AdaptiveReadAhead.segments(4L * 1024L * 1024L, seekedRecently = false, capSegments = 4))
        assertEquals(4, AdaptiveReadAhead.segments(16L * 1024L * 1024L, seekedRecently = false, capSegments = 4))
    }

    @Test
    fun `刚 seek 过先抓两段`() {
        assertEquals(2, AdaptiveReadAhead.segments(16L * 1024L * 1024L, seekedRecently = true, capSegments = 4))
        assertEquals(1, AdaptiveReadAhead.segments(16L * 1024L * 1024L, seekedRecently = true, capSegments = 1))
    }

    @Test
    fun `关闭预读或异常上限不抓`() {
        assertEquals(0, AdaptiveReadAhead.segments(16L * 1024L * 1024L, seekedRecently = false, capSegments = 0))
        assertEquals(0, AdaptiveReadAhead.segments(16L * 1024L * 1024L, seekedRecently = false, capSegments = -1))
    }

    @Test
    fun `上限小于阈值时不被放大`() {
        assertEquals(1, AdaptiveReadAhead.segments(16L * 1024L * 1024L, seekedRecently = false, capSegments = 1))
        assertEquals(2, AdaptiveReadAhead.segments(16L * 1024L * 1024L, seekedRecently = false, capSegments = 2))
    }

    @Test
    fun `负吞吐按未知处理`() {
        assertEquals(4, AdaptiveReadAhead.segments(-1L, seekedRecently = false, capSegments = 4))
    }
}
