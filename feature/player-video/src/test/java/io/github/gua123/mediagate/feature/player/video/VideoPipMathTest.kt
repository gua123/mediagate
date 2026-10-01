package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画中画纯逻辑的单测（**R13**）：比例分档、±10 秒跳转、自动进 PIP 与动作按钮的判定。
 *
 * 全是纯函数，不需要 Android 运行时——真机上"能不能进 PIP"由系统决定，这里只保证
 * **我们送进去的参数与判定是对的**。
 */
class VideoPipMathTest {

    // ---------------------------------------------------------------- 比例

    @Test
    fun aspectRatioOfComputesWidthOverHeight() {
        assertEquals(16f / 9f, VideoPipMath.aspectRatioOf(1920, 1080)!!, 0.0001f)
        assertEquals(4f / 3f, VideoPipMath.aspectRatioOf(640, 480)!!, 0.0001f)
        assertEquals(9f / 16f, VideoPipMath.aspectRatioOf(1080, 1920)!!, 0.0001f)
    }

    @Test
    fun aspectRatioOfReturnsNullForUnknownSize() {
        assertNull(VideoPipMath.aspectRatioOf(0, 1080))
        assertNull(VideoPipMath.aspectRatioOf(1920, 0))
        assertNull(VideoPipMath.aspectRatioOf(-1, -1))
    }

    @Test
    fun clampAspectRatioFallsBackForGarbage() {
        assertEquals(VideoPipMath.DEFAULT_ASPECT_RATIO, VideoPipMath.clampAspectRatio(Float.NaN), 0.0001f)
        assertEquals(VideoPipMath.DEFAULT_ASPECT_RATIO, VideoPipMath.clampAspectRatio(0f), 0.0001f)
        assertEquals(VideoPipMath.DEFAULT_ASPECT_RATIO, VideoPipMath.clampAspectRatio(-3f), 0.0001f)
    }

    @Test
    fun clampAspectRatioKeepsSystemLimits() {
        assertEquals(VideoPipMath.MAX_ASPECT_RATIO, VideoPipMath.clampAspectRatio(4f), 0.0001f)
        assertEquals(VideoPipMath.MIN_ASPECT_RATIO, VideoPipMath.clampAspectRatio(0.1f), 0.0001f)
        assertEquals(1.5f, VideoPipMath.clampAspectRatio(1.5f), 0.0001f)
    }

    @Test
    fun snapAspectRatioLandsOnThreeBuckets() {
        assertEquals(VideoPipMath.RATIO_16_9, VideoPipMath.snapAspectRatio(16f / 9f), 0.0001f)
        assertEquals(VideoPipMath.RATIO_4_3, VideoPipMath.snapAspectRatio(4f / 3f), 0.0001f)
        assertEquals(VideoPipMath.RATIO_9_16, VideoPipMath.snapAspectRatio(9f / 16f), 0.0001f)
    }

    @Test
    fun snapAspectRatioFallsBackToWidescreenForOddRatios() {
        // 方形与宽银幕都不在三档里：宁可回到 16:9，也不要送一个系统不认的比例给 PIP
        assertEquals(VideoPipMath.DEFAULT_ASPECT_RATIO, VideoPipMath.snapAspectRatio(1f), 0.0001f)
        assertEquals(VideoPipMath.DEFAULT_ASPECT_RATIO, VideoPipMath.snapAspectRatio(21f / 9f), 0.0001f)
    }

    @Test
    fun snapForSizeUsesThreeBucketsAndDefaultsWhenUnknown() {
        assertEquals(VideoPipMath.RATIO_16_9, VideoPipMath.snapForSize(1920, 1080), 0.0001f)
        assertEquals(VideoPipMath.RATIO_4_3, VideoPipMath.snapForSize(1440, 1080), 0.0001f)
        assertEquals(VideoPipMath.RATIO_9_16, VideoPipMath.snapForSize(1080, 1920), 0.0001f)
        assertEquals(VideoPipMath.DEFAULT_ASPECT_RATIO, VideoPipMath.snapForSize(0, 0), 0.0001f)
    }

    @Test
    fun ratioFractionGivesIntegerRationals() {
        assertEquals(16 to 9, VideoPipMath.ratioFraction(VideoPipMath.RATIO_16_9))
        assertEquals(4 to 3, VideoPipMath.ratioFraction(VideoPipMath.RATIO_4_3))
        assertEquals(9 to 16, VideoPipMath.ratioFraction(VideoPipMath.RATIO_9_16))
        // 认不出来的比例也必须有整数比（Rational 不接受 Float）
        assertEquals(16 to 9, VideoPipMath.ratioFraction(1f))
    }

    // ---------------------------------------------------------------- ±10 秒（R13）

    @Test
    fun forwardAndRewindAreTenSeconds() {
        assertEquals(30_000L, VideoPipMath.forwardTarget(20_000L, 600_000L))
        assertEquals(10_000L, VideoPipMath.rewindTarget(20_000L, 600_000L))
    }

    @Test
    fun rewindClampsToZero() {
        assertEquals(0L, VideoPipMath.rewindTarget(3_000L, 600_000L))
        assertEquals(0L, VideoPipMath.seekTarget(3_000L, -10_000L, 600_000L))
    }

    @Test
    fun forwardClampsToDuration() {
        assertEquals(600_000L, VideoPipMath.forwardTarget(595_000L, 600_000L))
    }

    @Test
    fun forwardKeepsGoingWhenDurationIsUnknown() {
        // 时长未知（0）时只钳下界：不能因为"不知道多长"就把快进吃掉
        assertEquals(70_000L, VideoPipMath.forwardTarget(60_000L, 0L))
    }

    @Test
    fun seekTargetTreatsNegativePositionAsZero() {
        assertEquals(10_000L, VideoPipMath.seekTarget(-5_000L, 10_000L, 600_000L))
        assertEquals(0L, VideoPipMath.seekTarget(-5_000L, -10_000L, 600_000L))
    }

    // ---------------------------------------------------------------- 自动进 PIP 与动作（R13）

    @Test
    fun autoEnterRequiresActuallyPlaying() {
        val playing = VideoPlayerUiState(
            status = VideoPlayerStatus.READY,
            playing = true,
        )
        assertTrue(VideoPipMath.autoEnterEnabled(playing))
        assertTrue(VideoPipMath.showsPauseAction(playing))

        val paused = playing.copy(playing = false)
        assertFalse(VideoPipMath.autoEnterEnabled(paused))
        assertFalse(VideoPipMath.showsPauseAction(paused))
    }

    @Test
    fun autoEnterIsOffWhileLoadingSwitchingOrEnded() {
        val base = VideoPlayerUiState(status = VideoPlayerStatus.READY, playing = true)
        assertFalse(VideoPipMath.autoEnterEnabled(base.copy(status = VideoPlayerStatus.LOADING)))
        assertFalse(VideoPipMath.autoEnterEnabled(base.copy(status = VideoPlayerStatus.ERROR)))
        assertFalse(VideoPipMath.autoEnterEnabled(base.copy(switching = true)))
        assertFalse(VideoPipMath.autoEnterEnabled(base.copy(ended = true)))
    }

    @Test
    fun autoEnterIsOffBeforeAnythingIsAttached() {
        assertFalse(VideoPipMath.autoEnterEnabled(VideoPlayerUiState()))
    }

    @Test
    fun seekStepMatchesTheRequirement() {
        assertEquals(10_000L, VideoPipMath.SEEK_STEP_MS)
    }
}
