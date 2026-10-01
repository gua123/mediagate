package io.github.gua123.mediagate.core.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判据来自 2026-10-03 的真机面包屑（用户 07:48 复现"点内核闪退"）。
 */
class VlcCrashHeuristicTest {

    private val crashed = listOf(
        "10-02 07:46:15.141 应用启动（versionName 0.1.19）",
        "10-02 07:47:51.862 开始播放：home/nas1/tg_down/akari_asmr/#Akari 2024.11.9.mp4（内核 Media3）",
        "10-02 07:48:01.437 切换内核：→ LibVLC（已释放旧内核）",
        "10-02 07:48:03.320 应用启动（versionName 0.1.19）",
    )

    @Test
    fun `真机那串面包屑判为崩过`() {
        assertTrue(VlcCrashHeuristic.vlcSwitchLooksCrashed(crashed))
    }

    @Test
    fun `切成功过就不算崩`() {
        val ok = listOf(
            "10-02 07:48:03.320 应用启动（versionName 0.1.19）",
            "10-02 07:49:10.000 切换内核：→ LibVLC（已释放旧内核）",
            "10-02 07:49:11.100 切换内核：新内核已创建 LibVLC",
            "10-02 07:49:11.300 切换内核：现场恢复完成",
            "10-02 07:49:11.320 切换内核：完成 LibVLC",
            "10-02 07:50:00.000 应用启动（versionName 0.1.19）",
        )
        assertFalse(VlcCrashHeuristic.vlcSwitchLooksCrashed(ok))
    }

    @Test
    fun `切到一半用户就退出、但下一次启动里又没完成新内核创建也算崩`() {
        val half = listOf(
            "10-02 07:48:01.437 切换内核：→ LibVLC（已释放旧内核）",
            "10-02 07:48:03.320 应用启动（versionName 0.1.19）",
        )
        assertTrue(VlcCrashHeuristic.vlcSwitchLooksCrashed(half))
    }

    @Test
    fun `还在同一次运行里（没有新的应用启动）不算崩`() {
        val inFlight = listOf(
            "10-02 07:48:03.320 应用启动（versionName 0.1.19）",
            "10-02 07:48:05.000 切换内核：→ LibVLC（已释放旧内核）",
        )
        assertFalse(VlcCrashHeuristic.vlcSwitchLooksCrashed(inFlight))
    }

    @Test
    fun `从没切过 LibVLC 不算崩`() {
        assertFalse(VlcCrashHeuristic.vlcSwitchLooksCrashed(listOf("10-02 07:46:15.141 应用启动（versionName 0.1.19）")))
        assertFalse(VlcCrashHeuristic.vlcSwitchLooksCrashed(emptyList()))
    }
}
