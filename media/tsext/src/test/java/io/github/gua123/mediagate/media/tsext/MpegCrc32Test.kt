package io.github.gua123.mediagate.media.tsext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MpegCrc32] 的 JVM 单测（R3）：PSI 段的 CRC 校验是「坏 CRC 只丢段、不中断扫描」的判据。
 *
 * 用标准向量钉住实现：MPEG-2 系统层 CRC-32 对 "123456789" = 0x0376E6E7。
 */
class MpegCrc32Test {

    @Test
    fun `标准向量不被语言或库影响`() {
        val vector = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x0376E6E7L, MpegCrc32.compute(vector))
        // 不是以太网 CRC32（java.util.zip.CRC32 对同一串给出 0xCBF43926）
        assertFalse(MpegCrc32.compute(vector) == 0xCBF43926L)
    }

    @Test
    fun `指定区间计算`() {
        val vector = "xx123456789yy".toByteArray(Charsets.US_ASCII)
        assertEquals(0x0376E6E7L, MpegCrc32.compute(vector, offset = 2, length = 9))
    }

    @Test
    fun `合法 PAT 段校验通过`() {
        val pat = TsSections.pat(listOf(1 to 0x1000))
        assertTrue(MpegCrc32.validateSection(pat))
        assertEquals(MpegCrc32.compute(pat, 0, pat.size - 4), MpegCrc32.readCrcOf(pat))
    }

    @Test
    fun `坏 CRC 被识破`() {
        val pat = TsSections.pat(listOf(1 to 0x1000), corruptCrc = true)
        assertFalse(MpegCrc32.validateSection(pat))
    }

    @Test
    fun `追加 CRC 后自成合法段`() {
        val body = byteArrayOf(0x00, 0x30, 0x0A, 0x01, 0x02)
        val section = MpegCrc32.appendCrc(body)
        assertEquals(body.size + 4, section.size)
        assertTrue(MpegCrc32.validateSection(section))
    }

    @Test
    fun `长度不足一律判否`() {
        assertFalse(MpegCrc32.validateSection(ByteArray(3)))
        assertFalse(MpegCrc32.validateSection(ByteArray(0)))
    }
}

/** 读段末 4 字节 CRC（测试辅助）。 */
internal fun MpegCrc32.readCrcOf(section: ByteArray): Long {
    val offset = section.size - 4
    return ((section[offset].toLong() and 0xFF) shl 24) or
        ((section[offset + 1].toLong() and 0xFF) shl 16) or
        ((section[offset + 2].toLong() and 0xFF) shl 8) or
        (section[offset + 3].toLong() and 0xFF)
}
