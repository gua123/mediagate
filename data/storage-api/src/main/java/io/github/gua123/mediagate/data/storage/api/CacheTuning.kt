
package io.github.gua123.mediagate.data.storage.api

/**
 * 分段缓存的**可调参数**（2026-10-03 用户要求：「我自己调一下并发数，你加到设置里吧」）。
 *
 * 用户实测公网 < 1 MB/s，想自己试"多开几条连接会不会快一点"——这类参数不该写死，
 * 但也不能让用户填出会把手机打死或把服务器惹毛的值，所以全部经 [normalized] 夹到安全区间。
 *
 * **哪些能中途改、哪些不能**：
 * - [chunkBytes] / [parallelChunks] / [readAheadSegments]：**即时生效**（每次取块/预读时现读）；
 * - [segmentBytes]：**只能在下次连接时生效**——段大小决定磁盘上段文件的切分方式，
 *   中途改会让已缓存的段对不上号（等于缓存作废），所以由 App 在重建远端根目录时应用。
 */
data class CacheTuning(
    /** 段大小（字节）：一次网络请求的最小单位，也是磁盘缓存文件的大小。 */
    val segmentBytes: Long = SegmentedCacheBackend.DEFAULT_SEGMENT_BYTES,
    /** 段内拆块大小（字节）：一个段拆成若干块**并发**取。 */
    val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    /** 并发块数上限（1..[MAX_PARALLEL_CHUNKS]）。 */
    val parallelChunks: Int = DEFAULT_PARALLEL_CHUNKS,
    /** 预读后续段数（0..[MAX_READ_AHEAD]）。 */
    val readAheadSegments: Int = DEFAULT_READ_AHEAD_SEGMENTS,
) {

    /** 夹到安全区间：非法/越界值一律退回默认，绝不让一个坏值把播放打死。 */
    fun normalized(): CacheTuning = CacheTuning(
        segmentBytes = segmentBytes.takeIf { it in SEGMENT_CHOICES } ?: SegmentedCacheBackend.DEFAULT_SEGMENT_BYTES,
        chunkBytes = chunkBytes.takeIf { it in CHUNK_CHOICES } ?: DEFAULT_CHUNK_BYTES,
        parallelChunks = parallelChunks.coerceIn(MIN_PARALLEL_CHUNKS, MAX_PARALLEL_CHUNKS),
        readAheadSegments = readAheadSegments.coerceIn(0, MAX_READ_AHEAD),
    )

    companion object {

        /** 并发块数下限（1 = 不并发，纯顺序下载）。 */
        const val MIN_PARALLEL_CHUNKS: Int = 1

        /** 并发块数上限（8；再多手机与服务端都吃不消）。 */
        const val MAX_PARALLEL_CHUNKS: Int = 8

        /** 预读段数上限。 */
        const val MAX_READ_AHEAD: Int = 4

        /** 段大小可选值（设置页按这些值给选项）。 */
        val SEGMENT_CHOICES: List<Long> = listOf(
            1L * 1024 * 1024,
            2L * 1024 * 1024,
            SegmentedCacheBackend.DEFAULT_SEGMENT_BYTES,
            8L * 1024 * 1024,
        )

        /** 块大小可选值。 */
        val CHUNK_CHOICES: List<Long> = listOf(
            512L * 1024,
            DEFAULT_CHUNK_BYTES,
            2L * 1024 * 1024,
            // 4 MB = 一个 4 MB 段只用 1 个请求：**公网高延迟链路上最省往返**（2026-10-03 加）
            4L * 1024 * 1024,
        )

        /** 预读段数可选值。 */
        val READ_AHEAD_CHOICES: List<Int> = listOf(0, 1, 2, 3, 4)

        /**
         * 从持久化值还原（DataStore 里存的是"人话"：MB / KB / 个数）。
         *
         * 任何一项缺失或非法都退回该项默认——旧版本升级上来不带这些键，属于正常情况。
         */
        fun fromStored(
            segmentMb: Int? = null,
            chunkKb: Int? = null,
            parallelChunks: Int? = null,
            readAheadSegments: Int? = null,
        ): CacheTuning = CacheTuning(
            segmentBytes = segmentMb?.let { it.toLong() * 1024 * 1024 } ?: SegmentedCacheBackend.DEFAULT_SEGMENT_BYTES,
            chunkBytes = chunkKb?.let { it.toLong() * 1024 } ?: DEFAULT_CHUNK_BYTES,
            parallelChunks = parallelChunks ?: DEFAULT_PARALLEL_CHUNKS,
            readAheadSegments = readAheadSegments ?: DEFAULT_READ_AHEAD_SEGMENTS,
        ).normalized()
    }
}
