package io.github.gua123.mediagate.data.storage.api

/**
 * 把"要预取的段区间"切成每批 [concurrency] 段（**2026-10-14 用户要求**：
 * 「可否单文件多线程，并且同时加载多个文件，定在同文件夹下，连接连接并且在打开文件夹时自动进行缓存」）。
 *
 * 设计口径（**与用户此前"把并发去掉"的要求并存**）：
 * - **播放路径不并发**：正在播的那一段仍旧是"一个请求顺序写完"，行为可预测、不和播放抢；
 * - **预取路径才并发**：同一文件一次发 [concurrency] 个 Range 请求（隐藏往返延迟），
 *   而且**按段单飞**保证"预取正在下的段"与"播放要的段"是同一个任务，不会重复下载。
 *
 * 纯函数在这里：批怎么切、边界怎么算，都能写进单测（这段逻辑曾在 0.1.43 踩过
 * "许可池被一个任务抢光 ⇒ 并发退化成串行"的坑）。
 */
object SegmentBatches {

    /** 预取时的默认并发（同一文件同时几个请求）。 */
    const val DEFAULT_PREFETCH_CONCURRENCY: Int = 4

    /**
     * 把段下标 [first]..[last] 切成每批最多 [concurrency] 个。
     *
     * @return 每批的段下标列表；区间非法（last < first）时返回空表。
     */
    fun batch(first: Long, last: Long, concurrency: Int = DEFAULT_PREFETCH_CONCURRENCY): List<List<Long>> {
        if (last < first) return emptyList()
        val size = if (concurrency <= 0) 1 else concurrency
        val result = mutableListOf<List<Long>>()
        var current = first
        while (current <= last) {
            val end = minOf(current + size - 1, last)
            result += (current..end).toList()
            current = end + 1
        }
        return result
    }
}
