package io.github.gua123.mediagate.feature.browser

import io.github.gua123.mediagate.core.model.MediaKind
import io.github.gua123.mediagate.core.model.MediaKindGuesser
import io.github.gua123.mediagate.core.model.RemoteEntry

/**
 * 列表封面**空闲预热**的策略（**2026-10-14 用户提出**：「可以在打开软件的空闲时加载视频文件的头部，
 * 并以视频文件的开头封面为结果；例如未加载视频文件开头多长时间时封面图片不加载，
 * 在已加载时刷新视频文件的封面截图」）。
 *
 * 为什么要有"策略"这一层：预热是**低优先级**的活，必须自己排队让路——
 * 列表刚出来先别抢、用户一操作就停、同一远端连接别开太多并发，否则"预热"会变成"拖慢"。
 * 纯函数在这里，边界（哪些条目、几个、隔多久）才写得进单测。
 */
object CoverWarmupPolicy {

    /** 一个目录最多预热多少个（只挑最前面这些，其余等用户滚到再说）。 */
    const val DEFAULT_BATCH: Int = 12

    /**
     * 并发上限（**用户问"多线程会不会更快"**）。
     *
     * 结论：**能快，但只开 2 个**——同一台远端服务器上并发太多会互相抢带宽、
     * 还可能被服务端限流，反而拖慢正在播的那条链路（带宽类问题在第 3 层解决，不在并发数上）。
     */
    const val DEFAULT_CONCURRENCY: Int = 2

    /** 列表出来之后先等一会儿再动手：用户多半正在翻，别跟他的滚动抢。 */
    const val DELAY_AFTER_LIST_MS: Long = 3_000L

    /** 每批之间留的间隔（给播放/交互让路；预热慢一点没关系，别打扰正在看的）。 */
    const val GAP_BETWEEN_BATCHES_MS: Long = 600L

    /**
     * 挑出要预热的条目。
     *
     * 只挑**视频**：图片本来就小、马上要显示；音频要读内嵌封面（更重）；
     * 目录不预热。用户的诉求也是"视频文件的头部"。
     */
    fun targets(entries: List<RemoteEntry>, limit: Int = DEFAULT_BATCH): List<RemoteEntry> {
        if (limit <= 0) return emptyList()
        return entries.asSequence()
            .filter { !it.isDirectory }
            .filter { MediaKindGuesser.guess(it.name) == MediaKind.VIDEO }
            .take(limit)
            .toList()
    }

    /** 把目标切成每批 [DEFAULT_CONCURRENCY] 个（并发发生在批内，批间留间隔让路）。 */
    fun batches(targets: List<RemoteEntry>, concurrency: Int = DEFAULT_CONCURRENCY): List<List<RemoteEntry>> {
        if (targets.isEmpty()) return emptyList()
        val size = if (concurrency <= 0) 1 else concurrency
        return targets.chunked(size)
    }
}
