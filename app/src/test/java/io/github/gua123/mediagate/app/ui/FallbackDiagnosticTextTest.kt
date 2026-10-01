package io.github.gua123.mediagate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 没有崩溃报告时的兜底诊断文本（**2026-10-03 用户反馈"没有捕捉到崩溃日志"**）。
 *
 * 原生崩溃/被系统杀掉时 Java 处理器不会跑，但"上次退出原因 + 面包屑"还在，
 * 这段文本就是让用户能一键复制发出来的那份东西。
 */
class FallbackDiagnosticTextTest {

    @Test
    fun `两者都没有时不给文本（界面也没什么可给的）`() {
        assertNull(fallbackDiagnosticText(null, emptyList()))
    }

    @Test
    fun `只有退出原因也能成文`() {
        val text = fallbackDiagnosticText("内存不足被系统杀掉 · 10-02 07:13", emptyList())
        assertTrue(text!!.contains("内存不足被系统杀掉"))
        assertTrue(text.contains("（没有面包屑）"))
    }

    @Test
    fun `退出原因与面包屑都在时要都带上（顺序保持旧到新）`() {
        val crumbs = listOf("10-02 07:11 应用启动", "10-02 07:13 切换内核：→ LibVLC（已释放旧内核）")
        val text = fallbackDiagnosticText("原生崩溃（native crash…） · 10-02 07:13", crumbs)!!
        assertTrue(text.contains("原生崩溃"))
        assertTrue(text.indexOf("应用启动") < text.indexOf("切换内核"))
    }
}
