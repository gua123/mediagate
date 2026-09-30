package io.github.gua123.mediagate.media.tsext

/** MPEG-TS 包长（plan 4.3：188 字节定长包）。 */
const val TS_PACKET_SIZE: Int = 188

/** TS 同步字节 0x47。 */
const val TS_SYNC_BYTE: Int = 0x47

/**
 * TS 包头访问器（R3/R4，plan 4.3 第 1 条）：**纯 Kotlin**，不需要 Android，JVM 可测。
 *
 * 这些函数是索引器的热路径（2 GB 文件约 1100 万个包），所以只做整数位运算、**不分配对象**；
 * 需要「一个包」的完整视图时用 [TsPacketParser.parse]。
 *
 * 包头 4 字节布局：
 * ```
 * byte0: sync(0x47)
 * byte1: transport_error_indicator(1) | payload_unit_start_indicator(1) | transport_priority(1) | PID[12:8](5)
 * byte2: PID[7:0]
 * byte3: transport_scrambling_control(2) | adaptation_field_control(2) | continuity_counter(4)
 * ```
 */
object TsPackets {

    /** 适配域控制字：1 = 只有载荷，2 = 只有适配域，3 = 适配域 + 载荷，0 = 保留（非法包）。 */
    const val AFC_PAYLOAD_ONLY: Int = 1
    const val AFC_ADAPTATION_ONLY: Int = 2
    const val AFC_ADAPTATION_AND_PAYLOAD: Int = 3

    /** PID 0：PAT。 */
    const val PID_PAT: Int = 0x0000

    /** PID 0x1FFF：空包（stuffing）。 */
    const val PID_NULL: Int = 0x1FFF

    fun isSyncByte(value: Byte): Boolean = (value.toInt() and 0xFF) == TS_SYNC_BYTE

    fun transportErrorIndicator(data: ByteArray, offset: Int): Boolean =
        (data[offset + 1].toInt() and 0x80) != 0

    fun payloadUnitStartIndicator(data: ByteArray, offset: Int): Boolean =
        (data[offset + 1].toInt() and 0x40) != 0

    fun pid(data: ByteArray, offset: Int): Int =
        ((data[offset + 1].toInt() and 0x1F) shl 8) or (data[offset + 2].toInt() and 0xFF)

    fun scramblingControl(data: ByteArray, offset: Int): Int =
        (data[offset + 3].toInt() ushr 6) and 0x03

    fun adaptationFieldControl(data: ByteArray, offset: Int): Int =
        (data[offset + 3].toInt() ushr 4) and 0x03

    fun continuityCounter(data: ByteArray, offset: Int): Int =
        data[offset + 3].toInt() and 0x0F

    fun hasAdaptationField(data: ByteArray, offset: Int): Boolean {
        val control = adaptationFieldControl(data, offset)
        return control == AFC_ADAPTATION_ONLY || control == AFC_ADAPTATION_AND_PAYLOAD
    }

    fun hasPayload(data: ByteArray, offset: Int): Boolean {
        val control = adaptationFieldControl(data, offset)
        return control == AFC_PAYLOAD_ONLY || control == AFC_ADAPTATION_AND_PAYLOAD
    }

    /** 适配域在整个包内的起始偏移（指向 adaptation_field_length 那一个字节）；无适配域返回 -1。 */
    fun adaptationFieldOffset(data: ByteArray, offset: Int): Int =
        if (hasAdaptationField(data, offset)) offset + 4 else -1

    /** 适配域总长度（**含** adaptation_field_length 字节本身）；无适配域返回 0。 */
    fun adaptationFieldLength(data: ByteArray, offset: Int): Int {
        if (!hasAdaptationField(data, offset)) return 0
        val declared = data[offset + 4].toInt() and 0xFF
        val available = TS_PACKET_SIZE - 4
        // 畸形包：声明的长度超出包内剩余空间时按剩余空间夹住，绝不让越界传播出去
        return (declared + 1).coerceAtMost(available)
    }

    /** 载荷在包内的起始偏移；无载荷返回 -1。 */
    fun payloadOffset(data: ByteArray, offset: Int): Int {
        if (!hasPayload(data, offset)) return -1
        val cursor = offset + 4 + adaptationFieldLength(data, offset)
        val end = offset + TS_PACKET_SIZE
        return if (cursor < end) cursor else -1
    }

    /** 载荷字节数；无载荷返回 0。 */
    fun payloadLength(data: ByteArray, offset: Int): Int {
        val start = payloadOffset(data, offset)
        return if (start < 0) 0 else offset + TS_PACKET_SIZE - start
    }

    /**
     * 从 [from] 起找下一个可信的同步位置（R3 容错：非 0x47 起手的流要能重新对齐）。
     *
     * 判据：该位置是 0x47，**并且**再隔一个包长（188 字节）也是 0x47；
     * 空间还够时再确认第三个包（三重确认，把随机数据的误判概率压到 ~1/2^24，见 TsIndexerTest 的大块非 TS 用例）。
     * 缓冲区尾部不足以确认时按现有信息退化判断。
     *
     * @return 找到的下标；找不到返回 -1。
     */
    fun findNextSync(data: ByteArray, from: Int, end: Int = data.size): Int {
        var index = from.coerceAtLeast(0)
        val limit = minOf(end, data.size)
        while (index < limit) {
            if (isSyncByte(data[index])) {
                val second = index + TS_PACKET_SIZE
                val third = index + 2 * TS_PACKET_SIZE
                val confirmed = when {
                    third < limit -> isSyncByte(data[second]) && isSyncByte(data[third])
                    second < limit -> isSyncByte(data[second])
                    else -> true
                }
                if (confirmed) return index
            }
            index++
        }
        return -1
    }
}

/**
 * 一个 TS 包的只读视图（R3）：字段解析自 [buffer] 的 [bufferOffset] 处，**不复制载荷**。
 *
 * 只在测试与「需要完整包语义」的地方用；索引器热路径直接用 [TsPackets] 的整数访问器。
 */
class TsPacket internal constructor(
    val bufferOffset: Int,
    val pid: Int,
    val transportError: Boolean,
    val payloadUnitStart: Boolean,
    val scramblingControl: Int,
    val adaptationFieldControl: Int,
    val continuityCounter: Int,
    val adaptationFieldOffset: Int,
    val adaptationFieldLength: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
) {

    /** 是否带适配域。 */
    val hasAdaptationField: Boolean
        get() = adaptationFieldControl == TsPackets.AFC_ADAPTATION_ONLY ||
            adaptationFieldControl == TsPackets.AFC_ADAPTATION_AND_PAYLOAD

    /** 是否带载荷。 */
    val hasPayload: Boolean
        get() = adaptationFieldControl == TsPackets.AFC_PAYLOAD_ONLY ||
            adaptationFieldControl == TsPackets.AFC_ADAPTATION_AND_PAYLOAD

    /** 是否加扰（加扰的载荷不能用于 NAL 扫描，plan 4.3 要跳过）。 */
    val isScrambled: Boolean get() = scramblingControl != 0

    /** 是否空包（stuffing）。 */
    val isNullPacket: Boolean get() = pid == TsPackets.PID_NULL

    /** 适配域内容（不含 adaptation_field_length 字节本身）；无适配域返回空数组。 */
    fun adaptationField(buffer: ByteArray): ByteArray {
        if (!hasAdaptationField || adaptationFieldLength <= 1) return ByteArray(0)
        val start = adaptationFieldOffset + 1
        val length = adaptationFieldLength - 1
        return buffer.copyOfRange(start, start + length)
    }

    /** 载荷副本；无载荷返回空数组。 */
    fun payload(buffer: ByteArray): ByteArray {
        if (payloadOffset < 0 || payloadLength <= 0) return ByteArray(0)
        return buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)
    }

    override fun toString(): String =
        "TsPacket(pid=0x${pid.toString(16)}, pusi=$payloadUnitStart, afc=$adaptationFieldControl, cc=$continuityCounter)"
}

/** [TsPacket] 的解析入口（R3）。 */
object TsPacketParser {

    /**
     * 解析 [buffer] 中 [offset] 处的 188 字节包。
     *
     * @return null 表示**不是**一个可用的 TS 包：长度不足、同步字节不是 0x47、或
     *   adaptation_field_control = 0（保留值，包非法）。调用方据此决定跳过/重同步。
     */
    fun parse(buffer: ByteArray, offset: Int = 0): TsPacket? {
        if (offset < 0 || offset + TS_PACKET_SIZE > buffer.size) return null
        if (!TsPackets.isSyncByte(buffer[offset])) return null
        val control = TsPackets.adaptationFieldControl(buffer, offset)
        if (control == 0) return null
        return TsPacket(
            bufferOffset = offset,
            pid = TsPackets.pid(buffer, offset),
            transportError = TsPackets.transportErrorIndicator(buffer, offset),
            payloadUnitStart = TsPackets.payloadUnitStartIndicator(buffer, offset),
            scramblingControl = TsPackets.scramblingControl(buffer, offset),
            adaptationFieldControl = control,
            continuityCounter = TsPackets.continuityCounter(buffer, offset),
            adaptationFieldOffset = TsPackets.adaptationFieldOffset(buffer, offset),
            adaptationFieldLength = TsPackets.adaptationFieldLength(buffer, offset),
            payloadOffset = TsPackets.payloadOffset(buffer, offset),
            payloadLength = TsPackets.payloadLength(buffer, offset),
        )
    }
}
