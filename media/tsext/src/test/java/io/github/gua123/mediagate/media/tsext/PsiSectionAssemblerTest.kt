package io.github.gua123.mediagate.media.tsext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PsiSectionAssembler] 的 JVM 单测（R3）：PAT/PMT 段跨包拼装、pointer_field、填充与畸形段。
 */
class PsiSectionAssemblerTest {

    private fun payloadsOf(packets: ByteArray, pid: Int): List<Triple<Int, Int, Boolean>> {
        val result = mutableListOf<Triple<Int, Int, Boolean>>()
        var offset = 0
        while (offset + TS_PACKET_SIZE <= packets.size) {
            if (TsPackets.pid(packets, offset) == pid) {
                val payloadOffset = TsPackets.payloadOffset(packets, offset)
                val payloadLength = TsPackets.payloadLength(packets, offset)
                if (payloadOffset >= 0 && payloadLength > 0) {
                    result += Triple(payloadOffset, payloadLength, TsPackets.payloadUnitStartIndicator(packets, offset))
                }
            }
            offset += TS_PACKET_SIZE
        }
        return result
    }

    /** 手工构造一个 TS 包（afc=1，剩余字节填 0xFF）。 */
    private fun packetWith(pid: Int, pusi: Boolean, cc: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = ((if (pusi) 0x40 else 0x00) or ((pid ushr 8) and 0x1F)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = ((1 shl 4) or (cc and 0x0F)).toByte()
        System.arraycopy(payload, 0, packet, 4, minOf(payload.size, TS_PACKET_SIZE - 4))
        return packet
    }

    private fun feedAll(assembler: PsiSectionAssembler, packets: ByteArray, pid: Int): List<ByteArray> {
        val sections = mutableListOf<ByteArray>()
        for ((offset, length, start) in payloadsOf(packets, pid)) {
            sections += assembler.feed(packets, offset, length, start)
        }
        return sections
    }

    @Test
    fun `单包段被完整取出`() {
        val pat = TsSections.pat(listOf(1 to 0x1000))
        assertTrue(pat.size < 183)
        val packets = TsMuxer().psi(0, pat).toByteArray()
        val sections = feedAll(PsiSectionAssembler(), packets, 0)
        assertEquals(1, sections.size)
        assertEquals(pat.toList(), sections[0].toList())
    }

    @Test
    fun `跨包段被拼完整`() {
        val streams = (0 until 40).map { PmtStreamSpec(0x1B, 0x0100 + it) }
        val pmt = TsSections.pmt(1, pcrPid = 0x0100, streams = streams)
        assertTrue("段应超过一个包的载荷：${pmt.size}", pmt.size > 183)
        val packets = TsMuxer().psi(TsFixtures.PMT_PID, pmt).toByteArray()
        val sections = feedAll(PsiSectionAssembler(), packets, TsFixtures.PMT_PID)
        assertEquals(1, sections.size)
        assertEquals(pmt.toList(), sections[0].toList())
        val parsed = PmtParser.parse(sections[0])!!
        assertEquals(40, parsed.streams.size)
    }

    @Test
    fun `pointer_field 补完上一个段`() {
        // 真实的 pointer_field 补段场景：大段跨两包，第二个包以 PUSI=1 开头，
        // pointer 指向「补完上一段所需字节数」，其后紧跟新的一段。
        val streams = (0 until 40).map { PmtStreamSpec(0x1B, 0x0100 + it) }
        val pmt = TsSections.pmt(1, pcrPid = 0x0100, streams = streams)
        val pat = TsSections.pat(listOf(1 to 0x1000))
        assertTrue(pmt.size > 183)
        val assembler = PsiSectionAssembler()

        val packetA = packetWith(
            pid = TsFixtures.PMT_PID,
            pusi = true,
            cc = 0,
            payload = byteArrayOf(0x00) + pmt.copyOfRange(0, 183),
        )
        assertTrue(assembler.feed(packetA, 4, 184, true).isEmpty())
        assertEquals(183, assembler.pendingBytes)

        val remaining = pmt.size - 183
        val packetB = packetWith(
            pid = TsFixtures.PMT_PID,
            pusi = true,
            cc = 1,
            payload = byteArrayOf(remaining.toByte()) + pmt.copyOfRange(183, pmt.size) + pat,
        )
        val sections = assembler.feed(packetB, 4, 184, true)
        assertEquals(2, sections.size)
        assertEquals(pmt.toList(), sections[0].toList())
        assertEquals(pat.toList(), sections[1].toList())
        assertEquals(0, assembler.pendingBytes)
    }

    @Test
    fun `填充字节不会产生假段`() {
        val assembler = PsiSectionAssembler()
        val stuffing = ByteArray(184) { 0xFF.toByte() }
        assertTrue(assembler.feed(stuffing, 0, stuffing.size, false).isEmpty())
        assertTrue(assembler.feed(stuffing, 0, stuffing.size, true).isEmpty())
        assertEquals(0, assembler.pendingBytes)
    }

    @Test
    fun `畸形段长被丢弃后可继续拼新段`() {
        val assembler = PsiSectionAssembler()
        val broken = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        broken[0] = 0x47
        broken[1] = 0x40
        broken[2] = 0x00
        broken[3] = 0x10
        broken[4] = 0x00
        broken[5] = 0x00 // table_id = PAT
        broken[6] = 0xBF.toByte() // section_length 高 4 位全 1 → 4095，超过上限
        broken[7] = 0xFF.toByte()
        assertTrue(assembler.feed(broken, 4, 184, true).isEmpty())
        assertEquals(0, assembler.pendingBytes)
        // 之后仍然能正常拼出完整段
        val pat = TsSections.pat(listOf(1 to 0x1000))
        val packets = TsMuxer().psi(0, pat).toByteArray()
        val sections = feedAll(assembler, packets, 0)
        assertEquals(1, sections.size)
    }

    @Test
    fun `没有缓存时非起始包不产出`() {
        val assembler = PsiSectionAssembler()
        val payload = ByteArray(184) { 0x00 }
        assertTrue(assembler.feed(payload, 0, payload.size, false).isEmpty())
    }

    @Test
    fun `重置清空未拼完的缓存`() {
        val streams = (0 until 40).map { PmtStreamSpec(0x1B, 0x0100 + it) }
        val pmt = TsSections.pmt(1, pcrPid = 0x0100, streams = streams)
        val assembler = PsiSectionAssembler()
        val packet = packetWith(
            pid = TsFixtures.PMT_PID,
            pusi = true,
            cc = 0,
            payload = byteArrayOf(0x00) + pmt.copyOfRange(0, 183),
        )
        assertTrue(assembler.feed(packet, 4, 184, true).isEmpty())
        assertEquals(183, assembler.pendingBytes)
        assembler.reset()
        assertEquals(0, assembler.pendingBytes)
    }
}
