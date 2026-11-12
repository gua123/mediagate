package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** 四种缩放档位的纯数学（2026-11-12 用户要求：默认居中 + 填充等更改画面方式）。 */
class VideoFitMathTest {

    @Test
    fun portraitVideoInLandscapeFitIsCenteredFullHeight() {
        val scale = VideoFitMath.scaleFor(ResizeMode.FIT, 720, 1280, 1904, 876)
        assertEquals(876f / 1280f, scale.x, 0.0001f)
        assertEquals(scale.x, scale.y, 0.0001f)
    }

    @Test
    fun cropUsesCoverScale() {
        val scale = VideoFitMath.scaleFor(ResizeMode.CROP, 720, 1280, 1904, 876)
        assertEquals(1904f / 720f, scale.x, 0.0001f)
        assertEquals(scale.x, scale.y, 0.0001f)
    }

    @Test
    fun stretchUsesIndependentAxes() {
        val scale = VideoFitMath.scaleFor(ResizeMode.STRETCH, 720, 1280, 1904, 876)
        assertEquals(1904f / 720f, scale.x, 0.0001f)
        assertEquals(876f / 1280f, scale.y, 0.0001f)
    }

    @Test
    fun originalIsOneToOne() {
        val scale = VideoFitMath.scaleFor(ResizeMode.ORIGINAL, 720, 1280, 1904, 876)
        assertEquals(1f, scale.x, 0f)
        assertEquals(1f, scale.y, 0f)
    }

    @Test
    fun invalidSizesFallBackToOne() {
        assertEquals(1f, VideoFitMath.scaleFor(ResizeMode.FIT, 0, 1280, 1904, 876).x, 0f)
        assertEquals(1f, VideoFitMath.scaleFor(ResizeMode.FIT, 720, 1280, 0, 876).x, 0f)
    }
}
