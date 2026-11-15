package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** 连接网络 → LibVLC 缓存档位的纯逻辑（2026-11-12 用户要求）。 */
class VlcCachePolicyTest {

    @Test
    fun lanUses1500Ms() {
        assertEquals(1500, VlcCachePolicy.forNetwork("LAN").networkCachingMs)
    }

    @Test
    fun wanUses4000Ms() {
        assertEquals(4000, VlcCachePolicy.forNetwork("WAN").networkCachingMs)
    }

    @Test
    fun localUsesDefault1000Ms() {
        assertEquals(1000, VlcCachePolicy.forNetwork(null).networkCachingMs)
        assertEquals(1000, VlcCachePolicy.forNetwork("  ").networkCachingMs)
    }

    @Test
    fun unknownNetworkUsesLocalDefault1000Ms() {
        assertEquals(1000, VlcCachePolicy.forNetwork("SOMETHING").networkCachingMs)
    }

    @Test
    fun labelIsCaseInsensitive() {
        assertEquals(1500, VlcCachePolicy.forNetwork("lan").networkCachingMs)
        assertEquals(4000, VlcCachePolicy.forNetwork(" wan ").networkCachingMs)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        assertEquals(0, VlcCachePolicy.clamp(-1))
        assertEquals(60000, VlcCachePolicy.clamp(999_999))
        assertEquals(1500, VlcCacheConfig(1500, 1000).sanitized().networkCachingMs)
        assertEquals(60000, VlcCacheConfig(99_999, 1000).sanitized().networkCachingMs)
    }
}
