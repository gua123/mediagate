package io.github.gua123.mediagate.data.storage.api

/**
 * 预取目标的换算（纯函数，**2026-10-03 用户反馈「第一次打开的视频，初次缓冲时间还是很长」后加**）。
 *
 * 两条经验：
 * - 只预取"开头"不够——**非 faststart 的 MP4 把 moov 放在文件末尾**，播放器一上来要读最后一段；
 * - 续播/拖拽落点也要预取——**没有 TS 索引时**用 `位置 / 时长 × 文件大小` 估算（码率基本恒定，够用）。
 */
object PrefetchTargets {

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
