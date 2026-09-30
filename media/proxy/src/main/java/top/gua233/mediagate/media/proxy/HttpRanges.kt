package io.github.gua123.mediagate.media.proxy

/**
 * HTTP `Range` 头解析（R4 拖拽 seek / plan 4.2 的 Range 口径，代理侧实现）。
 *
 * 支持 RFC 7233 的三种单段形式：
 * - `bytes=start-end`：闭区间，end 超过文件末尾时截到末尾；
 * - `bytes=start-`：从 start 到末尾；
 * - `bytes=-suffix`：最后 suffix 个字节。
 *
 * 不支持的形态一律**忽略 Range 头并按 200 全量返回**（RFC 允许）：
 * 多段 Range（`bytes=0-1,5-6`）、单位不是 bytes、语法错误（含非数字、缺横线）。
 * 唯一返回 [RangeSpec.Unsatisfiable]（→ 416）的情况是「语法合法但字节范围落在文件之外」。
 *
 * 纯 Kotlin，JVM 可直接单测。
 */
object HttpRanges {

    /** 单次 Range 解析结果。 */
    sealed interface RangeSpec {

        /** 无 Range / 不支持的 Range → 200 全量。 */
        data object Full : RangeSpec

        /** 可满足的一段（含端点）；`length = end - start + 1`。 */
        data class Partial(val start: Long, val end: Long) : RangeSpec {
            /** 本段字节数。 */
            val length: Long get() = end - start + 1
        }

        /** 语法合法但越界（或空文件）→ 416，并带上「总长未知」形式的 Content-Range 头。 */
        data object Unsatisfiable : RangeSpec
    }

    /**
     * 解析并判定 satisfiable。
     *
     * @param header `Range` 头原文；null / 空串 → [RangeSpec.Full]。
     * @param size 资源总长度；负数表示未知（此时不做 Range，返回 [RangeSpec.Full]）。
     */
    fun resolve(header: String?, size: Long): RangeSpec {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return RangeSpec.Full
        if (!raw.startsWith(BYTES_PREFIX, ignoreCase = true)) return RangeSpec.Full
        // 多段 Range 不支持：不是「越界」，而是「不满足本服务的能力」，按 RFC 忽略并全量返回
        if (raw.contains(',')) return RangeSpec.Full
        val spec = raw.substring(BYTES_PREFIX.length).trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeSpec.Full
        val firstText = spec.substring(0, dash).trim()
        val lastText = spec.substring(dash + 1).trim()
        // 长度未知（远端流式响应）：给不出 Content-Range，退回全量
        if (size < 0) return RangeSpec.Full
        if (firstText.isEmpty()) {
            // 后缀形式 bytes=-N：N = 0 与空文件都不可满足
            val suffix = lastText.toLongOrNull() ?: return RangeSpec.Full
            if (suffix <= 0 || size == 0L) return RangeSpec.Unsatisfiable
            val start = (size - suffix).coerceAtLeast(0)
            return RangeSpec.Partial(start, size - 1)
        }
        val start = firstText.toLongOrNull() ?: return RangeSpec.Full
        if (start < 0) return RangeSpec.Full
        if (start >= size) return RangeSpec.Unsatisfiable
        if (lastText.isEmpty()) return RangeSpec.Partial(start, size - 1)
        val end = lastText.toLongOrNull() ?: return RangeSpec.Full
        // 语法合法但 last < first → RFC 要求忽略整个 Range 头（不是 416）
        if (end < start) return RangeSpec.Full
        return RangeSpec.Partial(start, minOf(end, size - 1))
    }

    private const val BYTES_PREFIX = "bytes="
}
