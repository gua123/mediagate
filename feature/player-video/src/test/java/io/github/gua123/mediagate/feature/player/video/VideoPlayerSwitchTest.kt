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
    fun 解码档位只在原有引擎内重建_不再换内核() {
        // 2026-10-15 用户拍板只保留 Media3 之后：三种档位都是"同一内核内重建"
        //（强制软解由 Media3 自己按 softwareOnly 筛解码器实现，不再借 LibVLC）
        DecoderMode.entries.forEach { mode ->
            assertEquals(
                mode.name,
                DecoderAction.RebuildInPlace,
                VideoEngineSwitch.decoderAction(EngineKind.MEDIA3, mode, canUseVlc = false),
            )
        }
        // 万一当前内核是已下线的那个（历史状态），会切回 Media3
        assertEquals(
            DecoderAction.SwitchTo(EngineKind.MEDIA3),
            VideoEngineSwitch.decoderAction(EngineKind.VLC, DecoderMode.FORCE_HW, canUseVlc = true),
        )
    }

    @Test
    fun 没有别的内核可退时不给降级动作() {
        assertNull(VideoEngineSwitch.fallbackTarget(EngineKind.MEDIA3, canUseVlc = true))
        assertNull(VideoEngineSwitch.fallbackTarget(EngineKind.MEDIA3, canUseVlc = false))
        assertNull(VideoEngineSwitch.fallbackTarget(EngineKind.VLC, canUseVlc = true))
    }
}
