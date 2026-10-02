package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 控制层自动隐藏：有操作不隐藏、几秒可配置。 */
class ControlsAutoHideTest {

    @Test
    fun 有操作时不启动计时() {
        assertFalse(ControlsAutoHide.shouldStartCountdown(playing = true, interacting = true, inPip = false, controlsVisible = true))
        assertTrue(ControlsAutoHide.shouldStartCountdown(true, interacting = false, inPip = false, controlsVisible = true))
    }

    @Test
    fun 暂停时不隐藏_画中画时不隐藏_已隐藏时不重复计时() {
        assertFalse(ControlsAutoHide.shouldStartCountdown(playing = false, interacting = false, inPip = false, controlsVisible = true))
        assertFalse(ControlsAutoHide.shouldStartCountdown(playing = true, interacting = false, inPip = true, controlsVisible = true))
        assertFalse(ControlsAutoHide.shouldStartCountdown(playing = true, interacting = false, inPip = false, controlsVisible = false))
    }

    @Test
    fun 秒数档位与永不隐藏() {
        assertEquals(3_000L, ControlsAutoHide.timeoutMs(3))
        assertEquals(10_000L, ControlsAutoHide.timeoutMs(10))
        assertNull("0 = 永不自动隐藏", ControlsAutoHide.timeoutMs(0))
        assertEquals("陌生值回默认 3 秒", ControlsAutoHide.DEFAULT_SECONDS, ControlsAutoHide.normalizeSeconds(42))
    }

    @Test
    fun 档位文案() {
        assertEquals("3 秒", ControlsAutoHide.label(3))
        assertEquals("不自动隐藏", ControlsAutoHide.label(0))
    }
}
