package io.github.gua123.mediagate.media.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎切换的纯逻辑（R9：换内核后位置、倍速、字幕保持；R10：解码模式与内核选择）。
 *
 * 这些断言全部在 JVM 上跑，不需要任何播放器实例——Android 侧的调用留给
 * [PlayerEngineSwitcher]，本轮无法在无真机环境验证（见交付报告的真机清单）。
 */
class EngineSwitchPlannerTest {

    private val source = MediaSourceRef("local-file:/storage/emulated/0", "movies/影片.ts", "影片")

    private fun snapshot(
        positionMs: Long = 12_345L,
        durationMs: Long = 600_000L,
        speed: Float = 1.25f,
        decoderMode: DecoderMode = DecoderMode.AUTO_HW,
        resizeMode: ResizeMode = ResizeMode.CROP,
        playing: Boolean = true,
        subtitles: SubtitleState = SubtitleState(enabled = true, offsetMs = 500L, uri = "mediagate://local-file%3A%2Fx/a.srt", label = "a.srt"),
    ) = EngineSnapshot(
        media = source,
        positionMs = positionMs,
        durationMs = durationMs,
        speed = speed,
        decoderMode = decoderMode,
        resizeMode = resizeMode,
        playWhenReady = playing,
        subtitles = subtitles,
    )

    @Test
    fun `切换方案保留播放位置`() {
        val plan = EngineSwitchPlanner.plan(EngineKind.MEDIA3, EngineKind.VLC, snapshot(), SwitchReason.USER_REQUEST)
        assertEquals("停在旧内核的位置", 12_345L, plan.stopAtMs)
        assertEquals("新内核从同一位置续播", 12_345L, plan.restore.positionMs)
        assertEquals(EngineKind.MEDIA3, plan.from)
        assertEquals(EngineKind.VLC, plan.to)
        assertFalse("换内核不是原地重建", plan.rebuildOnly)
    }

    @Test
    fun `同一内核内重建会标明 rebuildOnly`() {
        val plan = EngineSwitchPlanner.plan(
            EngineKind.MEDIA3, EngineKind.MEDIA3,
            snapshot(decoderMode = DecoderMode.FORCE_SW), SwitchReason.DECODER_MODE_CHANGE,
        )
        assertTrue(plan.rebuildOnly)
        assertEquals(DecoderMode.FORCE_SW, plan.restore.decoderMode)
    }

    @Test
    fun `切换方案保留倍速并做边界兜底`() {
        val plan = EngineSwitchPlanner.plan(EngineKind.MEDIA3, EngineKind.VLC, snapshot(speed = 1.75f), SwitchReason.USER_REQUEST)
        assertEquals(1.75f, plan.restore.speed, 0.0001f)

        assertEquals("超出上限夹到 4x", 4.0f, EngineSwitchPlanner.sanitizeSpeed(10f), 0.0001f)
        assertEquals("低于下限夹到 0.25x", 0.25f, EngineSwitchPlanner.sanitizeSpeed(0.01f), 0.0001f)
        assertEquals("0 视为非法，回落到 1x", 1.0f, EngineSwitchPlanner.sanitizeSpeed(0f), 0.0001f)
        assertEquals("负值回落到 1x", 1.0f, EngineSwitchPlanner.sanitizeSpeed(-2f), 0.0001f)
        assertEquals("NaN 回落到 1x", 1.0f, EngineSwitchPlanner.sanitizeSpeed(Float.NaN), 0.0001f)
    }

    @Test
    fun `切换方案保留解码模式`() {
        val soft = EngineSwitchPlanner.plan(
            EngineKind.MEDIA3, EngineKind.VLC,
            snapshot(decoderMode = DecoderMode.FORCE_SW), SwitchReason.DECODER_MODE_CHANGE,
        )
        assertEquals(DecoderMode.FORCE_SW, soft.restore.decoderMode)

        val hard = EngineSwitchPlanner.plan(
            EngineKind.VLC, EngineKind.MEDIA3,
            snapshot(decoderMode = DecoderMode.FORCE_HW), SwitchReason.USER_REQUEST,
        )
        assertEquals(DecoderMode.FORCE_HW, hard.restore.decoderMode)
    }

    @Test
    fun `切换方案保留字幕开关_外挂轨与偏移`() {
        val plan = EngineSwitchPlanner.plan(EngineKind.MEDIA3, EngineKind.VLC, snapshot(), SwitchReason.USER_REQUEST)
        val subtitles = plan.restore.subtitles
        assertTrue(subtitles.enabled)
        assertEquals("mediagate://local-file%3A%2Fx/a.srt", subtitles.uri)
        assertEquals("a.srt", subtitles.label)
        assertEquals(500L, subtitles.offsetMs)
    }

    @Test
    fun `字幕偏移越界时被夹到允许区间`() {
        val tooLate = EngineSwitchPlanner.plan(
            EngineKind.MEDIA3, EngineKind.VLC,
            snapshot(subtitles = SubtitleState(offsetMs = 999_000L)), SwitchReason.USER_REQUEST,
        )
        assertEquals(SubtitleState.MAX_OFFSET_MS, tooLate.restore.subtitles.offsetMs)

        val tooEarly = EngineSwitchPlanner.plan(
            EngineKind.MEDIA3, EngineKind.VLC,
            snapshot(subtitles = SubtitleState(offsetMs = -999_000L)), SwitchReason.USER_REQUEST,
        )
        assertEquals(SubtitleState.MIN_OFFSET_MS, tooEarly.restore.subtitles.offsetMs)
    }

    @Test
    fun `切换方案保留画面模式_播放状态与媒体`() {
        val plan = EngineSwitchPlanner.plan(EngineKind.MEDIA3, EngineKind.VLC, snapshot(), SwitchReason.ERROR_FALLBACK)
        assertEquals(ResizeMode.CROP, plan.restore.resizeMode)
        assertTrue(plan.restore.playWhenReady)
        assertEquals(source, plan.restore.media)
        assertEquals(SwitchReason.ERROR_FALLBACK, plan.reason)
    }

    @Test
    fun `已播放到结尾时从头开始`() {
        val ended = EngineSwitchPlanner.plan(
            EngineKind.MEDIA3, EngineKind.VLC,
            snapshot(positionMs = 600_000L, durationMs = 600_000L), SwitchReason.USER_REQUEST,
        )
        assertEquals(0L, ended.restore.positionMs)
    }

    @Test
    fun `位置会被夹进 0 到时长之间并支持回退量`() {
        val negative = EngineSwitchPlanner.plan(
            EngineKind.MEDIA3, EngineKind.VLC,
            snapshot(positionMs = -5_000L), SwitchReason.USER_REQUEST,
        )
        assertEquals(0L, negative.restore.positionMs)

        val beyond = EngineSwitchPlanner.plan(
            EngineKind.MEDIA3, EngineKind.VLC,
            snapshot(positionMs = 900_000L, durationMs = 600_000L), SwitchReason.USER_REQUEST,
        )
        assertEquals("位置不小于时长 = 已播完，换内核后从头开始", 0L, beyond.restore.positionMs)

        val backoff = EngineSwitchPlanner.plan(
            EngineKind.MEDIA3, EngineKind.VLC,
            snapshot(positionMs = 10_000L), SwitchReason.DECODER_MODE_CHANGE, backoffMs = 1_500L,
        )
        assertEquals("允许留一点关键帧余量", 8_500L, backoff.restore.positionMs)
    }

    @Test
    fun `时长未知时不做上界夹取`() {
        val plan = EngineSwitchPlanner.plan(
            EngineKind.MEDIA3, EngineKind.VLC,
            snapshot(positionMs = 900_000L, durationMs = 0L), SwitchReason.USER_REQUEST,
        )
        assertEquals(900_000L, plan.restore.positionMs)
    }

    @Test
    fun `强制软解倾向 LibVLC 内核`() {
        assertEquals(EngineKind.VLC, EngineSelection.preferredFor(DecoderMode.FORCE_SW))
        assertEquals(EngineKind.MEDIA3, EngineSelection.preferredFor(DecoderMode.AUTO_HW))
        assertEquals(EngineKind.MEDIA3, EngineSelection.preferredFor(DecoderMode.FORCE_HW))
    }

    @Test
    fun `出错后按降级链回退内核`() {
        assertEquals(EngineKind.VLC, EngineSelection.fallbackFrom(EngineKind.MEDIA3))
        assertEquals("LibVLC 已是最后一道，无处可退", null, EngineSelection.fallbackFrom(EngineKind.VLC))
    }

    @Test
    fun `切换过程文案区分换内核与切解码器`() {
        val switching = EngineSwitchPlanner.plan(EngineKind.MEDIA3, EngineKind.VLC, snapshot(), SwitchReason.USER_REQUEST)
        val text = EngineSwitchPlanner.describe(switching)
        assertTrue(text, text.startsWith("正在切换播放内核："))
        assertTrue(text, text.contains("Media3 → LibVLC"))
        assertTrue(text, text.contains("位置 12.3s"))
        assertTrue(text, text.contains("倍速 1.25x"))
        assertTrue(text, text.contains("保留外挂字幕"))

        val rebuilding = EngineSwitchPlanner.plan(
            EngineKind.MEDIA3, EngineKind.MEDIA3,
            snapshot(decoderMode = DecoderMode.FORCE_SW), SwitchReason.DECODER_MODE_CHANGE,
        )
        val rebuildText = EngineSwitchPlanner.describe(rebuilding)
        assertTrue(rebuildText, rebuildText.startsWith("正在切换解码器："))
        assertTrue(rebuildText, rebuildText.contains("强制软解"))
    }

    @Test
    fun `秒数格式化覆盖分与小时`() {
        assertEquals("12.3s", EngineSwitchPlanner.formatSeconds(12_345L))
        assertEquals("0.0s", EngineSwitchPlanner.formatSeconds(0L))
        assertEquals("1:02", EngineSwitchPlanner.formatSeconds(62_000L))
        assertEquals("1:00:00", EngineSwitchPlanner.formatSeconds(3_600_000L))
    }

    @Test
    fun `解码模式映射到 VLC 选项与硬解开关`() {
        assertEquals(":avcodec-hw=none", DecoderMode.FORCE_SW.vlcAvcodecHwOption)
        assertEquals(":avcodec-hw=any", DecoderMode.AUTO_HW.vlcAvcodecHwOption)
        assertEquals(":avcodec-hw=any", DecoderMode.FORCE_HW.vlcAvcodecHwOption)

        assertEquals("--avcodec-hw=none", DecoderMode.FORCE_SW.vlcInstanceOption)
        assertEquals("--avcodec-hw=any", DecoderMode.FORCE_HW.vlcInstanceOption)

        assertEquals(false, DecoderMode.FORCE_SW.vlcHwDecoderEnabled)
        assertEquals(true, DecoderMode.AUTO_HW.vlcHwDecoderEnabled)
        assertEquals(true, DecoderMode.FORCE_HW.vlcHwDecoderEnabled)
        assertEquals("只有强制硬解才 force", false, DecoderMode.AUTO_HW.vlcHwDecoderForce)
        assertEquals(true, DecoderMode.FORCE_HW.vlcHwDecoderForce)
    }

    @Test
    fun `解码模式映射到 Media3 回退策略与选择器策略`() {
        assertTrue(DecoderMode.AUTO_HW.media3EnableDecoderFallback)
        assertTrue(DecoderMode.FORCE_SW.media3EnableDecoderFallback)
        assertFalse("强制硬解时不许回退到别的解码器", DecoderMode.FORCE_HW.media3EnableDecoderFallback)

        assertTrue(DecoderMode.FORCE_SW.preferSoftwareDecoder)
        assertFalse(DecoderMode.AUTO_HW.preferSoftwareDecoder)
        assertFalse(DecoderMode.FORCE_HW.preferSoftwareDecoder)
        assertNotEquals(DecoderMode.FORCE_SW.label, DecoderMode.FORCE_HW.label)
    }
}
