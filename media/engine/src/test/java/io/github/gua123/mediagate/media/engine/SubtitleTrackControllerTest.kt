package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕控制器（R14 外挂字幕单轨 / R9 换内核时保持字幕状态）。
 *
 * 纯 JVM：控制器本身不碰任何播放器，只把状态收敛并把变更回调给内核实现。
 */
class SubtitleTrackControllerTest {

    @Test
    fun `状态变更会回调内核落地`() {
        val applied = mutableListOf<SubtitleState>()
        val controller = DefaultSubtitleTrackController { applied += it }

        controller.selectTrack("mediagate://local-file%3A%2Fx/a.srt")
        controller.setEnabled(true)
        controller.setOffsetMs(500L)

        assertEquals("三次变更各回调一次", 3, applied.size)
        assertEquals("mediagate://local-file%3A%2Fx/a.srt", applied[0].uri)
        assertTrue(applied[1].enabled)
        assertEquals(500L, applied[2].offsetMs)
        assertTrue(controller.state.value.enabled)
        assertEquals(500L, controller.state.value.offsetMs)
        assertEquals("mediagate://local-file%3A%2Fx/a.srt", controller.state.value.uri)
    }

    @Test
    fun `空白轨道等于清空外挂字幕`() {
        val controller = DefaultSubtitleTrackController()
        controller.selectTrack("mediagate://local-file%3A%2Fx/a.srt")
        controller.selectTrack("   ")
        assertNull(controller.state.value.uri)
        controller.selectTrack(null)
        assertNull(controller.state.value.uri)
    }

    @Test
    fun `时间轴微调被夹到允许区间`() {
        val controller = DefaultSubtitleTrackController()
        controller.setOffsetMs(999_999L)
        assertEquals(SubtitleState.MAX_OFFSET_MS, controller.state.value.offsetMs)
        controller.setOffsetMs(-999_999L)
        assertEquals(SubtitleState.MIN_OFFSET_MS, controller.state.value.offsetMs)
        assertEquals(0L, SubtitleState.clampOffset(0L))
    }

    @Test
    fun `一次性恢复会把整份状态（含标签）带过去`() {
        val applied = mutableListOf<SubtitleState>()
        val controller = DefaultSubtitleTrackController { applied += it }
        controller.restore(
            SubtitleState(
                enabled = true,
                offsetMs = -1_000L,
                uri = "mediagate://local-file%3A%2Fx/b.ass",
                label = "b.ass",
            ),
        )
        assertEquals("整体恢复只落地一次", 1, applied.size)
        assertEquals("mediagate://local-file%3A%2Fx/b.ass", applied[0].uri)
        assertTrue(applied[0].enabled)
        assertEquals(-1_000L, applied[0].offsetMs)
        assertEquals("b.ass", controller.state.value.label)
    }

    @Test
    fun `默认状态是关闭且无偏移`() {
        val state = DefaultSubtitleTrackController().state.value
        assertFalse(state.enabled)
        assertEquals(0L, state.offsetMs)
        assertNull(state.uri)
    }
}
