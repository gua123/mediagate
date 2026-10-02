package io.github.gua123.mediagate.data.storage.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分段下载的拆块逻辑（**2026-10-03 用户问「缓冲能不能多线程」**）。
 *
 * 这里只测"拆得对不对"——真正并发由 SegmentedCacheBackend 用信号量限制。
 */
class SegmentChunksTest {

    @Test
    fun `4MB 段按 1MB 拆成四块且首尾相接`() {
        val chunks = chunkRanges(start = 8L * 1024 * 1024, length = 4L * 1024 * 1024, chunkBytes = 1024 * 1024)
        assertEquals(4, chunks.size)
        assertEquals(8L * 1024 * 1024, chunks.first().offset)
        chunks.zipWithNext { a, b -> assertEquals("块必须首尾相接", a.offset + a.length, b.offset) }
        assertEquals(listOf(1024L, 1024L, 1024L, 1024L) .map { it * 1024 }, chunks.map { it.length })
    }

    @Test
    fun `最后一块可以是短的`() {
        val chunks = chunkRanges(start = 0L, length = 2_500L, chunkBytes = 1_000L)
        assertEquals(listOf(1_000L, 1_000L, 500L), chunks.map { it.length })
        assertEquals(listOf(0L, 1_000L, 2_000L), chunks.map { it.offset })
    }

    @Test
    fun `段比块小的时候只拆一块`() {
        val chunks = chunkRanges(start = 100L, length = 300L, chunkBytes = 1024L)
        assertEquals(listOf(ByteChunk(100L, 300L)), chunks)
    }

    @Test
    fun `长度为 0 或负数时不拆（调用方不该拿它去发请求）`() {
        assertEquals(emptyList<ByteChunk>(), chunkRanges(0L, 0L, 1024L))
        assertEquals(emptyList<ByteChunk>(), chunkRanges(0L, -5L, 1024L))
    }

    @Test
    fun `块大小为 0 时按 1 字节兜底，不会死循环`() {
        val chunks = chunkRanges(start = 0L, length = 3L, chunkBytes = 0L)
        assertEquals(3, chunks.size)
        assertEquals(3L, chunks.sumOf { it.length })
    }
}
