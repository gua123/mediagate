package io.github.gua123.mediagate.data.storage.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段缓存的可调参数（**2026-10-03 用户口径：把并发去掉、保留单文件缓存**）。
 *
 * 现在只有两项：段大小与预读段数。重点是"坏值不许传到下载路径上"。
 */
class CacheTuningTest {

    @Test
    fun 默认值就是现在的生产口径() {
        val t = CacheTuning()
        assertEquals(4L * 1024 * 1024, t.segmentBytes)
        assertEquals(2, t.readAheadSegments)
    }

    @Test
    fun 预读段数越界会被夹回() {
        assertEquals(4, CacheTuning(readAheadSegments = 99).normalized().readAheadSegments)
        assertEquals(0, CacheTuning(readAheadSegments = -1).normalized().readAheadSegments)
    }

    @Test
    fun 缓存上限按GB且只接受备选值() {
        assertEquals(1L * 1024 * 1024 * 1024, CacheTuning().maxBytes)
        assertEquals(4L * 1024 * 1024 * 1024, CacheTuning(maxBytes = 4L * 1024 * 1024 * 1024).normalized().maxBytes)
        assertEquals("陌生值退回默认", CacheTuning().maxBytes, CacheTuning(maxBytes = 3L * 1024 * 1024 * 1024).normalized().maxBytes)
    }

    @Test
    fun 缓存上限不会小于一个段() {
        // 1 MB 段 + 1 GB 上限没问题；上限被夹到至少一个段
        val t = CacheTuning(segmentBytes = 8L * 1024 * 1024, maxBytes = 1024).normalized()
        assertTrue("上限应至少容纳一个段：${t.maxBytes}", t.maxBytes >= t.segmentBytes)
    }

    @Test
    fun 段大小只接受备选值() {
        assertEquals(8L * 1024 * 1024, CacheTuning(segmentBytes = 8L * 1024 * 1024).normalized().segmentBytes)
        assertEquals(4L * 1024 * 1024, CacheTuning(segmentBytes = 3L * 1024 * 1024).normalized().segmentBytes)
    }

    @Test
    fun 从持久化值还原存的是MB与段数() {
        val t = CacheTuning.fromStored(segmentMb = 8, readAheadSegments = 3)
        assertEquals(8L * 1024 * 1024, t.segmentBytes)
        assertEquals(3, t.readAheadSegments)
    }

    @Test
    fun 没存过或存了陌生值都退回默认() {
        assertEquals(CacheTuning(), CacheTuning.fromStored())
        val odd = CacheTuning.fromStored(segmentMb = 999, readAheadSegments = 999)
        assertEquals(CacheTuning().segmentBytes, odd.segmentBytes)
        assertEquals(CacheTuning.MAX_READ_AHEAD, odd.readAheadSegments)
    }
}
