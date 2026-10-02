
package io.github.gua123.mediagate.data.storage.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分段缓存可调参数（**2026-10-03 用户要求把并发数开放到设置里**）。
 *
 * 重点是"坏值不许传到下载路径上"：越界/陌生值一律退回默认。
 */
class CacheTuningTest {

    @Test
    fun `默认值就是现在的生产口径`() {
        val t = CacheTuning()
        assertEquals(4L * 1024 * 1024, t.segmentBytes)
        assertEquals(1L * 1024 * 1024, t.chunkBytes)
        assertEquals(4, t.parallelChunks)
        assertEquals(2, t.readAheadSegments)
    }

    @Test
    fun `并发数与预读越界会被夹回`() {
        assertEquals(8, CacheTuning(parallelChunks = 99).normalized().parallelChunks)
        assertEquals(1, CacheTuning(parallelChunks = 0).normalized().parallelChunks)
        assertEquals(1, CacheTuning(parallelChunks = -3).normalized().parallelChunks)
        assertEquals(4, CacheTuning(readAheadSegments = 99).normalized().readAheadSegments)
        assertEquals(0, CacheTuning(readAheadSegments = -1).normalized().readAheadSegments)
    }

    @Test
    fun `段大小与块大小只接受备选值`() {
        assertEquals(8L * 1024 * 1024, CacheTuning(segmentBytes = 8L * 1024 * 1024).normalized().segmentBytes)
        assertEquals(4L * 1024 * 1024, CacheTuning(segmentBytes = 3L * 1024 * 1024).normalized().segmentBytes)
        assertEquals(512L * 1024, CacheTuning(chunkBytes = 512L * 1024).normalized().chunkBytes)
        assertEquals(1L * 1024 * 1024, CacheTuning(chunkBytes = 7L).normalized().chunkBytes)
    }

    @Test
    fun `从持久化值还原（存的是 MB 与 KB 与个数）`() {
        val t = CacheTuning.fromStored(segmentMb = 8, chunkKb = 512, parallelChunks = 6, readAheadSegments = 3)
        assertEquals(8L * 1024 * 1024, t.segmentBytes)
        assertEquals(512L * 1024, t.chunkBytes)
        assertEquals(6, t.parallelChunks)
        assertEquals(3, t.readAheadSegments)
    }

    @Test
    fun `没存过就整套默认（旧版本升级上来的情形）`() {
        assertEquals(CacheTuning(), CacheTuning.fromStored())
    }

    @Test
    fun `存了陌生值：尺寸退回默认，个数被夹到上限`() {
        val t = CacheTuning.fromStored(segmentMb = 999, chunkKb = 999, parallelChunks = 999, readAheadSegments = 999)
        assertEquals("段大小只认备选值，陌生值退回默认", CacheTuning().segmentBytes, t.segmentBytes)
        assertEquals("块大小同理", CacheTuning().chunkBytes, t.chunkBytes)
        assertEquals("个数类参数是夹回区间而不是退回默认", CacheTuning.MAX_PARALLEL_CHUNKS, t.parallelChunks)
        assertEquals(CacheTuning.MAX_READ_AHEAD, t.readAheadSegments)
    }
}
