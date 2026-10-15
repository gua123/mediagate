package io.github.gua123.mediagate.feature.player.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.EngineSwitchPlanner
import io.github.gua123.mediagate.media.engine.EngineSnapshot
import io.github.gua123.mediagate.media.engine.MediaSourceRef
import io.github.gua123.mediagate.media.engine.ResizeMode
import io.github.gua123.mediagate.media.engine.SwitchReason

/**
 * [VideoPlayerUiState.reduce] 的 JVM 单测（R4 拖拽语义 / R9-R10 切换状态 / R18 续播提示）。
 *
 * 归约是纯函数，所以「状态怎么迁移」可以逐条钉住——页面与 ViewModel 都只是它的调用方。
 */
class VideoPlayerUiStateTest {

    private val plan = EngineSwitchPlanner.plan(
        from = EngineKind.MEDIA3,
        to = EngineKind.VLC,
        snapshot = EngineSnapshot(
            media = MediaSourceRef("fake:video", "Movies/a.mp4", "a.mp4"),
            positionMs = 12_000L,
            durationMs = 60_000L,
            speed = 1.5f,
            playWhenReady = true,
        ),
        reason = SwitchReason.USER_REQUEST,
    )

    @Test
    fun loadStartedResetsToLoadingWithTitleFromPath() {
        val previous = VideoPlayerUiState(
            status = VideoPlayerStatus.ERROR,
            playing = true,
            positionMs = 9_000L,
            durationMs = 60_000L,
            errorKind = VideoErrorKind.PLAYBACK,
            canFallback = true,
            resumeHint = true,
        )

        val next = previous.reduce(VideoPlayerEvent.LoadStarted("Movies/2026/b.mp4"))

        assertEquals(VideoPlayerStatus.LOADING, next.status)
        assertEquals("Movies/2026/b.mp4", next.path)
        assertEquals("b.mp4", next.title)
        assertEquals(0L, next.positionMs)
        assertEquals(0L, next.durationMs)
        assertFalse(next.playing)
        assertFalse(next.resumeHint)
        assertNull(next.errorKind)
        assertFalse(next.canFallback)
    }

    @Test
    fun siblingsLoadedSetsQueueAndClampsIndex() {
        val next = VideoPlayerUiState().reduce(
            VideoPlayerEvent.SiblingsLoaded(listOf("Movies/a.mp4", "Movies/b.mp4", "Movies/c.mp4"), 9),
        )

        // 队列列好了但内核还没装载：状态仍是 LOADING，等 EngineAttached 才转 READY
        assertEquals(VideoPlayerStatus.LOADING, next.status)
        assertEquals(3, next.count)
        assertEquals(3, next.position)
        assertEquals("Movies/c.mp4", next.path)
        assertFalse(next.canNext)
        assertTrue(next.canPrevious)
    }

    @Test
    fun episodeOpenedResetsProgressOfPreviousEpisode() {
        val state = VideoPlayerUiState(
            status = VideoPlayerStatus.READY,
            siblingPaths = listOf("Movies/a.mp4", "Movies/b.mp4"),
            siblingIndex = 0,
            path = "Movies/a.mp4",
            positionMs = 55_000L,
            durationMs = 60_000L,
            ended = true,
        )

        val next = state.reduce(VideoPlayerEvent.EpisodeOpened(state.siblingPaths, 1))

        assertEquals("Movies/b.mp4", next.path)
        assertEquals(2, next.position)
        assertEquals(0L, next.positionMs)
        assertEquals(0L, next.durationMs)
        assertFalse(next.ended)
        assertEquals(VideoPlayerStatus.READY, next.status)
    }

    @Test
    fun tickUpdatesPositionDurationAndFlags() {
        val next = VideoPlayerUiState().reduce(
            VideoPlayerEvent.Tick(15_000L, 60_000L, playing = true, buffering = false, ended = false),
        )

        assertEquals(15_000L, next.positionMs)
        assertEquals(60_000L, next.durationMs)
        assertTrue(next.playing)
        assertEquals(0.25f, next.progress, 0.0001f)
        assertEquals("0:15", next.positionText)
        assertEquals("1:00", next.durationText)
    }

    @Test
    fun tickNeverOverwritesTheDragTarget() {
        val dragging = VideoPlayerUiState(
            positionMs = 10_000L,
            durationMs = 100_000L,
            dragging = true,
            dragPositionMs = 50_000L,
        )

        // 拖拽中：内核轮询照旧更新真实位置，但进度条显示的是拖到的位置（R4）
        val next = dragging.reduce(
            VideoPlayerEvent.Tick(11_000L, 100_000L, playing = true, buffering = false, ended = false),
        )

        assertEquals(11_000L, next.positionMs)
        assertEquals(50_000L, next.displayPositionMs)
        assertEquals(0.5f, next.progress, 0.0001f)
    }

    @Test
    fun seekChangedConvertsRatioAndSeekFinishedCommits() {
        val state = VideoPlayerUiState(positionMs = 10_000L, durationMs = 100_000L)

        val dragging = state.reduce(VideoPlayerEvent.SeekStarted).reduce(VideoPlayerEvent.SeekChanged(0.8f))
        assertTrue(dragging.dragging)
        assertEquals(80_000L, dragging.dragPositionMs)

        val committed = dragging.reduce(VideoPlayerEvent.SeekFinished)
        assertFalse(committed.dragging)
        assertEquals(80_000L, committed.positionMs)
        assertEquals(80_000L, committed.displayPositionMs)
    }

    @Test
    fun seekFinishedClearsEndedFlag() {
        val ended = VideoPlayerUiState(
            durationMs = 100_000L,
            positionMs = 100_000L,
            ended = true,
            dragging = true,
            dragPositionMs = 5_000L,
        )

        assertFalse(ended.reduce(VideoPlayerEvent.SeekFinished).ended)
    }

    @Test
    fun switchPlannedShowsSwitchingMessageThenCompletedRestoresState() {
        val switching = VideoPlayerUiState().reduce(VideoPlayerEvent.SwitchPlanned(plan))

        assertTrue(switching.switching)
        assertEquals(EngineSwitchPlanner.describe(plan), switching.switchMessage)
        // 换内核不是「同内核重建」，不显示黑屏提示
        assertFalse(switching.blackoutHint)

        val done = switching.reduce(
            VideoPlayerEvent.SwitchCompleted(EngineKind.VLC, DecoderMode.AUTO_HW, 12_000L, playing = true),
        )

        assertFalse(done.switching)
        assertNull(done.switchMessage)
        assertEquals(EngineKind.VLC, done.engineKind)
        assertEquals(12_000L, done.positionMs)
        assertTrue(done.playing)
        assertEquals(VideoPlayerStatus.READY, done.status)
    }

    @Test
    fun rebuildOnlyPlanShowsBlackoutHintUntilCleared() {
        val rebuild = EngineSwitchPlanner.plan(
            from = EngineKind.MEDIA3,
            to = EngineKind.MEDIA3,
            snapshot = EngineSnapshot(decoderMode = DecoderMode.FORCE_HW),
            reason = SwitchReason.DECODER_MODE_CHANGE,
        )

        val switching = VideoPlayerUiState().reduce(VideoPlayerEvent.SwitchPlanned(rebuild))
        assertTrue(switching.switching)
        assertTrue(switching.blackoutHint)

        val hintKept = switching.reduce(
            VideoPlayerEvent.SwitchCompleted(EngineKind.MEDIA3, DecoderMode.FORCE_HW, 0L, playing = false),
        )
        assertFalse(hintKept.switching)
        assertTrue(hintKept.blackoutHint)
        assertEquals(DecoderMode.FORCE_HW, hintKept.decoderMode)

        val cleared = hintKept.reduce(VideoPlayerEvent.SwitchHintCleared)
        assertFalse(cleared.blackoutHint)
    }

    @Test
    fun switchFailedAndPlaybackFailedBothLandInErrorState() {
        val failed = VideoPlayerUiState()
            .reduce(VideoPlayerEvent.SwitchPlanned(plan))
            .reduce(VideoPlayerEvent.SwitchFailed("建 LibVLC 失败", canFallback = false))

        assertEquals(VideoPlayerStatus.ERROR, failed.status)
        assertFalse(failed.switching)
        assertEquals(VideoErrorKind.PLAYBACK, failed.errorKind)
        assertEquals("建 LibVLC 失败", failed.errorDetail)

        val playback = VideoPlayerUiState().reduce(
            VideoPlayerEvent.Failed(VideoErrorKind.PLAYBACK, "解码失败", canFallback = true),
        )
        assertEquals(VideoPlayerStatus.ERROR, playback.status)
        assertTrue(playback.canFallback)
    }

    @Test
    fun resumeAvailableShowsHintAndClearsIt() {
        val resumed = VideoPlayerUiState().reduce(VideoPlayerEvent.ResumeAvailable(45_000L))

        assertTrue(resumed.resumeHint)
        assertEquals(45_000L, resumed.positionMs)
        assertFalse(resumed.reduce(VideoPlayerEvent.ResumeHintCleared).resumeHint)
    }

    @Test
    fun speedResizeAndDecoderEventsUpdateTheirFields() {
        val state = VideoPlayerUiState()
            .reduce(VideoPlayerEvent.SpeedChanged(1.5f))
            .reduce(VideoPlayerEvent.ResizeModeChanged(ResizeMode.CROP))
            .reduce(VideoPlayerEvent.DecoderModeChanged(DecoderMode.FORCE_SW))

        assertEquals(1.5f, state.speed, 0.0001f)
        assertEquals("1.5×", state.speedLabel)
        assertEquals(ResizeMode.CROP, state.resizeMode)
        assertEquals(DecoderMode.FORCE_SW, state.decoderMode)
        assertEquals("强制软解", state.decoderLabel)
    }

    @Test
    fun derivedLabelsFollowCurrentEngine() {
        val media3 = VideoPlayerUiState(engineKind = EngineKind.MEDIA3)
        assertEquals("Media3", media3.engineLabel)
        // 2026-10-15 起按钮是**轮转**：Media3 → 自研引擎 → LibVLC → Media3
        assertEquals(EngineKind.NATIVE, media3.otherEngine)
        assertEquals("自研引擎", media3.otherEngineLabel)

        val native = media3.reduce(
            VideoPlayerEvent.SwitchCompleted(EngineKind.NATIVE, DecoderMode.AUTO_HW, 0L, playing = false),
        )
        assertEquals("自研引擎", native.engineLabel)
        // 没被探针确认过的 LibVLC 不进轮转（2026-10-15 收紧），所以从自研引擎下一个是回 Media3
        assertEquals(EngineKind.MEDIA3, native.otherEngine)

        // 确认能用时它才回到轮转里
        val confirmed = VideoPlayerUiState(engineKind = EngineKind.NATIVE, vlcUsable = true)
        assertEquals(EngineKind.VLC, confirmed.otherEngine)

        val vlc = confirmed.reduce(
            VideoPlayerEvent.SwitchCompleted(EngineKind.VLC, DecoderMode.AUTO_HW, 0L, playing = false),
        )
        assertEquals(EngineKind.MEDIA3, vlc.otherEngine)
    }

    @Test
    fun storageErrorsAreClassified() {
        assertEquals(
            VideoErrorKind.ACCESS_DENIED,
            VideoPlayerErrors.classify(io.github.gua123.mediagate.data.storage.api.StorageException.AccessDenied("没权限")),
        )
        assertEquals(
            VideoErrorKind.NETWORK,
            VideoPlayerErrors.classify(io.github.gua123.mediagate.data.storage.api.StorageException.Network("断网")),
        )
        assertEquals(VideoErrorKind.UNKNOWN, VideoPlayerErrors.classify(IllegalStateException("???")))
    }

    // ------------------------------------------------ 内核轮转与能力提示（2026-10-15 M4 下半）

    @Test
    fun 内核按钮在_Media3_与自研引擎之间轮转() {
        val media3 = VideoPlayerUiState(engineKind = EngineKind.MEDIA3)
        assertEquals(EngineKind.NATIVE, media3.otherEngine)

        // 默认（探针没给过结论）只有两档：Media3 ⇄ 自研引擎
        val native = VideoPlayerUiState(engineKind = EngineKind.NATIVE)
        assertEquals(EngineKind.MEDIA3, native.otherEngine)
    }

    @Test
    fun 探针确认能用时_LibVLC_才回到轮转里() {
        val confirmed = VideoPlayerUiState(engineKind = EngineKind.NATIVE, vlcUsable = true)
        assertEquals(EngineKind.VLC, confirmed.otherEngine)
    }

    @Test
    fun LibVLC_已知不可用或未知时轮转都要跳过它() {
        // 已知不可用：跳过（老口径）
        assertEquals(
            EngineKind.MEDIA3,
            VideoPlayerUiState(engineKind = EngineKind.NATIVE, vlcUsable = false).otherEngine,
        )
        // 未知：也跳过（2026-10-15 收紧——LibVLC 在用户机器上会把 App 带走，
        // 探针改成按需跑之后「未知」是常态，不改的话点两下内核就撞上吓人的确认框）
        assertEquals(
            EngineKind.MEDIA3,
            VideoPlayerUiState(engineKind = EngineKind.NATIVE, vlcUsable = null).otherEngine,
        )
    }

    @Test
    fun 自研引擎要藏掉它不支持的档位并把原因写在旁边() {
        val native = VideoPlayerUiState(engineKind = EngineKind.NATIVE)
        assertFalse("倍速芯片不该出现（点了也没用）", native.canChangeSpeed)
        assertFalse("解码档位芯片不该出现", native.canChangeDecoder)
        assertEquals("自研引擎暂无倍速与硬解档位", native.capsNotice)
    }

    @Test
    fun 自检没过时要能拦住_并且带上原因() {
        val blocked = VideoPlayerUiState(engineKind = EngineKind.MEDIA3, engineUsable = false, engineProbeReason = "音频设备打不开")
        assertFalse("自检没过就不能放行切换到自研引擎", blocked.engineUsable != false)
        assertEquals("音频设备打不开", blocked.engineProbeReason)

        // 还没测出结论（null）时不拦人：探针可能只是没跑完
        val unknown = VideoPlayerUiState(engineKind = EngineKind.MEDIA3)
        assertNull(unknown.engineUsable)
    }

    @Test
    fun 自研引擎播不了时要有自动回退的一次性说明() {
        val state = VideoPlayerUiState(engineKind = EngineKind.NATIVE).reduce(
            VideoPlayerEvent.EngineFallbackNoticeRaised("自研引擎播不了这个文件（解码器打不开），已自动换回 Media3 继续"),
        )
        assertTrue(state.engineFallbackNotice!!.contains("已自动换回 Media3"))

        val cleared = state.reduce(VideoPlayerEvent.EngineFallbackNoticeCleared)
        assertNull(cleared.engineFallbackNotice)
    }

    @Test
    fun 旧内核的能力齐平_不该多出提示() {
        for (kind in listOf(EngineKind.MEDIA3, EngineKind.VLC)) {
            val state = VideoPlayerUiState(engineKind = kind)
            assertTrue(kind.label + " 应当能调倍速", state.canChangeSpeed)
            assertTrue(kind.label + " 应当能调解码档位", state.canChangeDecoder)
            assertNull(kind.label + " 不该出现能力提示", state.capsNotice)
        }
    }
}
