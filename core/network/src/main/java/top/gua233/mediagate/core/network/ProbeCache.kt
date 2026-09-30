package io.github.gua123.mediagate.core.network

import java.util.concurrent.ConcurrentHashMap

/**
 * 探测结果缓存（**R7**：plan 4.5「结果缓存 60 s，`NetworkCallback` 变化即失效」）。
 *
 * 为什么需要它：切一次 Wi-Fi / 进一次连接页都会触发一轮探测，没有缓存就会反复打服务端；
 * 但缓存又不能太长——网络换了还拿旧结果就是"明明连得上却提示失败"。
 * 于是：**60 s TTL + 网络变化手动 [invalidate]**。
 *
 * 时间是注入的（[ProbeClock]），单测里把时钟往前拨 61 s 就能验证过期。
 *
 * @param clock 单调时钟。
 * @param ttlMs 存活时长（默认 [DEFAULT_TTL_MS] = 60 s）。
 */
class ProbeCache<T : Any>(
    private val clock: ProbeClock = ProbeClock.System,
    val ttlMs: Long = DEFAULT_TTL_MS,
) {

    private data class Entry<T>(val value: T, val storedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry<T>>()

    /**
     * 取缓存值；不存在或已过期返回 null（过期的条目顺带清掉）。
     *
     * @param atMs 判定时刻，默认 [ProbeClock.nowMs]（单测可显式传）。
     */
    fun get(key: String, atMs: Long = clock.nowMs()): T? {
        val entry = entries[key] ?: return null
        if (atMs - entry.storedAtMs >= ttlMs) {
            entries.remove(key, entry)
            return null
        }
        return entry.value
    }

    /** 写缓存。 */
    fun put(key: String, value: T, atMs: Long = clock.nowMs()) {
        entries[key] = Entry(value, atMs)
    }

    /** 取缓存并附带年龄（毫秒）；未命中返回 -1。界面用来提示「60 秒内复用结果」。 */
    fun ageMs(key: String, atMs: Long = clock.nowMs()): Long {
        val entry = entries[key] ?: return -1L
        if (atMs - entry.storedAtMs >= ttlMs) {
            entries.remove(key, entry)
            return -1L
        }
        return atMs - entry.storedAtMs
    }

    /**
     * 失效缓存。
     *
     * @param key 指定 key；null = 全部清空（网络变化时用）。
     */
    fun invalidate(key: String? = null) {
        if (key == null) entries.clear() else entries.remove(key)
    }

    /** 当前有效条目数（诊断 / 单测用）。 */
    val size: Int get() = entries.size

    companion object {
        /** plan 4.5：结果缓存 60 s。 */
        const val DEFAULT_TTL_MS: Long = 60_000L
    }
}
