
package io.github.gua123.mediagate.data.storage.api

/**
 * 分段缓存的**可调参数**（2026-10-03 用户口径：去掉并发、保留单文件缓存；
 * 随后又要求「在设置中增加缓存大小按 gb 为单位」）。
 *
 * 三件事：
 * - [segmentBytes]：缓存的最小单位（越大越省请求，越小越省磁盘）；
 * - [maxBytes]：**缓存总上限**（GB 口径，设置页按 1/2/4/8 GB 给选项）；
 * - [readAheadSegments]：读到某段后顺手把后面几段也拉进来（0 = 不预读）。
 *
 * 生效口径：**段大小与总上限只能在下次连接时生效**（它们决定磁盘上已有段怎么算、淘汰预算多少，
 * 中途改会让缓存对不上号）；预读段数随时可改、即时生效。
 */
data class CacheTuning(
    /** 段大小（字节）：一次网络请求的最小单位，也是磁盘缓存文件的大小。 */
    val segmentBytes: Long = SegmentedCacheBackend.DEFAULT_SEGMENT_BYTES,
    /** 缓存总上限（字节）：设置页按 GB 展示。 */
    val maxBytes: Long = SegmentedCacheBackend.DEFAULT_MAX_BYTES,
    /** 预读后续段数（0..[MAX_READ_AHEAD]）。 */
    val readAheadSegments: Int = SegmentedCacheBackend.DEFAULT_READ_AHEAD_SEGMENTS,
) {

    /** 夹到安全区间：非法/越界值一律退回默认，绝不让一个坏值把播放打死。 */
    fun normalized(): CacheTuning {
        val segment = segmentBytes.takeIf { it in SEGMENT_CHOICES } ?: SegmentedCacheBackend.DEFAULT_SEGMENT_BYTES
        val total = (maxBytes.takeIf { it in CACHE_CHOICES } ?: SegmentedCacheBackend.DEFAULT_MAX_BYTES)
            // 总上限不能小于一个段（否则一段都存不下，缓存直接失效）
            .coerceAtLeast(segment)
        return CacheTuning(
            segmentBytes = segment,
            maxBytes = total,
            readAheadSegments = readAheadSegments.coerceIn(0, MAX_READ_AHEAD),
        )
    }

    companion object {

        /** 预读段数上限。 */
        const val MAX_READ_AHEAD: Int = 4

        /** 段大小可选值（设置页按这些值给选项）。 */
        val SEGMENT_CHOICES: List<Long> = listOf(
            1L * 1024 * 1024,
            2L * 1024 * 1024,
            SegmentedCacheBackend.DEFAULT_SEGMENT_BYTES,
            8L * 1024 * 1024,
        )

        /** 缓存总上限可选值：**按 GB**（用户要求）。 */
        val CACHE_CHOICES: List<Long> = listOf(
            1L * 1024 * 1024 * 1024,
            2L * 1024 * 1024 * 1024,
            4L * 1024 * 1024 * 1024,
            8L * 1024 * 1024 * 1024,
        )

        /** 预读段数可选值。 */
        val READ_AHEAD_CHOICES: List<Int> = listOf(0, 1, 2, 3, 4)

        /**
         * 从持久化值还原（DataStore 里存的是"人话"：MB / GB / 段数）。
         *
         * 任何一项缺失或非法都退回该项默认——旧版本升级上来不带这些键，属于正常情况。
         */
        fun fromStored(
            segmentMb: Int? = null,
            cacheGb: Int? = null,
            readAheadSegments: Int? = null,
        ): CacheTuning = CacheTuning(
            segmentBytes = segmentMb?.let { it.toLong() * 1024 * 1024 } ?: SegmentedCacheBackend.DEFAULT_SEGMENT_BYTES,
            maxBytes = cacheGb?.let { it.toLong() * 1024 * 1024 * 1024 } ?: SegmentedCacheBackend.DEFAULT_MAX_BYTES,
            readAheadSegments = readAheadSegments ?: SegmentedCacheBackend.DEFAULT_READ_AHEAD_SEGMENTS,
        ).normalized()
    }
}
