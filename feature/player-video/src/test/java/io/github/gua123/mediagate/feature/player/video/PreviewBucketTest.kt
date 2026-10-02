package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 预览帧节流桶（2026-10-03「预览图总是显示生成中」的修复配套）。 */
class PreviewBucketTest {

    @Test
    fun 同一个五秒桶内不重复取() {
        assertEquals(PreviewBucket.of(0L), PreviewBucket.of(4_999L))
        assertEquals(PreviewBucket.of(10_000L), PreviewBucket.of(14_999L))
    }

    @Test
    fun 跨桶就要重取() {
        assertNotEquals(PreviewBucket.of(4_999L), PreviewBucket.of(5_000L))
    }

    @Test
    fun 负数与零都归到第零桶() {
        assertEquals(0L, PreviewBucket.of(-1L))
        assertEquals(0L, PreviewBucket.of(0L))
    }
}
