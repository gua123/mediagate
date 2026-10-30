package io.github.gua123.mediagate.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段大小是内置参数（2026-10-30 用户要求：与 nPlayer 的 64KiB 块表不是同一实现，
 * 4MB 由 SFTP 基准选出，不再允许用户改）。
 *
 * 这类"删掉一个设置入口"的改动编译器管不了：把 setter 或 UI 选项加回来也能编译，
 * 所以用源码级护栏钉住。
 */
class SegmentSettingGuardTest {

    private fun source(relative: String): String = File("src/main/java/" + relative).readText()

    @Test
    fun 段大小不再暴露为可调项() {
        val section = source("io/github/gua123/mediagate/app/ui/NetworkTuningSection.kt")
        assertFalse("NetworkTuningSection 不能再有段大小回调", section.contains("onSegmentBytes"))
        assertFalse("NetworkTuningSection 不能再列段大小选项", section.contains("SEGMENT_CHOICES"))
        assertTrue("界面要明确段大小是内置参数", section.contains("段大小") && section.contains("内置"))

        val settings = source("io/github/gua123/mediagate/app/NetworkTuningSettings.kt")
        assertFalse("NetworkTuningSettings 不能再提供 setSegmentMb", settings.contains("setSegmentMb"))
    }
}
