package io.github.gua123.mediagate.data.storage.api

/**
 * **缓存上限的磁盘余量校验**（2026-10-20，借鉴 nPlayer 的 free_disk_space 检查）。
 *
 * 用户设置的缓存上限是"逻辑上限"，但如果手机磁盘本来就快满了，照上限建缓存会把最后一点空间吃掉。
 * 规则：
 * - 上限设为 **0 = 关闭缓存**（直接返回 0，调用方不再套缓存层）；
 * - 磁盘剩余未知（拿不到）时按用户设置执行，不瞎猜；
 * - 否则**保留 25% 磁盘余量**：可用缓存不超过剩余空间的 3/4；
 * - 可用空间连一个段都放不下 ⇒ 返回 0（关了比反复失败好）。
 *
 * 纯函数、可单测；真正取剩余空间由 :app 用 StatFs 做。
 */
object CacheBudget {

    /**
     * @param requestedBytes 用户设置的缓存上限；<= 0 表示关闭
     * @param freeBytes 磁盘剩余字节（< 0 表示未知）
     * @param segmentBytes 段大小
     * @return 实际使用的上限；0 表示不缓存
     */
    fun effectiveMaxBytes(requestedBytes: Long, freeBytes: Long, segmentBytes: Long): Long {
        if (requestedBytes <= 0L) return 0L
        if (freeBytes < 0L) return requestedBytes
        val usable = (freeBytes / 4L) * 3L
        val floor = segmentBytes.coerceAtLeast(1L)
        if (usable < floor) return 0L
        return minOf(requestedBytes, usable)
    }
}
