package io.github.gua123.mediagate.data.storage.api

/**
 * 分段缓存的运行统计（**2026-10-25 用户要求「缓存按推荐方案改造」**）。
 *
 * 只统计"段级"行为：命中/未命中、发起的下载请求数、累计下载字节、最近一段下载的吞吐。
 * 用途：
 * - 诊断卡里给用户/我判断"是缓存命中低，还是网络慢"；
 * - 自适应预读据此决定一次抓几段（吞吐低就少抓，别与播放抢）。
 *
 * @property segmentHits 直接命中本地段的次数。
 * @property segmentMisses 需要起下载任务的次数。
 * @property downloadRequests 实际发起的段下载任务数。
 * @property downloadedBytes 实际写进缓存的字节数（失败/作废的不算）。
 * @property recentThroughputBytesPerSec 最近一次成功下载的吞吐（EWMA，字节/秒；0 = 还没有样本）。
 */
data class CacheStats(
    val segmentHits: Long = 0L,
    val segmentMisses: Long = 0L,
    val downloadRequests: Long = 0L,
    val downloadedBytes: Long = 0L,
    val recentThroughputBytesPerSec: Long = 0L,
) {

    /** 段命中率（0..1）；没有任何访问时为 null（界面显示"—"，不瞎算）。 */
    val hitRate: Double?
        get() {
            val total = segmentHits + segmentMisses
            if (total <= 0L) return null
            return segmentHits.toDouble() / total.toDouble()
        }
}
