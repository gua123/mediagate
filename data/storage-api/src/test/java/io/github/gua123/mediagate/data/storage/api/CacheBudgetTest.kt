package io.github.gua123.mediagate.data.storage.api

import org.junit.Assert.assertEquals
import org.junit.Test

/** 缓存上限的磁盘余量校验（2026-10-20，借鉴 nPlayer 的 free_disk_space 检查）。 */
class CacheBudgetTest {

    private val segment = 4L * 1024 * 1024
    private val gb = 1024L * 1024 * 1024

    @Test
    fun 上限为零就是关闭() {
        assertEquals(0L, CacheBudget.effectiveMaxBytes(0L, 100L * gb, segment))
        assertEquals(0L, CacheBudget.effectiveMaxBytes(-1L, 100L * gb, segment))
    }

    @Test
    fun 磁盘够用时按用户设置() {
        assertEquals(8L * gb, CacheBudget.effectiveMaxBytes(8L * gb, 100L * gb, segment))
    }

    @Test
    fun 磁盘不够时保留四分之一余量() {
        val free = 2L * gb
        assertEquals(free / 4 * 3, CacheBudget.effectiveMaxBytes(8L * gb, free, segment))
    }

    @Test
    fun 连一个段都放不下就关闭() {
        assertEquals(0L, CacheBudget.effectiveMaxBytes(8L * gb, segment - 1, segment))
    }

    @Test
    fun 剩余未知时不瞎猜() {
        assertEquals(8L * gb, CacheBudget.effectiveMaxBytes(8L * gb, -1L, segment))
    }
}
