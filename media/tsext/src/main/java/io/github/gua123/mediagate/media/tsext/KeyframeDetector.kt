package io.github.gua123.mediagate.media.tsext

/**
 * NAL 单元扫描（R3/R4，plan 4.3 第 1 条「IDR/IRAP 检测」）。
 *
 * 起手码两种形态都要认：3 字节 `00 00 01` 与 4 字节 `00 00 00 01`——
 * 后者在扫描 `00 00 01` 时会在第 2 个字节处命中，NAL 头仍然是 `01` 之后的那个字节，天然兼容。
 *
 * 纯 Kotlin，JVM 可测；都在 ES 载荷上原地扫描，不复制数据。
 */
object NalUnits {

    /**
     * 遍历 [data] 的 `[offset, offset+length)` 区间内所有 NAL 头的下标。
     *
     * 只认「起手码之后紧跟的字节」是 NAL 头：H.264 的低 5 位 / H.265 的高 6 位（去掉 forbidden 位）。
     */
    internal inline fun forEachNalHeader(
        data: ByteArray,
        offset: Int,
        length: Int,
        action: (headerIndex: Int) -> Unit,
    ) {
        if (length <= 0) return
        val end = minOf(offset + length, data.size)
        var index = offset.coerceAtLeast(0)
        while (index + 3 < end) {
            if (data[index] == ZERO && data[index + 1] == ZERO && data[index + 2] == ONE) {
                val header = index + 3
                if (header >= end) return
                action(header)
                index = header + 1
            } else {
                index++
            }
        }
    }

    /** H.264 NAL 类型列表（`byte & 0x1F`），按出现顺序；测试与诊断用。 */
    fun h264Types(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Int> {
        val types = ArrayList<Int>(8)
        forEachNalHeader(data, offset, length) { header -> types += data[header].toInt() and 0x1F }
        return types
    }

    /** H.265 NAL 类型列表（`(byte >> 1) & 0x3F`），按出现顺序；测试与诊断用。 */
    fun h265Types(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Int> {
        val types = ArrayList<Int>(8)
        forEachNalHeader(data, offset, length) { header -> types += (data[header].toInt() and 0x7E) ushr 1 }
        return types
    }

    private val ZERO: Byte = 0
    private val ONE: Byte = 1
}

/**
 * 关键帧识别（R3/R4，plan 4.3 第 1 条）。
 *
 * 口径与 plan 一致：
 * - **H.264**：起手码后的 NAL type = 5（IDR）即关键帧；SPS/PPS/AUD 单独出现不算。
 * - **H.265**：NAL type 19/20/21（IDR_W_RADL / IDR_N_LP / CRA_NUT）即关键帧；
 *   本实现按 H.265 规范的 IRAP 全集 16..23 判定（BLA/IDR/CRA 都是随机访问点，19/20/21 是其中最常见的三个），
 *   这样遇到 BLA 开头的流也不会漏掉随机访问点。
 */
object KeyframeDetector {

    /** H.264 IDR（Instantaneous Decoding Refresh）。 */
    const val H264_NAL_IDR: Int = 5

    /** H.264 非 IDR 编码条带（用于反例测试）。 */
    const val H264_NAL_NON_IDR: Int = 1

    /** H.265 IDR_W_RADL。 */
    const val H265_NAL_IDR_W_RADL: Int = 19

    /** H.265 IDR_N_LP（无前导 IDR）。 */
    const val H265_NAL_IDR_N_LP: Int = 20

    /** H.265 CRA_NUT（Clean Random Access）。 */
    const val H265_NAL_CRA: Int = 21

    /** H.265 IRAP（Intra Random Access Point）类型全集：16..23（BLA / IDR / CRA）。 */
    val H265_IRAP_TYPES: IntRange = 16..23

    /** [type] 是否 H.265 的 IRAP。 */
    fun isH265IrapType(type: Int): Boolean = type in H265_IRAP_TYPES

    /** 载荷里是否含 H.264 IDR（找不到起手码/没有 NAL 时返回 false，不抛异常）。 */
    fun isH264Idr(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Boolean {
        var found = false
        NalUnits.forEachNalHeader(data, offset, length) { header ->
            if ((data[header].toInt() and 0x1F) == H264_NAL_IDR) {
                found = true
            }
        }
        return found
    }

    /** 载荷里是否含 H.265 IRAP（16..23，含 19/20/21）。 */
    fun isH265Irap(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Boolean {
        var found = false
        NalUnits.forEachNalHeader(data, offset, length) { header ->
            if (isH265IrapType((data[header].toInt() and 0x7E) ushr 1)) {
                found = true
            }
        }
        return found
    }

    /**
     * 按编码类型判关键帧（plan 4.3 第 1 条）。
     *
     * 非 H.264/H.265（MPEG-2 / MPEG-4 / 其它）返回 false：这类流本轮不扫帧内图，
     * 索引点退化为「每个 PES 起点」，由调用方决定是否接受。
     */
    fun isKeyframe(
        codec: TsVideoCodec,
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Boolean = when (codec) {
        TsVideoCodec.H264 -> isH264Idr(data, offset, length)
        TsVideoCodec.H265 -> isH265Irap(data, offset, length)
        else -> false
    }
}
