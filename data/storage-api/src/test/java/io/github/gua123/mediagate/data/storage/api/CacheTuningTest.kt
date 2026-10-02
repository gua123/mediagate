package io.github.gua123.mediagate.data.storage.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段缓存的可调参数（**2026-10-03 用户口径：把并发去掉、保留单文件缓存**）。
 *
 * 现在只有两项：段大小与预读段数。重点是"坏值不许传到下载路径上"。
 */
class CacheTuningTest {

    @Test
    fun 默认值就是现在的生产口径() {
        val t = CacheTuning()
        assertEquals(4L * 1024 * 1024, t.segmentBytes)
        assertEquals(2, t.readAheadSegments)
    }

    @Test
    fun 预读段数越界会被夹回() {
        assertEquals(4, CacheTuning(readAheadSegments = 99).normalized().readAheadSegments)
        assertEquals(0, CacheTuning(readAheadSegments = -1).normalized().readAheadSegments)
    }

    @Test
    fun 缓存上限默认值与快捷档位() {
        assertEquals("默认 1 GB", 1L * 1024 * 1024 * 1024, CacheTuning().maxBytes)
        // 快捷档位只是"方便的取值"，任意值同样合法（见下一个用例）
        CacheTuning.CACHE_CHOICES.forEach { bytes ->
            assertEquals(bytes, CacheTuning(maxBytes = bytes).normalized().maxBytes)
        }
    }

    @Test
    fun 缓存上限不会小于一个段() {
        // 1 MB 段 + 1 GB 上限没问题；上限被夹到至少一个段
        val t = CacheTuning(segmentBytes = 8L * 1024 * 1024, maxBytes = 1024).normalized()
        assertTrue("上限应至少容纳一个段：${t.maxBytes}", t.maxBytes >= t.segmentBytes)
    }

    @Test
    fun 缓存上限现在接受任意值_只夹到安全区间() {
        // 2026-10-03 用户要求「网络缓冲上限增加一个可以输入的窗口」⇒ 不再只认档位
        val custom = CacheTuning(maxBytes = 3L * 1024 * 1024 * 1024 + 512L * 1024 * 1024).normalized()
        assertEquals("3.5 GB 应原样保留", 3L * 1024 * 1024 * 1024 + 512L * 1024 * 1024, custom.maxBytes)
        assertEquals("太小夹到下限", CacheTuning.MIN_CACHE_BYTES, CacheTuning(maxBytes = 1024).normalized().maxBytes)
        assertEquals("太大夹到上限", CacheTuning.MAX_CACHE_BYTES, CacheTuning(maxBytes = Long.MAX_VALUE / 2).normalized().maxBytes)
    }

    @Test
    fun 旧版只存过GB键也能迁移() {
        // 老版本写的是 cache_gb=4；现在优先读 MB 键，读不到就用 GB ×1024
        val migrated = CacheTuning.fromStored(cacheGb = 4)
        assertEquals(4L * 1024 * 1024 * 1024, migrated.maxBytes)
        val preferMb = CacheTuning.fromStored(cacheMb = 2500, cacheGb = 4)
        assertEquals("有 MB 键时以 MB 为准", 2500L * 1024 * 1024, preferMb.maxBytes)
    }

    @Test
    fun 段大小只接受备选值() {
        assertEquals(8L * 1024 * 1024, CacheTuning(segmentBytes = 8L * 1024 * 1024).normalized().segmentBytes)
        assertEquals(4L * 1024 * 1024, CacheTuning(segmentBytes = 3L * 1024 * 1024).normalized().segmentBytes)
    }

    @Test
    fun 从持久化值还原存的是MB与段数() {
        val t = CacheTuning.fromStored(segmentMb = 8, readAheadSegments = 3)
        assertEquals(8L * 1024 * 1024, t.segmentBytes)
        assertEquals(3, t.readAheadSegments)
    }

    @Test
    fun 没存过或存了陌生值都退回默认() {
        assertEquals(CacheTuning(), CacheTuning.fromStored())
        val odd = CacheTuning.fromStored(segmentMb = 999, readAheadSegments = 999)
        assertEquals(CacheTuning().segmentBytes, odd.segmentBytes)
        assertEquals(CacheTuning.MAX_READ_AHEAD, odd.readAheadSegments)
    }
}
