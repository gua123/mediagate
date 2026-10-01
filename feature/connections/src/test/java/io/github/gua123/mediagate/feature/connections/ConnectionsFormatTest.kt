package io.github.gua123.mediagate.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 展示格式化的 JVM 单测（**R8** 的"最近检测时间"与状态徽标）。
 */
class ConnectionsFormatTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `从未检测`() {
        assertEquals("从未检测", ConnectionsFormat.lastCheckedText(null, now))
        assertEquals("从未检测", ConnectionsFormat.lastCheckedText(0L, now))
    }

    @Test
    fun `刚刚检测`() {
        assertEquals("刚刚检测", ConnectionsFormat.lastCheckedText(now, now))
        assertEquals("刚刚检测", ConnectionsFormat.lastCheckedText(now - 30_000L, now))
    }

    @Test
    fun `分钟与小时粒度`() {
        assertEquals("5 分钟前检测", ConnectionsFormat.lastCheckedText(now - 5L * 60_000L, now))
        assertEquals("3 小时前检测", ConnectionsFormat.lastCheckedText(now - 3L * 3_600_000L, now))
    }

    @Test
    fun `超过一天给绝对时间`() {
        val old = now - 3L * 24L * 3_600_000L
        val text = ConnectionsFormat.lastCheckedText(old, now)
        assertTrue("应当是绝对时间：" + text, text.matches(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }

    @Test
    fun `时钟回拨也不崩`() {
        assertEquals("刚刚检测", ConnectionsFormat.lastCheckedText(now + 5_000L, now))
    }

    @Test
    fun `状态徽标文案`() {
        assertEquals("正常 · 刚刚检测", ConnectionsFormat.badgeText(ConnectionStatus.OK, now, now))
        assertEquals("未测试 · 从未检测", ConnectionsFormat.badgeText(ConnectionStatus.UNTESTED, null, now))
        assertTrue(ConnectionsFormat.badgeText(ConnectionStatus.PARTIAL, now, now).startsWith("部分可用"))
    }
}
