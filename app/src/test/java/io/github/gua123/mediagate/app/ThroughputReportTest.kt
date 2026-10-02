package io.github.gua123.mediagate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测速结果的换算与文案（**2026-10-03 用户要求把并发数开放到设置里**之后，
 * 需要一把"走真实路径的尺子"来验证调参有没有用）。
 */
class ThroughputReportTest {

    @Test
    fun 速度换算_8MB用4秒就是2MB每秒() {
        val report = ThroughputReport(bytes = 8L * 1024 * 1024, millis = 4000)
        assertEquals(2.0, report.mbPerSecond, 0.01)
    }

    @Test
    fun 耗时为0时不除零() {
        assertEquals(0.0, ThroughputReport(bytes = 1024, millis = 0).mbPerSecond, 0.0)
    }

    @Test
    fun 成功文案里要有速度与当前设置() {
        val text = formatThroughput(
            report = ThroughputReport(bytes = 4L * 1024 * 1024, millis = 2000),
            path = "/video/a.mp4",
            parallelChunks = 8,
            chunkBytes = 512L * 1024,
            readAheadSegments = 0,
        )
        assertTrue(text, text.contains("2.00 MB/s"))
        assertTrue(text, text.contains("并发 8"))
        assertTrue(text, text.contains("512 KB"))
        assertTrue(text, text.contains("/video/a.mp4"))
    }

    @Test
    fun 失败文案要写清原因与当前设置() {
        val text = formatThroughput(
            report = ThroughputReport(bytes = 0, millis = 300, error = "连接超时"),
            path = "/video/a.mp4",
            parallelChunks = 4,
            chunkBytes = 1024L * 1024,
            readAheadSegments = 2,
        )
        assertTrue(text, text.contains("测速失败：连接超时"))
        assertTrue(text, text.contains("预读 2 段"))
    }

    @Test
    fun 字节换算() {
        assertEquals("512 B", humanBytes(512))
        assertEquals("1 KB", humanBytes(1024))
        assertEquals("1.0 MB", humanBytes(1024L * 1024))
        assertEquals("8.0 MB", humanBytes(8L * 1024 * 1024))
    }
}
