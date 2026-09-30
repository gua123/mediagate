package io.github.gua123.mediagate.media.tsext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyframeDetector] 与 [NalUnits] 的 JVM 单测（R3/R4，plan 4.3 第 1 条）。
 *
 * 覆盖 H.264 的 NAL type 5（IDR）与 H.265 的 IRAP（19/20/21）两类关键帧标记。
 */
class KeyframeDetectorTest {

    @Test
    fun `H264 IDR 被识别`() {
        val accessUnit = H26x.h264(idr = true)
        assertTrue(KeyframeDetector.isH264Idr(accessUnit))
        assertTrue(KeyframeDetector.isKeyframe(TsVideoCodec.H264, accessUnit))
    }

    @Test
    fun `H264 非 IDR 不被识别`() {
        val accessUnit = H26x.h264(idr = false)
        assertFalse(KeyframeDetector.isH264Idr(accessUnit))
        assertFalse(KeyframeDetector.isKeyframe(TsVideoCodec.H264, accessUnit))
    }

    @Test
    fun `只有 SPS 与 PPS 不算关键帧`() {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67) + ByteArray(8))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68) + ByteArray(8))
        assertFalse(KeyframeDetector.isH264Idr(out.toByteArray()))
    }

    @Test
    fun `三字节起手码同样识别`() {
        val threeByte = byteArrayOf(0x00, 0x00, 0x01, 0x65) + ByteArray(16) { 0x33 }
        assertTrue(KeyframeDetector.isH264Idr(threeByte))
        val fourByte = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65) + ByteArray(16) { 0x33 }
        assertTrue(KeyframeDetector.isH264Idr(fourByte))
    }

    @Test
    fun `H264 NAL 类型列表按顺序给出`() {
        val accessUnit = H26x.h264(idr = true)
        assertEquals(listOf(9, 7, 8, 5), NalUnits.h264Types(accessUnit))
    }

    @Test
    fun `H265 三种 IRAP 都被识别`() {
        for (irap in listOf(KeyframeDetector.H265_NAL_IDR_W_RADL, KeyframeDetector.H265_NAL_IDR_N_LP, KeyframeDetector.H265_NAL_CRA)) {
            val accessUnit = H26x.h265(irapType = irap)
            assertTrue("IRAP $irap 应被识别", KeyframeDetector.isH265Irap(accessUnit))
            assertTrue(KeyframeDetector.isKeyframe(TsVideoCodec.H265, accessUnit))
        }
        assertTrue(KeyframeDetector.isH265IrapType(16))
        assertTrue(KeyframeDetector.isH265IrapType(23))
        assertFalse(KeyframeDetector.isH265IrapType(24))
    }

    @Test
    fun `H265 非 IRAP 不被识别`() {
        val trail = H26x.h265(irapType = null)
        assertFalse(KeyframeDetector.isH265Irap(trail))
        assertFalse(KeyframeDetector.isKeyframe(TsVideoCodec.H265, trail))
    }

    @Test
    fun `H265 NAL 类型解析`() {
        val accessUnit = H26x.h265(irapType = 19)
        assertEquals(listOf(32, 33, 34, 19), NalUnits.h265Types(accessUnit))
    }

    @Test
    fun `空载荷与随机数据不误判`() {
        assertFalse(KeyframeDetector.isH264Idr(ByteArray(0)))
        assertFalse(KeyframeDetector.isH265Irap(ByteArray(0)))
        assertFalse(KeyframeDetector.isH264Idr(ByteArray(200) { 0x11 }))
        assertFalse(KeyframeDetector.isKeyframe(TsVideoCodec.H264, ByteArray(4) { 0x00 }))
        // 起手码在末尾、没有 NAL 头时不越界
        assertFalse(KeyframeDetector.isH264Idr(byteArrayOf(0x00, 0x00, 0x01)))
    }

    @Test
    fun `非 H264 与 H265 编码不做关键帧识别`() {
        val idr = H26x.h264(idr = true)
        assertFalse(KeyframeDetector.isKeyframe(TsVideoCodec.MPEG2, idr))
        assertFalse(KeyframeDetector.isKeyframe(TsVideoCodec.MPEG4, idr))
        assertFalse(KeyframeDetector.isKeyframe(TsVideoCodec.OTHER, idr))
    }

    @Test
    fun `只在给定区间内扫描`() {
        val accessUnit = H26x.h264(idr = true)
        assertTrue(KeyframeDetector.isH264Idr(accessUnit, 0, accessUnit.size))
        val filler = ByteArray(accessUnit.size) { 0x55 }
        val mixed = filler + accessUnit
        assertTrue(KeyframeDetector.isH264Idr(mixed, filler.size, accessUnit.size))
        assertFalse(KeyframeDetector.isH264Idr(mixed, 0, filler.size))
    }
}
