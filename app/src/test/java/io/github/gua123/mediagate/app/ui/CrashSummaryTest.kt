package io.github.gua123.mediagate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 诊断卡片的首行摘要（从报告里抽异常那一行）。 */
class CrashSummaryTest {

    @Test
    fun `抽得出异常那一行`() {
        val report = listOf(
            "== mediagate 崩溃报告 ==",
            "版本：0.1.8",
            "",
            "== 异常 ==",
            "java.lang.IllegalStateException: 播放器炸了",
            "\tat io.github...(Unknown Source)",
        ).joinToString("\n")

        assertEquals("java.lang.IllegalStateException: 播放器炸了", crashSummary(report))
    }

    @Test
    fun `格式不对时退回首行而不是崩`() {
        assertEquals("随便一段文本", crashSummary("随便一段文本"))
        assertEquals("（报告为空）", crashSummary(""))
    }
}
