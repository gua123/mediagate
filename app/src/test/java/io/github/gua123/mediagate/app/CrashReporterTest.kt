package io.github.gua123.mediagate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃报告的渲染（**2026-10-03** 真机"一播 MP4 就闪退"之后补的）。
 *
 * 报告是唯一能定位真机崩溃的东西，所以格式要稳：版本、设备、异常、出事前的日志，一个都不能少。
 */
class CrashReporterTest {

    private val device = DeviceInfo(
        versionName = "0.1.8",
        versionCode = 9L,
        packageName = "io.github.gua123.mediagate",
        manufacturer = "Xiaomi",
        model = "23127PN0CC",
        release = "16",
        sdkInt = 36,
    )

    @Test
    fun `报告里要有版本设备异常与出事前的日志`() {
        val error = IllegalStateException("播放器炸了")
        val report = CrashReporter.render(
            device = device,
            thread = Thread.currentThread(),
            throwable = error,
            logLines = listOf("01-01 00:00:00.000 I/player-video 开始播放", "01-01 00:00:00.100 E/player-video 打开失败"),
            nowMs = 1_700_000_000_000L,
        )

        assertTrue(report.contains("0.1.8"))
        assertTrue(report.contains("versionCode 9"))
        assertTrue(report.contains("Xiaomi 23127PN0CC"))
        assertTrue(report.contains("Android 16（SDK 36）"))
        assertTrue(report.contains("IllegalStateException: 播放器炸了"))
        assertTrue("要有堆栈", report.contains("CrashReporterTest"))
        assertTrue(report.contains("开始播放"))
        assertTrue("日志是旧到新", report.indexOf("开始播放") < report.indexOf("打开失败"))
    }

    @Test
    fun `没有日志时如实说明（可能是崩在日志就绪之前）`() {
        val report = CrashReporter.render(device, Thread.currentThread(), RuntimeException("早崩"), emptyList())
        assertTrue(report.contains("没有日志"))
    }
}
