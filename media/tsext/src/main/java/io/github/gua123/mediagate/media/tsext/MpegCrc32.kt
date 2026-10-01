package io.github.gua123.mediagate.media.tsext

/**
 * MPEG-2 系统层 CRC-32（R3）：PSI 段（PAT/PMT）末 4 字节的校验。
 *
 * 参数与以太网 CRC 不同，别用 `java.util.zip.CRC32`：
 * 多项式 0x04C11DB7、初值 0xFFFFFFFF、**不反转输入输出、不做最终异或**、MSB first。
 *
 * 纯 Kotlin，JVM 可测；单测用标准向量（"123456789" 的 MPEG-2 CRC = 0x0376E6E7）钉住实现。
 */
object MpegCrc32 {

    private const val POLYNOMIAL: Long = 0x04C11DB7L

    private val TABLE: LongArray = LongArray(256) { index ->
        var value = index.toLong() shl 24
        repeat(8) {
            value = if (value and 0x80000000L != 0L) {
                ((value shl 1) xor POLYNOMIAL) and 0xFFFFFFFFL
            } else {
                (value shl 1) and 0xFFFFFFFFL
            }
        }
        value
    }

    /** 计算 `[offset, offset+length)` 的 MPEG-2 CRC-32（返回无符号 32 位值）。 */
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        var crc = 0xFFFFFFFFL
        val end = offset + length
        for (index in offset until end) {
            val byte = data[index].toInt() and 0xFF
            val tableIndex = ((crc ushr 24) xor byte.toLong()).toInt() and 0xFF
            crc = ((crc shl 8) and 0xFFFFFFFFL) xor TABLE[tableIndex]
        }
        return crc and 0xFFFFFFFFL
    }

    /**
     * 校验一个完整的 PSI 段（末 4 字节是 CRC）。
     *
     * @return false 表示 CRC 不符或长度不足——调用方应当**丢弃该段并计数**（R3 容错），不要崩。
     */
    fun validateSection(section: ByteArray, offset: Int = 0, length: Int = section.size - offset): Boolean {
        if (length < MIN_SECTION_WITH_CRC) return false
        val expected = compute(section, offset, length - 4)
        val actual = readUInt32(section, offset + length - 4)
        return expected == actual
    }

    /** 把 [body] 的 CRC 追加成完整段（测试与合成样本用）。 */
    fun appendCrc(body: ByteArray): ByteArray {
        val crc = compute(body)
        return body + byteArrayOf(
            ((crc ushr 24) and 0xFF).toByte(),
            ((crc ushr 16) and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte(),
        )
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    /** 带 CRC 的最小段长：3 字节段头 + 4 字节 CRC。 */
    const val MIN_SECTION_WITH_CRC: Int = 7
}
