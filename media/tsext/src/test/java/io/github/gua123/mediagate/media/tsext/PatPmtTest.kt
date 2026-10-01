package io.github.gua123.mediagate.media.tsext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PAT/PMT 解析与节目选择的 JVM 单测（R3，plan 4.3 第 1 条）。
 */
class PatPmtTest {

    private fun patOf(vararg programs: Pair<Int, Int>, networkPid: Int? = null, version: Int = 0): PatInfo =
        PatParser.parse(TsSections.pat(programs.toList(), networkPid = networkPid, version = version))!!

    private fun pmtOf(
        programNumber: Int = 1,
        pcrPid: Int = TsFixtures.VIDEO_PID,
        streams: List<PmtStreamSpec> = listOf(PmtStreamSpec(TsFixtures.H264, TsFixtures.VIDEO_PID)),
        version: Int = 0,
        programInfoLength: Int = 0,
    ): PmtInfo = PmtParser.parse(
        TsSections.pmt(programNumber, pcrPid, streams, version = version, programInfoLength = programInfoLength),
    )!!

    @Test
    fun `PAT 解析出节目与网络 PID`() {
        val pat = patOf(1 to 0x1000, 2 to 0x1001, networkPid = 0x0010)
        assertEquals(1, pat.transportStreamId)
        assertEquals(0x0010, pat.networkPid)
        assertEquals(2, pat.programs.size)
        assertEquals(PatProgram(1, 0x1000), pat.programs[0])
        assertEquals(PatProgram(2, 0x1001), pat.programs[1])
    }

    @Test
    fun `PAT 版本号被解析`() {
        assertEquals(5, patOf(1 to 0x1000, version = 5).versionNumber)
    }

    @Test
    fun `PAT 坏 CRC 返回 null`() {
        val section = TsSections.pat(listOf(1 to 0x1000), corruptCrc = true)
        assertNull(PatParser.parse(section))
        // 关掉校验时仍然能解析（用于诊断/测试构造）
        assertTrue(PatParser.parse(section, validateCrc = false) != null)
    }

    @Test
    fun `PAT table_id 不符返回 null`() {
        val section = TsSections.pmt(1, 0x0100, listOf(PmtStreamSpec(TsFixtures.H264, TsFixtures.VIDEO_PID)))
        assertNull(PatParser.parse(section))
    }

    @Test
    fun `PAT 长度不足返回 null`() {
        assertNull(PatParser.parse(ByteArray(5)))
        assertNull(PatParser.parse(TsSections.pat(listOf(1 to 0x1000)).copyOf(8)))
    }

    @Test
    fun `PMT 解析 PCR 与基本流`() {
        val pmt = pmtOf(
            streams = listOf(
                PmtStreamSpec(TsFixtures.H264, TsFixtures.VIDEO_PID),
                PmtStreamSpec(TsFixtures.AAC, TsFixtures.AUDIO_PID),
            ),
        )
        assertEquals(1, pmt.programNumber)
        assertEquals(TsFixtures.VIDEO_PID, pmt.pcrPid)
        assertEquals(2, pmt.streams.size)
        assertEquals(TsFixtures.VIDEO_PID, pmt.firstVideoStream()!!.pid)
        assertEquals(TsVideoCodec.H264, pmt.firstVideoStream()!!.videoCodec)
        assertEquals(listOf(TsFixtures.AUDIO_PID), pmt.audioPids)
    }

    @Test
    fun `PMT 跳过 program_info 与 es_info 描述符`() {
        val pmt = pmtOf(
            streams = listOf(
                PmtStreamSpec(TsFixtures.H264, TsFixtures.VIDEO_PID, esInfoLength = 6),
                PmtStreamSpec(TsFixtures.AAC, TsFixtures.AUDIO_PID, esInfoLength = 3),
            ),
            programInfoLength = 8,
        )
        assertEquals(2, pmt.streams.size)
        assertEquals(TsFixtures.VIDEO_PID, pmt.firstVideoStream()!!.pid)
        assertEquals(TsFixtures.AUDIO_PID, pmt.audioPids.single())
    }

    @Test
    fun `PMT 坏 CRC 返回 null`() {
        val section = TsSections.pmt(
            1,
            0x0100,
            listOf(PmtStreamSpec(TsFixtures.H264, TsFixtures.VIDEO_PID)),
            corruptCrc = true,
        )
        assertNull(PmtParser.parse(section))
        assertTrue(PmtParser.parse(section, validateCrc = false) != null)
    }

    @Test
    fun `HEVC 与其它流类型映射到正确编码`() {
        assertEquals(TsVideoCodec.H265, TsVideoCodec.ofStreamType(0x24))
        assertEquals(TsVideoCodec.H264, TsVideoCodec.ofStreamType(0x1B))
        assertEquals(TsVideoCodec.MPEG2, TsVideoCodec.ofStreamType(0x02))
        assertEquals(TsVideoCodec.MPEG4, TsVideoCodec.ofStreamType(0x10))
        assertEquals(TsVideoCodec.OTHER, TsVideoCodec.ofStreamType(0x42))
        assertTrue(TsVideoCodec.H264.supportsKeyframeScan)
        assertTrue(TsVideoCodec.H265.supportsKeyframeScan)
        assertTrue(!TsVideoCodec.MPEG2.supportsKeyframeScan)
        assertTrue(TsStreamTypes.isVideo(0x24))
        assertTrue(TsStreamTypes.isAudio(0x0F))
        assertTrue(TsStreamTypes.isAudio(0x06))
    }

    @Test
    fun `多节目取第一个含视频的节目`() {
        val pat = patOf(1 to 0x1000, 2 to 0x1001)
        val pmts = mapOf(
            0x1000 to pmtOf(1, pcrPid = TsFixtures.VIDEO_PID, streams = listOf(PmtStreamSpec(TsFixtures.H264, TsFixtures.VIDEO_PID))),
            0x1001 to pmtOf(2, pcrPid = 0x0200, streams = listOf(PmtStreamSpec(TsFixtures.AAC, 0x0200))),
        )
        val selected = TsProgramSelector.select(pat) { pmts[it] }!!
        assertEquals(1, selected.programNumber)
        assertEquals(TsFixtures.VIDEO_PID, selected.videoPid)
    }

    @Test
    fun `第一个节目纯音频时选后面的视频节目`() {
        val pat = patOf(1 to 0x1000, 2 to 0x1001)
        val pmts = mapOf(
            0x1000 to pmtOf(1, pcrPid = 0x0200, streams = listOf(PmtStreamSpec(TsFixtures.AAC, 0x0200))),
            0x1001 to pmtOf(2, pcrPid = 0x0300, streams = listOf(PmtStreamSpec(0x24, 0x0300))),
        )
        val selected = TsProgramSelector.select(pat) { pmts[it] }!!
        assertEquals(2, selected.programNumber)
        assertEquals(0x0300, selected.videoPid)
        assertEquals(TsVideoCodec.H265, selected.videoCodec)
    }

    @Test
    fun `全部节目没有视频时返回 null`() {
        val pat = patOf(1 to 0x1000)
        val pmts = mapOf(0x1000 to pmtOf(1, pcrPid = 0x0200, streams = listOf(PmtStreamSpec(TsFixtures.AAC, 0x0200))))
        assertNull(TsProgramSelector.select(pat) { pmts[it] })
    }

    @Test
    fun `PMT 还没到达时返回 null 而不抛异常`() {
        val pat = patOf(1 to 0x1000, 2 to 0x1001)
        assertNull(TsProgramSelector.select(pat) { null })
        assertNull(TsProgramSelector.select(patOf(), { null }))
    }
}
