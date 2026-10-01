package io.github.gua123.mediagate.feature.player.video

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.gua123.mediagate.core.model.RemoteEntry
import io.github.gua123.mediagate.data.storage.api.StorageException
import io.github.gua123.mediagate.media.engine.DecoderMode
import io.github.gua123.mediagate.media.engine.EngineKind
import io.github.gua123.mediagate.media.engine.EngineState
import io.github.gua123.mediagate.media.engine.ResizeMode

/**
 * [VideoPlayerViewModel] 的 JVM 单测（R4 拖拽 / R9 多内核 / R10 硬软解 / R18 断点续播）。
 *
 * 用假宿主 + 假内核驱动真实 ViewModel：验证「进页面建内核起播 → 读断点续播 → 每 5 秒/暂停写断点 →
 * 换内核不丢位置倍速字幕 → 改档位换内核或原地重建 → 出错一键降级 → 拖拽提交时机 → 上下集边界」。
 *
 * 时间控制：播放页有一个永不结束的进度采样循环，所以**只用 advanceTimeBy/runCurrent，不用
 * advanceUntilIdle**（后者会一直推进虚拟时间不返回）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        clearViewModels()
        Dispatchers.resetMain()
    }

    /**
     * 每个用例都走它：**必须在 runTest 收尾之前**清掉 ViewModel。
     *
     * 原因：播放页有一个永不结束的进度采样循环（每 500 ms 一次 delay），runTest 在测试体结束后的
     * 收尾会调用 advanceUntilIdleOr 排空调度器——只要还有待执行的延时任务，它就会一直推进虚拟时间
     * 不返回（现象是测试 JVM 100% CPU 卡死）。onCleared 会取消采样循环，所以放在 finally 里最稳。
     */
    private fun playerTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            clearViewModels()
        }
    }

    /** 清掉本用例建过的 ViewModel（触发 onCleared：取消采样循环 + 写最后一次断点）。 */
    private fun clearViewModels() {
        stores.forEach { it.clear() }
        stores.clear()
    }

    @Test
    fun openFileBuildsEngineAndStartsPlayback() = playerTest {
        val env = environment()

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertEquals(listOf(EngineKind.MEDIA3), env.createCalls)
        val engine = env.last!!
        assertEquals(
            listOf("setMedia:Movies/b.mp4", "prepare", "play"),
            engine.calls.filterNot { it.startsWith("decoder:") },
        )

        tick(1)
        val state = vm.state.value
        assertEquals(VideoPlayerStatus.READY, state.status)
        assertEquals("Movies/b.mp4", state.path)
        assertEquals("b.mp4", state.title)
        assertEquals(3, state.count)
        assertEquals(2, state.position)
        assertEquals(EngineKind.MEDIA3, state.engineKind)
        assertTrue(state.playing)
    }

    @Test
    fun persistedEngineAndDecoderModeAreUsedOnEntry() = playerTest {
        val env = FakeVideoPlayerEnvironment(
            entries = movieEntries(),
            preferences = FakeVideoPreferences(engine = EngineKind.VLC, decoderMode = DecoderMode.FORCE_SW),
        )

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertEquals(listOf(EngineKind.VLC), env.createCalls)
        assertEquals(DecoderMode.FORCE_SW, env.last!!.decoderMode)
        assertEquals(EngineKind.VLC, vm.state.value.engineKind)
        assertEquals(DecoderMode.FORCE_SW, vm.state.value.decoderMode)
    }

    @Test
    fun resumeFromSavedProgressSeeksAndHints() = playerTest {
        val progress = FakeProgressStore().apply { seed("fake:video", "Movies/b.mp4", 45_000L) }
        val env = environment(progress = progress)
        val vm = player(env, "Movies/b.mp4")
        settle()

        val engine = env.last!!
        assertTrue(engine.seekCalls.contains(45_000L))
        assertTrue(vm.state.value.resumeHint)
        assertEquals(45_000L, vm.state.value.positionMs)

        advanceTimeBy(4_100L)
        runCurrent()
        assertFalse(vm.state.value.resumeHint)
    }

    @Test
    fun progressIsWrittenEveryFiveSecondsAndOnceOnPause() = playerTest {
        val progress = FakeProgressStore()
        val env = environment(progress = progress)
        player(env, "Movies/b.mp4")
        settle()
        val engine = env.last!!
        engine.position = 1_000L

        tick(10)
        assertEquals(1_000L, progress.lastPositionOf("Movies/b.mp4"))

        val beforePause = progress.saved.size
        engine.position = 2_000L
        engine.isPlaying = false
        tick(1)
        assertEquals(beforePause + 1, progress.saved.size)
        assertEquals(2_000L, progress.lastPositionOf("Movies/b.mp4"))
    }

    @Test
    fun clearingWritesFinalProgress() = playerTest {
        val progress = FakeProgressStore()
        val env = environment(progress = progress)
        player(env, "Movies/b.mp4")
        settle()
        val engine = env.last!!
        engine.position = 42_000L
        tick(1)

        clearViewModels()
        runCurrent()

        assertEquals(42_000L, progress.lastPositionOf("Movies/b.mp4"))
    }

    @Test
    fun switchingEngineKeepsPositionSpeedAndSubtitles() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/b.mp4")
        settle()
        val old = env.last!!
        old.position = 12_000L
        old.duration = 60_000L
        old.speedValue = 1.5f
        old.isPlaying = true
        old.subtitles().selectTrack("mediagate://fake:video/Movies/b.srt")
        old.subtitles().setEnabled(true)

        vm.switchEngine()
        settle()

        assertTrue(old.released)
        assertEquals(listOf(EngineKind.MEDIA3, EngineKind.VLC), env.createCalls)
        val fresh = env.last!!
        assertEquals(12_000L, fresh.position)
        assertEquals(1.5f, fresh.speed, 0.0001f)
        assertTrue(fresh.isPlaying)
        assertEquals("mediagate://fake:video/Movies/b.srt", fresh.subtitles().state.value.uri)
        assertTrue(fresh.subtitles().state.value.enabled)
        assertEquals(listOf(EngineKind.VLC), env.preferences.engineWrites)

        val state = vm.state.value
        assertEquals(EngineKind.VLC, state.engineKind)
        assertEquals(12_000L, state.positionMs)
        assertFalse(state.switching)
    }

    @Test
    fun decoderModeCycleSwitchesToVlcAndPersistsChoice() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/b.mp4")
        settle()

        vm.cycleDecoderMode()
        settle()

        assertEquals(listOf(DecoderMode.FORCE_SW), env.preferences.decoderWrites)
        assertEquals(listOf(EngineKind.MEDIA3, EngineKind.VLC), env.createCalls)
        assertEquals(EngineKind.VLC, vm.state.value.engineKind)
        assertEquals(DecoderMode.FORCE_SW, vm.state.value.decoderMode)
    }

    @Test
    fun decoderModeWithoutProxyRebuildsInPlaceAndHintsBlackout() = playerTest {
        val env = environment(proxy = null)
        val vm = player(env, "Movies/b.mp4")
        settle()
        val engine = env.last!!

        vm.cycleDecoderMode()
        settle()

        assertEquals(listOf(EngineKind.MEDIA3), env.createCalls)
        assertEquals(DecoderMode.FORCE_SW, engine.decoderMode)
        assertEquals(listOf(DecoderMode.FORCE_SW), env.preferences.decoderWrites)
        assertTrue(vm.state.value.blackoutHint)

        advanceTimeBy(2_100L)
        runCurrent()
        assertFalse(vm.state.value.blackoutHint)
    }

    @Test
    fun engineErrorOffersFallbackAndFallbackKeepsPosition() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/b.mp4")
        settle()
        val broken = env.last!!
        broken.position = 30_000L
        broken.state.value = EngineState.Error("本机解码器无法解码该媒体")
        settle()

        assertEquals(VideoPlayerStatus.ERROR, vm.state.value.status)
        assertEquals(VideoErrorKind.PLAYBACK, vm.state.value.errorKind)
        assertTrue(vm.state.value.canFallback)

        vm.fallbackFromFailure()
        settle()

        assertEquals(listOf(EngineKind.MEDIA3, EngineKind.VLC), env.createCalls)
        assertEquals(30_000L, env.last!!.position)
        assertEquals(VideoPlayerStatus.READY, vm.state.value.status)
        assertEquals(EngineKind.VLC, vm.state.value.engineKind)
    }

    @Test
    fun fallbackIsNotOfferedOrRunWithoutProxy() = playerTest {
        val env = environment(proxy = null)
        val vm = player(env, "Movies/b.mp4")
        settle()
        env.last!!.state.value = EngineState.Error("解码失败")
        settle()

        assertEquals(VideoPlayerStatus.ERROR, vm.state.value.status)
        assertFalse(vm.state.value.canFallback)

        vm.fallbackFromFailure()
        settle()
        assertEquals(listOf(EngineKind.MEDIA3), env.createCalls)
    }

    @Test
    fun dragIsNotOverwrittenByTicksAndCommitsOnRelease() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/b.mp4")
        settle()
        val engine = env.last!!
        engine.duration = 100_000L
        engine.position = 10_000L
        tick(1)

        vm.onSeekChange(0.5f)
        assertEquals(50_000L, vm.state.value.displayPositionMs)

        engine.position = 11_000L
        tick(1)
        assertEquals(11_000L, vm.state.value.positionMs)
        assertEquals(50_000L, vm.state.value.displayPositionMs)

        vm.onSeekFinished()
        assertEquals(50_000L, engine.position)
        assertEquals(50_000L, vm.state.value.positionMs)
        assertFalse(vm.state.value.dragging)
    }

    @Test
    fun episodesAdvanceWithinQueueAndStopAtBoundaries() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/b.mp4")
        settle()
        val engine = env.last!!
        assertEquals(2, vm.state.value.position)

        vm.next()
        settle()
        assertEquals("Movies/c.mp3", vm.state.value.path)
        assertEquals(3, vm.state.value.position)
        assertFalse(vm.state.value.canNext)
        assertTrue(engine.calls.contains("setMedia:Movies/c.mp3"))
        // 换集复用同一个内核，不重建
        assertEquals(listOf(EngineKind.MEDIA3), env.createCalls)

        vm.next()
        settle()
        assertEquals("Movies/c.mp3", vm.state.value.path)

        vm.previous()
        settle()
        vm.previous()
        settle()
        assertEquals("Movies/a.mp4", vm.state.value.path)
        assertFalse(vm.state.value.canPrevious)

        vm.previous()
        settle()
        assertEquals("Movies/a.mp4", vm.state.value.path)
    }

    @Test
    fun speedAndResizeCyclesAreAppliedToTheEngine() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/b.mp4")
        settle()
        val engine = env.last!!

        vm.cycleSpeed()
        vm.cycleResizeMode()

        assertEquals(1.5f, engine.speed, 0.0001f)
        assertEquals(ResizeMode.CROP, engine.resizeMode)
        assertEquals(1.5f, vm.state.value.speed, 0.0001f)
        assertEquals(ResizeMode.CROP, vm.state.value.resizeMode)
    }

    @Test
    fun togglePlayPauseDrivesTheEngine() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/b.mp4")
        settle()
        val engine = env.last!!
        assertTrue(engine.isPlaying)

        vm.togglePlayPause()
        assertFalse(engine.isPlaying)
        tick(1)
        assertFalse(vm.state.value.playing)

        vm.togglePlayPause()
        assertTrue(engine.isPlaying)
    }

    @Test
    fun siblingsFailureIsClassifiedInsteadOfCrashing() = playerTest {
        val env = environment()
        env.failSiblings["Movies"] = StorageException.AccessDenied("没有访问权限")

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertEquals(VideoPlayerStatus.ERROR, vm.state.value.status)
        assertEquals(VideoErrorKind.ACCESS_DENIED, vm.state.value.errorKind)
        assertEquals("没有访问权限", vm.state.value.errorDetail)
        assertTrue(env.createCalls.isEmpty())
    }

    @Test
    fun missingRootDirectoryFailsWithAccessDenied() = playerTest {
        val env = FakeVideoPlayerEnvironment(entries = movieEntries(), backend = null)

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertEquals(VideoPlayerStatus.ERROR, vm.state.value.status)
        assertEquals(VideoErrorKind.ACCESS_DENIED, vm.state.value.errorKind)
        assertTrue(env.createCalls.isEmpty())
    }

    @Test
    fun engineCreationFailureLandsInErrorState() = playerTest {
        val env = environment()
        env.failCreateFor = { StorageException.NotSupported("没有可用的解码器") }

        val vm = player(env, "Movies/b.mp4")
        settle()

        assertEquals(VideoPlayerStatus.ERROR, vm.state.value.status)
        assertEquals(VideoErrorKind.NOT_SUPPORTED, vm.state.value.errorKind)
    }

    @Test
    fun `播放列表可以直接跳到同文件夹的任意一条`() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/a.mp4")
        settle()
        // 队列口径：同目录的视频与音频都算一集（音频文件名沿用夹具里的 c.mp3）
        assertEquals(listOf("Movies/a.mp4", "Movies/b.mp4", "Movies/c.mp3"), vm.state.value.siblingPaths)

        // 跳到第 3 条（2026-10-03 用户要求：播放时直接跳转同文件夹的其他文件）
        vm.openEpisodeAt(2)
        settle()

        assertEquals(2, vm.state.value.siblingIndex)
        assertEquals("Movies/c.mp3", vm.state.value.path)

        // 跳回第 2 条
        vm.openEpisodeAt(1)
        settle()
        assertEquals("Movies/b.mp4", vm.state.value.path)
    }

    @Test
    fun `跳转到越界下标或无变化时不动作`() = playerTest {
        val env = environment()
        val vm = player(env, "Movies/a.mp4")
        settle()
        val engineBefore = env.created.size

        vm.openEpisodeAt(99)
        settle()
        vm.openEpisodeAt(-1)
        settle()
        vm.openEpisodeAt(0) // 就是当前这条
        settle()

        assertEquals("下标越界/同一条都不该换媒体", "Movies/a.mp4", vm.state.value.path)
        assertEquals(0, vm.state.value.siblingIndex)
        assertEquals("也不该重建内核", engineBefore, env.created.size)
    }

    // ------------------------------------------------------------------ 测试脚手架

    private fun TestScope.player(env: FakeVideoPlayerEnvironment, path: String): VideoPlayerViewModel {
        val vm = VideoPlayerViewModel(
            environment = env,
            initialPath = path,
            io = dispatcher,
            exitScope = backgroundScope,
        )
        val store = ViewModelStore()
        store.put("player-video", vm)
        stores += store
        return vm
    }

    private fun environment(
        entries: Map<String, List<RemoteEntry>> = movieEntries(),
        proxy: String? = "http://127.0.0.1:1",
        progress: FakeProgressStore = FakeProgressStore(),
        preferences: FakeVideoPreferences = FakeVideoPreferences(),
    ): FakeVideoPlayerEnvironment {
        return FakeVideoPlayerEnvironment(
            entries = entries,
            proxyBaseUrl = proxy,
            preferences = preferences,
            progress = progress,
        )
    }

    /** 三集（两个视频 + 一个音频）加一个干扰项：可播队列按名称排序为 a.mp4 / b.mp4 / c.mp3。 */
    private fun movieEntries(): Map<String, List<RemoteEntry>> = mapOf(
        "Movies" to listOf(
            videoEntry("Movies/a.mp4"),
            otherEntry("Movies/cover.jpg"),
            videoEntry("Movies/b.mp4"),
            audioEntry("Movies/c.mp3"),
        ),
    )
}
