package io.github.gua123.mediagate.media.tsext

/**
 * PSI 段（section）拼装器（R3，plan 4.3 第 1 条「解析 PAT/PMT」）。
 *
 * TS 包只有 184 字节载荷，PAT/PMT 段可能跨包；本类把某个 PID 的载荷按
 * `pointer_field` + `section_length` 拼回完整段，交给 [PatParser] / [PmtParser]。
 *
 * 规则（ISO/IEC 13818-1）：
 * - `payload_unit_start_indicator = 1` 的包，载荷首字节是 `pointer_field`，
 *   表示「新段从载荷的第 pointer_field 字节之后开始」；pointer_field 之前的字节用于补完上一个段；
 * - 一个包的载荷里可以有多个段，段尾剩下的 0xFF 是填充字节；
 * - 段长超过 [maxSectionLength] 视为畸形，丢弃已缓存内容（不崩）。
 *
 * **非线程安全**：每个 PID 一个实例，只在扫描协程里用。
 */
class PsiSectionAssembler(
    private val maxSectionLength: Int = DEFAULT_MAX_SECTION_LENGTH,
) {

    private val buffer = ByteArray(maxSectionLength)

    private var size = 0

    /** 当前缓存了多少字节（诊断/测试用）。 */
    val pendingBytes: Int get() = size

    /** 清空缓存（遇到不连续或重新开始时调用）。 */
    fun reset() {
        size = 0
    }

    /**
     * 喂入一个 TS 包的载荷。
     *
     * @param payloadUnitStart 该包是否带 `payload_unit_start_indicator`。
     * @return 本次喂入后**拼完整**的段（可能 0 个或多个），每个都是完整的 `table_id ... CRC` 字节数组。
     */
    fun feed(payload: ByteArray, offset: Int, length: Int, payloadUnitStart: Boolean): List<ByteArray> {
        val out = ArrayList<ByteArray>(1)
        if (length <= 0 || offset < 0 || offset + length > payload.size) return out
        val end = offset + length
        if (payloadUnitStart) {
            val pointer = payload[offset].toInt() and 0xFF
            var cursor = offset + 1
            if (size > 0) {
                // pointer_field 之前是上一个段的尾巴
                val head = minOf(pointer, end - cursor).coerceAtLeast(0)
                if (head > 0) appendAndDrain(payload, cursor, head, out)
            }
            cursor += pointer
            if (cursor >= end) return out
            // 新段开始：上一个没拼完的段作废
            size = 0
            appendAndDrain(payload, cursor, end - cursor, out)
        } else if (size > 0) {
            appendAndDrain(payload, offset, length, out)
        }
        return out
    }

    private fun appendAndDrain(source: ByteArray, from: Int, count: Int, out: MutableList<ByteArray>) {
        var sourceOffset = from
        var remaining = count
        while (remaining > 0) {
            val space = buffer.size - size
            if (space <= 0) {
                // 防御：异常累积时直接丢弃，保证不越界
                size = 0
                return
            }
            val chunk = minOf(space, remaining)
            System.arraycopy(source, sourceOffset, buffer, size, chunk)
            size += chunk
            sourceOffset += chunk
            remaining -= chunk
            drain(out)
        }
    }

    private fun drain(out: MutableList<ByteArray>) {
        while (true) {
            if (size <= 0) return
            // 填充字节
            if ((buffer[0].toInt() and 0xFF) == 0xFF) {
                size = 0
                return
            }
            if (size < SECTION_HEADER_BYTES) return
            val sectionLength = ((buffer[1].toInt() and 0x0F) shl 8) or (buffer[2].toInt() and 0xFF)
            val total = SECTION_HEADER_BYTES + sectionLength
            if (sectionLength == 0 || total > buffer.size) {
                // 畸形段：丢弃缓存，等下一个 PUSI
                size = 0
                return
            }
            if (size < total) return
            out += buffer.copyOfRange(0, total)
            val rest = size - total
            if (rest > 0) System.arraycopy(buffer, total, buffer, 0, rest)
            size = rest
        }
    }

    companion object {

        /** PSI 段头 3 字节：table_id + section_syntax/length。 */
        const val SECTION_HEADER_BYTES: Int = 3

        /** 标准段长上限（section_length 是 12 位，最大 4095）。 */
        const val DEFAULT_MAX_SECTION_LENGTH: Int = 4096
    }
}
