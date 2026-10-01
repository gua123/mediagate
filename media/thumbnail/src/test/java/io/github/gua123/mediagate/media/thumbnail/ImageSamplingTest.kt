package io.github.gua123.mediagate.media.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ImageSampling] 的 JVM 单测（R1 / R5）：采样率必须永远是 2 的幂、
 * 且是「解码后仍不小于请求尺寸」的最大值。
 */
class ImageSamplingTest {

    @Test
    fun `原图不大于请求尺寸时不采样`() {
        assertEquals(1, ImageSampling.sampleSize(800, 600, 1080, 1920))
        assertEquals(1, ImageSampling.sampleSize(1080, 1920, 1080, 1920))
    }

    @Test
    fun `八千乘六千的大图按请求宽降到最省的 2 的幂`() {
        // 8000/8 = 1000 < 1080 会糊过头，因此停在 4（2000 >= 1080）
        assertEquals(4, ImageSampling.sampleSize(8000, 6000, 1080, 0))
        // 请求 256（列表缩略图）：8000/32 = 250 < 256，停在 16（500 >= 256）
        assertEquals(16, ImageSampling.sampleSize(8000, 6000, 256, 0))
    }

    @Test
    fun `同时约束宽高时取更保守的采样率`() {
        // 只按宽能到 4（2000>=1080），按高 6000/4=1500>=1080 也成立；再翻倍高就只有 750 < 1080
        assertEquals(4, ImageSampling.sampleSize(8000, 6000, 1080, 1080))
        assertEquals(2, ImageSampling.sampleSize(8000, 6000, 4000, 1080))
    }

    @Test
    fun `未约束的方向不影响结果`() {
        assertEquals(
            ImageSampling.sampleSize(4000, 3000, 500, 0),
            ImageSampling.sampleSize(4000, 3000, 500, -1),
        )
    }

    @Test
    fun `尺寸或请求未知时退化为不采样`() {
        assertEquals(1, ImageSampling.sampleSize(0, 0, 256, 256))
        assertEquals(1, ImageSampling.sampleSize(-1, -1, 256, 256))
        assertEquals(1, ImageSampling.sampleSize(4000, 3000, 0, 0))
        assertEquals(1, ImageSampling.sampleSize(4000, 3000, -1, -1))
    }

    @Test
    fun `结果永远是 2 的幂`() {
        for (width in listOf(257, 999, 1024, 1920, 4096, 8000, 12000)) {
            val sample = ImageSampling.sampleSize(width, width * 3 / 4, 128, 0)
            assertEquals("width=$width sample=$sample", 0, sample and (sample - 1))
        }
    }
}
