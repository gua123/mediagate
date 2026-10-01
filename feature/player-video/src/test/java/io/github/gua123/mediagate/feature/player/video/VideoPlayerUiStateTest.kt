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
        assertEquals(EngineKind.VLC, media3.otherEngine)
        assertEquals("LibVLC", media3.otherEngineLabel)

        val vlc = media3.reduce(
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
}
