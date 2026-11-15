package io.github.gua123.mediagate.media.engine

/**
 * LibVLC 的缓存配置（毫秒）。**这是时间型缓存（PTS 预缓冲），不是磁盘缓存**。
 *
 * 2026-11-12 用户要求：「将视频播放引擎更改为 libvlc，并设置为硬解模式，然后根据连接的
 * 局域网或公网改变缓存的配置」⇒ 同一条连接走局域网时少缓冲（起播/拖拽更快），
 * 走公网时多缓冲（抗抖动），本地文件用最小的一档。
 *
 * 依据（LibVLC 3.x 源码）：network-caching 由 access 层换算成 PTS delay，
 * 默认 1000 ms、范围 0–60000；值越大越抗卡顿，但首帧/seek/切集更慢。
 *
 * @property networkCachingMs 传给 LibVLC 的 network-caching（毫秒）。
 * @property fileCachingMs 传给 LibVLC 的 file-caching（毫秒）；经回环 HTTP 代理播放时主要看 network，保留它是为了直连文件场景。
 */
data class VlcCacheConfig(
    val networkCachingMs: Int,
    val fileCachingMs: Int,
) {
    /** 生成实例级/媒体级选项时用（先夹到 LibVLC 的合法区间）。 */
    fun sanitized(): VlcCacheConfig = VlcCacheConfig(
        networkCachingMs = VlcCachePolicy.clamp(networkCachingMs),
        fileCachingMs = VlcCachePolicy.clamp(fileCachingMs),
    )
}

/**
 * 连接网络类型 → LibVLC 缓存档位（**纯函数，JVM 可测**）。
 *
 * 取值口径：
 * - **局域网（LAN）**：1500 ms。链路近、RTT 小，缓冲太多只会让 seek 变钝；
 * - **公网（WAN）**：4000 ms。给抖动和慢速链路留余量，代价是首帧和拖拽更慢；
 * - **本地文件 / 未知**：1000 ms，LibVLC 默认值。
 *
 * 说明：本项目所有片源都经本机回环 HTTP 代理送给 LibVLC，所以真正生效的是
 * network-caching；file-caching 一并带上，方便将来直连本地文件。
 */
object VlcCachePolicy {

    /** 局域网：比 LibVLC 默认多 500 ms，抵消回环 HTTP + 缓存层的一点抖动。 */
    const val LAN_NETWORK_CACHING_MS: Int = 1500

    /** 公网：4 秒，足够覆盖常见的移动网络抖动，又不至于让 seek 等太久。 */
    const val WAN_NETWORK_CACHING_MS: Int = 4000

    /** 未知网络：与本地同档 1000 ms（LibVLC 默认值）。 */
    const val UNKNOWN_NETWORK_CACHING_MS: Int = 1000

    /** 本地文件：LibVLC 默认。 */
    const val FILE_CACHING_MS: Int = 1000

    /** LibVLC 允许的缓存范围（源码 change_integer_range(0, 60000)）。 */
    const val MIN_CACHING_MS: Int = 0
    const val MAX_CACHING_MS: Int = 60000

    /** 把任意值夹到 LibVLC 合法区间（坏配置不该让播放失败）。 */
    fun clamp(ms: Int): Int = ms.coerceIn(MIN_CACHING_MS, MAX_CACHING_MS)

    /**
     * @param networkLabel 连接地址标签：LAN / WAN；本地根目录传 null。
     */
    fun forNetwork(networkLabel: String?): VlcCacheConfig {
        val network = when (networkLabel?.trim()?.uppercase()) {
            "LAN" -> LAN_NETWORK_CACHING_MS
            "WAN" -> WAN_NETWORK_CACHING_MS
            null, "" -> FILE_CACHING_MS
            else -> UNKNOWN_NETWORK_CACHING_MS
        }
        return VlcCacheConfig(networkCachingMs = network, fileCachingMs = FILE_CACHING_MS)
    }
}
