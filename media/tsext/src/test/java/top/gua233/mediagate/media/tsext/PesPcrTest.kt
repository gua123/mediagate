package io.github.gua123.mediagate.media.tsext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PES 头时间戳与 PCR 解析的 JVM 单测（R3/R4）：33 位时间戳、90 kHz 换算、适配域 PCR。
 */
class PesPcrTest {

    private fun pesPacket(
        streamId: Int = 0xE0,
        pts: Long? = 90_000L,
        dts: Long? = null,
        payload: Int = 100,
    ): ByteArray = TsMuxer().pes(TsFixtures.VIDEO_PID, streamId, pts, ByteArray(payload) { 0x11 }, dtsTicks = dts).toByteArray()

    @Test
    fun `只有 PTS 的 PES 头`() {
        val data = pesPacket(pts = 90_000L)
        val header = PesParser.parse(data, 4, 184)!!
        assertEquals(0xE0, header.streamId)
        assertEquals(90_000L, header.ptsTicks)
        assertNull(header.dtsTicks)
        assertEquals(1_000L, header.ptsMs)
        assertEquals(14, header.headerBytes)
        assertTrue(header.isVideoStream)
        assertFalse(header.isAudioStream)
    }

    @Test
    fun `PTS 加 DTS 的 PES 头`() {
        val data = pesPacket(pts = 180_000L, dts = 90_000L)
        val header = PesParser.parse(data, 4, 184)!!
        assertEquals(180_000L, header.ptsTicks)
        assertEquals(90_000L, header.dtsTicks)
        assertEquals(2_000L, header.ptsMs)
        assertEquals(1_000L, header.dtsMs)
        assertEquals(19, header.headerBytes)
    }

    @Test
    fun `没有时间戳时两个字段都为 null`() {
        val data = pesPacket(pts = null)
        val header = PesParser.parse(data, 4, 184)!!
        assertNull(header.ptsTicks)
        assertNull(header.ptsMs)
        assertFalse(header.hasPts)
        assertEquals(9, header.headerBytes)
    }

    @Test
    fun `marker 位不合法视为没有时间戳`() {
        val data = pesPacket(pts = 90_000L).copyOf()
        data[4 + 9] = (data[4 + 9].toInt() and 0xFE).toByte() // 清掉 marker 位
        val header = PesParser.parse(data, 4, 184)!!
        assertNull(header.ptsTicks)
    }

    @Test
    fun `三十三位边界与毫秒换算`() {
        assertEquals(0L, PesParser.ticksToMs(0L))
        assertEquals(1_000L, PesParser.ticksToMs(90_000L))
        assertEquals(95_443_717L, PesParser.ticksToMs(PesParser.MAX_PTS_TICKS))
        assertEquals(95_443_717L, PesParser.ticksToMs((1L shl 33) - 1))
        assertEquals(0L, PesParser.maskTicks(1L shl 33))
        assertEquals(PesParser.MAX_PTS_TICKS, PesParser.maskTicks(-1L))
        // 最大值的往返：编码 → 解析
        val encoded = TsMuxer().encodeTimestamp(0x2, PesParser.MAX_PTS_TICKS)
        assertEquals(PesParser.MAX_PTS_TICKS, PesParser.timestampFrom(encoded, 0))
        val zero = TsMuxer().encodeTimestamp(0x2, 0L)
        assertEquals(0L, PesParser.timestampFrom(zero, 0))
    }

    @Test
    fun `非 PES 起始返回 null`() {
        val data = ByteArray(200) { 0x11 }
        assertNull(PesParser.parse(data, 0, 200))
        assertFalse(PesParser.isPesStart(data, 0))
        assertTrue(PesParser.isPesStart(pesPacket(), 4))
    }

    @Test
    fun `无扩展头的 stream id 不会误读时间戳`() {
        val data = pesPacket(streamId = 0xBE, pts = null, payload = 40)
        val header = PesParser.parse(data, 4, 184)!!
        assertEquals(0xBE, header.streamId)
        assertEquals(6, header.headerBytes)
        assertNull(header.ptsTicks)
    }

    @Test
    fun `长度不足的 PES 头返回 null`() {
        val data = pesPacket()
        assertNull(PesParser.parse(data, 4, 3))
        assertNull(PesParser.parse(data, 180, 20))
    }

    @Test
    fun `PCR 基值与扩展被正确还原`() {
        val base = 1_234_567L
        val extension = 271
        val data = TsMuxer().pcrOnly(TsFixtures.VIDEO_PID, base, extension).toByteArray()
        val pcr = AdaptationField.pcr(data, 0)!!
        assertEquals(base, pcr.baseTicks)
        assertEquals(extension, pcr.extension)
        assertEquals(base * 300 + extension, pcr.pcr27MHz)
        assertEquals(PesParser.ticksToMs(base), pcr.ms)
        assertTrue(AdaptationField.hasPcr(data, 0))
        assertTrue(AdaptationField.randomAccessIndicator(data, 0).not())
    }

    @Test
    fun `三十三位 PCR 也能解析`() {
        val base = PesParser.MAX_PTS_TICKS
        val data = TsMuxer().pcrOnly(TsFixtures.VIDEO_PID, base, extension = 299).toByteArray()
        val pcr = AdaptationField.pcr(data, 0)!!
        assertEquals(base, pcr.baseTicks)
        assertEquals(299, pcr.extension)
    }

    @Test
    fun `没有适配域或没有 PCR 标志时返回 null`() {
        val pesOnly = pesPacket()
        assertNull(AdaptationField.pcr(pesOnly, 4))
        assertNull(AdaptationField.flags(pesOnly, 4))
        // 有适配域但没有 PCR 标志
        val noPcr = TsMuxer().pcrOnly(TsFixtures.VIDEO_PID, 0L).toByteArray().copyOf()
        noPcr[5] = 0x00 // 清掉 flags 里的 PCR_flag
        assertNull(AdaptationField.pcr(noPcr, 0))
    }

    @Test
    fun `不连续标志被识别`() {
        val data = TsMuxer().pcrOnly(TsFixtures.VIDEO_PID, 90_000L, discontinuity = true).toByteArray()
        assertTrue(AdaptationField.discontinuityIndicator(data, 0))
        val normal = TsMuxer().pcrOnly(TsFixtures.VIDEO_PID, 90_000L).toByteArray()
        assertFalse(AdaptationField.discontinuityIndicator(normal, 0))
    }
}
