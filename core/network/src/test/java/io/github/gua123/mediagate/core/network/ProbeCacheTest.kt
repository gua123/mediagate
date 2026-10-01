package io.github.gua123.mediagate.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ProbeCache] 的 JVM 单测（**R7**：plan 4.5「结果缓存 60 s」）。
 *
 * 时钟是注入的假时钟，所以"60 s 前有效 / 60 s 后过期"是确定性断言，不靠 sleep。
 */
class ProbeCacheTest {

    private val clock = FakeClock(1_000L)
    private val cache = ProbeCache<String>(clock)

    @Test
    fun `默认 TTL 是 plan 要求的 60 秒`() {
        assertEquals(60_000L, ProbeCache.DEFAULT_TTL_MS)
        assertEquals(60_000L, cache.ttlMs)
    }

    @Test
    fun `写入后立刻可读`() {
        cache.put("k", "v")
        assertEquals("v", cache.get("k"))
        assertEquals(1, cache.size)
    }

    @Test
    fun `59 秒时仍然命中`() {
        cache.put("k", "v")
        clock.advance(59_999L)
        assertEquals("v", cache.get("k"))
        assertEquals(59_999L, cache.ageMs("k"))
    }

    @Test
    fun `满 60 秒即过期并清掉条目`() {
        cache.put("k", "v")
        clock.advance(60_000L)
        assertNull(cache.get("k"))
        assertEquals(0, cache.size)
        assertEquals(-1L, cache.ageMs("k"))
    }

    @Test
    fun `invalidate 单个 key 与全清`() {
        cache.put("a", "1")
        cache.put("b", "2")
        cache.invalidate("a")
        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
        cache.invalidate()
        assertNull(cache.get("b"))
        assertEquals(0, cache.size)
    }

    @Test
    fun `网络变化场景：invalidate 之后即使没过期也拿不到旧结果`() {
        cache.put("k", "旧结果")
        assertEquals("旧结果", cache.get("k"))
        cache.invalidate()
        clock.advance(1_000L) // 只过了 1 秒（远没到 60 s）
        assertNull("网络变化后必须立刻失效", cache.get("k"))
    }

    @Test
    fun `不存在的 key 返回 -1 年龄`() {
        assertEquals(-1L, cache.ageMs("missing"))
    }
}
