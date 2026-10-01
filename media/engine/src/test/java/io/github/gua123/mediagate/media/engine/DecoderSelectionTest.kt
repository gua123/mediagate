package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码器候选挑选（R10「做不到就记录并回报」的纯逻辑部分）。
 *
 * 真机上的 MediaCodec 列表拿不到，这里用等价的候选数据验证策略：
 * 有软解码器就只留软解码器；一个都没有就如实退回硬解并给出提示。
 */
class DecoderSelectionTest {

    private val hardware = CodecCandidate("OMX.qcom.video.decoder.hevc", softwareOnly = false)
    private val hardware2 = CodecCandidate("c2.qti.avc.decoder", softwareOnly = false)
    private val software = CodecCandidate("c2.android.avc.decoder", softwareOnly = true)
    private val software2 = CodecCandidate("OMX.google.hevc.decoder", softwareOnly = true)

    @Test
    fun `强制软解时只保留软件解码器`() {
        val selection = DecoderSelection.select(DecoderMode.FORCE_SW, listOf(hardware, software, hardware2, software2))
        assertEquals(listOf(software, software2), selection.ordered)
        assertFalse(selection.onlyHardwareAvailable)
        assertNull(DecoderSelection.note(DecoderMode.FORCE_SW, selection))
    }

    @Test
    fun `没有软件解码器时如实退回硬解并给出提示`() {
        val selection = DecoderSelection.select(DecoderMode.FORCE_SW, listOf(hardware, hardware2))
        assertEquals("退回默认顺序", listOf(hardware, hardware2), selection.ordered)
        assertTrue(selection.onlyHardwareAvailable)
        val note = DecoderSelection.note(DecoderMode.FORCE_SW, selection)
        assertNotNull("必须给出可读的提示（R10）", note)
        assertTrue(note!!, note.contains("软件解码器"))
    }

    @Test
    fun `自动与强制硬解档位不改变候选顺序`() {
        val candidates = listOf(hardware, software)
        for (mode in listOf(DecoderMode.AUTO_HW, DecoderMode.FORCE_HW)) {
            val selection = DecoderSelection.select(mode, candidates)
            assertEquals(candidates, selection.ordered)
            assertFalse(selection.onlyHardwareAvailable)
            assertNull(DecoderSelection.note(mode, selection))
        }
    }

    @Test
    fun `候选为空时不误报软解提示`() {
        val selection = DecoderSelection.select(DecoderMode.FORCE_SW, emptyList())
        assertTrue(selection.ordered.isEmpty())
        assertFalse("没有候选不等于「只有硬解」", selection.onlyHardwareAvailable)
    }

    @Test
    fun `解码报告字段自洽`() {
        val report = DecoderReport(
            requested = DecoderMode.FORCE_SW,
            applied = "c2.android.avc.decoder",
            softwareApplied = true,
            note = null,
        )
        assertEquals(DecoderMode.FORCE_SW, report.requested)
        assertTrue(report.softwareApplied)
        assertNull(report.note)
    }
}
