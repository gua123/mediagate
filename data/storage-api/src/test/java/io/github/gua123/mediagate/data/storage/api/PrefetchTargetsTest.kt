package io.github.gua123.mediagate.data.storage.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预取目标换算：尾段 + 无索引时的落点估算。 */
class PrefetchTargetsTest {

    // ------------------------------------------------ 打开前的分级预取计划（2026-10-15 大文件优化）

    @Test
    fun 小文件只抓首段与尾段() {
        val segment = 4L * 1024 * 1024
        val size = 200L * 1024 * 1024  // 200 MB
        val plan = PrefetchTargets.openPlan(size, segment)
        assertEquals("首段 + 尾段", 2, plan.size)
        assertEquals(0L, plan[0].offset)
        assertEquals(segment, plan[0].length)
        assertEquals("尾段取最后一段", size - segment, plan[1].offset)
    }

    @Test
    fun 大文件要多抓两段开头() {
        val segment = 4L * 1024 * 1024
        val size = 2L * 1024 * 1024 * 1024  // 2 GiB
        val plan = PrefetchTargets.openPlan(size, segment)
        assertEquals("首段（加长） + 尾段", 2, plan.size)
        assertEquals(0L, plan[0].offset)
        assertEquals(
            "≥1 GiB 的文件头部抓 3 段（索引与首个关键帧常不在第一段里）",
            segment * PrefetchTargets.BIG_FILE_HEAD_SEGMENTS,
            plan[0].length,
        )
        assertEquals(size - segment, plan[1].offset)
    }

    @Test
    fun 有续播落点时要把它也算进去() {
        val segment = 4L * 1024 * 1024
        val size = 2L * 1024 * 1024 * 1024
        val plan = PrefetchTargets.openPlan(size, segment, positionMs = 600_000L, durationMs = 1_200_000L)
        assertEquals("首段 + 尾段 + 落点", 3, plan.size)
        val resume = plan.last()
        assertEquals("落点 = 位置/时长 × 大小", size / 2, resume.offset)
        assertEquals(segment, resume.length)
    }

    @Test
    fun 落点与首尾重叠时不重复发请求() {
        val segment = 4L * 1024 * 1024
        val size = 100L * segment
        // 落点算出来刚好在首段里 ⇒ 只保留首段与尾段
        val plan = PrefetchTargets.openPlan(size, segment, positionMs = 1L, durationMs = size.toDouble().toLong())
        assertTrue("不该出现重叠区间：" + plan, plan.size <= 2)
        // 落点落在尾段里也一样
        val tailPlan = PrefetchTargets.openPlan(size, segment, positionMs = size - 1, durationMs = size)
        assertTrue("不该出现重叠区间：" + tailPlan, tailPlan.size <= 2)
    }

    @Test
    fun 大小未知时什么都不预取() {
        assertTrue(PrefetchTargets.openPlan(null, 4L * 1024 * 1024).isEmpty())
        assertTrue(PrefetchTargets.openPlan(0L, 4L * 1024 * 1024).isEmpty())
        assertTrue("段大小为 0 是配置错，也不许瞎猜", PrefetchTargets.openPlan(1000L, 0L).isEmpty())
    }

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
