package io.github.gua123.mediagate.media.tsext

/**
 * PES 头解析结果（R3/R4，plan 4.3 第 1 条「扫 PES 头 + IDR/IRAP + PCR/PTS」）。
 *
 * @param streamId PES stream_id（视频 0xE0..0xEF，音频 0xC0..0xDF）。
 * @param packetLength PES_packet_length 字段（0 表示只对视频流合法、长度不定）。
 * @param ptsTicks 33 位 PTS（90 kHz 时基）；没有 PTS 时为 null。
 * @param dtsTicks 33 位 DTS；只有 PTS+DTS 形态才有。
 * @param headerBytes 头部总字节数（含 6 字节起始前缀），ES 数据从这里开始。
 */
data class PesHeader(
    val streamId: Int,
    val packetLength: Int,
    val scramblingControl: Int,
    val ptsTicks: Long?,
    val dtsTicks: Long?,
    val headerBytes: Int,
) {

    val hasPts: Boolean get() = ptsTicks != null

    val hasDts: Boolean get() = dtsTicks != null

    /** PTS 换算成毫秒（90 kHz → ms）。 */
    val ptsMs: Long? get() = ptsTicks?.let(PesParser::ticksToMs)

    val dtsMs: Long? get() = dtsTicks?.let(PesParser::ticksToMs)

    /** 是否视频流（0xE0..0xEF）。 */
    val isVideoStream: Boolean get() = streamId in 0xE0..0xEF

    /** 是否音频流（0xC0..0xDF）。 */
    val isAudioStream: Boolean get() = streamId in 0xC0..0xDF
}

/**
 * PES 头解析（R3/R4）。纯 Kotlin、无分配之外的副作用，JVM 可测。
 *
 * 时间戳是 33 位、90 kHz 时基：最大值 2^33-1 ≈ 26.5 小时回绕一次，
 * 换算成毫秒用 Long 运算（`ticks * 1000 / 90000`），不会溢出。
 */
object PesParser {

    /** PES 起始码前缀：00 00 01。 */
    const val START_CODE_PREFIX: Int = 0x000001

    /** PTS/DTS 时基：90 kHz。 */
    const val PTS_CLOCK_HZ: Long = 90_000L

    /** 33 位时间戳的最大值（回绕点）。 */
    const val MAX_PTS_TICKS: Long = (1L shl 33) - 1L

    /** PES 起始前缀 + stream_id 长度。 */
    const val PREFIX_BYTES: Int = 4

    /** 无扩展头的 PES（padding/private_stream_2 等）头长。 */
    const val MIN_HEADER_BYTES: Int = 6

    /** 带扩展头时固定的 3 字节（flags1 + flags2 + header_data_length）。 */
    const val OPTIONAL_HEADER_BYTES: Int = 3

    /** 只有 PTS 时可选字段 5 字节。 */
    const val PTS_BYTES: Int = 5

    /** PTS+DTS 时可选字段 10 字节。 */
    const val PTS_DTS_BYTES: Int = 10

    private val NO_EXTENSION_STREAM_IDS = setOf(0xBC, 0xBE, 0xBF, 0xF0, 0xF1, 0xF2, 0xF8, 0xFF)

    /** [offset] 处是否是 PES 起始（00 00 01）。 */
    fun isPesStart(data: ByteArray, offset: Int): Boolean =
        offset >= 0 && offset + 3 <= data.size &&
            (data[offset].toInt() and 0xFF) == 0x00 &&
            (data[offset + 1].toInt() and 0xFF) == 0x00 &&
            (data[offset + 2].toInt() and 0xFF) == 0x01

    /**
     * 解析 [data] 的 [offset, offset+length) 处的 PES 头。
     *
     * 容错口径（R3）：畸形（长度不足、marker 位不对）**不抛异常**，
     * 能给出多少信息就给多少（时间戳给不出就为 null），完全不是 PES 时返回 null。
     */
    fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): PesHeader? {
        if (length < MIN_HEADER_BYTES || !isPesStart(data, offset)) return null
        if (offset + MIN_HEADER_BYTES > data.size) return null
        val streamId = data[offset + 3].toInt() and 0xFF
        val packetLength = readUInt16(data, offset + 4)
        if (streamId in NO_EXTENSION_STREAM_IDS) {
            return PesHeader(streamId, packetLength, scramblingControl = 0, ptsTicks = null, dtsTicks = null, headerBytes = MIN_HEADER_BYTES)
        }
        if (length < MIN_HEADER_BYTES + OPTIONAL_HEADER_BYTES) {
            return PesHeader(streamId, packetLength, 0, null, null, MIN_HEADER_BYTES)
        }
        val flags = data[offset + 6].toInt() and 0xFF
        val scrambling = (flags ushr 4) and 0x03
        val ptsDtsFlags = (data[offset + 7].toInt() ushr 6) and 0x03
        val headerDataLength = data[offset + 8].toInt() and 0xFF
        val headerBytes = MIN_HEADER_BYTES + OPTIONAL_HEADER_BYTES + headerDataLength
        var pts: Long? = null
        var dts: Long? = null
        val optionalStart = offset + MIN_HEADER_BYTES + OPTIONAL_HEADER_BYTES
        if (ptsDtsFlags == 0b10 && headerDataLength >= PTS_BYTES && optionalStart + PTS_BYTES <= offset + length) {
            pts = timestampFrom(data, optionalStart)
        } else if (ptsDtsFlags == 0b11 && headerDataLength >= PTS_DTS_BYTES && optionalStart + PTS_DTS_BYTES <= offset + length) {
            pts = timestampFrom(data, optionalStart)
            dts = timestampFrom(data, optionalStart + PTS_BYTES)
        }
        return PesHeader(streamId, packetLength, scrambling, pts, dts, headerBytes)
    }

    /**
     * 从 [offset] 处的 5 字节里取 33 位时间戳（PTS 或 DTS）。
     *
     * 位布局：`0010/0011 | PTS[32..30] | marker | PTS[29..22] | PTS[21..15] | marker | PTS[14..7] | PTS[6..0] | marker`。
     *
     * @return null 表示 marker 位不合法或字节不够——调用方应当当作「这个包没有时间戳」。
     */
    fun timestampFrom(data: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + PTS_BYTES > data.size) return null
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        val b3 = data[offset + 3].toInt() and 0xFF
        val b4 = data[offset + 4].toInt() and 0xFF
        if (b0 and 0x01 == 0 || b2 and 0x01 == 0 || b4 and 0x01 == 0) return null
        return ((b0 and 0x0E).toLong() shl 29) or
            (b1.toLong() shl 22) or
            ((b2 and 0xFE).toLong() shl 14) or
            (b3.toLong() shl 7) or
            ((b4 and 0xFE).toLong() shr 1)
    }

    /** 33 位 90 kHz 时间戳 → 毫秒（向下取整）。 */
    fun ticksToMs(ticks: Long): Long = ticks * 1000L / PTS_CLOCK_HZ

    /** 33 位回绕掩码（PTS/DTS/PCR base 都是 33 位）。 */
    fun maskTicks(ticks: Long): Long = ticks and MAX_PTS_TICKS
}

/**
 * 适配域里的 PCR（R3/R4，plan 4.3 第 1 条）。
 *
 * @param baseTicks 33 位 90 kHz 基值。
 * @param extension 9 位 27 MHz 扩展，`pcr = base * 300 + extension`。
 */
data class PcrInfo(val baseTicks: Long, val extension: Int) {

    /** 27 MHz 计数（PCR 的完整表示）。 */
    val pcr27MHz: Long get() = baseTicks * 300L + extension

    /** 毫秒（按 90 kHz 基值换算，向后兼容 TS 常见用法）。 */
    val ms: Long get() = PesParser.ticksToMs(baseTicks)
}

/**
 * 适配域（adaptation field）解析（R3/R4）：PCR、随机访问标志、不连续标志。
 */
object AdaptationField {

    const val FLAG_DISCONTINUITY_INDICATOR = 0x80
    const val FLAG_RANDOM_ACCESS_INDICATOR = 0x40
    const val FLAG_ELEMENTARY_STREAM_PRIORITY = 0x20
    const val FLAG_PCR = 0x10
    const val FLAG_OPCR = 0x08
    const val FLAG_SPLICING_POINT = 0x04
    const val FLAG_TRANSPORT_PRIVATE_DATA = 0x02
    const val FLAG_ADAPTATION_FIELD_EXTENSION = 0x01

    /** PCR 占 6 字节。 */
    const val PCR_BYTES: Int = 6

    /** 最少要有：adaptation_field_length + flags。 */
    private const val MIN_LENGTH_FOR_FLAGS = 2

    /** 最少要有：length + flags + 6 字节 PCR。 */
    private const val MIN_LENGTH_FOR_PCR = 8

    /** 适配域 flags 字节；适配域太短返回 null。 */
    fun flags(data: ByteArray, packetOffset: Int): Int? {
        if (!TsPackets.hasAdaptationField(data, packetOffset)) return null
        if (TsPackets.adaptationFieldLength(data, packetOffset) < MIN_LENGTH_FOR_FLAGS) return null
        return data[packetOffset + 5].toInt() and 0xFF
    }

    fun hasPcr(data: ByteArray, packetOffset: Int): Boolean =
        (flags(data, packetOffset) ?: 0) and FLAG_PCR != 0

    /** 随机访问标志（plan 4.3：与 IDR/IRAP 互为佐证）。 */
    fun randomAccessIndicator(data: ByteArray, packetOffset: Int): Boolean =
        (flags(data, packetOffset) ?: 0) and FLAG_RANDOM_ACCESS_INDICATOR != 0

    /** 不连续标志（拼接流的时间轴重置常伴随它）。 */
    fun discontinuityIndicator(data: ByteArray, packetOffset: Int): Boolean =
        (flags(data, packetOffset) ?: 0) and FLAG_DISCONTINUITY_INDICATOR != 0

    /**
     * 解析 PCR；没有 PCR 或长度不足返回 null。
     *
     * 位布局：base = b0<<25 | b1<<17 | b2<<9 | b3<<1 | b4>>7，extension = (b4&1)<<8 | b5。
     */
    fun pcr(data: ByteArray, packetOffset: Int): PcrInfo? {
        if (!TsPackets.hasAdaptationField(data, packetOffset)) return null
        if (TsPackets.adaptationFieldLength(data, packetOffset) < MIN_LENGTH_FOR_PCR) return null
        if (!hasPcr(data, packetOffset)) return null
        val start = packetOffset + 6
        if (start + PCR_BYTES > data.size) return null
        val b0 = data[start].toInt() and 0xFF
        val b1 = data[start + 1].toInt() and 0xFF
        val b2 = data[start + 2].toInt() and 0xFF
        val b3 = data[start + 3].toInt() and 0xFF
        val b4 = data[start + 4].toInt() and 0xFF
        val b5 = data[start + 5].toInt() and 0xFF
        val base = (b0.toLong() shl 25) or (b1.toLong() shl 17) or (b2.toLong() shl 9) or
            (b3.toLong() shl 1) or (b4.toLong() ushr 7)
        val extension = ((b4 and 0x01) shl 8) or b5
        return PcrInfo(base, extension)
    }
}
