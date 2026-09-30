package io.github.gua123.mediagate.media.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.gua123.mediagate.core.model.MediaKind

/**
 * [ThumbnailPipeline.select] 的 JVM 单测（M1-F）：
 * 音频必须走内嵌封面、图片必须走缩略图流水线，未接线时退回抽帧分支。
 */
class ThumbnailPipelineTest {

    @Test
    fun `视频永远走抽帧`() {
        assertEquals(
            ThumbnailPipeline.FRAME,
            ThumbnailPipeline.select(MediaKind.VIDEO, audioArtworkAvailable = true, imagePreviewAvailable = true),
        )
        assertEquals(
            ThumbnailPipeline.FRAME,
            ThumbnailPipeline.select(MediaKind.VIDEO, audioArtworkAvailable = false, imagePreviewAvailable = false),
        )
    }

    @Test
    fun `音频接线后走内嵌封面而不是抽帧`() {
        assertEquals(
            ThumbnailPipeline.AUDIO_ARTWORK,
            ThumbnailPipeline.select(MediaKind.AUDIO, audioArtworkAvailable = true, imagePreviewAvailable = true),
        )
    }

    @Test
    fun `图片接线后走缩略图流水线`() {
        assertEquals(
            ThumbnailPipeline.IMAGE_PREVIEW,
            ThumbnailPipeline.select(MediaKind.IMAGE, audioArtworkAvailable = true, imagePreviewAvailable = true),
        )
    }

    @Test
    fun `未接线时音频与图片退回抽帧分支`() {
        assertEquals(
            ThumbnailPipeline.FRAME,
            ThumbnailPipeline.select(MediaKind.AUDIO, audioArtworkAvailable = false, imagePreviewAvailable = true),
        )
        assertEquals(
            ThumbnailPipeline.FRAME,
            ThumbnailPipeline.select(MediaKind.IMAGE, audioArtworkAvailable = true, imagePreviewAvailable = false),
        )
    }

    @Test
    fun `字幕与其它类型走抽帧分支不会被误判`() {
        assertEquals(
            ThumbnailPipeline.FRAME,
            ThumbnailPipeline.select(MediaKind.SUBTITLE, audioArtworkAvailable = true, imagePreviewAvailable = true),
        )
        assertEquals(
            ThumbnailPipeline.FRAME,
            ThumbnailPipeline.select(MediaKind.OTHER, audioArtworkAvailable = true, imagePreviewAvailable = true),
        )
    }
}
