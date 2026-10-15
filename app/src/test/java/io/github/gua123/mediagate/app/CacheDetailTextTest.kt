package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.data.storage.api.SegmentedCacheBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存明细的文案（**纯函数**）。
 *
 * 2026-10-15 用户要求：「关于缓存部分，是否可以增加一个可视化界面，告诉我哪些缓存过，哪些没有」——
 * 这一页要能让用户一眼看清三件事：**是哪个文件**、**缓存了多少**、**缓存到什么程度**。
 * 总大小未知时**不许瞎算百分比**（宁可显示「总大小未知」）。
 */
class CacheDetailTextTest {

    private fun entry(
        path: String? = "home/nas1/电影/a.mp4",
        bytes: Long = 8L * 1024 * 1024,
        segments: Int = 2,
        total: Long? = 100L * 1024 * 1024,
    ) = SegmentedCacheBackend.CachedFileEntry("dir-hash", path, bytes, segments, total)

    @Test
    fun 一条记录要说清缓存了多少与占比() {
        val line = cacheEntryLine(entry())
        assertTrue(line, line.contains("已缓存 8.0 MB"))
        assertTrue(line, line.contains("2 段"))
        assertTrue(line, line.contains("占该文件 8%"))
    }

    @Test
    fun 总大小未知时不显示百分比() {
        val line = cacheEntryLine(entry(total = null))
        assertTrue(line, line.contains("总大小未知"))
        assertTrue("不许瞎算百分比", !line.contains("%"))
    }

    @Test
    fun 名称未知的老缓存也要能显示与统计() {
        val legacy = entry(path = null, bytes = 4L * 1024 * 1024, segments = 1, total = null)
        assertEquals("（旧版本缓存，名称未知）", legacy.displayName)
        val summary = cacheSummaryLine(listOf(entry(), legacy))
        assertTrue(summary, summary.contains("2 个文件"))
        assertTrue(summary, summary.contains("旧版本缓存"))
    }

    @Test
    fun 空缓存与合计文案() {
        assertEquals("还没有缓存过任何文件", cacheSummaryLine(emptyList()))
        val summary = cacheSummaryLine(listOf(entry(bytes = 100L * 1024 * 1024, total = null)))
        assertTrue(summary, summary.contains("1 个文件"))
        assertTrue(summary, summary.contains("100.0 MB"))
    }

    @Test
    fun 字节数按量级换单位() {
        assertEquals("512 B", cacheMegabytes(512))
        assertEquals("2 KB", cacheMegabytes(2048))
        assertEquals("1.5 MB", cacheMegabytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.00 GB", cacheMegabytes(2L * 1024 * 1024 * 1024))
    }
}
