package io.github.gua123.mediagate.data.storage.api

/**
 * 预取目标的换算（纯函数，**2026-10-03 用户反馈「第一次打开的视频，初次缓冲时间还是很长」后加**）。
 *
 * 两条经验：
 * - 只预取"开头"不够——**非 faststart 的 MP4 把 moov 放在文件末尾**，播放器一上来要读最后一段；
 * - 续播/拖拽落点也要预取——**没有 TS 索引时**用 `位置 / 时长 × 文件大小` 估算（码率基本恒定，够用）。
 */
object PrefetchTargets {

    /** 一个预取区间（字节）。 */
    data class Chunk(val offset: Long, val length: Long)

    /** 超过这个大小就按"大文件"对待（1 GiB）：多抓一段开头，并把三个目标一起发出去。 */
    const val BIG_FILE_BYTES: Long = 1L shl 30

    /** 大文件的头部抓几段（小文件 1 段足够：容器头 + 第一个关键帧都在里面）。 */
    const val BIG_FILE_HEAD_SEGMENTS: Int = 3

    /**
     * **打开一个文件之前该预取哪些区间**（纯函数，单测覆盖）。
     *
     * 2026-10-15 用户反馈「**针对上 G 大小的视频文件进行特殊缓存优化，现在文件第一次点还是会等待时间比较久**」⇒
     * 分级处理：小文件只抓首段（容器头）＋尾段（moov / cues 常在末尾）；**≥1 GiB 的大文件多抓两段开头**
     * ——大文件的索引与首个关键帧常常不在第一段里，而每次请求都要一个往返（实测公网 RTT ≈600 ms），
     * 与其让播放器一段一段去要，不如一次并发发出去（预取让路仍是硬约束：正在播的请求优先）。
     *
     * 去重规则：区间重叠就丢掉后面的（同一段不重复下载，也不与播放请求抢）。
     * 任何参数不可用都**跳过对应目标**，绝不瞎猜偏移。
     */
    fun openPlan(
        size: Long?,
        segmentBytes: Long,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
    ): List<Chunk> {
        // 大小未知（stat 失败 / 还没测出来）⇒ 什么都不预取：**宁可不动，也不要瞎猜偏移**
        val total = size ?: return emptyList()
        if (total <= 0L || segmentBytes <= 0L) return emptyList()
        val size = total
        val headSegments = if (size >= BIG_FILE_BYTES) BIG_FILE_HEAD_SEGMENTS else 1
        val headBytes = (segmentBytes * headSegments).coerceAtMost(size)
        val chunks = mutableListOf(Chunk(offset = 0L, length = headBytes))
        tailOffset(size, segmentBytes, headBytes)?.let { chunks += Chunk(offset = it, length = segmentBytes) }
        offsetForPosition(positionMs, durationMs, size)?.let { offset ->
            if (offset > 0L) {
                chunks += Chunk(offset = offset, length = segmentBytes.coerceAtMost(size - offset))
            }
        }
        // 去重：已有区间覆盖到的起点就不要再发一次（含"落在已有区间里"的情形）
        val result = mutableListOf<Chunk>()
        chunks.forEach { chunk ->
            val covered = result.any { it.offset <= chunk.offset && chunk.offset < it.offset + it.length }
            if (!covered) result += chunk
        }
        return result
    }

    /** 尾段偏移；文件比 `headBytes + segment` 还小时返回 null（不值得预取）。 */
    fun tailOffset(size: Long, segmentBytes: Long, headBytes: Long): Long? {
        if (size <= 0L || segmentBytes <= 0L) return null
        if (size <= headBytes + segmentBytes) return null
        return size - segmentBytes
    }

    /**
     * 按播放位置估算字节偏移（没有索引时的兜底）。
     *
     * @return 偏移；位置/时长/大小任一不可用时返回 null（**宁可不动，也不要瞎猜偏移**）。
     */
    fun offsetForPosition(positionMs: Long, durationMs: Long, size: Long): Long? {
        if (positionMs <= 0L || durationMs <= 0L || size <= 0L) return null
        val fraction = (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        return (size * fraction).toLong().coerceIn(0L, (size - 1).coerceAtLeast(0L))
    }
}
