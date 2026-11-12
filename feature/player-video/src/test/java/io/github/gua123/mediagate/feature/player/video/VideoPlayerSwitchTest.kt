package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.MediaSourceRef
import io.github.gua123.mediagate.media.engine.ResizeMode
import io.github.gua123.mediagate.media.engine.RestoreSpec
import io.github.gua123.mediagate.media.engine.SwitchReason

/**
 * [VideoEngineSwitch] 的 JVM 单测（R9 位置/倍速/字幕保持、R10 档位落地方式、plan 4.6 降级决策）。
 *
 * 用假内核验证「切换方案怎么落到新内核上」，所以真机上最容易出错的两处（读现场、恢复顺序）
 * 在 JVM 里就能钉住。
 */
class VideoPlayerSwitchTest {

    private val media = MediaSourceRef(backendId = "fake:video", path = "Movies/a.mp4", title = "a.mp4")

    @Test
    fun planForKeepsPositionSpeedAndPlayState() {
        val engine = FakePlayerEngine(EngineKind.MEDIA3).apply {
            currentMedia = media
            position = 30_000L
            duration = 60_000L
            isPlaying = true
            speedValue = 1.5f
        }

        val plan = VideoEngineSwitch.planFor(engine, EngineKind.VLC, SwitchReason.USER_REQUEST)

        assertEquals(EngineKind.MEDIA3, plan.from)
        assertEquals(EngineKind.VLC, plan.to)
        assertEquals(30_000L, plan.stopAtMs)
        assertEquals(30_000L, plan.restore.positionMs)
        assertEquals(1.5f, plan.restore.speed, 0.0001f)
        assertTrue(plan.restore.playWhenReady)
        assertEquals(media.path, plan.restore.media?.path)
        assertEquals(SwitchReason.USER_REQUEST, plan.reason)
        assertFalse(plan.rebuildOnly)
    }

    @Test
    fun planForSameKindIsRebuildOnly() {
        val engine = FakePlayerEngine(EngineKind.MEDIA3)
        val plan = VideoEngineSwitch.planFor(
            engine = engine,
            target = EngineKind.MEDIA3,
            reason = SwitchReason.DECODER_MODE_CHANGE,
            decoderMode = DecoderMode.FORCE_HW,
        )

        assertTrue(plan.rebuildOnly)
        assertEquals(DecoderMode.FORCE_HW, plan.restore.decoderMode)
        assertEquals(SwitchReason.DECODER_MODE_CHANGE, plan.reason)
    }

    @Test
    fun snapshotFallsBackToUiPositionWhenEngineReportsZero() {
        val engine = FakePlayerEngine(EngineKind.MEDIA3).apply {
            position = 0L
            isPlaying = false
        }

        val snapshot = VideoEngineSwitch.snapshotOf(engine, positionFloorMs = 42_000L, playWhenReady = true)

        // 出错的内核把位置报成 0 时，用 UI 最后观测到的 42 秒兜底（「一键切 LibVLC 续播」不丢进度）
        assertEquals(42_000L, snapshot.positionMs)
        assertTrue(snapshot.playWhenReady)
    }

    @Test
    fun applyRestoreOrderAndValues() {
        val engine = FakePlayerEngine(EngineKind.VLC)
        val restore = RestoreSpec(
            media = media,
            positionMs = 12_000L,
            speed = 1.5f,
            decoderMode = DecoderMode.FORCE_SW,
            resizeMode = ResizeMode.CROP,
            playWhenReady = true,
        )

        VideoEngineSwitch.applyRestore(engine, restore)

        assertEquals(
            listOf(
                "decoder:FORCE_SW",
                "resize:CROP",
                "setMedia:Movies/a.mp4",
                "prepare",
                "seekTo:12000",
                "speed:1.5",
                "play",
            ),
            engine.calls,
        )
        assertEquals(12_000L, engine.position)
        assertEquals(1.5f, engine.speed, 0.0001f)
        assertEquals(ResizeMode.CROP, engine.resizeMode)
        assertEquals(DecoderMode.FORCE_SW, engine.decoderMode)
        assertTrue(engine.isPlaying)
    }

    @Test
    fun applyRestoreWithoutMediaOnlySetsDecoderAndResize() {
        val engine = FakePlayerEngine(EngineKind.VLC)
        VideoEngineSwitch.applyRestore(
            engine,
            RestoreSpec(
                media = null,
                positionMs = 1_000L,
                speed = 1f,
                decoderMode = DecoderMode.AUTO_HW,
                resizeMode = ResizeMode.FIT,
                playWhenReady = true,
            ),
        )

        assertEquals(listOf("decoder:AUTO_HW", "resize:FIT"), engine.calls)
        assertFalse(engine.isPlaying)
    }

    @Test
    fun 解码档位在_LibVLC_内重建_不换内核() {
        // 2026-11-12 起视频内核固定 LibVLC：三种档位都是"同一内核内重建"
        DecoderMode.entries.forEach { mode ->
            assertEquals(
                mode.name,
                DecoderAction.RebuildInPlace,
                VideoEngineSwitch.decoderAction(EngineKind.VLC, mode, canUseVlc = true),
            )
        }
        // 旧状态（当前是 Media3）且代理可用时，改档位会切回 LibVLC
        assertEquals(
            DecoderAction.SwitchTo(EngineKind.VLC),
            VideoEngineSwitch.decoderAction(EngineKind.MEDIA3, DecoderMode.FORCE_HW, canUseVlc = true),
        )
    }

    @Test
    fun LibVLC_出错时可退回_Media3_Media3_无路可退() {
        assertNull(VideoEngineSwitch.fallbackTarget(EngineKind.MEDIA3, canUseVlc = true))
        assertNull(VideoEngineSwitch.fallbackTarget(EngineKind.MEDIA3, canUseVlc = false))
        // 退回 Media3 不依赖回环代理，所以 canUseVlc=false 也应给出动作
        assertEquals(EngineKind.MEDIA3, VideoEngineSwitch.fallbackTarget(EngineKind.VLC, canUseVlc = true))
        assertEquals(EngineKind.MEDIA3, VideoEngineSwitch.fallbackTarget(EngineKind.VLC, canUseVlc = false))
    }
}
