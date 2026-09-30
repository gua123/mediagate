package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.ResizeMode

/**
 * [VideoPlayerMath] 的 JVM 单测（R4 拖拽换算 / R9-R10 档位轮转 / R1 上下集边界）。
 *
 * 这些函数是页面上「点一下会发生什么」的唯一出处，所以逐条钉住。
 */
class VideoPlayerMathTest {

    @Test
    fun formatDurationCoversUnknownMinutesAndHours() {
        assertEquals("--:--", VideoPlayerMath.formatDuration(-1L))
        assertEquals("0:00", VideoPlayerMath.formatDuration(0L))
        assertEquals("1:05", VideoPlayerMath.formatDuration(65_000L))
        assertEquals("1:01:05", VideoPlayerMath.formatDuration(3_665_000L))
    }

    @Test
    fun seekRatioIsZeroWhenDurationUnknown() {
        assertEquals(0f, VideoPlayerMath.seekRatio(12_000L, 0L), 0.0001f)
        assertEquals(0.25f, VideoPlayerMath.seekRatio(25_000L, 100_000L), 0.0001f)
        // 越界（内核报的位置偶尔会超过时长）夹到 1，不让进度条画出界
        assertEquals(1f, VideoPlayerMath.seekRatio(120_000L, 100_000L), 0.0001f)
    }

    @Test
    fun positionOfRatioRoundTripsAndClamps() {
        assertEquals(50_000L, VideoPlayerMath.positionOfRatio(0.5f, 100_000L))
        assertEquals(0L, VideoPlayerMath.positionOfRatio(0.5f, 0L))
        assertEquals(0L, VideoPlayerMath.positionOfRatio(-1f, 100_000L))
        assertEquals(100_000L, VideoPlayerMath.positionOfRatio(3f, 100_000L))
    }

    @Test
    fun speedCyclesThroughFourSteps() {
        assertEquals(1f, VideoPlayerMath.nextSpeed(0.5f), 0.0001f)
        assertEquals(1.5f, VideoPlayerMath.nextSpeed(1f), 0.0001f)
        assertEquals(2f, VideoPlayerMath.nextSpeed(1.5f), 0.0001f)
        assertEquals(0.5f, VideoPlayerMath.nextSpeed(2f), 0.0001f)
        // 不在档位里的值（内核被改过）回到默认 1 倍
        assertEquals(1f, VideoPlayerMath.nextSpeed(1.25f), 0.0001f)
    }

    @Test
    fun speedLabelDropsTrailingZero() {
        assertEquals("1×", VideoPlayerMath.speedLabel(1f))
        assertEquals("0.5×", VideoPlayerMath.speedLabel(0.5f))
        assertEquals("1.5×", VideoPlayerMath.speedLabel(1.5f))
    }

    @Test
    fun decoderModeCyclesAutoSoftHard() {
        assertEquals(DecoderMode.FORCE_SW, VideoPlayerMath.nextDecoderMode(DecoderMode.AUTO_HW))
        assertEquals(DecoderMode.FORCE_HW, VideoPlayerMath.nextDecoderMode(DecoderMode.FORCE_SW))
        assertEquals(DecoderMode.AUTO_HW, VideoPlayerMath.nextDecoderMode(DecoderMode.FORCE_HW))
    }

    @Test
    fun resizeModeCyclesFourSteps() {
        assertEquals(ResizeMode.CROP, VideoPlayerMath.nextResizeMode(ResizeMode.FIT))
        assertEquals(ResizeMode.STRETCH, VideoPlayerMath.nextResizeMode(ResizeMode.CROP))
        assertEquals(ResizeMode.ORIGINAL, VideoPlayerMath.nextResizeMode(ResizeMode.STRETCH))
        assertEquals(ResizeMode.FIT, VideoPlayerMath.nextResizeMode(ResizeMode.ORIGINAL))
    }

    @Test
    fun playableEntriesKeepsVideoAndAudioSortedByName() {
        val entries = listOf(
            videoEntry("Movies/b.MP4"),
            otherEntry("Movies/cover.jpg"),
            audioEntry("Movies/a.mp3"),
            RemoteEntry(name = "sub", path = "Movies/sub", isDirectory = true),
            otherEntry("Movies/readme.txt"),
            videoEntry("Movies/c.ts"),
        )

        assertEquals(
            listOf("Movies/a.mp3", "Movies/b.MP4", "Movies/c.ts"),
            VideoPlayerMath.playableEntries(entries).map { it.path },
        )
        assertTrue(VideoPlayerMath.playableEntries(emptyList()).isEmpty())
    }

    @Test
    fun nextAndPreviousIndexStopAtBoundaries() {
        assertNull(VideoPlayerMath.nextIndex(2, 3))
        assertEquals(2, VideoPlayerMath.nextIndex(1, 3))
        assertNull(VideoPlayerMath.previousIndex(0))
        assertEquals(0, VideoPlayerMath.previousIndex(1))

        // 空队列 / 只有一集：两边都到头
        assertNull(VideoPlayerMath.nextIndex(0, 0))
        assertNull(VideoPlayerMath.previousIndex(0))
        assertNull(VideoPlayerMath.nextIndex(0, 1))
        assertTrue(VideoPlayerMath.canGoNext(0, 3))
        assertFalse(VideoPlayerMath.canGoPrevious(0))
    }

    @Test
    fun indexOfPathAndFileName() {
        assertEquals(1, VideoPlayerMath.indexOfPath(listOf("a.mp4", "b.mp4"), "b.mp4"))
        assertEquals(-1, VideoPlayerMath.indexOfPath(listOf("a.mp4"), "b.mp4"))
        assertEquals("b.mp4", VideoPlayerMath.fileNameOf("Movies/2026/b.mp4"))
        assertEquals("b.mp4", VideoPlayerMath.fileNameOf("b.mp4"))
    }
}
