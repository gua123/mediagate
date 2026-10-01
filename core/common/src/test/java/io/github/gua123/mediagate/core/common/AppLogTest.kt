package io.github.gua123.mediagate.core.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppLog] 的环形缓冲（**2026-10-03** 崩溃报告要用它带出"出事前的现场"）。
 *
 * 钉住三件事：只留最近 N 行、顺序是旧→新、超长行会被截断（防止一条巨长的日志把内存吃光）。
 */
class AppLogTest {

    @After
    fun tearDown() {
        AppLog.clearBuffer()
    }

    @Test
    fun `只保留最近若干行且顺序是旧到新`() {
        repeat(AppLog.RING_CAPACITY + 50) { index ->
            AppLog.i("test", "line-" + index)
        }

        val snapshot = AppLog.snapshot()
        assertEquals(AppLog.RING_CAPACITY, snapshot.size)
        assertTrue("最早的应当已被挤掉：" + snapshot.first(), snapshot.first().endsWith("line-50"))
        assertTrue("最新的在最后：" + snapshot.last(), snapshot.last().endsWith("line-" + (AppLog.RING_CAPACITY + 49)))
    }

    @Test
    fun `多行日志（带堆栈）会按行拆开`() {
        AppLog.e("test", "boom", IllegalStateException("坏了"))

        val snapshot = AppLog.snapshot()
        assertTrue("第一行是消息本身：" + snapshot.first(), snapshot.first().contains("boom"))
        assertTrue("后面应当有堆栈行", snapshot.size > 1)
        assertTrue(snapshot.any { it.contains("IllegalStateException") })
    }

    @Test
    fun `超长行会被截断`() {
        val huge = "x".repeat(AppLog.MAX_LINE_CHARS * 3)
        AppLog.i("test", huge)

        val line = AppLog.snapshot().last()
        assertTrue("截断后不该超过上限：" + line.length, line.length <= AppLog.MAX_LINE_CHARS + 64)
    }

    @Test
    fun `清空之后什么都不剩`() {
        AppLog.i("test", "有内容")
        assertTrue(AppLog.snapshot().isNotEmpty())

        AppLog.clearBuffer()

        assertTrue(AppLog.snapshot().isEmpty())
    }
}
