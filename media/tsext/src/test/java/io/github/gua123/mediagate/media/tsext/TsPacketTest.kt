package io.github.gua123.mediagate.media.tsext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TsPacketParser] / [TsPackets] 的 JVM 单测（R3）：188 字节包、同步字节、PID/PUSI/加扰/适配域。
 */
class TsPacketTest {

    private fun payloadTs(pid: Int = TsFixtures.VIDEO_PID, payloadBytes: Int = 200): ByteArray =
        TsMuxer().pes(pid, 0xE0, 90_000L, ByteArray(payloadBytes) { 0x11 }).toByteArray()

    @Test
    fun `解析基本字段`() {
        val data = payloadTs()
        val packet = TsPacketParser.parse(data, 0)!!
        assertEquals(TsFixtures.VIDEO_PID, packet.pid)
        assertTrue(packet.payloadUnitStart)
        assertFalse(packet.hasAdaptationField)
        assertEquals(TsPackets.AFC_PAYLOAD_ONLY, packet.adaptationFieldControl)
        assertEquals(4, packet.payloadOffset)
        assertEquals(184, packet.payloadLength)
        assertFalse(packet.isScrambled)
        assertFalse(packet.transportError)
        assertFalse(packet.isNullPacket)
    }

    @Test
    fun `同步字节不对返回 null`() {
        val data = payloadTs().copyOf()
        data[0] = 0x46
        assertNull(TsPacketParser.parse(data, 0))
    }

    @Test
    fun `长度不足返回 null`() {
        assertNull(TsPacketParser.parse(ByteArray(187), 0))
        assertNull(TsPacketParser.parse(payloadTs(), 1))
    }

    @Test
    fun `适配域控制为零的畸形包被拒绝`() {
        val data = TsMuxer().malformedPacket(0x0100, adaptationFieldControl = 0).toByteArray()
        assertNull(TsPacketParser.parse(data, 0))
        // 控制字 1 / 2 / 3 都是合法包
        assertEquals(1, TsPackets.adaptationFieldControl(payloadTs(), 0))
    }

    @Test
    fun `仅适配域的包没有载荷`() {
        val data = TsMuxer().pcrOnly(TsFixtures.VIDEO_PID, baseTicks = 90_000L).toByteArray()
        val packet = TsPacketParser.parse(data, 0)!!
        assertTrue(packet.hasAdaptationField)
        assertFalse(packet.hasPayload)
        assertEquals(-1, packet.payloadOffset)
        assertEquals(0, packet.payloadLength)
        // 适配域总长（含 length 字节）= 184；内容（不含 length 字节）= 183
        assertEquals(184, packet.adaptationFieldLength)
        assertEquals(183, packet.adaptationField(data).size)
    }

    @Test
    fun `适配域加载荷时载荷从适配域之后开始`() {
        // afc=3：adaptation(4 字节) + 180 字节载荷
        val data = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        data[0] = 0x47
        data[1] = 0x40
        data[2] = 0x00
        data[3] = ((3 shl 4) or 0x07).toByte()
        data[4] = 2 // adaptation_field_length：flags + 1 字节
        data[5] = 0x00
        data[6] = 0xAA.toByte() // 适配域最后一个字节
        data[7] = 0xCC.toByte() // 载荷第一个字节
        val packet = TsPacketParser.parse(data, 0)!!
        assertTrue(packet.hasAdaptationField)
        assertTrue(packet.hasPayload)
        assertEquals(3, packet.adaptationFieldLength)
        assertEquals(7, packet.payloadOffset)
        assertEquals(181, packet.payloadLength)
        assertEquals(0x07, packet.continuityCounter)
        assertEquals(0xCC, data[packet.payloadOffset].toInt() and 0xFF)
        assertEquals(2, packet.adaptationField(data).size)
    }

    @Test
    fun `加扰标志与传输错误标志被解析`() {
        val data = payloadTs().copyOf()
        data[1] = (data[1].toInt() or 0x80).toByte() // transport_error_indicator
        data[3] = (data[3].toInt() or 0x80).toByte() // scrambling_control = 2
        val packet = TsPacketParser.parse(data, 0)!!
        assertTrue(packet.transportError)
        assertEquals(2, packet.scramblingControl)
        assertTrue(packet.isScrambled)
    }

    @Test
    fun `空包被识别`() {
        val data = TsMuxer().pes(TsPackets.PID_NULL, 0xE0, 90_000L, ByteArray(100) { 0xFF.toByte() }).toByteArray()
        assertTrue(TsPacketParser.parse(data, 0)!!.isNullPacket)
    }

    @Test
    fun `重同步跳过垃圾并双重确认`() {
        val garbage = ByteArray(50) { 0x11 }
        val ts = payloadTs(payloadBytes = 400)
        val combined = garbage + ts
        assertEquals(50, TsPackets.findNextSync(combined, 1))
    }

    @Test
    fun `找不到同步位置返回负一`() {
        assertEquals(-1, TsPackets.findNextSync(ByteArray(600) { 0x11 }, 0))
        // 单个 0x47 不足以确认（后面 188 字节处不是 0x47）
        val single = ByteArray(400) { 0x11 }
        single[10] = 0x47
        assertEquals(-1, TsPackets.findNextSync(single, 0))
    }

    @Test
    fun `包头访问器与解析结果一致`() {
        val data = TsMuxer().pes(0x0123, 0xE0, 90_000L, ByteArray(50) { 0x22 }).toByteArray()
        assertEquals(0x0123, TsPackets.pid(data, 0))
        assertTrue(TsPackets.isSyncByte(data[0]))
        assertTrue(TsPackets.payloadUnitStartIndicator(data, 0))
        assertEquals(0, TsPackets.scramblingControl(data, 0))
        assertEquals(-1, TsPackets.adaptationFieldOffset(data, 0))
        assertEquals(-1, TsPackets.payloadOffset(TsMuxer().pcrOnly(0x0100, 0L).toByteArray(), 0))
    }
}
