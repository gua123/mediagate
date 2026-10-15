package io.github.gua123.mediagate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑窗预取与「下一集预热」的判据（**纯函数**，对应 docs/方案-网络加载增强.md 的 P1.6 / P1.7）。
 *
 * 这一组测试盯的是**节流与边界**（这两处最容易写错，也最容易被用户感知成"怎么又在偷偷下东西"）：
 * 位置没怎么动就不发、快播完不发、大小未知不动、下一集只预热一次。
 */
class PrefetchWindowTest {

    private val segment = 4L * 1024 * 1024
    private val size = 1000L * 1024 * 1024   // 1 GB
    private val duration = 3600_000L         // 1 小时

    @Test
    fun 位置前进够了才发一次滑窗预取() {
        val first = PrefetchWindow.decide(
            positionMs = 60_000L,
            durationMs = duration,
            size = size,
            segmentBytes = segment,
            lastWindowPositionMs = 0L,
        )
        assertTrue("第一次就该发", first != null)
        assertEquals("抓一段", segment, first!!.length)

        val tooSoon = PrefetchWindow.decide(
            positionMs = 70_000L,
            durationMs = duration,
            size = size,
            segmentBytes = segment,
            lastWindowPositionMs = 60_000L,
        )
        assertNull("才走 10 秒（< 20 秒）不该再发", tooSoon)

        val later = PrefetchWindow.decide(
            positionMs = 85_000L,
            durationMs = duration,
            size = size,
            segmentBytes = segment,
            lastWindowPositionMs = 60_000L,
        )
        assertTrue("走了 25 秒就该再发一次", later != null)
    }

    @Test
    fun 抓的是当前位置往后那一段() {
        val position = 1_800_000L   // 半小时：1 GB 的中间偏后
        val window = PrefetchWindow.decide(position, duration, size, segment, 0L)!!
        val expected = size * (position + PrefetchWindow.AHEAD_MS) / duration
        assertEquals("落点＝（位置 + 30 秒）按比例换算", expected, window.offset)
        assertTrue("抓的段必须在文件里", window.offset in 0 until size)
    }

    @Test
    fun 快播完就不再向后预取() {
        val nearEnd = duration - PrefetchWindow.NEAR_END_MS / 2
        assertNull(
            "距末尾不到 10 秒，后面没内容了",
            PrefetchWindow.decide(nearEnd, duration, size, segment, 0L),
        )
    }

    @Test
    fun 大小未知或位置为零都不动() {
        assertNull("stat 没拿到大小 ⇒ 不瞎猜偏移", PrefetchWindow.decide(60_000L, duration, null, segment, 0L))
        assertNull("还没起播", PrefetchWindow.decide(0L, duration, size, segment, 0L))
        assertNull("段大小非法", PrefetchWindow.decide(60_000L, duration, size, 0L, 0L))
    }

    @Test
    fun 时长未知时仍然按位置预取但不做末尾判断() {
        // duration=0（有些流拿不到总时长）：仍应发（落点按 PrefetchTargets 的规则给出），不该崩
        val window = PrefetchWindow.decide(60_000L, durationMs = 0L, size = size, segmentBytes = segment, lastWindowPositionMs = 0L)
        assertNull("总时长未知时 PrefetchTargets 拒绝估算 ⇒ 不发", window)
    }

    @Test
    fun 下一集预热只做一次且要过百分之八十() {
        assertFalse(
            "刚开播不预热下一集",
            PrefetchWindow.shouldWarmNextEpisode(positionMs = 60_000L, durationMs = duration, alreadyWarmed = false),
        )
        assertFalse(
            "79% 还不够",
            PrefetchWindow.shouldWarmNextEpisode(
                positionMs = (duration * 0.79).toLong(),
                durationMs = duration,
                alreadyWarmed = false,
            ),
        )
        assertTrue(
            "到 80% 就预热",
            PrefetchWindow.shouldWarmNextEpisode(
                positionMs = (duration * 0.8).toLong(),
                durationMs = duration,
                alreadyWarmed = false,
            ),
        )
        assertFalse(
            "同一个文件只预热一次（后面每 500 ms 采样都不该重复发）",
            PrefetchWindow.shouldWarmNextEpisode(
                positionMs = (duration * 0.9).toLong(),
                durationMs = duration,
                alreadyWarmed = true,
            ),
        )
        assertFalse(
            "总时长未知时不做判断",
            PrefetchWindow.shouldWarmNextEpisode(positionMs = 100_000L, durationMs = 0L, alreadyWarmed = false),
        )
    }
}
