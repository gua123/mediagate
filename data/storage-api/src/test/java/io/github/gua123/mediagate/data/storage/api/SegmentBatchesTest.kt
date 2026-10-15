package io.github.gua123.mediagate.data.storage.api

import org.junit.Assert.assertEquals
import org.junit.Test

/** 预取分批（2026-10-14 用户要求"单文件多线程"）。 */
class SegmentBatchesTest {

    @Test
    fun 按并发数切批_末批可以不满() {
        assertEquals(listOf(listOf(0L, 1L, 2L, 3L), listOf(4L, 5L)), SegmentBatches.batch(0, 5, concurrency = 4))
    }

    @Test
    fun 并发数为一时逐段串行() {
        assertEquals(listOf(listOf(2L), listOf(3L)), SegmentBatches.batch(2, 3, concurrency = 1))
    }

    @Test
    fun 非法区间与零并发都要安全() {
        assertEquals(emptyList<List<Long>>(), SegmentBatches.batch(5, 4, concurrency = 4))
        // 并发 0 兜底成 1，不能死循环也不能空转
        assertEquals(listOf(listOf(0L), listOf(1L)), SegmentBatches.batch(0, 1, concurrency = 0))
    }
}
