package io.github.gua123.mediagate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **预约缓存只温开头**（2026-10-20 用户要求：「加载开头一段时间的缓存，而不是缓存该文件的全部」）。
 *
 * 纯函数四条：目标字节、是否够用、文案、边界（小文件整份 / 大小未知）。
 */
class CacheHeadPlanTest {

    private val mb = 1024L * 1024

    @Test
    fun 大文件只温开头十二兆() {
        assertEquals(12L * mb, CacheHeadPlan.targetBytes(100L * mb))
        assertEquals(12L * mb, CacheHeadPlan.targetBytes(CacheHeadPlan.HEAD_BYTES + 1))
    }

    @Test
    fun 小文件按整份算() {
        assertEquals(3L * mb, CacheHeadPlan.targetBytes(3L * mb))
        assertEquals("整份（文件较小）", CacheHeadPlan.label(3L * mb))
    }

    @Test
    fun 大小未知时用固定目标不瞎猜整份() {
        assertEquals(CacheHeadPlan.HEAD_BYTES, CacheHeadPlan.targetBytes(null))
        assertFalse("未知大小不能当作已完成", CacheHeadPlan.satisfied(null, 1L * mb))
        assertTrue("缓存够 12 MB 就算够", CacheHeadPlan.satisfied(null, CacheHeadPlan.HEAD_BYTES))
    }

    @Test
    fun 够用判据按目标而不是整个文件() {
        val total = 500L * mb
        assertFalse(CacheHeadPlan.satisfied(total, 0L))
        assertFalse("下了 11 MB 还不够", CacheHeadPlan.satisfied(total, 11L * mb))
        assertTrue("够 12 MB 就够（哪怕整个文件 500 MB）", CacheHeadPlan.satisfied(total, 12L * mb))
    }
}
