package io.github.gua123.mediagate.data.storage.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 预取目标换算：尾段 + 无索引时的落点估算。 */
class PrefetchTargetsTest {

    @Test
    fun 尾段偏移取最后一段() {
        assertEquals(96L, PrefetchTargets.tailOffset(size = 100L, segmentBytes = 4L, headBytes = 8L))
    }

    @Test
    fun 文件太小就不预取尾段() {
        assertNull(PrefetchTargets.tailOffset(size = 10L, segmentBytes = 4L, headBytes = 8L))
        assertNull(PrefetchTargets.tailOffset(size = 0L, segmentBytes = 4L, headBytes = 8L))
    }

    @Test
    fun 按位置与时长估算偏移() {
        // 看到一半：1000 字节的文件 ⇒ 偏移 500
        assertEquals(500L, PrefetchTargets.offsetForPosition(50L, 100L, 1000L))
        assertEquals(0L, PrefetchTargets.offsetForPosition(0L, 100L, 1000L).let { it ?: 0L })
    }

    @Test
    fun 参数不可用时不猜偏移() {
        assertNull(PrefetchTargets.offsetForPosition(0L, 100L, 1000L))
        assertNull(PrefetchTargets.offsetForPosition(50L, 0L, 1000L))
        assertNull(PrefetchTargets.offsetForPosition(50L, 100L, 0L))
    }

    @Test
    fun 位置超过时长时夹到文件末尾之前() {
        assertEquals(999L, PrefetchTargets.offsetForPosition(200L, 100L, 1000L))
    }
}
